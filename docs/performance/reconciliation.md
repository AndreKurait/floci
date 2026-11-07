# Event-driven reconciliation

This is a historical October 24, 2026 design review and experiment. The implementation
descriptions and timings below apply to the recorded revisions, not to current main.
Follow-up entries retain the decisions made at that time; they are not a current
status inventory. The benchmark cleanup has since been hardened independently of
those measurements.

Floci has two kinds of waits: waiting for local state to change, and waiting for a
real deadline or external process. This change uses local notifications for the
first kind, while retaining health intervals, retries, and recovery sweeps for the
second. It does not shorten AWS-configured waits or infer successful completion
from an API response.

## Application review

The review used `dev` revision `f2173e8de69004da4d2ebcba63a7905166fac13a`.
The following groups cover the recurring scheduling and resource ownership patterns
across the application. Entries marked follow-up are design findings, not implemented
features or measured lifecycle savings.

| Area | Structural gap | Decision and implications |
| --- | --- | --- |
| Auto Scaling | A single timer performs every group's reconciliation, at ten-second intervals. Desired capacity and completed EC2 launches wait for a sweep; one blocking group delays other accounts. | Implement keyed notifications after successful mutations, four bounded workers, explicit account/region context, and launch-state wakeups. Keep group locks, identity checks and periodic repair. |
| EC2 launch admission | CloudFormation checks a local instance state every 50 ms. Both ordinary and compare-and-set transitions can publish a new state. | Wait on the instance monitor with a monotonic deadline; notify from both state writers. Docker cleanup runs outside that monitor. This reduces wakeups without changing the launch timeout. |
| Lambda provisioned concurrency | One-second global scans detect lost environments. An idle environment can fail with no invocation available to trigger repair. | Reuse a serial keyed queue; signal failed release, discarded environments, alias updates, teardown completion and terminal runtime failure. Retain a ten-second repair sweep. Initialization, account quota, in-flight leases and actual teardown still gate capacity. |
| ELBv2 | Ordered blocking work serializes independent probes from the same Vert.x context. Periodic callbacks can accumulate while probes block. Duplicate registrations produce extra probes, and interval changes leave the old timer running. | Run at most 32 blocking probes concurrently, with one outstanding probe per target and at most 4096 retained keys. Repeated registrations preserve cadence; changed settings invalidate old results. Keep configured success/failure thresholds and real HTTP checks. |
| Lambda code staging | Identical archives are unpacked into multiple function-owned volumes. | Follow-up: content-addressed, tenant-scoped immutable artifacts can remove redundant I/O. A usable design also needs atomic publication, single-flight preparation, reference ownership, bounded eviction, restart discovery and rollback cleanup. Hashing alone does not establish safe lifecycle ownership. |
| Step Functions | Cached platform-thread executors host coordinator waits; nested `.sync` tasks poll and some paths sleep before their first read. | Follow-up: publish terminal execution futures and use notification-before-read registration. Keep cancellation propagation, task/execution deadlines, retries, history ordering and bounded admission. Virtual threads alone do not bound the number of executions. |
| SWF | Long polls already use a versioned notification around an authoritative claim. Notifications are scoped to a domain, waking unrelated task lists; timeout expiry still scans. | Keep the correct lost-wakeup protection. Follow-up: task-list and task-kind indexes, plus deadline scheduling with periodic recovery. Preserve atomic claims and heartbeat changes. |
| SQS | Sends notify receivers, but the empty scan and wait are separate: a notification in that gap can add up to the one-second retry interval. Receivers also rescan for visibility expiry. | Follow-up: a versioned signal, queue-incarnation guards and next-visible-message deadlines. Do not hold a signal lock across storage or dead-letter routing; preserve FIFO group eligibility and redrive counts. |
| Lambda stream/SQS event sources and Pipes | Per-mapping periodic scans and cached thread pools repeat work when idle. | Follow-up: publish source changes after committed writes and reconcile the affected mapping. Retry-not-before, batching windows, shard ordering, checkpoint ownership, disable/reset and admission throttles remain separate predicates. External DynamoDB/Kafka sources still require polling. |
| ECS and container-backed services | ECS globally reconciles every five seconds; RDS, OpenSearch, EKS and other managers poll external readiness. | Follow-up: service-keyed work and Docker lifecycle notifications, with real readiness probes and repair. A Docker `start` event does not mean the service is ready. Keep existing reset and task ownership barriers. |
| CloudWatch, Scheduler, TTL, retention and flushers | Many independent periodic scans allocate or traverse whole stores. Some timers encode required time semantics. | Follow-up: dirty-key evaluation, deadline heaps and shared timer infrastructure, separated from bounded blocking workers. Window boundaries, retention expiry and durability guarantees must remain correct. |
| Storage | Cross-account discovery and per-account scans can repeatedly traverse the same store. Reconciliation can write unchanged objects. | Follow-up: owner/resource indexes and change-aware persistence. An in-process notification is not a durable event; persistent and WAL modes must continue recovering from their authoritative records. |
| Logging | Serialized log callbacks still use a synchronized byte accumulator per byte. | Separate small optimization: bounded geometric byte buffer with complete-line UTF-8 decoding. Preserve malformed-input bounds, final tails and close ordering. |
| Native clients and guest processes | Client polling, explicit race workarounds, guest initialization and graceful shutdown can dominate wall time outside Floci. | Separate owners and acceptance tests. These changes cannot remove a caller's sleep, make OpenSearch initialization instantaneous, or treat workflow completion as physical deletion. |

## Why JDK coordination, rather than etcd

These controllers and their authoritative state currently live in one Floci JVM.
A monitor, a completion future and a bounded work queue can notify them directly.
Adding etcd would introduce a separate state authority, network delivery, leases,
revision tracking and operational availability into that local path.

An etcd watch becomes useful when resource ownership is intentionally distributed
across processes. That design also needs transactional state publication, replay
from a known revision, compaction recovery through a fresh snapshot, fencing of
former owners, idempotent effects and backpressure. A watch notification alone is
not an ownership or exactly-once guarantee. The current storage implementations do
not become distributed simply by publishing their changes to etcd.

Primary references:

- [JDK executor contracts](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ExecutorService.html)
- [JDK scheduled executor behavior](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ScheduledThreadPoolExecutor.html)
- [Vert.x ordered blocking execution](https://github.com/eclipse-vertx/vert.x/blob/5.0.10/vertx-core/src/main/java/io/vertx/core/Vertx.java)
- [etcd watch guarantees](https://etcd.io/docs/v3.6/learning/api_guarantees/)

## Shared queue contract

`KeyedWorkQueue<K>` treats a notification as "this key may need reconciliation."
The action rereads authoritative state; queued events do not carry mutable resource
snapshots or commands to replay.

1. Admission and completion use the same short monitor. An admitted key owns one
   record, regardless of the number of notifications.
2. A key runs at most once concurrently. A notification while it runs sets a dirty
   flag, causing one more pass after completion. Dirty keys rejoin the tail.
3. Different keys use a bounded number of virtual threads. A timer or API caller
   never performs the blocking reconciliation itself.
4. Capacity includes queued and running keys. Duplicate notifications are accepted
   at capacity. A new key is rejected when full; callers keep periodic repair for
   authoritative state that remains outstanding. Queue capacity is not an AWS quota.
5. Runtime failures release the worker slot. Recovery remains the service's job;
   a failing action does not spin in an immediate automatic retry loop.
6. Close rejects admission, discards queued notifications and interrupts active
   work. Services retain ownership of resource cleanup and bounded draining.
7. `snapshot()` reports active/retained keys, completed actions, coalesced requests,
   admission rejections and failures for tests and future metrics integration.

The queue does not copy a caller's thread-local security context. The ASG adapter
carries immutable account/region/name coordinates and enters `RequestScopes` in
the worker. Lambda's adapter uses its existing explicit account-aware stores and
serial allocation lock. ELB target resolution uses the target group's owning ARN.

## Health scheduling details

The health checker keeps scheduling separate from HTTP I/O. Re-registering a target
only schedules its first probe if the registration is new. While a probe is queued
or executing, subsequent ticks do not queue another for that target. A saturated
queue leaves health unchanged and allows a later periodic tick to retry admission.

Monitoring generations and target object identity reject late results after a
configuration change, deregistration, group deletion or reset. Changing the interval
replaces its timer; changing other settings retains the timer cadence and advances
the generation. Success/failure counters survive configuration changes. Health is
published as one immutable status/reason/description tuple.

The 32-worker/4096-key health bounds and four-worker/4096-key ASG bounds are internal
limits. They cap executor growth, not the service's resource inventory. Sustained
load beyond service capacity can still increase convergence latency. A sufficiently
large inventory can repeatedly overflow the notification queue, so saturation
requires capacity tuning or a fair paginated repair traversal before it is a
supported scaling target. This change makes no claim of unlimited scale.

## Reproducing the performance experiment

Build the exact baseline and candidate with Java 25 using `./mvnw package -DskipTests`.
Run each JVM on a separate loopback port, with an isolated data directory and Docker
resource namespace, `floci.services.ec2.mock=true`,
`floci.services.ec2.vpc-networks.enabled=false` and `floci.services.elbv2.mock=false`.
Use identical JVM limits and service settings for both. The HTTP targets are local
to the benchmark process, so run the JVMs on the same host rather than behind a
container's distinct loopback interface.

Install `boto3` into a virtual environment, then run:

```sh
python tools/performance/reconciliation_benchmark.py \
  --endpoint http://127.0.0.1:24566 --label baseline --revision BASE_SHA \
  --runs 3 --output baseline.json
python tools/performance/reconciliation_benchmark.py \
  --endpoint http://127.0.0.1:24567 --label candidate --revision CANDIDATE_SHA \
  --runs 3 --output candidate.json
```

The script uses synthetic credentials and rejects non-loopback endpoints. It warms
SDK/HTTP management paths, records host load and measures:

- `SetDesiredCapacity` through one ASG instance reaching `InService`, using mock EC2.
- First complete real HTTP probe round for 32 local targets taking 400 ms each.
- All targets becoming healthy with threshold two configured at a five-second interval.
  Initial admission can become healthy after one probe; this measurement does not
  establish two-success recovery behavior.

API latency is reported independently. Neither experiment includes Lambda cold
launch, guest startup, application indexing or physical guest deletion. The
results characterize controller latency and probe concurrency, not end-to-end
fleet lifecycle speed. Every experiment creates unique resource names and deletes
its resources afterward.

## Measured result

Three runs per build on the same host, baseline `f2173e8de` and candidate
`707e3452d`. Each JVM used Java 25, eight active processors, `-Xms128m -Xmx2g`,
mock EC2 and real ELB HTTP probes. Values below are medians.

| Completed operation | Baseline | Candidate |
| --- | ---: | ---: |
| ASG desired capacity to `InService` (mock EC2) | 13.376 s | 0.028 s |
| First HTTP probe round, 32 targets at 400 ms each | 12.873 s | 0.429 s |
| All 32 targets healthy, threshold 2, interval 5 s | 25.748 s | 5.423 s |

The first probe round improved about 30 times, and configured target-health
completion about 4.7 times, in this controlled workload. ASG completion removed
the timer wait; API latency stayed in the millisecond range in both builds. This
is not a Lambda, OpenSearch or full fleet lifecycle speedup. The host was shared:
recorded start/end one-minute load averages ranged from 21.08 to 23.92.
Raw timings are in `tools/performance/results/reconciliation-20261024.json`.

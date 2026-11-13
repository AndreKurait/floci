# Event-driven reconciliation

Floci has two kinds of waits: waiting for local state to change, and waiting for a
real deadline or external process. This change uses local notifications for the
first kind, while retaining health intervals, retries, and recovery sweeps for the
second. It does not shorten AWS-configured waits or infer successful completion
from an API response.

## Historical experiment scope

The review and measurements below describe the recorded October 24 revisions,
not the current combined branch. Later changes include task-list-scoped SWF
wakeups and initial ELB target admission after one successful probe. Recovery of
an unhealthy target still uses its configured success threshold. Rerunning the
benchmark on a newer revision produces new results; the archived timings remain
attributed to their original sources.

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
| SQS | Sends notify receivers, but the empty scan and wait are separate: a notification in that gap can add up to the one-second retry interval. Empty receivers also rescan once per second. | Implement a versioned signal owned by the queue incarnation. Publish after successful message mutations, retain the scanned incarnation when claiming, and wake on close. Empty queues park until a change or the request deadline. Nonempty queues retain expiry/retry scans; eligibility indexes and next-visible-message deadlines remain follow-up work. |
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
- All targets becoming healthy with two successful checks at a five-second interval.

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

A separate fault-recovery smoke used a real Docker Python 3.12 Lambda runtime
with one initialized provisioned environment. After killing that owned container,
the test observed Docker alone until a replacement container was running, then
checked `READY` and invoked the function successfully. The replacement was running
after 1.788 seconds. No Lambda status read or invocation occurred during replacement.
This is one recovery check, not a baseline/candidate latency comparison or a
measurement of full runtime readiness at the moment Docker reported `running`.

Automated coverage includes concurrent producer convergence, bounded admission,
dirty-key fairness, shutdown interruption, real HTTP probe concurrency and stale
results after configuration changes, deregistration, stop and reset. The AWS SDK
integration exercises event-driven ASG convergence and deletion across separate
accounts in commercial and GovCloud regions.

## SQS notification and idle-work experiment

`VersionedSignal` complements the keyed work queue for a caller that is already
waiting. It snapshots a version before reading authoritative state and parks only
while that version remains unchanged. A mutation between the empty read and the
wait therefore cannot lose its notification. The signal owns no worker threads.

Each `GuardedMessageQueue` owns its signal, so deletion, reset and shutdown can
close that incarnation and release its waiters. Receivers keep the same queue
object through the claim instead of resolving a replacement under the same URL.
Message insertion, internal redrive delivery, deletion and visibility changes
signal after successful persistence. A claim itself does not signal: an unresolved
dead-letter destination must not cause an immediate self-triggered retry loop.

Empty queues wait for the request deadline or a mutation. Nonempty queues retain
the existing one-second expiry/retry ceiling. This does not add a visibility index
or claim an improvement for every active-queue workload.

The in-process benchmark runs 100 concurrent empty 20-second receives, each on its
own queue, and counts authoritative queue-store reads. It has no network or Docker
dependencies. Three runs per build used Java 25 with eight active processors and
`-Xms128m -Xmx2g`. Baseline `f2173e8de` and candidate `edab9da04` produced:

| Operation, per run | Baseline | Candidate |
| --- | ---: | ---: |
| Completed empty receives | 100 | 100 |
| Requested wait | 20 s | 20 s |
| Queue-store reads | 2,200 | 300 |
| Reads per receive | 22 | 3 |
| Observed completion range | 20.002-20.004 s | 20.002-20.006 s |

That is 86.4% fewer queue-store reads with the requested wait preserved. It is not
a measurement of CPU consumption, network throughput, or active-queue delivery
latency. Raw results are in `tools/performance/results/sqs-idle-20261024.json`.
The deterministic tests separately force a message into the scan-to-wait gap and
verify notification, shutdown and incarnation behavior.

To reproduce, keep `SqsIdlePollBenchmark.java` from the candidate checkout available
to both builds and set `FLOCI_BENCHMARK_SOURCE` to its absolute path. In each
checkout, build with Java 25 and run:

```sh
./mvnw package -DskipTests
FLOCI_BENCHMARK_CP="$(find target/quarkus-app -name '*.jar' -print | paste -sd ':' -)"
mkdir -p target/performance-classes
javac -cp "$FLOCI_BENCHMARK_CP" -d target/performance-classes "$FLOCI_BENCHMARK_SOURCE"
java -XX:ActiveProcessorCount=8 -Xms128m -Xmx2g \
  -cp "target/performance-classes:$FLOCI_BENCHMARK_CP" \
  io.github.hectorvent.floci.services.sqs.SqsIdlePollBenchmark \
  baseline "$(git rev-parse HEAD)" 100 20 3
```

Use `candidate` as the label for the second build.

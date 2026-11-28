# CI test partitioning

`partition.sh` assigns every discovered test class to one of eight shards.
Classes using the same `@TestProfile` stay together to share Quarkus startup.
Groups and ordinary classes are placed in the least loaded shard, with long
suites placed first.

`test-durations.json` records rounded-up seconds for suites taking more than
five seconds in the cited CI run. Faster, new and unmeasured classes have a
one-second weight. These are scheduling estimates, not test deadlines or a list
of tests to include. Stale timing entries cannot introduce a class into a shard.

Refresh the estimates from uploaded `TEST-*.xml` reports when CI becomes
unbalanced. Read each suite's `name` and `time`, replace dots in the name with
slashes, keep durations above five seconds, round up to whole seconds and sort
the mapping by class path. Preserve the source run and head in the JSON.
An incomplete run supplies only the suites it actually reported; missing
durations retain the default weight.

The compiled-class comparison and disjoint-union gate still check the complete
test manifest. Each shard runs plain JUnit classes concurrently with a five-minute fork limit,
then Quarkus classes sequentially with a 25-minute fork limit, within a 35-minute job limit. The partitioner tests run on shard 1; run them
locally with `python3 -m unittest discover -s .github/ci -p 'test_partition.py'`.

## Runner capacity

Ordinary checks share these concurrency slots across pull requests and branches:

| Work | Concurrent jobs |
| --- | ---: |
| JVM test shards | 4 |
| Native compatibility image builds | 1 |
| Compatibility suites, CLI image checks and native EC2 suites | 3 |

Every shard and architecture still runs. Job-level `queue: max` keeps up to 100
pending jobs per slot; the default single pending job would cancel required
checks from other pull requests. The existing workflow-level concurrency still
cancels superseded revisions of the same pull request. Small checks and result
aggregation do not take these slots.

The JVM suite uses eight shards, with four ordinary shards running concurrently.
The partitioner keeps each test profile together and balances recorded suite
seconds from `test-durations.json`; new classes receive a nonzero default weight.
Those historical weights are scheduling estimates, not current runtime guarantees.

Dev image qualification calls the same eight-shard suite with
`release-validation: true`. Its per-run slots allow eight concurrent shards
outside the ordinary PR slots. Moving-tag promotion requires that suite, the
image build, both architecture smokes and the stock-agent smoke to succeed.
Release and nightly image builders retain their existing publication gates.

These limits reduce competition for hosted runners; they do not reserve runner
capacity against other repositories or reduce the work needed for a full test
run. Ordinary PRs can take longer to finish.

## Maintaining fork main

AndreKurait/floci's `main` retains these scheduling controls and the bounded
Maven bootstrap from fork PRs #1197 and #1221. It integrates upstream changes
without discarding fork changes. Upstream contribution branches start from
`upstream/main`, so they remain independent of fork CI customizations.

Before synchronizing, record the observed fork and upstream heads and create
a backup. If upstream is ahead, merge it into an isolated checkout of fork
`main`, resolve conflicts while retaining both sides' behavior, and qualify
the resulting tree. Publish a signed commit with DCO using an ordinary
fast-forward push. Never reset fork `main` to upstream or force-push it.

The server's `sync-fork-main.py` checks both histories, protected feature
commits, the scheduling controls, and the shared Maven cache/bootstrap before
publication. It requires exact-candidate qualification evidence and the common
worker lock. A GitHub branch ruleset also blocks force pushes and deletion.
Protected feature changes require an explicit policy update with fresh
qualification; a failed preservation check is a reason to reconcile the
candidate.

When upstream changes a workflow, retain its complete shard and architecture
matrices, triggers, test commands, and release gates. Assign added shards or
suites to the existing capacity slots instead of restoring an older matrix.
Dev reconciliation also retains any broader test coverage already on dev.

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
test manifest. Every shard runs its selected tests with the same 20-minute fork
limit and 30-minute job limit. The partitioner tests run on shard 1; run them
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

`Fork dev image` calls CI with `release-validation: true`. Its eight JVM shards
use separate slots and can run together. Release image builders, smoke tests and
tag promotion also run outside the ordinary test slots. Promotion still requires
the complete test suite, both image architectures and the existing smoke checks
to pass. A running dev publication finishes while newer dev pushes coalesce into
the next pending publication.

These limits reduce competition for hosted runners; they do not reserve runner
capacity against other repositories or reduce the work needed for a full test
run. Ordinary PRs can take longer to finish.

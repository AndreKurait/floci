# CI runner capacity

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

Release and nightly image builders run outside these ordinary test slots. Their
existing tests and publication gates remain in place.

These limits reduce competition for hosted runners; they do not reserve runner
capacity against other repositories or reduce the work needed for a full test
run. Ordinary PRs can take longer to finish.

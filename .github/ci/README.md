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

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

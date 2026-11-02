# Startup metrics experiment

This isolated fork experiment compiles the full source/test classpath, then runs the first actual Quarkus test class from the previous shard experiment in four fresh JVMs on one runner: lifecycle, direct Surefire, direct Surefire, lifecycle. It measures startup only and retains the two existing tests unchanged.

A small Java agent fails startup unless the metrics property reaches the actual test JVM. It records only PID, time, Java version and processor count. Each invocation removes stale metrics and moves its single-profile metrics and reports to a unique directory. The original and instrumented POM hashes, source/class hashes, commands, runner CPU model and dependency cache key are retained. The POM is restored even on failure. No credentials, environment dump or JVM argument dump is recorded.

Quarkus build workers already run in parallel. Build-step durations overlap and do not cover pre-augmentation indexing. Same-runner ABBA ordering reduces host and order confounding but does not make warmed filesystem caches cold. This experiment is not a full-suite qualification, native migration or evidence of a startup speedup.

Run `python3 tools/startup-experiment/run.py --single` for a local instrumentation check; omit `--single` for the hosted comparison. Use a clean disposable checkout with no existing `startup-evidence` directory. Existing test fixtures own their listeners. Docker inventories/events are captured by the hosted workflow; this selected fixture does not launch containers.

Both invocation modes request an ephemeral test HTTPS port. This avoids collision with unrelated local listeners without altering the test class or profile.

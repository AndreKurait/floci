# AppConfig compatibility clock

Install the isolated fixture, then build the compatibility image:

```sh
./mvnw -f compatibility-tests/clock/pom.xml clean install
./mvnw clean package -Pcompat-clock -DskipTests
```

Add `-Dnative` for the native compatibility executable. Ordinary builds omit
`compat-clock` and therefore have no dependency on the fixture jar. Its classes
are compiled in the fixture's own `target` directory, never the application's
`target/classes`, including when switching profiles without cleaning. Keep the
fixture's Quarkus version aligned with the root POM.

The profile replaces the qualified clock used throughout `AppConfigService`.
Everything
else, including authentication and certificate clocks, retains real time. Without
`FLOCI_TEST_APPCONFIG_CLOCK_FILE`, the compatibility build also uses real time.
An invalid configured timestamp fails when the clock is first used; it does not
fall back to real time. No HTTP control endpoint or AWS protocol change is involved.

To run the Python SDK suite without rollout and bake sleeps:

1. Create a private test directory with `clock_dir=$(mktemp -d)`, make it readable
   by the Floci container with `chmod 755 "$clock_dir"`, and initialize `instant`:
   `date -u +%Y-%m-%dT%H:%M:%SZ > "$clock_dir/instant"`.
2. Mount that directory at `/test-clock` in both Floci and the Python SDK client,
   read-only in Floci and writable in the client.
3. Set `FLOCI_TEST_APPCONFIG_CLOCK_FILE=/test-clock/instant` in both containers.
4. Set `FLOCI_TEST_REQUIRE_APPCONFIG_CLOCK=true` in the SDK client to fail immediately
   if the clock path is missing.
5. Run the existing SDK suite normally. The compatibility workflow does this
   automatically for its Python jobs.

Mount the directory, not the file: tests atomically replace `instant` to ensure
background readers never observe a partially written timestamp. The fixture
advances time monotonically and verifies Floci's deployment timestamps before
using it. The fixture rejects xdist workers and concurrent processes sharing the
clock, using a separate lock file that survives atomic timestamp replacement.
Use a dedicated Floci instance and clock directory for each suite; unrelated
clients must not share this test instance.

The tests use real AWS SDK requests. They check the state just before the baking
boundary, advance beyond it, and verify the exact scheduled completion timestamp.
They exercise both retrieval APIs after rollback and reversion, including a
deleted previous version in both baking and completed states. Without either
environment variable, the suite retains its real-time path.

Run clock and lifecycle unit tests with:

```sh
./mvnw -f compatibility-tests/clock/pom.xml test
./mvnw test -Dtest=AppConfigDeploymentLifecycleTest
```

The compatibility workflow runs the fixture unit tests before building its native
image. Release builds do not need to install the fixture.

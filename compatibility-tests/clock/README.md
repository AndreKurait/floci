# AppConfig compatibility clock

Build the compatibility image with `./mvnw clean package -Pcompat-clock -DskipTests`.
Add `-Dnative` when building the native compatibility executable. Release builds
must omit `compat-clock`; the file clock is an additional source directory that
ordinary builds do not compile. Use `clean` when switching build profiles so a
previous compatibility build cannot leave test classes in the output directory.

The profile replaces only the qualified AppConfig deployment clock. Everything
else, including authentication and certificate clocks, retains real time. Without
`FLOCI_TEST_APPCONFIG_CLOCK_FILE`, the compatibility build also uses real time.
No HTTP control endpoint or AWS protocol change is involved.

To run the Python SDK suite without rollout and bake sleeps:

1. Run `mkdir -p /tmp/floci-appconfig-clock`, then initialize `instant` in UTC:
   `date -u +%Y-%m-%dT%H:%M:%SZ > /tmp/floci-appconfig-clock/instant`.
2. Mount that directory at `/test-clock` in both Floci and the Python SDK client,
   read-only in Floci and writable in the client.
3. Set `FLOCI_TEST_APPCONFIG_CLOCK_FILE=/test-clock/instant` in both containers.
4. Run the existing SDK suite normally. The compatibility workflow does this
   automatically for its Python jobs.

Mount the directory, not the file: tests atomically replace `instant` to ensure
background readers never observe a partially written timestamp. The fixture
advances time monotonically and verifies Floci's deployment timestamps before
using it. Do not run tests sharing this clock in parallel, or attach unrelated
clients to this test Floci instance.

The tests still use real AWS SDK requests. They check intermediate rollout
percentages, the pre-deadline states, exact completion timestamps, both retrieval
APIs after rollback, and CloudFormation's asynchronous resource/stack transitions.
Without the environment variable they retain the real-time path.

Run clock and lifecycle unit tests with:

```sh
./mvnw test -Pcompat-clock \
  -Dtest=FileClockTest,AppConfigDeploymentLifecycleTest,CloudFormationAppConfigDeploymentLifecycleTest
```

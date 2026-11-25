package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.EpochSeconds;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.stepfunctions.model.Execution;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ManualClock;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every date Step Functions reports is read from the one injected clock: the start and stop of an
 * execution, each history event, the state's {@code EnteredTime}, and the creation and update
 * dates of state machines, versions, aliases and activities. The clock starts in the past and
 * moves only when a test advances it, so a date taken from the wall clock cannot equal the instant
 * these tests expect.
 *
 * <p>The real service, executor and JSON handler share that clock, with no HTTP and no container.
 * A Wait state parks its worker in the sleeper until the test, having moved the clock, releases it.
 */
@Timeout(60)
class StepFunctionsTimelineStampsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/test-role";
    /** The name {@link AslExecutor} gives the threads that run executions. */
    private static final String WORKER_THREAD_NAME = "sfn-executor";
    /** The shipped {@code floci.services.stepfunctions.max-wait-seconds}. */
    private static final int CONFIGURED_MAX_WAIT_SECONDS = 30;
    private static final long SETTLE_TIMEOUT_SECONDS = 10;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(3);
    private static final String WAIT_THEN_REPORT = """
            {"StartAt": "W", "States": {
              "W": {"Type": "Wait", "Seconds": 3, "Next": "P"},
              "P": {"Type": "Pass", "Parameters": {"entered.$": "$$.State.EnteredTime",
                                                   "started.$": "$$.Execution.StartTime"}, "End": true}}}
            """;
    private static final String PASS = """
            {"StartAt": "P", "States": {"P": {"Type": "Pass", "End": true}}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ManualClock clock = new ManualClock(START);
    private final CountDownLatch workerParked = new CountDownLatch(1);
    private final CountDownLatch workerReleased = new CountDownLatch(1);
    private final CompletableFuture<Void> workerHandedBack = new CompletableFuture<>();
    private final AslExecutor executor;
    private final StepFunctionsJsonHandler handler;

    @SuppressWarnings("unchecked")
    StepFunctionsTimelineStampsTest() {
        AtomicReference<StepFunctionsService> serviceReference = new AtomicReference<>();
        Instance<StepFunctionsService> serviceInstance = mock(Instance.class);
        when(serviceInstance.get()).thenAnswer(invocation -> serviceReference.get());
        executor = AslExecutorFixture.builder()
                .objectMapper(mapper)
                .sfnService(serviceInstance)
                .clock(clock)
                .sleeper(nanos -> {
                    workerParked.countDown();
                    workerReleased.await();
                })
                .maxWaitSeconds(CONFIGURED_MAX_WAIT_SECONDS)
                .build();
        // The last thing a worker does with its execution is hand it back to be stored, whatever
        // ended it, so that write is where the execution is known to have no worker left.
        StorageBackend<String, Execution> executions = new InMemoryStorage<>() {
            @Override
            public synchronized void put(String key, Execution execution) {
                super.put(key, execution);
                if (WORKER_THREAD_NAME.equals(Thread.currentThread().getName())
                        && !"RUNNING".equals(execution.getStatus())) {
                    workerHandedBack.complete(null);
                }
            }
        };
        StepFunctionsService service = new StepFunctionsService(
                new InMemoryStorageFactory(ACCOUNT).withStore("sfn-executions.json", executions),
                new RegionResolver(REGION, ACCOUNT), executor, mapper, mock(SfnMockLoader.class),
                mock(StepFunctionsEventPublisher.class), clock);
        serviceReference.set(service);
        handler = new StepFunctionsJsonHandler(service, mapper, clock);
    }

    @AfterEach
    void stopWorkers() {
        executor.stop();
    }

    @Test
    void anExecutionStartsStopsAndRecordsItsHistoryOnTheTimeline() throws Exception {
        String executionArn = startExecution(createStateMachine("stamps", WAIT_THEN_REPORT, "STANDARD"));
        awaitWorkerParked();
        assertEquals(seconds(START), describeExecution(executionArn).path("startDate").asDouble());

        clock.advance(WAIT);
        workerReleased.countDown();
        JsonNode ended = awaitEnded(executionArn);

        Instant end = START.plus(WAIT);
        assertEquals("SUCCEEDED", ended.path("status").asText());
        assertEquals(seconds(START), ended.path("startDate").asDouble());
        assertEquals(seconds(end), ended.path("stopDate").asDouble());
        assertEquals("{\"entered\":\"" + end + "\",\"started\":\"" + START + "\"}",
                ended.path("output").asText());

        List<String> stamped = new ArrayList<>();
        executionHistory(executionArn).forEach(event -> stamped.add(event.path("type").asText() + "@"
                + Instant.ofEpochMilli(Math.round(event.path("timestamp").asDouble() * 1000))));
        assertEquals(List.of("ExecutionStarted@" + START, "WaitStateEntered@" + START,
                "WaitStateExited@" + end, "PassStateEntered@" + end, "PassStateExited@" + end,
                "ExecutionSucceeded@" + end), stamped);
    }

    @Test
    void stopExecutionAnswersWithTheStopDateTheExecutionKeeps() throws Exception {
        String executionArn = startExecution(createStateMachine("stopped", WAIT_THEN_REPORT, "STANDARD"));
        awaitWorkerParked();
        clock.advance(Duration.ofSeconds(1));

        JsonNode stopped = call("StopExecution", request().put("executionArn", executionArn));
        Instant stoppedAt = START.plusSeconds(1);
        assertEquals(seconds(stoppedAt), stopped.path("stopDate").asDouble());

        // The worker comes out of its Wait after the stop. Stopping again later still reports the
        // instant the execution stopped, which is the one DescribeExecution reports.
        clock.advance(WAIT);
        workerReleased.countDown();
        awaitEnded(executionArn);
        JsonNode stoppedAgain = call("StopExecution", request().put("executionArn", executionArn));
        assertEquals(seconds(stoppedAt), stoppedAgain.path("stopDate").asDouble());
        assertEquals(seconds(stoppedAt), describeExecution(executionArn).path("stopDate").asDouble());

        JsonNode events = executionHistory(executionArn);
        JsonNode aborted = events.get(events.size() - 1);
        assertEquals("ExecutionAborted", aborted.path("type").asText());
        assertEquals(seconds(stoppedAt), aborted.path("timestamp").asDouble());
    }

    @Test
    void stateMachineVersionAliasAndActivityDatesAreOnTheTimeline() {
        String stateMachineArn = createStateMachine("dated", PASS, "STANDARD");
        assertEquals(seconds(START), describeStateMachine(stateMachineArn).path("creationDate").asDouble());

        clock.advance(Duration.ofSeconds(10));
        JsonNode updated = call("UpdateStateMachine", request().put("stateMachineArn", stateMachineArn)
                .put("definition", PASS.replace("\"P\"", "\"Q\"")).put("publish", true));
        assertEquals(seconds(START.plusSeconds(10)), updated.path("updateDate").asDouble());
        assertEquals(seconds(START), describeStateMachine(stateMachineArn).path("creationDate").asDouble());
        String versionArn = updated.path("stateMachineVersionArn").asText();
        assertEquals(seconds(START.plusSeconds(10)),
                describeStateMachine(versionArn).path("creationDate").asDouble());

        clock.advance(Duration.ofSeconds(10));
        JsonNode routing = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("stateMachineVersionArn", versionArn).put("weight", 100));
        JsonNode alias = call("CreateStateMachineAlias", request().put("name", "live")
                .set("routingConfiguration", routing));
        assertEquals(seconds(START.plusSeconds(20)), alias.path("creationDate").asDouble());
        String aliasArn = alias.path("stateMachineAliasArn").asText();

        clock.advance(Duration.ofSeconds(10));
        JsonNode aliasUpdate = call("UpdateStateMachineAlias", request()
                .put("stateMachineAliasArn", aliasArn).put("description", "later"));
        assertEquals(seconds(START.plusSeconds(30)), aliasUpdate.path("updateDate").asDouble());
        JsonNode describedAlias = call("DescribeStateMachineAlias",
                request().put("stateMachineAliasArn", aliasArn));
        assertEquals(seconds(START.plusSeconds(20)), describedAlias.path("creationDate").asDouble());
        assertEquals(seconds(START.plusSeconds(30)), describedAlias.path("updateDate").asDouble());

        String activityArn = call("CreateActivity", request().put("name", "dated-activity"))
                .path("activityArn").asText();
        assertEquals(seconds(START.plusSeconds(30)), call("DescribeActivity",
                request().put("activityArn", activityArn)).path("creationDate").asDouble());
    }

    @Test
    void aSynchronousExpressExecutionIsNamedAndDatedOnTheTimeline() {
        String stateMachineArn = createStateMachine("express-dated", PASS, "EXPRESS");
        clock.advance(Duration.ofMillis(1500));

        JsonNode ran = call("StartSyncExecution",
                request().put("stateMachineArn", stateMachineArn).put("name", "run").put("input", "{}"));

        Instant at = START.plusMillis(1500);
        assertEquals("SUCCEEDED", ran.path("status").asText());
        assertEquals("arn:aws:states:us-east-1:000000000000:express:express-dated:2026-01-01T00:00:01.500Z:run",
                ran.path("executionArn").asText());
        assertEquals(seconds(at), ran.path("startDate").asDouble());
        assertEquals(seconds(at), ran.path("stopDate").asDouble());
    }

    /** One request through the JSON handler, answered with the response body. */
    private JsonNode call(String action, ObjectNode request) {
        return (JsonNode) handler.handle(action, request, REGION).getEntity();
    }

    private ObjectNode request() {
        return mapper.createObjectNode();
    }

    private String createStateMachine(String name, String definition, String type) {
        return call("CreateStateMachine", request().put("name", name).put("definition", definition)
                .put("roleArn", ROLE_ARN).put("type", type)).path("stateMachineArn").asText();
    }

    private String startExecution(String stateMachineArn) {
        return call("StartExecution", request().put("stateMachineArn", stateMachineArn).put("input", "{}"))
                .path("executionArn").asText();
    }

    private JsonNode describeStateMachine(String arn) {
        return call("DescribeStateMachine", request().put("stateMachineArn", arn));
    }

    private JsonNode describeExecution(String executionArn) {
        return call("DescribeExecution", request().put("executionArn", executionArn));
    }

    private JsonNode executionHistory(String executionArn) {
        return call("GetExecutionHistory", request().put("executionArn", executionArn)).path("events");
    }

    private void awaitWorkerParked() throws InterruptedException {
        assertTrue(workerParked.await(SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the worker never reached its Wait");
    }

    /** Blocks until the execution's worker has handed it back for the last time, then describes it. */
    private JsonNode awaitEnded(String executionArn) throws InterruptedException, ExecutionException {
        try {
            workerHandedBack.get(SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("execution was still " + describeExecution(executionArn), e);
        }
        return describeExecution(executionArn);
    }

    private static double seconds(Instant instant) {
        return EpochSeconds.fromMillis(instant.toEpochMilli());
    }
}

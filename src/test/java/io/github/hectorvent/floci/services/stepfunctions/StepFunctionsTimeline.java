package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.TestTime;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The real Step Functions service, executor and JSON handler on one {@link TestTime} timeline, with
 * no HTTP and no container: every pause a state machine can make (a Wait, a Retry backoff, a
 * {@code .sync} poll) parks in {@link TestTime#sleep}, and every bounded wait (a task token, a
 * Parallel or Map join, a nested execution, the activity long poll) ends when the timeline reaches
 * its timeout. Requests go through {@link StepFunctionsJsonHandler}, so what a test reads back is
 * the wire response.
 *
 * <p>Before advancing, wait for the workers to be at rest: {@link TestTime#awaitSleepers} for the
 * ones pausing, {@link TimelineWaits#awaitWaiting} for the ones in a bounded wait.
 */
final class StepFunctionsTimeline implements AutoCloseable {

    static final String REGION = "us-east-1";
    static final String ACCOUNT = "000000000000";
    /** The shipped {@code floci.services.stepfunctions.max-wait-seconds}. */
    static final int CONFIGURED_MAX_WAIT_SECONDS = 30;

    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/test-role";
    private static final long ENDED_TIMEOUT_SECONDS = 10;
    /** The name {@link AslExecutor} gives the threads that run executions. */
    private static final String WORKER_THREAD_NAME = "sfn-executor";

    private final ObjectMapper mapper = new ObjectMapper();
    private final TestTime time = TestTime.create();
    private final TimelineWaits waits = new TimelineWaits(time);
    private final Map<String, CompletableFuture<Void>> recorded = new ConcurrentHashMap<>();
    private final AslExecutor executor;
    private final StepFunctionsService service;
    private final StepFunctionsJsonHandler handler;

    @SuppressWarnings("unchecked")
    StepFunctionsTimeline() {
        AtomicReference<StepFunctionsService> serviceReference = new AtomicReference<>();
        Instance<StepFunctionsService> serviceInstance = mock(Instance.class);
        when(serviceInstance.get()).thenAnswer(invocation -> serviceReference.get());
        executor = AslExecutorFixture.builder()
                .objectMapper(mapper)
                .sfnService(serviceInstance)
                .clock(time.clock())
                .sleeper(time::sleepNanos)
                .nanoTime(time::nanoTime)
                .waits(waits)
                .nestedWaiter(waits)
                .maxWaitSeconds(CONFIGURED_MAX_WAIT_SECONDS)
                .build();
        // The last thing a worker does with its execution is hand it back to be stored, whatever
        // ended it, so that write is where the execution is known to have no worker left. A worker
        // also stores the nested executions it starts (still RUNNING) and stops, which is why
        // awaitEnded is for the executions a test started itself.
        StorageBackend<String, Execution> executions = new InMemoryStorage<>() {
            @Override
            public synchronized void put(String key, Execution execution) {
                super.put(key, execution);
                if (WORKER_THREAD_NAME.equals(Thread.currentThread().getName())
                        && !"RUNNING".equals(execution.getStatus())) {
                    recorded(execution.getExecutionArn()).complete(null);
                }
            }
        };
        service = new StepFunctionsService(
                new InMemoryStorageFactory(ACCOUNT).withStore("sfn-executions.json", executions),
                new RegionResolver(REGION, ACCOUNT), executor, mapper, mock(SfnMockLoader.class),
                mock(StepFunctionsEventPublisher.class), time.clock(), time::nanoTime, waits);
        serviceReference.set(service);
        handler = new StepFunctionsJsonHandler(service, mapper, time.clock());
    }

    TestTime time() {
        return time;
    }

    TimelineWaits waits() {
        return waits;
    }

    StepFunctionsService service() {
        return service;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    /** One request through the JSON handler, answered with the response body. */
    JsonNode call(String action, ObjectNode request) {
        return (JsonNode) handler.handle(action, request, REGION).getEntity();
    }

    ObjectNode request() {
        return mapper.createObjectNode();
    }

    String createStateMachine(String name, String definition) {
        return call("CreateStateMachine", request().put("name", name).put("definition", definition)
                .put("roleArn", ROLE_ARN)).path("stateMachineArn").asText();
    }

    String createExpressStateMachine(String name, String definition) {
        return call("CreateStateMachine", request().put("name", name).put("definition", definition)
                .put("roleArn", ROLE_ARN).put("type", "EXPRESS")).path("stateMachineArn").asText();
    }

    String createActivity(String name) {
        return call("CreateActivity", request().put("name", name)).path("activityArn").asText();
    }

    String startExecution(String stateMachineArn, String input) {
        return call("StartExecution", request().put("stateMachineArn", stateMachineArn).put("input", input))
                .path("executionArn").asText();
    }

    JsonNode describeExecution(String executionArn) {
        return call("DescribeExecution", request().put("executionArn", executionArn));
    }

    JsonNode executionHistory(String executionArn) {
        return call("GetExecutionHistory", request().put("executionArn", executionArn)).path("events");
    }

    List<String> eventTypes(String executionArn) {
        return eventTypes(executionHistory(executionArn));
    }

    static List<String> eventTypes(JsonNode events) {
        List<String> types = new ArrayList<>();
        events.forEach(event -> types.add(event.path("type").asText()));
        return types;
    }

    /** The task token of the activity task a worker is handed, which must already be on its way. */
    String getActivityTask(String activityArn) {
        return call("GetActivityTask", request().put("activityArn", activityArn).put("workerName", "worker"))
                .path("taskToken").asText();
    }

    void sendTaskHeartbeat(String taskToken) {
        call("SendTaskHeartbeat", request().put("taskToken", taskToken));
    }

    void stopExecution(String executionArn, String error, String cause) {
        call("StopExecution", request().put("executionArn", executionArn).put("error", error).put("cause", cause));
    }

    void sendTaskSuccess(String taskToken, String output) {
        call("SendTaskSuccess", request().put("taskToken", taskToken).put("output", output));
    }

    /**
     * Blocks until the execution's worker has handed it back for the last time, then answers
     * DescribeExecution. That covers a worker that comes out of its state after StopExecution
     * already ended the execution: once this returns, nothing is left that could write to it.
     * Only for an execution the test started, not one nested under another.
     */
    JsonNode awaitEnded(String executionArn) {
        try {
            recorded(executionArn).get(ENDED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("execution was still " + describeExecution(executionArn) + " with "
                    + time.sleepers() + " sleeper(s) and " + waits.waiting() + " bounded wait(s) at "
                    + time.clock().instant(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + executionArn, e);
        } catch (ExecutionException e) {
            throw new AssertionError(e);
        }
        return describeExecution(executionArn);
    }

    private CompletableFuture<Void> recorded(String executionArn) {
        return recorded.computeIfAbsent(executionArn, arn -> new CompletableFuture<>());
    }

    /** Interrupts whatever the workers are still parked in, so no thread outlives the test. */
    @Override
    public void close() {
        executor.stop();
    }
}

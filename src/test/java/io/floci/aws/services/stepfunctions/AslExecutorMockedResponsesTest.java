package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.lambda.LambdaExecutorService;
import io.floci.aws.services.stepfunctions.model.Execution;
import io.floci.aws.services.stepfunctions.model.HistoryEvent;
import io.floci.aws.services.stepfunctions.model.MockedResponseStep;
import io.floci.aws.services.stepfunctions.model.MockedTestCase;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import io.floci.aws.testing.TestTime;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class AslExecutorMockedResponsesTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String UNSUPPORTED_RESOURCE = "arn:aws:states:::unsupported:invoke";
    /** The {@code IntervalSeconds} of the retriers below, with a {@code BackoffRate} of 1. */
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);
    private static final Duration ONE_NANOSECOND = Duration.ofNanos(1);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TestTime time = TestTime.create();
    private LambdaExecutorService lambdaExecutor;
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() {
        lambdaExecutor = mock(LambdaExecutorService.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().stepfunctions().maxWaitSeconds()).thenReturn(30);

        executor = AslExecutorFixture.builder()
                .lambdaExecutor(lambdaExecutor)
                .objectMapper(objectMapper)
                .config(config)
                .vertx(vertx)
                .customResourceLiveness(null)
                .clock(time.clock())
                .sleeper(time::sleepNanos)
                .nanoTime(time::nanoTime)
                .build();
    }

    @Test
    void mockedReturnReplacesUnsupportedResource() throws Exception {
        MockedTestCase mocks = testCase(Map.of("Call API", List.of(
                returnStep(0, 0, "{\"StatusCode\": 200, \"ResponseBody\": {\"id\": 1}}"))));

        Execution execution = run("""
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE), mocks);

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals(200, output.path("StatusCode").asInt());
        assertEquals(1, output.path("ResponseBody").path("id").asInt());
        verifyNoInteractions(lambdaExecutor);
    }

    @Test
    void mockedThrowKeepsErrorAndCauseThroughCatch() throws Exception {
        MockedTestCase mocks = testCase(Map.of("Call API", List.of(
                throwStep(0, 0, "ApiGateway.422", "Unprocessable"))));

        Execution execution = run("""
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Catch": [{
                        "ErrorEquals": ["ApiGateway.422"],
                        "Next": "HandleError"
                      }]
                    },
                    "HandleError": {
                      "Type": "Pass",
                      "End": true
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE), mocks);

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals("ApiGateway.422", output.path("Error").asText());
        assertEquals("Unprocessable", output.path("Cause").asText());
    }

    @Test
    void attemptKeyedMocksDriveRetry() throws Exception {
        MockedTestCase mocks = testCase(Map.of("Call API", List.of(
                throwStep(0, 0, "ApiGateway.429", "Too many requests"),
                returnStep(1, 2, "{\"retried\": true}"))));

        Execution execution = runThroughOneBackoff("""
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["ApiGateway.429"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 2
                      }]
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE), mocks);

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path("retried").asBoolean());
    }

    @Test
    void mockedResponseIndexContinuesAcrossMapIterations() throws Exception {
        MockedTestCase mocks = testCase(Map.of("Call API", List.of(
                returnStep(0, 0, "{\"Payload\": \"first\"}"),
                returnStep(1, 1, "{\"Payload\": \"second\"}"))));

        Execution execution = run("""
                {
                  "StartAt": "Each",
                  "States": {
                    "Each": {
                      "Type": "Map",
                      "ItemsPath": "$.ids",
                      "MaxConcurrency": 1,
                      "ItemProcessor": {
                        "StartAt": "Call API",
                        "States": {
                          "Call API": {
                            "Type": "Task",
                            "Resource": "%s",
                            "OutputPath": "$.Payload",
                            "End": true
                          }
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE), mocks, "{\"ids\": [1, 2]}");

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals(List.of("first", "second"),
                objectMapper.readValue(execution.getOutput(), List.class));
    }

    @Test
    void mockedResponseIndexRestartsForEachExecution() throws Exception {
        MockedTestCase mocks = testCase(Map.of("Call API", List.of(
                returnStep(0, 0, "{\"value\": \"first\"}"),
                returnStep(1, 1, "{\"value\": \"second\"}"))));
        String definition = """
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE);

        Execution firstExecution = run(definition, mocks, "{}", "first-execution");
        Execution secondExecution = run(definition, mocks, "{}", "second-execution");

        assertEquals("first", objectMapper.readTree(firstExecution.getOutput()).path("value").asText());
        assertEquals("first", objectMapper.readTree(secondExecution.getOutput()).path("value").asText());
    }

    @Test
    void missingAttemptEntryFailsExecution() throws Exception {
        MockedTestCase mocks = testCase(Map.of("Call API", List.of(
                throwStep(0, 0, "ApiGateway.429", "Too many requests"))));

        Execution execution = runThroughOneBackoff("""
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["ApiGateway.429"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 2
                      }]
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE), mocks);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertTrue(execution.getCause().contains("attempt 1"));
    }

    @Test
    void statesRuntimeIsNotRetriedOrCaughtEvenWhenNamedExplicitly() throws Exception {
        MockedTestCase mocks = testCase(Map.of("Call API", List.of(
                throwStep(0, 0, "ApiGateway.429", "Too many requests"))));

        Execution execution = runThroughOneBackoff("""
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [
                        {
                          "ErrorEquals": ["ApiGateway.429"],
                          "IntervalSeconds": 1,
                          "BackoffRate": 1.0,
                          "MaxAttempts": 2
                        },
                        {
                          "ErrorEquals": ["States.Runtime"],
                          "MaxAttempts": 3
                        }
                      ],
                      "Catch": [{
                        "ErrorEquals": ["States.Runtime"],
                        "Next": "ShouldNotCatch"
                      }]
                    },
                    "ShouldNotCatch": {"Type": "Pass", "End": true}
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE), mocks);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertTrue(execution.getCause().contains("attempt 1"));
    }

    private MockedTestCase testCase(Map<String, List<MockedResponseStep>> stateResponses) {
        return new MockedTestCase("mock-test", "Case", stateResponses);
    }

    private MockedResponseStep returnStep(int from, int to, String json) throws Exception {
        return new MockedResponseStep(from, to, objectMapper.readTree(json), null, null);
    }

    private MockedResponseStep throwStep(int from, int to, String error, String cause) {
        return new MockedResponseStep(from, to, null, error, cause);
    }

    /** Runs a definition that must not pause; one that does fails instead of waiting. */
    private Execution run(String definition, MockedTestCase mocks) {
        return run(definition, mocks, "{}");
    }

    private Execution run(String definition, MockedTestCase mocks, String input) {
        return run(definition, mocks, input, "mock-test-execution");
    }

    private Execution run(String definition, MockedTestCase mocks, String input, String executionName) {
        Execution execution = AslExecutorFixture.finished(
                AslExecutorFixture.start(() -> execute(definition, mocks, input, executionName)));
        assertEquals(0, time.sleepers());
        return execution;
    }

    /**
     * Runs a definition whose first attempt is retried after {@link #RETRY_INTERVAL}: one
     * nanosecond before the interval is over the run is still backing off, and it needs no further
     * time once it is.
     */
    private Execution runThroughOneBackoff(String definition, MockedTestCase mocks) {
        CompletableFuture<Execution> running = AslExecutorFixture.start(
                () -> execute(definition, mocks, "{}", "mock-test-execution"));
        time.awaitSleepers(1);
        time.advance(RETRY_INTERVAL.minus(ONE_NANOSECOND));
        assertEquals(1, time.sleepers());
        assertFalse(running.isDone());
        time.advance(ONE_NANOSECOND);
        Execution execution = AslExecutorFixture.finished(running);
        assertEquals(0, time.sleepers());
        return execution;
    }

    private Execution execute(String definition, MockedTestCase mocks, String input, String executionName) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("mock-test");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:mock-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName(executionName);
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:mock-test:%s".formatted(REGION, ACCOUNT, executionName));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        ArrayList<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, mocks, (updated, events) -> {
        });
        return execution;
    }
}

package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.github.hectorvent.floci.testing.TestTime;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retry policy semantics of the ASL executor. Attempt counts, defaults, and backoff
 * behavior were verified against real AWS Step Functions in us-east-1.
 *
 * <p>The backoffs run on a {@link TestTime} timeline: a retrier's pause is its real
 * {@code IntervalSeconds}, and the next attempt is asserted not to have started one nanosecond
 * before the pause is over. {@code StepFunctionsRetryIntegrationTest} keeps a backoff on the real
 * sleeper.
 */
@QuarkusTest
class AslExecutorRetryTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String FLAKY_FUNCTION_NAME = "flaky-lambda";
    private static final String FLAKY_FUNCTION_ARN =
            "arn:aws:lambda:%s:%s:function:%s".formatted(REGION, ACCOUNT, FLAKY_FUNCTION_NAME);
    /** The shipped {@code floci.services.stepfunctions.max-wait-seconds}. */
    private static final int CONFIGURED_MAX_WAIT_SECONDS = 30;
    private static final Duration ONE_NANOSECOND = Duration.ofNanos(1);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TestTime time = TestTime.create();
    private LambdaExecutorService lambdaExecutor;
    private LambdaFunctionStore functionStore;
    private LambdaFunction flakyFunction;
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() {
        lambdaExecutor = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);
        flakyFunction = new LambdaFunction();
        flakyFunction.setFunctionName(FLAKY_FUNCTION_NAME);
        flakyFunction.setFunctionArn(FLAKY_FUNCTION_ARN);
        when(functionStore.get(REGION, FLAKY_FUNCTION_NAME)).thenReturn(Optional.of(flakyFunction));

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.StepFunctionsServiceConfig stepFunctionsConfig =
                mock(EmulatorConfig.StepFunctionsServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.stepfunctions()).thenReturn(stepFunctionsConfig);
        when(stepFunctionsConfig.maxWaitSeconds()).thenReturn(CONFIGURED_MAX_WAIT_SECONDS);

        executor = AslExecutorFixture.builder()
                .lambdaExecutor(lambdaExecutor)
                .functionStore(functionStore)
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
    void subsecondCeilingCapsBackoffBeforeJitter() throws Exception {
        assertEquals(0.1, AslExecutor.retryDelaySeconds(objectMapper.readTree("""
                {"IntervalSeconds":5,"BackoffRate":2,"MaxDelaySeconds":3}
                """), 3, 0.5, 0.1));
        assertEquals(0.05, AslExecutor.retryDelaySeconds(objectMapper.readTree("""
                {"IntervalSeconds":5,"BackoffRate":2,"MaxDelaySeconds":3,"JitterStrategy":"FULL"}
                """), 3, 0.5, 0.1));
    }

    @Test
    void retriesUntilTheTaskSucceeds() throws Exception {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"))
                .thenReturn(new InvokeResult(200, null,
                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null, "req-2"));

        Execution execution = runThroughOneBackoff(Duration.ofSeconds(1), """
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["Boom"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 2
                      }]
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path("ok").asBoolean());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void exhaustedRetriesFailWithOriginalError() {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"));

        Execution execution = runThroughOneBackoff(Duration.ofSeconds(1), """
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["Boom"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 1
                      }]
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Boom", execution.getError());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void backoffRateMultipliesThePauseBeforeEachFurtherAttempt() {
        alwaysFail();

        CompletableFuture<Execution> running = AslExecutorFixture.start(() -> execute("""
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["Boom"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 2.0,
                        "MaxAttempts": 3
                      }]
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN), "{}"));

        pauseUntilTheNextAttempt(running, Duration.ofSeconds(1), 1);
        pauseUntilTheNextAttempt(running, Duration.ofSeconds(2), 2);
        pauseUntilTheNextAttempt(running, Duration.ofSeconds(4), 3);

        Execution execution = AslExecutorFixture.finished(running);
        assertEquals("FAILED", execution.getStatus());
        assertEquals("Boom", execution.getError());
        verifyAttempts(4);
        assertEquals(0, time.sleepers());
    }

    @Test
    void anIntervalPastTheConfiguredWaitCeilingPausesForTheCeiling() {
        alwaysFail();

        Execution execution = runThroughOneBackoff(Duration.ofSeconds(CONFIGURED_MAX_WAIT_SECONDS), """
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["Boom"],
                        "IntervalSeconds": 100,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 1
                      }]
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Boom", execution.getError());
        verifyAttempts(2);
    }

    @Test
    void lambdaServiceErrorRetrierDoesNotMatchFunctionError() {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"));

        Execution execution = run("""
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["Lambda.ClientExecutionTimeoutException", "Lambda.ServiceException",
                                        "Lambda.AWSLambdaException", "Lambda.SdkClientException"],
                        "IntervalSeconds": 1,
                        "MaxAttempts": 3
                      }]
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Boom", execution.getError());
        verify(lambdaExecutor, times(1))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void exhaustedRetrierFallsThroughToCatch() throws Exception {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"));

        Execution execution = runThroughOneBackoff(Duration.ofSeconds(1), """
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["States.ALL"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 1
                      }],
                      "Catch": [{
                        "ErrorEquals": ["States.ALL"],
                        "Next": "HandleError"
                      }]
                    },
                    "HandleError": {
                      "Type": "Pass",
                      "End": true
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals("Boom",
                objectMapper.readTree(execution.getOutput()).path("Error").asText());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void parallelStateRetriesFailingBranch() throws Exception {
        failOnceThenSucceed();

        Execution execution = runThroughOneBackoff(Duration.ofSeconds(1), """
                {
                  "StartAt": "Par",
                  "States": {
                    "Par": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "Inner",
                        "States": {"Inner": {"Type": "Task", "Resource": "%s", "End": true}}
                      }],
                      "Retry": [{
                        "ErrorEquals": ["Boom"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 1
                      }],
                      "End": true
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path(0).path("ok").asBoolean());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void parallelBranchFailureReachesCatch() throws Exception {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"));

        Execution execution = run("""
                {
                  "StartAt": "Par",
                  "States": {
                    "Par": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "Inner",
                        "States": {"Inner": {"Type": "Task", "Resource": "%s", "End": true}}
                      }],
                      "Catch": [{
                        "ErrorEquals": ["States.ALL"],
                        "Next": "HandleError"
                      }],
                      "End": true
                    },
                    "HandleError": {"Type": "Pass", "End": true}
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals("Boom",
                objectMapper.readTree(execution.getOutput()).path("Error").asText());
        verify(lambdaExecutor, times(1))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void taskInsideParallelBranchRetriesIndependently() throws Exception {
        failOnceThenSucceed();

        Execution execution = runThroughOneBackoff(Duration.ofSeconds(1), """
                {
                  "StartAt": "Par",
                  "States": {
                    "Par": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "Inner",
                        "States": {
                          "Inner": {
                            "Type": "Task",
                            "Resource": "%s",
                            "End": true,
                            "Retry": [{
                              "ErrorEquals": ["Boom"],
                              "IntervalSeconds": 1,
                              "BackoffRate": 1.0,
                              "MaxAttempts": 1
                            }]
                          }
                        }
                      }],
                      "End": true
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path(0).path("ok").asBoolean());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void mapStateRetriesFailingIteration() throws Exception {
        failOnceThenSucceed();

        Execution execution = runThroughOneBackoff(Duration.ofSeconds(1), """
                {
                  "StartAt": "Loop",
                  "States": {
                    "Loop": {
                      "Type": "Map",
                      "ItemsPath": "$.items",
                      "ItemProcessor": {
                        "ProcessorConfig": {"Mode": "INLINE"},
                        "StartAt": "Inner",
                        "States": {"Inner": {"Type": "Task", "Resource": "%s", "End": true}}
                      },
                      "Retry": [{
                        "ErrorEquals": ["Boom"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 1
                      }],
                      "End": true
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN), "{\"items\": [1]}");

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path(0).path("ok").asBoolean());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void branchErrorStillFailsTheExecution() {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenThrow(new AssertionError("boom"));

        Execution execution = run("""
                {
                  "StartAt": "Par",
                  "States": {
                    "Par": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "Inner",
                        "States": {"Inner": {"Type": "Task", "Resource": "%s", "End": true}}
                      }],
                      "End": true
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
    }

    @Test
    void retryDelayFollowsBackoffAndCaps() throws Exception {
        assertEquals(10.0, delay("{\"IntervalSeconds\": 10, \"BackoffRate\": 2.0}", 1, 0.5));
        assertEquals(20.0, delay("{\"IntervalSeconds\": 10, \"BackoffRate\": 2.0}", 2, 0.5));
        assertEquals(15.0, delay("{\"IntervalSeconds\": 10, \"BackoffRate\": 2.0, \"MaxDelaySeconds\": 15}", 2, 0.5));
        assertEquals(30.0, delay("{\"IntervalSeconds\": 20, \"BackoffRate\": 2.0}", 2, 0.5));
    }

    @Test
    void fullJitterScalesTheDelayByTheRandomFactor() throws Exception {
        String retrier = "{\"IntervalSeconds\": 10, \"BackoffRate\": 1.0, \"JitterStrategy\": \"FULL\"}";
        assertEquals(0.0, delay(retrier, 1, 0.0));
        assertEquals(5.0, delay(retrier, 1, 0.5));
        assertEquals(10.0, delay("{\"IntervalSeconds\": 10, \"BackoffRate\": 1.0, \"JitterStrategy\": \"NONE\"}", 1, 0.5));
    }

    @Test
    void theDelayCapIsTheConfiguredWaitCeiling() throws Exception {
        String retrier = "{\"IntervalSeconds\": 100, \"BackoffRate\": 2.0}";
        assertEquals(30.0, delay(retrier, 2, 0.5));
        assertEquals(120.0, delay(retrier, 2, 0.5, 120));
    }

    private double delay(String retrierJson, int attemptsUsed, double random) throws Exception {
        return delay(retrierJson, attemptsUsed, random, 30);
    }

    private double delay(String retrierJson, int attemptsUsed, double random, int maxWaitSeconds) throws Exception {
        return AslExecutor.retryDelaySeconds(objectMapper.readTree(retrierJson), attemptsUsed, random, maxWaitSeconds);
    }

    private void alwaysFail() {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"));
    }

    private void failOnceThenSucceed() {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"))
                .thenReturn(new InvokeResult(200, null,
                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null, "req-2"));
    }

    /**
     * Runs a definition that backs off exactly once. The pause is the retrier's own: one nanosecond
     * before it is over the run is still in it with one attempt made, and it finishes without any
     * further time passing once it is.
     */
    private Execution runThroughOneBackoff(Duration pause, String definition) {
        return runThroughOneBackoff(pause, definition, "{}");
    }

    private Execution runThroughOneBackoff(Duration pause, String definition, String input) {
        CompletableFuture<Execution> running = AslExecutorFixture.start(() -> execute(definition, input));
        pauseUntilTheNextAttempt(running, pause, 1);
        Execution execution = AslExecutorFixture.finished(running);
        assertEquals(0, time.sleepers());
        return execution;
    }

    /**
     * The run has made {@code attempts} attempts and is backing off: the next one does not start
     * until {@code pause} is over.
     */
    private void pauseUntilTheNextAttempt(CompletableFuture<Execution> running, Duration pause, int attempts) {
        time.awaitSleepers(1);
        verifyAttempts(attempts);
        time.advance(pause.minus(ONE_NANOSECOND));
        assertEquals(1, time.sleepers());
        assertFalse(running.isDone());
        verifyAttempts(attempts);
        time.advance(ONE_NANOSECOND);
    }

    private void verifyAttempts(int attempts) {
        verify(lambdaExecutor, times(attempts))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    /** Runs a definition that must not pause; one that does fails instead of waiting. */
    private Execution run(String definition) {
        Execution execution = AslExecutorFixture.finished(AslExecutorFixture.start(() -> execute(definition, "{}")));
        assertEquals(0, time.sleepers());
        return execution;
    }

    private Execution execute(String definition, String input) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("retry-test");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:retry-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("retry-test-execution");
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:retry-test:retry-test-execution".formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        ArrayList<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }
}

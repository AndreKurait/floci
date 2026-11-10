package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.stubbing.Answer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Express execution, synchronous or not, is one AWS ends with States.Timeout once it has run for
 * its maximum execution time. Once it has timed out it runs no further state, and the first
 * outcome published, the timeout or one the execution reached in time, is the one that stands.
 *
 * <p>Every case runs on a {@link TestTime} timeline against the real limits, five minutes for
 * Express and a year for Standard. A Wait state parks until the test advances to the instant under
 * test, and a slow integration is one that moves the timeline on by the time it took, so none takes
 * real time.
 */
class AslExecutorSyncTimeoutTest {

    /** The longest an Express execution may run, {@code AslExecutor.EXPRESS_MAX_EXECUTION_TIME}. */
    private static final Duration EXPRESS_MAX_EXECUTION_TIME = Duration.ofMinutes(5);
    /** A Wait that outlasts the Express limit; the Wait cap is raised to let it. */
    private static final Duration LONG_WAIT = Duration.ofMinutes(10);
    private static final Duration ONE_NANOSECOND = Duration.ofNanos(1);
    private static final long ENDED_TIMEOUT_SECONDS = 10;
    /** The name {@link AslExecutor} gives the threads that run executions. */
    private static final String WORKER_THREAD_NAME = "sfn-executor";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:slow";
    private static final Set<String> TERMINAL_EVENTS =
            Set.of("ExecutionSucceeded", "ExecutionFailed", "ExecutionTimedOut", "ExecutionAborted");
    private static final String WAIT_THEN_PASS = """
            {"StartAt":"Pause","States":{
              "Pause":{"Type":"Wait","Seconds":%d,"Next":"After"},
              "After":{"Type":"Pass","End":true}}}
            """.formatted(LONG_WAIT.toSeconds());

    private final LambdaExecutorService lambdaExecutor = mock(LambdaExecutorService.class);
    private final LambdaFunctionStore functionStore = mock(LambdaFunctionStore.class);
    private final TestTime time = TestTime.create();
    private final TimelineWaits timeline = new TimelineWaits(time);
    private final AslExecutor executor = executor(BoundedWaits.BLOCKING);
    private final List<HistoryEvent> history = new CopyOnWriteArrayList<>();
    /** Released when the worker hands the execution back, which is the last thing it does with it. */
    private final CountDownLatch workerEnded = new CountDownLatch(1);

    @AfterEach
    void stopWorkers() {
        executor.stop();
    }

    @Test
    void executionPastItsMaximumTimeRunsNoFurtherStateAndStaysTimedOut() {
        StateMachine stateMachine = stateMachine(WAIT_THEN_PASS);
        Execution execution = execution(stateMachine);

        CompletableFuture<Execution> answered = AslExecutorFixture.start(() -> {
            executor.executeSync(stateMachine, execution, history, this::handedBack);
            return execution;
        });
        advanceToTheMaximumTime(execution);

        AslExecutorFixture.finished(answered);
        assertEndedTimedOut(execution);
        assertFalse(history.stream().anyMatch(event -> "PassStateEntered".equals(event.getType())),
                "the state after the timeout ran");
    }

    @Test
    void lastStateFinishingAtTheMaximumTimeStillEndsTimedOut() {
        lambdaAnswers(invocation -> {
            time.sleepAdvancing(EXPRESS_MAX_EXECUTION_TIME);
            return new InvokeResult(200, null, bytes("{}"), null, "late-success");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        executor.executeSync(stateMachine, execution, history, this::handedBack);

        assertEndedTimedOut(execution);
    }

    @Test
    void lastStateFinishingOneNanosecondInsideTheMaximumTimeSucceeds() {
        lambdaAnswers(invocation -> {
            time.sleepAdvancing(EXPRESS_MAX_EXECUTION_TIME.minus(ONE_NANOSECOND));
            return new InvokeResult(200, null, bytes("{}"), null, "in-time");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        executor.executeSync(stateMachine, execution, history, this::handedBack);

        awaitWorkerEnded();
        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals("ExecutionSucceeded", history.getLast().getType());
    }

    @Test
    void lastStateFailingAtTheMaximumTimeStillEndsTimedOut() {
        lambdaAnswers(invocation -> {
            time.sleepAdvancing(EXPRESS_MAX_EXECUTION_TIME);
            return new InvokeResult(200, "Unhandled",
                    bytes("{\"errorType\":\"Boom\",\"errorMessage\":\"late\"}"), null, "late-failure");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        executor.executeSync(stateMachine, execution, history, this::handedBack);

        assertEndedTimedOut(execution);
    }

    @Test
    void lastStateThrowingAtTheMaximumTimeStillEndsTimedOut() {
        lambdaAnswers(invocation -> {
            time.sleepAdvancing(EXPRESS_MAX_EXECUTION_TIME);
            throw new IllegalStateException("runtime broke after the deadline");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        executor.executeSync(stateMachine, execution, history, this::handedBack);

        assertEndedTimedOut(execution);
    }

    @Test
    void expressExecutionStartedAsynchronouslyEndsAtItsMaximumTime() {
        StateMachine stateMachine = stateMachine(WAIT_THEN_PASS);
        stateMachine.setType("EXPRESS");
        Execution execution = execution(stateMachine);

        executor.executeAsync(stateMachine, execution, history, this::handedBack);
        advanceToTheMaximumTime(execution);

        assertEndedTimedOut(execution);
        assertFalse(history.stream().anyMatch(event -> "PassStateEntered".equals(event.getType())),
                "the state after the timeout ran");
    }

    @Test
    void standardExecutionIsNotLimitedToTheExpressBudget() {
        StateMachine stateMachine = stateMachine(WAIT_THEN_PASS);
        stateMachine.setType("STANDARD");
        Execution execution = execution(stateMachine);

        executor.executeAsync(stateMachine, execution, history, this::handedBack);
        time.awaitSleepers(1);
        time.advance(EXPRESS_MAX_EXECUTION_TIME);
        assertStillInItsWait(execution);
        time.advance(LONG_WAIT.minus(EXPRESS_MAX_EXECUTION_TIME).minus(ONE_NANOSECOND));
        assertStillInItsWait(execution);
        time.advance(ONE_NANOSECOND);

        awaitWorkerEnded();
        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals("ExecutionSucceeded", history.getLast().getType());
        assertTrue(history.stream().anyMatch(event -> "PassStateEntered".equals(event.getType())));
    }

    @ParameterizedTest
    @CsvSource({"31535999, SUCCEEDED", "31536000, TIMED_OUT"})
    void standardExecutionHasAOneYearQuota(long elapsedSeconds, String expectedStatus) {
        lambdaAnswers(invocation -> {
            time.sleepAdvancing(Duration.ofSeconds(elapsedSeconds));
            return new InvokeResult(200, null, bytes("{}"), null, "completed");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        executor.executeAsync(stateMachine, execution, history, this::handedBack);

        awaitWorkerEnded();
        assertEquals(expectedStatus, execution.getStatus());
        assertEquals(expectedStatus.equals("TIMED_OUT") ? "ExecutionTimedOut" : "ExecutionSucceeded",
                history.getLast().getType());
    }

    @Test
    void callerTimeoutLeavesAnExecutionThatAlreadyEndedAsItWas() {
        Execution execution = execution(stateMachine(WAIT_THEN_PASS));
        execution.setStatus("SUCCEEDED");
        AtomicInteger updates = new AtomicInteger();

        executor.answerTimedOutUnlessEnded(execution, history, (updated, events) -> updates.incrementAndGet());

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals(0, updates.get());
    }

    @Test
    void callerTimeoutEndsAStillRunningExecutionTimedOut() {
        Execution execution = execution(stateMachine(WAIT_THEN_PASS));
        AtomicInteger updates = new AtomicInteger();

        executor.answerTimedOutUnlessEnded(execution, history, (updated, events) -> updates.incrementAndGet());

        assertEquals("TIMED_OUT", execution.getStatus());
        assertNotNull(execution.getStopDate());
        assertEquals(1, updates.get());
    }

    @Test
    void lateOutcomeAfterTheCallerWasAnsweredKeepsTheTimeout() {
        lambdaAnswers(invocation -> {
            time.sleepAdvancing(EXPRESS_MAX_EXECUTION_TIME);
            return new InvokeResult(200, null, bytes("{}"), null, "late-success");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        // The caller's wait ran out first; the worker's late outcome arrives after it.
        executor.answerTimedOutUnlessEnded(execution, history, (updated, events) -> {
        });
        executor.executeSync(stateMachine, execution, history, this::handedBack);

        assertEndedTimedOut(execution);
    }

    @Test
    void callerIsAnsweredTimedOutAtTheMaximumTimeWhileItsIntegrationIsStillRunning() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        lambdaAnswers(invocation -> {
            invoked.countDown();
            released.await();
            return new InvokeResult(200, null, bytes("{}"), null, "late-success");
        });
        AslExecutor timelineExecutor = executor(timeline);
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);
        try {
            CompletableFuture<Execution> answered = AslExecutorFixture.start(() -> {
                timelineExecutor.executeSync(stateMachine, execution, history, this::handedBack);
                return execution;
            });
            assertTrue(invoked.await(ENDED_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the integration never started");
            timeline.awaitWaiting(1);

            time.advance(EXPRESS_MAX_EXECUTION_TIME.minus(ONE_NANOSECOND));
            assertEquals(1, timeline.waiting());
            assertFalse(answered.isDone());
            assertEquals("RUNNING", execution.getStatus());

            time.advance(ONE_NANOSECOND);
            AslExecutorFixture.finished(answered);
            assertEquals("TIMED_OUT", execution.getStatus());
            assertNotNull(execution.getStopDate());
            assertEquals(1, workerEnded.getCount(), "the worker is still in its integration");

            // The integration comes back with a success the execution no longer has time for.
            released.countDown();
            assertEndedTimedOut(execution);
        } finally {
            released.countDown();
            timelineExecutor.stop();
        }
    }

    private AslExecutor executor(BoundedWaits waits) {
        return AslExecutorFixture.builder()
                .lambdaExecutor(lambdaExecutor)
                .functionStore(functionStore)
                .maxWaitSeconds(Math.toIntExact(LONG_WAIT.toSeconds()))
                .clock(time.clock())
                .nanoTime(time::nanoTime)
                .sleeper(time::sleepNanos)
                .waits(waits)
                .build();
    }

    /**
     * The execution is in its Wait: one nanosecond before its maximum time it is still there, and
     * the nanosecond after is the one that ends it.
     */
    private void advanceToTheMaximumTime(Execution execution) {
        time.awaitSleepers(1);
        time.advance(EXPRESS_MAX_EXECUTION_TIME.minus(ONE_NANOSECOND));
        assertStillInItsWait(execution);
        time.advance(ONE_NANOSECOND);
    }

    private void assertStillInItsWait(Execution execution) {
        assertEquals(1, time.sleepers());
        assertEquals("RUNNING", execution.getStatus());
        assertEquals(1, workerEnded.getCount());
        assertFalse(history.stream().anyMatch(event -> TERMINAL_EVENTS.contains(event.getType())),
                "the execution ended early: " + history);
    }

    private void handedBack(Execution execution, List<HistoryEvent> events) {
        if (WORKER_THREAD_NAME.equals(Thread.currentThread().getName())) {
            workerEnded.countDown();
        }
    }

    private void awaitWorkerEnded() {
        try {
            assertTrue(workerEnded.await(ENDED_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the worker never handed the execution back: " + history);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the worker", e);
        }
    }

    private void lambdaAnswers(Answer<InvokeResult> answer) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName("slow");
        function.setFunctionArn(FUNCTION_ARN);
        when(functionStore.get("us-east-1", "slow")).thenReturn(Optional.of(function));
        when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(answer);
    }

    private void assertEndedTimedOut(Execution execution) {
        awaitWorkerEnded();
        assertEquals("TIMED_OUT", execution.getStatus());
        assertEquals("ExecutionTimedOut", history.getLast().getType());
    }

    private static String lambdaTask() {
        return """
                {"StartAt":"Slow","States":{"Slow":{"Type":"Task","Resource":"%s","End":true}}}
                """.formatted(FUNCTION_ARN);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static StateMachine stateMachine(String definition) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("sync-timeout-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:us-east-1:000000000000:stateMachine:sync-timeout-test");
        stateMachine.setDefinition(definition);
        return stateMachine;
    }

    private static Execution execution(StateMachine stateMachine) {
        Execution execution = new Execution();
        execution.setName("sync-timeout-exec");
        execution.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:sync-timeout-test:sync-timeout-exec");
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");
        execution.setStatus("RUNNING");
        return execution;
    }
}

package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two {@code TimeoutSeconds} fields of ASL, each enforced on its own clock: the state
 * machine's total budget and a {@code Task}'s wait for its task token, the second one bounded a
 * second time by {@code HeartbeatSeconds}.
 *
 * <p>Runs the real service and executor on a controlled timeline, so each bound is asserted at its
 * own instant: one nanosecond before it the execution is still RUNNING, at it the execution has
 * ended. {@link StepFunctionsTimeoutSecondsIntegrationTest} keeps one real-time case per blocking
 * primitive behind these bounds.
 */
@Timeout(60)
class StepFunctionsTimeoutSecondsTest {

    private static final Duration ONE_NANO = Duration.ofNanos(1);
    private static final String LINGER_PAST_THE_BUDGET = """
            {
                "StartAt": "Linger",
                "TimeoutSeconds": 3,
                "States": {
                    "Linger": {"Type": "Wait", "Seconds": 5, "End": true}
                }
            }
            """;

    private final StepFunctionsTimeline sfn = new StepFunctionsTimeline();

    @AfterEach
    void stopWorkers() {
        sfn.close();
    }

    @Test
    void stateMachineTimeoutSecondsCutsAWaitLongerThanTheRemainingBudget() {
        String execArn = sfn.startExecution(
                sfn.createStateMachine("top-level-timeout-wait", LINGER_PAST_THE_BUDGET), "{}");
        sfn.time().awaitSleepers(1);

        JsonNode timedOut = advanceToTheBound(execArn, Duration.ofSeconds(3));

        assertEquals("TIMED_OUT", timedOut.path("status").asText());
        assertTrue(timedOut.has("stopDate"), "stopDate must be set on a timed out execution");
        assertFalse(timedOut.has("error"), "TIMED_OUT carries no error key: " + timedOut);
        assertFalse(timedOut.has("cause"), "TIMED_OUT carries no cause key: " + timedOut);
    }

    @Test
    void timedOutExecutionEmitsExecutionTimedOutAndNoStateExitedForTheCutState() {
        String execArn = sfn.startExecution(
                sfn.createStateMachine("top-level-timeout-history", LINGER_PAST_THE_BUDGET), "{}");
        sfn.time().awaitSleepers(1);

        advanceToTheBound(execArn, Duration.ofSeconds(3));

        JsonNode events = sfn.executionHistory(execArn);
        assertEquals(List.of("ExecutionStarted", "WaitStateEntered", "ExecutionTimedOut"),
                StepFunctionsTimeline.eventTypes(events), "history was " + events);
        JsonNode timedOut = events.get(events.size() - 1);
        assertEquals(0, timedOut.path("previousEventId").asLong());
        assertEquals("States.Timeout",
                timedOut.path("executionTimedOutEventDetails").path("error").asText());
        assertFalse(timedOut.path("executionTimedOutEventDetails").has("cause"),
                "ExecutionTimedOut carries only the error: " + timedOut);
    }

    @Test
    void aWaitThatFitsTheBudgetEndsTheExecutionSucceeded() {
        String execArn = sfn.startExecution(sfn.createStateMachine("top-level-timeout-fits", """
                {
                    "StartAt": "Linger",
                    "TimeoutSeconds": 5,
                    "States": {
                        "Linger": {"Type": "Wait", "Seconds": 3, "End": true}
                    }
                }
                """), "{}");
        sfn.time().awaitSleepers(1);

        JsonNode succeeded = advanceToTheBound(execArn, Duration.ofSeconds(3));

        assertEquals("SUCCEEDED", succeeded.path("status").asText(), "execution was " + succeeded);
        assertEquals(List.of("ExecutionStarted", "WaitStateEntered", "WaitStateExited", "ExecutionSucceeded"),
                sfn.eventTypes(execArn));
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForAParallelBranch() {
        // A branch is never cut mid-state, so its Wait sleeps on its own thread. What the budget
        // cuts is the join: the execution ends while the branch is still running, and the
        // Parallel state gets no Exited event and no state after it is entered.
        String smArn = sfn.createStateMachine("top-level-timeout-loop", """
                {
                    "StartAt": "Fork",
                    "TimeoutSeconds": 3,
                    "States": {
                        "Fork": {
                            "Type": "Parallel",
                            "Branches": [{
                                "StartAt": "Linger",
                                "States": {"Linger": {"Type": "Wait", "Seconds": 5, "End": true}}
                            }],
                            "Next": "Unreached"
                        },
                        "Unreached": {"Type": "Pass", "End": true}
                    }
                }
                """);

        String execArn = sfn.startExecution(smArn, "{}");
        sfn.waits().awaitWaiting(1);
        sfn.time().awaitSleepers(1);
        // The budget's 3 seconds end it, two seconds before the branch's Wait would.
        JsonNode timedOut = advanceToTheBound(execArn, Duration.ofSeconds(3));

        assertEquals("TIMED_OUT", timedOut.path("status").asText());
        JsonNode events = sfn.executionHistory(execArn);
        // The branch's Wait was entered and never exited: the budget cut it.
        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ParallelStateStarted",
                        "WaitStateEntered", "ExecutionTimedOut"),
                StepFunctionsTimeline.eventTypes(events), "history was " + events);
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForATaskToken() {
        // Measured against us-east-1: a state machine whose budget is shorter than the Task's own
        // TimeoutSeconds ends TIMED_OUT the instant the budget runs out, and writes nothing about
        // the task it cut. No ActivityTimedOut, no TaskFailed, no ExecutionFailed.
        String activityArn = sfn.createActivity("top-level-timeout-token");
        String smArn = sfn.createStateMachine("top-level-timeout-token", """
                {
                    "StartAt": "Await",
                    "TimeoutSeconds": 3,
                    "States": {
                        "Await": {
                            "Type": "Task",
                            "Resource": "%s",
                            "TimeoutSeconds": 60,
                            "End": true
                        }
                    }
                }
                """.formatted(activityArn));

        String execArn = sfn.startExecution(smArn, "{}");
        sfn.waits().awaitWaiting(1);
        JsonNode timedOut = advanceToTheBound(execArn, Duration.ofSeconds(3));

        assertEquals("TIMED_OUT", timedOut.path("status").asText(), timedOut.toString());
        JsonNode events = sfn.executionHistory(execArn);
        assertEquals(List.of("ExecutionStarted", "TaskStateEntered", "ActivityScheduled",
                "ActivityStarted", "ExecutionTimedOut"), StepFunctionsTimeline.eventTypes(events),
                "history was " + events);
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForANestedExecution() {
        // The child sleeps far past the parent's budget. The parent ends TIMED_OUT where its budget
        // runs out, and the child is aborted with it, as AWS does (measured: ABORTED, no error).
        String childArn = sfn.createStateMachine("nested-child", """
                {
                    "StartAt": "Linger",
                    "States": {"Linger": {"Type": "Wait", "Seconds": 10, "End": true}}
                }
                """);
        String parentArn = sfn.createStateMachine("nested-parent", """
                {
                    "StartAt": "RunChild",
                    "TimeoutSeconds": 3,
                    "States": {
                        "RunChild": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::states:startExecution.sync",
                            "Parameters": {"StateMachineArn": "%s", "Input": {}},
                            "End": true
                        }
                    }
                }
                """.formatted(childArn));

        String execArn = sfn.startExecution(parentArn, "{}");
        sfn.waits().awaitWaiting(1);
        sfn.time().awaitSleepers(1);
        assertEquals(List.of("RUNNING"), childStatuses(childArn));
        JsonNode timedOut = advanceToTheBound(execArn, Duration.ofSeconds(3));

        assertEquals("TIMED_OUT", timedOut.path("status").asText(), timedOut.toString());
        JsonNode events = sfn.executionHistory(execArn);
        assertEquals("ExecutionTimedOut", events.get(events.size() - 1).path("type").asText(),
                "history was " + events);
        assertEquals(List.of("ABORTED"), childStatuses(childArn));
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForMapIterations() {
        // Two iterations run concurrently and each sleeps past the budget, so what the budget cuts
        // is the Map state's wait for them rather than any single iteration.
        String smArn = sfn.createStateMachine("top-level-timeout-map", lingeringMap());

        String execArn = sfn.startExecution(smArn, "{\"items\": [1, 2]}");
        sfn.waits().awaitWaiting(1);
        sfn.time().awaitSleepers(2);
        JsonNode timedOut = advanceToTheBound(execArn, Duration.ofSeconds(3));

        assertEquals("TIMED_OUT", timedOut.path("status").asText(), timedOut.toString());
        JsonNode events = sfn.executionHistory(execArn);
        // Both iterations entered their Wait and neither exited it. They run concurrently, so
        // the order their events interleave in is theirs to choose.
        List<String> types = StepFunctionsTimeline.eventTypes(events);
        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "MapStateStarted"), types.subList(0, 3),
                "history was " + events);
        assertEquals(List.of("MapIterationStarted", "MapIterationStarted", "WaitStateEntered", "WaitStateEntered"),
                types.subList(3, 7).stream().sorted().toList(), "history was " + events);
        assertEquals(List.of("ExecutionTimedOut"), types.subList(7, types.size()), "history was " + events);
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForASingleMapIteration() {
        // A single item runs the Map state's serial path rather than the concurrent one; the
        // budget must cut it the same way it cuts a concurrent iteration above.
        String smArn = sfn.createStateMachine("top-level-timeout-map-single-item", lingeringMap());

        String execArn = sfn.startExecution(smArn, "{\"items\": [1]}");
        sfn.time().awaitSleepers(1);
        JsonNode timedOut = advanceToTheBound(execArn, Duration.ofSeconds(3));

        assertEquals("TIMED_OUT", timedOut.path("status").asText(), timedOut.toString());
        JsonNode events = sfn.executionHistory(execArn);
        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "MapStateStarted", "MapIterationStarted",
                        "WaitStateEntered", "ExecutionTimedOut"),
                StepFunctionsTimeline.eventTypes(events), "history was " + events);
    }

    @Test
    void stateMachineTimeoutSecondsCutsARetryBackoffLongerThanTheRemainingBudget() {
        // The Lambda does not exist, so every attempt fails immediately and the only thing this
        // state spends time on is the backoff between attempts. AWS ends the execution when the
        // budget runs out, mid-backoff, rather than FAILED on the state's own error after it.
        String smArn = sfn.createStateMachine("top-level-timeout-retry", """
                {
                    "StartAt": "Flaky",
                    "TimeoutSeconds": 3,
                    "States": {
                        "Flaky": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::lambda:invoke",
                            "Parameters": {"FunctionName": "absent-function"},
                            "Retry": [{"ErrorEquals": ["States.ALL"], "IntervalSeconds": 10, "MaxAttempts": 1}],
                            "End": true
                        }
                    }
                }
                """);

        String execArn = sfn.startExecution(smArn, "{}");
        sfn.time().awaitSleepers(1);
        JsonNode timedOut = advanceToTheBound(execArn, Duration.ofSeconds(3));

        assertEquals("TIMED_OUT", timedOut.path("status").asText(), timedOut.toString());
        JsonNode events = sfn.executionHistory(execArn);
        assertEquals("ExecutionTimedOut", events.get(events.size() - 1).path("type").asText(),
                "history was " + events);
    }

    @Test
    void taskTimeoutSecondsBoundsTheWholeWaitForTheTaskToken() {
        String activityArn = sfn.createActivity("timeout-seconds-total");
        String smArn = sfn.createStateMachine("task-timeout-seconds", activityTask(activityArn, 3, 0));

        String execArn = sfn.startExecution(smArn, "{}");
        sfn.waits().awaitWaiting(1);
        JsonNode failed = advanceToTheBound(execArn, Duration.ofSeconds(3));

        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("States.Timeout", failed.path("error").asText());
        assertFalse(failed.has("cause"), "a task that ran out of time carries no cause key: " + failed);
    }

    @Test
    void taskHeartbeatSecondsBoundsTheGapBetweenHeartbeats() {
        String activityArn = sfn.createActivity("heartbeat-seconds-gap");
        String smArn = sfn.createStateMachine("task-heartbeat-gap", activityTask(activityArn, 60, 5));
        String execArn = sfn.startExecution(smArn, "{}");

        // A worker picks the task up and then goes silent, so the gap HeartbeatSeconds allows runs
        // out long before the state's TimeoutSeconds does.
        sfn.getActivityTask(activityArn);
        sfn.waits().awaitWaiting(1);
        JsonNode failed = advanceToTheBound(execArn, Duration.ofSeconds(5));

        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("States.Timeout", failed.path("error").asText());
        assertFalse(failed.has("cause"), "a task that missed its heartbeats carries no cause key: " + failed);

        JsonNode events = sfn.executionHistory(execArn);
        assertEquals(List.of("ExecutionStarted", "TaskStateEntered", "ActivityScheduled",
                "ActivityStarted", "ActivityTimedOut", "ExecutionFailed"),
                StepFunctionsTimeline.eventTypes(events), "history was " + events);
        JsonNode timedOut = events.get(events.size() - 2);
        assertEquals("States.Timeout",
                timedOut.path("activityTimedOutEventDetails").path("error").asText());
        assertFalse(timedOut.path("activityTimedOutEventDetails").has("cause"),
                "ActivityTimedOut carries only the error: " + timedOut);
    }

    @ParameterizedTest
    @ValueSource(strings = {"States.HeartbeatTimeout", "States.Timeout"})
    void aCatchMatchesAHeartbeatExpiryUnderEitherErrorName(String errorEquals) throws Exception {
        String activityArn = sfn.createActivity("heartbeat-seconds-catch");
        String smArn = sfn.createStateMachine("task-heartbeat-catch", """
                {
                    "StartAt": "Await",
                    "States": {
                        "Await": {
                            "Type": "Task",
                            "Resource": "%s",
                            "TimeoutSeconds": 60,
                            "HeartbeatSeconds": 5,
                            "Catch": [{"ErrorEquals": ["%s"], "Next": "Recovered"}],
                            "End": true
                        },
                        "Recovered": {"Type": "Pass", "End": true}
                    }
                }
                """.formatted(activityArn, errorEquals));
        String execArn = sfn.startExecution(smArn, "{}");

        sfn.getActivityTask(activityArn);
        sfn.waits().awaitWaiting(1);
        JsonNode caught = advanceToTheBound(execArn, Duration.ofSeconds(5));

        assertEquals("SUCCEEDED", caught.path("status").asText(),
                "Catch on " + errorEquals + " left the execution " + caught);
        assertEquals("States.Timeout",
                sfn.mapper().readTree(caught.path("output").asText()).path("Error").asText(),
                "Catch on " + errorEquals + " reported the wrong error");
    }

    @Test
    void heartbeatsKeepATaskAliveUntilItIsAnswered() throws Exception {
        String activityArn = sfn.createActivity("heartbeat-seconds-reset");
        String smArn = sfn.createStateMachine("task-heartbeat-reset", activityTask(activityArn, 12, 3));
        String execArn = sfn.startExecution(smArn, "{}");

        String taskToken = sfn.getActivityTask(activityArn);
        // Five seconds of work reported every 500 ms: nearly twice the heartbeat gap, so the task
        // survives only if each heartbeat pushes that gap's deadline forward.
        for (int beat = 0; beat < 10; beat++) {
            sfn.waits().awaitWaiting(1);
            sfn.time().advance(Duration.ofMillis(500));
            sfn.sendTaskHeartbeat(taskToken);
        }
        // The last heartbeat, five seconds in, holds the task for three seconds more.
        sfn.waits().awaitWaiting(1);
        sfn.time().advance(Duration.ofSeconds(3).minus(ONE_NANO));
        assertEquals("RUNNING", sfn.describeExecution(execArn).path("status").asText());
        sfn.sendTaskSuccess(taskToken, "{\"answered\":true}");

        JsonNode succeeded = sfn.awaitEnded(execArn);
        assertEquals("SUCCEEDED", succeeded.path("status").asText(), "execution was " + succeeded);
        assertTrue(sfn.mapper().readTree(succeeded.path("output").asText()).path("answered").asBoolean());
    }

    @Test
    void heartbeatsDoNotCarryATaskPastItsTimeoutSeconds() {
        String activityArn = sfn.createActivity("heartbeat-seconds-ceiling");
        String smArn = sfn.createStateMachine("task-heartbeat-ceiling", activityTask(activityArn, 12, 3));
        String execArn = sfn.startExecution(smArn, "{}");

        String taskToken = sfn.getActivityTask(activityArn);
        // A heartbeat every two seconds never lets the three-second gap run out; the last one, ten
        // seconds in, would hold the task until thirteen.
        for (int beat = 0; beat < 5; beat++) {
            sfn.waits().awaitWaiting(1);
            sfn.time().advance(Duration.ofSeconds(2));
            sfn.sendTaskHeartbeat(taskToken);
        }
        sfn.waits().awaitWaiting(1);
        JsonNode failed = advanceToTheBound(execArn, Duration.ofSeconds(2));

        assertEquals("FAILED", failed.path("status").asText(), "execution was " + failed);
        assertEquals("States.Timeout", failed.path("error").asText());
        assertEquals(List.of("ExecutionStarted", "TaskStateEntered", "ActivityScheduled",
                "ActivityStarted", "ActivityTimedOut", "ExecutionFailed"), sfn.eventTypes(execArn));
    }

    /**
     * Moves the timeline to one nanosecond short of {@code bound} from now, where the execution
     * must still be RUNNING, then onto it, and answers the execution as it ended there.
     */
    private JsonNode advanceToTheBound(String execArn, Duration bound) {
        sfn.time().advance(bound.minus(ONE_NANO));
        JsonNode before = sfn.describeExecution(execArn);
        assertEquals("RUNNING", before.path("status").asText(),
                "one nanosecond before its bound the execution was " + before);
        assertFalse(before.has("stopDate"), "a running execution has no stopDate: " + before);
        sfn.time().advance(ONE_NANO);
        return sfn.awaitEnded(execArn);
    }

    private List<String> childStatuses(String childStateMachineArn) {
        JsonNode executions = sfn.call("ListExecutions",
                sfn.request().put("stateMachineArn", childStateMachineArn)).path("executions");
        return executions.findValuesAsText("status");
    }

    private static String lingeringMap() {
        return """
                {
                    "StartAt": "OverItems",
                    "TimeoutSeconds": 3,
                    "States": {
                        "OverItems": {
                            "Type": "Map",
                            "ItemsPath": "$.items",
                            "ItemProcessor": {
                                "StartAt": "Linger",
                                "States": {"Linger": {"Type": "Wait", "Seconds": 10, "End": true}}
                            },
                            "End": true
                        }
                    }
                }
                """;
    }

    private static String activityTask(String activityArn, int timeoutSeconds, int heartbeatSeconds) {
        String heartbeat = heartbeatSeconds > 0 ? ",\"HeartbeatSeconds\": " + heartbeatSeconds : "";
        return """
                {
                    "StartAt": "Await",
                    "States": {
                        "Await": {
                            "Type": "Task",
                            "Resource": "%s",
                            "TimeoutSeconds": %d%s,
                            "End": true
                        }
                    }
                }
                """.formatted(activityArn, timeoutSeconds, heartbeat);
    }
}

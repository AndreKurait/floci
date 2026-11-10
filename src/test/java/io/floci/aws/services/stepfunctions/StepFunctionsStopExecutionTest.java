package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards that {@code StopExecution} stops the execution instead of relabelling it: the status,
 * error and cause it writes are what {@code DescribeExecution} returns, and the worker thread that
 * is still inside the {@code Wait} does not overwrite them when it gets there.
 *
 * <p>Runs on a controlled timeline, so "the worker is inside the Wait" and "the worker has come out
 * of it" are states the test waits for rather than durations it sleeps through.
 */
@Timeout(60)
class StepFunctionsStopExecutionTest {

    private static final Duration WAIT = Duration.ofSeconds(3);
    private static final Duration ONE_NANO = Duration.ofNanos(1);
    private static final String WAITING_MACHINE = """
            {"StartAt": "W", "States": {"W": {"Type": "Wait", "Seconds": %d, "End": true}}}
            """.formatted(WAIT.toSeconds());

    private final StepFunctionsTimeline sfn = new StepFunctionsTimeline();

    @AfterEach
    void stopWorkers() {
        sfn.close();
    }

    @Test
    void stoppedExecutionStaysAbortedAfterTheWaitElapses() {
        String executionArn = startWaitingExecution("stop-survives-wait");

        sfn.stopExecution(executionArn, "Manual", "probe");
        JsonNode stopped = sfn.describeExecution(executionArn);
        assertEquals("ABORTED", stopped.path("status").asText());
        double stopDate = stopped.path("stopDate").asDouble();

        // The stop does not wake the worker: it stays in its Wait until the Wait is over.
        sfn.time().advance(WAIT.minus(ONE_NANO));
        assertEquals(1, sfn.time().sleepers(), "the worker left its Wait before it was over");
        sfn.time().advance(ONE_NANO);

        JsonNode described = sfn.awaitEnded(executionArn);
        assertEquals("ABORTED", described.path("status").asText());
        assertEquals("Manual", described.path("error").asText());
        assertEquals("probe", described.path("cause").asText());
        assertEquals(stopDate, described.path("stopDate").asDouble());
        assertFalse(described.has("output"), "the worker wrote its output over the abort: " + described);
    }

    @Test
    void abortedExecutionEndsItsHistoryAtExecutionAborted() {
        String executionArn = startWaitingExecution("stop-ends-history");

        sfn.stopExecution(executionArn, "Manual", "probe");
        List<String> typesAtTheStop = sfn.eventTypes(executionArn);

        // The worker is still inside the Wait. Let it come out and try to record the state it left.
        sfn.time().advance(WAIT);
        sfn.awaitEnded(executionArn);

        JsonNode events = sfn.executionHistory(executionArn);
        List<String> types = StepFunctionsTimeline.eventTypes(events);
        List<Long> ids = new ArrayList<>();
        events.forEach(event -> ids.add(event.path("id").asLong()));

        assertAll(
                () -> assertEquals(List.of("ExecutionStarted", "WaitStateEntered", "ExecutionAborted"), types,
                        "history continued past the terminal event"),
                () -> assertEquals(typesAtTheStop, types, "the worker added to a sealed history"),
                () -> assertEquals(ids.size(), new HashSet<>(ids).size(),
                        "history repeats an event id: " + ids + " for " + types));
    }

    @Test
    void anExecutionNobodyStopsSucceedsWhenItsWaitIsOver() {
        String executionArn = startWaitingExecution("never-stopped");

        sfn.time().advance(WAIT.minus(ONE_NANO));
        assertEquals("RUNNING", sfn.describeExecution(executionArn).path("status").asText());
        sfn.time().advance(ONE_NANO);

        assertEquals("SUCCEEDED", sfn.awaitEnded(executionArn).path("status").asText());
        assertEquals(List.of("ExecutionStarted", "WaitStateEntered", "WaitStateExited", "ExecutionSucceeded"),
                sfn.eventTypes(executionArn));
    }

    /** Starts an execution and returns once its worker is inside the Wait. */
    private String startWaitingExecution(String name) {
        String executionArn = sfn.startExecution(sfn.createStateMachine(name, WAITING_MACHINE), "{}");
        sfn.time().awaitSleepers(1);
        return executionArn;
    }
}

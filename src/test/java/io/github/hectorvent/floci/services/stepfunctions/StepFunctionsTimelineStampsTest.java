package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.EpochSeconds;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every date Step Functions reports is read from the one injected clock: the start and stop of an
 * execution, each history event, the state's {@code EnteredTime}, and the creation and update
 * dates of state machines, versions, aliases and activities. The timeline starts in the past, so a
 * date taken from the wall clock cannot equal the instant these tests expect.
 */
@Timeout(60)
class StepFunctionsTimelineStampsTest {

    private static final Instant START = TestTime.DEFAULT_START;
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

    private final StepFunctionsTimeline sfn = new StepFunctionsTimeline();

    @AfterEach
    void stopWorkers() {
        sfn.close();
    }

    @Test
    void anExecutionStartsStopsAndRecordsItsHistoryOnTheTimeline() {
        String executionArn = sfn.startExecution(sfn.createStateMachine("stamps", WAIT_THEN_REPORT), "{}");
        sfn.time().awaitSleepers(1);
        assertEquals(seconds(START), sfn.describeExecution(executionArn).path("startDate").asDouble());

        sfn.time().advance(WAIT);
        JsonNode ended = sfn.awaitEnded(executionArn);

        Instant end = START.plus(WAIT);
        assertEquals("SUCCEEDED", ended.path("status").asText());
        assertEquals(seconds(START), ended.path("startDate").asDouble());
        assertEquals(seconds(end), ended.path("stopDate").asDouble());
        assertEquals("{\"entered\":\"" + end + "\",\"started\":\"" + START + "\"}",
                ended.path("output").asText());

        List<String> stamped = new ArrayList<>();
        sfn.executionHistory(executionArn).forEach(event -> stamped.add(event.path("type").asText() + "@"
                + Instant.ofEpochMilli(Math.round(event.path("timestamp").asDouble() * 1000))));
        assertEquals(List.of("ExecutionStarted@" + START, "WaitStateEntered@" + START,
                "WaitStateExited@" + end, "PassStateEntered@" + end, "PassStateExited@" + end,
                "ExecutionSucceeded@" + end), stamped);
    }

    @Test
    void stopExecutionAnswersWithTheStopDateTheExecutionKeeps() {
        String executionArn = sfn.startExecution(sfn.createStateMachine("stopped", WAIT_THEN_REPORT), "{}");
        sfn.time().awaitSleepers(1);
        sfn.time().advance(Duration.ofSeconds(1));

        JsonNode stopped = sfn.call("StopExecution", sfn.request().put("executionArn", executionArn));
        Instant stoppedAt = START.plusSeconds(1);
        assertEquals(seconds(stoppedAt), stopped.path("stopDate").asDouble());

        // The worker comes out of its Wait two seconds after the stop. Stopping again later still
        // reports the instant the execution stopped, which is the one DescribeExecution reports.
        sfn.time().advance(WAIT);
        sfn.awaitEnded(executionArn);
        JsonNode stoppedAgain = sfn.call("StopExecution", sfn.request().put("executionArn", executionArn));
        assertEquals(seconds(stoppedAt), stoppedAgain.path("stopDate").asDouble());
        assertEquals(seconds(stoppedAt), sfn.describeExecution(executionArn).path("stopDate").asDouble());

        JsonNode events = sfn.executionHistory(executionArn);
        JsonNode aborted = events.get(events.size() - 1);
        assertEquals("ExecutionAborted", aborted.path("type").asText());
        assertEquals(seconds(stoppedAt), aborted.path("timestamp").asDouble());
    }

    @Test
    void stateMachineVersionAliasAndActivityDatesAreOnTheTimeline() {
        String stateMachineArn = sfn.createStateMachine("dated", PASS);
        assertEquals(seconds(START), describeStateMachine(stateMachineArn).path("creationDate").asDouble());

        sfn.time().advance(Duration.ofSeconds(10));
        JsonNode updated = sfn.call("UpdateStateMachine", sfn.request().put("stateMachineArn", stateMachineArn)
                .put("definition", PASS.replace("\"P\"", "\"Q\"")).put("publish", true));
        assertEquals(seconds(START.plusSeconds(10)), updated.path("updateDate").asDouble());
        assertEquals(seconds(START), describeStateMachine(stateMachineArn).path("creationDate").asDouble());
        String versionArn = updated.path("stateMachineVersionArn").asText();
        assertEquals(seconds(START.plusSeconds(10)),
                describeStateMachine(versionArn).path("creationDate").asDouble());

        sfn.time().advance(Duration.ofSeconds(10));
        JsonNode routing = sfn.mapper().createArrayNode().add(sfn.mapper().createObjectNode()
                .put("stateMachineVersionArn", versionArn).put("weight", 100));
        JsonNode alias = sfn.call("CreateStateMachineAlias", sfn.request().put("name", "live")
                .set("routingConfiguration", routing));
        assertEquals(seconds(START.plusSeconds(20)), alias.path("creationDate").asDouble());
        String aliasArn = alias.path("stateMachineAliasArn").asText();

        sfn.time().advance(Duration.ofSeconds(10));
        JsonNode aliasUpdate = sfn.call("UpdateStateMachineAlias", sfn.request()
                .put("stateMachineAliasArn", aliasArn).put("description", "later"));
        assertEquals(seconds(START.plusSeconds(30)), aliasUpdate.path("updateDate").asDouble());
        JsonNode describedAlias = sfn.call("DescribeStateMachineAlias",
                sfn.request().put("stateMachineAliasArn", aliasArn));
        assertEquals(seconds(START.plusSeconds(20)), describedAlias.path("creationDate").asDouble());
        assertEquals(seconds(START.plusSeconds(30)), describedAlias.path("updateDate").asDouble());

        String activityArn = sfn.createActivity("dated-activity");
        assertEquals(seconds(START.plusSeconds(30)), sfn.call("DescribeActivity",
                sfn.request().put("activityArn", activityArn)).path("creationDate").asDouble());
    }

    @Test
    void aSynchronousExpressExecutionIsNamedAndDatedOnTheTimeline() {
        String stateMachineArn = sfn.createExpressStateMachine("express-dated", PASS);
        sfn.time().advance(Duration.ofMillis(1500));

        JsonNode ran = sfn.call("StartSyncExecution",
                sfn.request().put("stateMachineArn", stateMachineArn).put("name", "run").put("input", "{}"));

        Instant at = START.plusMillis(1500);
        assertEquals("SUCCEEDED", ran.path("status").asText());
        assertEquals("arn:aws:states:us-east-1:000000000000:express:express-dated:2026-01-01T00:00:01.500Z:run",
                ran.path("executionArn").asText());
        assertEquals(seconds(at), ran.path("startDate").asDouble());
        assertEquals(seconds(at), ran.path("stopDate").asDouble());
    }

    private JsonNode describeStateMachine(String arn) {
        return sfn.call("DescribeStateMachine", sfn.request().put("stateMachineArn", arn));
    }

    private static double seconds(Instant instant) {
        return EpochSeconds.fromMillis(instant.toEpochMilli());
    }
}

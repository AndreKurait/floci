package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The two fixed durations around an activity's task token: the 60 seconds GetActivityTask holds a
 * worker's poll open, and the 5 minutes a token that was answered is still recognized as closed.
 *
 * <p>Runs the real service, executor and JSON handler on a controlled timeline, so each duration is
 * asserted at its own instant: one nanosecond before it nothing has changed.
 */
@Timeout(60)
class StepFunctionsActivityTaskTimingTest {

    private static final Duration ONE_NANO = Duration.ofNanos(1);
    private static final Duration LONG_POLL = Duration.ofSeconds(60);
    private static final Duration CLOSED_TOKEN_RETENTION = Duration.ofMinutes(5);

    private final StepFunctionsTimeline sfn = new StepFunctionsTimeline();

    @AfterEach
    void stopWorkers() {
        sfn.close();
    }

    @Test
    void getActivityTaskAnswersWithoutATaskOnceSixtySecondsHavePassed() {
        String activityArn = sfn.createActivity("idle");

        CompletableFuture<JsonNode> poll = AslExecutorFixture.start(() -> getActivityTask(activityArn));
        sfn.waits().awaitWaiting(1);
        sfn.time().advance(LONG_POLL.minus(ONE_NANO));
        assertEquals(1, sfn.waits().waiting());
        assertFalse(poll.isDone(), "the poll stays open until the sixtieth second");

        sfn.time().advance(ONE_NANO);
        JsonNode answer = AslExecutorFixture.finished(poll);

        assertEquals(sfn.mapper().createObjectNode(), answer);
        assertEquals(0, sfn.waits().waiting());
    }

    @Test
    void aTaskScheduledWhileAWorkerPollsIsHandedOverWithoutWaitingOutThePoll() {
        String activityArn = sfn.createActivity("busy");
        String smArn = sfn.createStateMachine("activity-handover", activityTask(activityArn));
        CompletableFuture<JsonNode> poll = AslExecutorFixture.start(() -> getActivityTask(activityArn));
        sfn.waits().awaitWaiting(1);
        sfn.time().advance(LONG_POLL.minus(ONE_NANO));

        // Nobody advances the timeline again: the task itself ends the poll.
        String execArn = sfn.startExecution(smArn, "{\"k\":1}");
        JsonNode task = AslExecutorFixture.finished(poll);

        assertEquals("{\"k\":1}", task.path("input").asText());
        sfn.sendTaskSuccess(task.path("taskToken").asText(), "{\"done\":true}");
        JsonNode ended = sfn.awaitEnded(execArn);
        assertEquals("SUCCEEDED", ended.path("status").asText());
        assertEquals("{\"done\":true}", ended.path("output").asText());
    }

    @Test
    void anAnsweredTokenIsReportedClosedForFiveMinutesAndUnknownAfterThat() {
        String activityArn = sfn.createActivity("answered");
        String smArn = sfn.createStateMachine("activity-answered", activityTask(activityArn));
        String execArn = sfn.startExecution(smArn, "{}");
        String taskToken = sfn.getActivityTask(activityArn);
        sfn.sendTaskSuccess(taskToken, "{}");
        assertEquals("SUCCEEDED", sfn.awaitEnded(execArn).path("status").asText());

        assertEquals("TaskTimedOut", answerAgain(taskToken).getErrorCode());
        sfn.time().advance(CLOSED_TOKEN_RETENTION.minus(ONE_NANO));
        assertEquals("TaskTimedOut", answerAgain(taskToken).getErrorCode());

        sfn.time().advance(ONE_NANO);
        assertEquals("InvalidToken", answerAgain(taskToken).getErrorCode());
    }

    private JsonNode getActivityTask(String activityArn) {
        return sfn.call("GetActivityTask", sfn.request().put("activityArn", activityArn).put("workerName", "worker"));
    }

    private AwsException answerAgain(String taskToken) {
        return assertThrows(AwsException.class, () -> sfn.sendTaskSuccess(taskToken, "{}"));
    }

    private static String activityTask(String activityArn) {
        return """
                {
                    "StartAt": "Await",
                    "States": {
                        "Await": {"Type": "Task", "Resource": "%s", "End": true}
                    }
                }
                """.formatted(activityArn);
    }
}

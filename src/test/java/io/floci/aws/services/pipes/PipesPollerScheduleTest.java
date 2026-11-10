package io.floci.aws.services.pipes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.dynamodb.backend.DynamoDbStreamReader;
import io.floci.aws.services.kinesis.KinesisService;
import io.floci.aws.services.pipes.model.DesiredState;
import io.floci.aws.services.pipes.model.Pipe;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.testing.TestTime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When a pipe's source is polled: once per second from the moment polling starts, checked on both
 * sides of each tick with the timers and the poll executor on a controlled timeline.
 */
class PipesPollerScheduleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration ONE_MILLI = Duration.ofMillis(1);

    private final TestTime time = TestTime.create();
    private final SqsService sqsService = mock(SqsService.class);
    private final PipesKafkaConsumerManager kafkaConsumerManager = mock(PipesKafkaConsumerManager.class);
    private final PipesPoller poller = pollerRunningPollsOn(time.newScheduler());

    private PipesPoller pollerRunningPollsOn(ExecutorService pollExecutor) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        when(sqsService.receiveMessage(anyString(), anyInt(), anyInt(), anyInt(), anyString())).thenReturn(List.of());
        return new PipesPoller(TimelineVertx.on(time), sqsService, mock(KinesisService.class),
                mock(DynamoDbStreamReader.class), kafkaConsumerManager, mock(PipesTargetInvoker.class),
                new PipesFilterMatcher(MAPPER), MAPPER, config, pollExecutor);
    }

    private static Pipe sqsPipe(String name) {
        Pipe pipe = new Pipe();
        pipe.setName(name);
        pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/" + name);
        pipe.setSource("arn:aws:sqs:us-east-1:000000000000:" + name + "-source");
        pipe.setTarget("arn:aws:sqs:us-east-1:000000000000:" + name + "-target");
        pipe.setDesiredState(DesiredState.RUNNING);
        return pipe;
    }

    private void verifyPolls(Pipe pipe, int count) {
        verify(sqsService, times(count)).receiveMessage(
                "http://localhost:4566/000000000000/" + pipe.getName() + "-source", 10, 30, 0, "us-east-1");
    }

    @Test
    void aRunningPipePollsItsSourceOnceASecond() {
        Pipe pipe = sqsPipe("orders");
        poller.startPolling(pipe);

        time.advance(POLL_INTERVAL.minus(ONE_MILLI));
        verifyPolls(pipe, 0);
        time.advance(ONE_MILLI);
        verifyPolls(pipe, 1);

        time.advance(POLL_INTERVAL.minus(ONE_MILLI));
        verifyPolls(pipe, 1);
        time.advance(ONE_MILLI);
        verifyPolls(pipe, 2);

        time.advance(Duration.ofMinutes(1));
        verifyPolls(pipe, 62);
    }

    @Test
    void startingAPipeTwiceKeepsOneTimer() {
        Pipe pipe = sqsPipe("orders");
        poller.startPolling(pipe);
        time.advance(Duration.ofMillis(500));
        poller.startPolling(pipe);

        time.advance(Duration.ofMillis(500));
        verifyPolls(pipe, 1);
        time.advance(POLL_INTERVAL);
        verifyPolls(pipe, 2);
        assertEquals(1, time.pendingTasks());
    }

    @Test
    void eachPipePollsOnItsOwnSecondAndStoppingOneLeavesTheOtherPolling() {
        Pipe first = sqsPipe("first");
        Pipe second = sqsPipe("second");
        poller.startPolling(first);
        time.advance(Duration.ofMillis(400));
        poller.startPolling(second);

        time.advance(Duration.ofMillis(600));
        verifyPolls(first, 1);
        verifyPolls(second, 0);
        time.advance(Duration.ofMillis(400));
        verifyPolls(second, 1);

        poller.stopPolling(first);
        assertFalse(poller.isPolling(first));
        assertTrue(poller.isPolling(second));
        verify(kafkaConsumerManager).close(first);
        time.advance(Duration.ofSeconds(10));
        verifyPolls(first, 1);
        verifyPolls(second, 11);
    }

    @Test
    void aStoppedPipeIsNotPolledAgainAndARestartWaitsAFullInterval() {
        Pipe pipe = sqsPipe("orders");
        poller.startPolling(pipe);
        time.advance(POLL_INTERVAL);
        verifyPolls(pipe, 1);

        poller.stopPolling(pipe);
        assertEquals(0, time.pendingTasks(), "Stopping cancels the pipe's timer");
        time.advance(Duration.ofMinutes(1));
        verifyPolls(pipe, 1);

        poller.startPolling(pipe);
        time.advance(POLL_INTERVAL.minus(ONE_MILLI));
        verifyPolls(pipe, 1);
        time.advance(ONE_MILLI);
        verifyPolls(pipe, 2);
    }

    @Test
    void aTickSkipsAPipeThatIsNoLongerMeantToRun() {
        Pipe pipe = sqsPipe("orders");
        poller.startPolling(pipe);
        pipe.setDesiredState(DesiredState.STOPPED);

        time.advance(Duration.ofSeconds(5));

        verify(sqsService, never()).receiveMessage(anyString(), anyInt(), anyInt(), anyInt(), anyString());
        assertTrue(poller.isPolling(pipe));
    }

    @Test
    void aTickThatFindsThePreviousPollUnfinishedStartsNoSecondOne() {
        TestTime polls = TestTime.create();
        PipesPoller slowPoller = pollerRunningPollsOn(polls.newScheduler());
        Pipe pipe = sqsPipe("orders");
        slowPoller.startPolling(pipe);

        time.advance(Duration.ofSeconds(3));
        assertEquals(1, polls.pendingTasks(), "Three ticks, and the first poll has not run yet");
        polls.runDue();
        verifyPolls(pipe, 1);

        time.advance(POLL_INTERVAL);
        polls.runDue();
        verifyPolls(pipe, 2);
    }

    @Test
    void aPollThatFailsIsFollowedByTheNextOneASecondLater() {
        Pipe pipe = sqsPipe("orders");
        when(sqsService.receiveMessage(anyString(), anyInt(), anyInt(), anyInt(), anyString()))
                .thenThrow(new IllegalStateException("queue gone"))
                .thenReturn(List.of());
        poller.startPolling(pipe);

        time.advance(POLL_INTERVAL);
        verifyPolls(pipe, 1);
        time.advance(POLL_INTERVAL);
        verifyPolls(pipe, 2);
    }

    @Test
    void clearingThePollerCancelsEveryTimer() {
        poller.startPolling(sqsPipe("first"));
        poller.startPolling(sqsPipe("second"));
        assertEquals(2, time.pendingTasks());

        poller.clear();

        assertEquals(0, time.pendingTasks());
        time.advance(Duration.ofMinutes(1));
        verify(sqsService, never()).receiveMessage(anyString(), anyInt(), anyInt(), anyInt(), anyString());
    }
}

package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When SQS delivers, on a timeline the test moves: visibility timeouts, delays, retention, long
 * polls and throttled move tasks at the durations a queue is really configured with, each asserted
 * one nanosecond before the instant and at it.
 */
class SqsDeliveryTimingTest {

    private static final String REGION = "us-east-1";
    private static final String BASE_URL = "http://localhost:4566";
    private static final Duration ONE_NANO = Duration.ofNanos(1);

    private final TestTime time = TestTime.create();
    private final TimelineSqsTiming timing = new TimelineSqsTiming(time);
    private final JoinableMoveTasks moveTasks = new JoinableMoveTasks();
    private final SqsService sqs =
            new SqsService(new InMemoryStorage<>(), 30, 1048576, BASE_URL, time.clock(), timing, moveTasks);

    @AfterEach
    void stopMoveTaskWorkers() {
        sqs.stop();
    }

    @Test
    void aReceivedMessageReappearsWhenTheDefaultThirtySecondVisibilityTimeoutIsUp() {
        String url = sqs.createQueue("default-visibility", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "msg", 0, REGION);
        assertEquals(1, sqs.receiveMessage(url, 1, -1, 0, REGION).size());

        time.advance(Duration.ofSeconds(30).minus(ONE_NANO));
        assertTrue(sqs.receiveMessage(url, 1, -1, 0, REGION).isEmpty());
        assertEquals("1", attribute(url, "ApproximateNumberOfMessagesNotVisible"));

        time.advance(ONE_NANO);
        assertEquals("0", attribute(url, "ApproximateNumberOfMessagesNotVisible"));
        List<Message> redelivered = sqs.receiveMessage(url, 1, -1, 0, REGION);
        assertEquals(1, redelivered.size());
        assertEquals(2, redelivered.getFirst().getReceiveCount());
    }

    /** A VisibilityTimeout on the request wins over the queue's; 43200 is the AWS maximum. */
    @ParameterizedTest
    @ValueSource(ints = {1, 120, 43200})
    void aVisibilityTimeoutOnTheReceiveHidesTheMessageForExactlyThatLong(int seconds) {
        String url = sqs.createQueue("receive-visibility", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "msg", 0, REGION);
        assertEquals(1, sqs.receiveMessage(url, 1, seconds, 0, REGION).size());

        time.advance(Duration.ofSeconds(seconds).minus(ONE_NANO));
        assertTrue(sqs.receiveMessage(url, 1, 30, 0, REGION).isEmpty());

        time.advance(ONE_NANO);
        assertEquals(1, sqs.receiveMessage(url, 1, 30, 0, REGION).size());
    }

    @Test
    void changeMessageVisibilityRestartsTheTimeoutFromTheMomentOfTheCall() {
        String url = sqs.createQueue("change-visibility", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "msg", 0, REGION);
        Message received = sqs.receiveMessage(url, 1, 30, 0, REGION).getFirst();

        time.advance(Duration.ofSeconds(20));
        sqs.changeMessageVisibility(url, received.getReceiptHandle(), 60, REGION);

        // 80 seconds after the receive: 20 already spent, then 60 from the change.
        time.advance(Duration.ofSeconds(60).minus(ONE_NANO));
        assertTrue(sqs.receiveMessage(url, 1, 30, 0, REGION).isEmpty());

        time.advance(ONE_NANO);
        assertEquals(1, sqs.receiveMessage(url, 1, 30, 0, REGION).size());
    }

    @Test
    void changeMessageVisibilityToZeroMakesTheMessageVisibleWithoutTimePassing() {
        String url = sqs.createQueue("release-visibility", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "msg", 0, REGION);
        Message received = sqs.receiveMessage(url, 1, 30, 0, REGION).getFirst();
        assertTrue(sqs.receiveMessage(url, 1, 30, 0, REGION).isEmpty());

        sqs.changeMessageVisibility(url, received.getReceiptHandle(), 0, REGION);

        assertEquals(1, sqs.receiveMessage(url, 1, 30, 0, REGION).size());
    }

    @Test
    void aFifoGroupStaysLockedUntilItsInFlightMessageBecomesVisibleAgain() {
        String url = sqs.createQueue("group-lock-timing.fifo",
                Map.of("FifoQueue", "true", "ContentBasedDeduplication", "true"), REGION).getQueueUrl();
        sqs.sendMessage(url, "first", 0, "group", null, REGION);
        sqs.sendMessage(url, "second", 0, "group", null, REGION);
        assertEquals(List.of("first"), bodies(sqs.receiveMessage(url, 1, 30, 0, REGION)));

        time.advance(Duration.ofSeconds(30).minus(ONE_NANO));
        assertTrue(sqs.receiveMessage(url, 10, 30, 0, REGION).isEmpty(),
                "the second message waits behind the first while the first is in flight");

        time.advance(ONE_NANO);
        assertEquals(List.of("first", "second"), bodies(sqs.receiveMessage(url, 10, 30, 0, REGION)));
    }

    /** The message-level DelaySeconds of a standard queue; 900 is the AWS maximum. */
    @ParameterizedTest
    @ValueSource(ints = {1, 900})
    void aDelayedMessageIsDeliveredWhenItsDelaySecondsAreUp(int seconds) {
        String url = sqs.createQueue("message-delay", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "msg", seconds, REGION);

        time.advance(Duration.ofSeconds(seconds).minus(ONE_NANO));
        assertTrue(sqs.receiveMessage(url, 1, 30, 0, REGION).isEmpty());
        assertEquals("1", attribute(url, "ApproximateNumberOfMessagesDelayed"));

        time.advance(ONE_NANO);
        assertEquals("0", attribute(url, "ApproximateNumberOfMessagesDelayed"));
        assertEquals(1, sqs.receiveMessage(url, 1, 30, 0, REGION).size());
    }

    /** A queue created without MessageRetentionPeriod keeps a message four days; 14 days is the AWS maximum. */
    @ParameterizedTest
    @CsvSource({",345600", "1209600,1209600"})
    void aMessageIsRetainedForTheQueuesRetentionPeriod(String configured, long seconds) {
        Map<String, String> attributes = configured == null ? null : Map.of("MessageRetentionPeriod", configured);
        String url = sqs.createQueue("retention-period", attributes, REGION).getQueueUrl();
        assertEquals(String.valueOf(seconds), attribute(url, "MessageRetentionPeriod"));
        sqs.sendMessage(url, "msg", 0, REGION);

        time.advance(Duration.ofSeconds(seconds).minus(ONE_NANO));
        assertEquals(1, sqs.peekMessages(url, REGION).size());

        time.advance(ONE_NANO);
        assertTrue(sqs.peekMessages(url, REGION).isEmpty());
        assertTrue(sqs.receiveMessage(url, 1, 30, 0, REGION).isEmpty());
    }

    @Test
    void anInFlightMessageStillExpiresAtTheEndOfItsRetentionPeriod() {
        String url = sqs.createQueue("retention-in-flight", Map.of("MessageRetentionPeriod", "60"), REGION)
                .getQueueUrl();
        sqs.sendMessage(url, "msg", 0, REGION);
        Message received = sqs.receiveMessage(url, 1, 600, 0, REGION).getFirst();

        time.advance(Duration.ofSeconds(60).minus(ONE_NANO));
        assertEquals("1", attribute(url, "ApproximateNumberOfMessagesNotVisible"));

        time.advance(ONE_NANO);
        assertEquals("0", attribute(url, "ApproximateNumberOfMessagesNotVisible"));
        AwsException gone = assertThrows(AwsException.class,
                () -> sqs.changeMessageVisibility(url, received.getReceiptHandle(), 0, REGION));
        assertEquals(400, gone.getHttpStatus());
    }

    /** One second, and the AWS maximum of twenty. */
    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void aLongPollOnAnEmptyQueueReturnsEmptyWhenItsWaitTimeSecondsAreUp(int seconds) throws Exception {
        String url = sqs.createQueue("long-poll-empty", null, REGION).getQueueUrl();
        Instant start = time.now();

        CompletableFuture<List<Message>> poll = receive(url, seconds);
        assertEquals(Duration.ofSeconds(seconds), timing.awaitPark(), "an empty queue is not rescanned");

        time.advance(Duration.ofSeconds(seconds).minus(ONE_NANO));
        assertFalse(poll.isDone());

        time.advance(ONE_NANO);
        assertTrue(poll.get(30, TimeUnit.SECONDS).isEmpty());
        assertEquals(0, timing.unreportedParks(), "the poll returned at its deadline instead of waiting again");
        assertEquals(start.plusSeconds(seconds), time.now());
    }

    @Test
    void aLongPollReceivesAMessageWhoseVisibilityTimeoutEndsWhileItWaits() throws Exception {
        String url = sqs.createQueue("long-poll-visibility", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "msg", 0, REGION);
        assertEquals(1, sqs.receiveMessage(url, 1, 30, 0, REGION).size());
        time.advance(Duration.ofSeconds(25));

        // Nothing signals a visibility timeout ending, so a poll on a nonempty queue rescans every second.
        CompletableFuture<List<Message>> poll = receive(url, 20);
        for (int second = 0; second < 5; second++) {
            assertEquals(Duration.ofSeconds(1), timing.awaitPark());
            assertFalse(poll.isDone(), "still in flight " + (25 + second) + " s after the receive");
            time.advance(second < 4 ? Duration.ofSeconds(1) : Duration.ofSeconds(1).minus(ONE_NANO));
        }
        assertFalse(poll.isDone());

        time.advance(ONE_NANO);
        List<Message> received = poll.get(30, TimeUnit.SECONDS);
        assertEquals(List.of("msg"), bodies(received));
        assertEquals(2, received.getFirst().getReceiveCount());
    }

    @Test
    void aLongPollReceivesADelayedMessageWhenItsDelayEnds() throws Exception {
        String url = sqs.createQueue("long-poll-delay", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "msg", 3, REGION);

        CompletableFuture<List<Message>> poll = receive(url, 20);
        for (int second = 0; second < 3; second++) {
            assertEquals(Duration.ofSeconds(1), timing.awaitPark());
            assertFalse(poll.isDone());
            time.advance(Duration.ofSeconds(1));
        }

        assertEquals(List.of("msg"), bodies(poll.get(30, TimeUnit.SECONDS)));
    }

    @Test
    void aLongPollThatFindsNothingToDeliverGivesUpAtItsDeadline() throws Exception {
        String url = sqs.createQueue("long-poll-in-flight", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "msg", 0, REGION);
        assertEquals(1, sqs.receiveMessage(url, 1, 30, 0, REGION).size());

        CompletableFuture<List<Message>> poll = receive(url, 20);
        for (int second = 0; second < 20; second++) {
            assertEquals(Duration.ofSeconds(1), timing.awaitPark());
            assertFalse(poll.isDone());
            time.advance(Duration.ofSeconds(1));
        }

        assertTrue(poll.get(30, TimeUnit.SECONDS).isEmpty(), "the message stays in flight for another ten seconds");
        assertEquals(0, timing.unreportedParks());
    }

    @Test
    void aThrottledMoveTaskMovesOneMessageEachInterval() {
        String dlqUrl = sqs.createQueue("rate-dlq", null, REGION).getQueueUrl();
        String dlqArn = arn("rate-dlq");
        sqs.createQueue("rate-source", Map.of("RedrivePolicy",
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"), REGION);
        String destinationUrl = sqs.createQueue("rate-destination", null, REGION).getQueueUrl();
        for (String body : List.of("a", "b", "c")) {
            sqs.sendMessage(dlqUrl, body, 0, REGION);
        }

        String task = sqs.startMessageMoveTask(dlqArn, arn("rate-destination"), 1, REGION);
        assertEquals(List.of("a"), bodies(sqs.peekMessages(destinationUrl, REGION)),
                "the first message moves with the request");

        assertEquals(Duration.ofSeconds(1), timing.awaitPark());
        time.advance(Duration.ofSeconds(1).minus(ONE_NANO));
        assertEquals(List.of("a"), bodies(sqs.peekMessages(destinationUrl, REGION)));
        assertEquals(1, moveTask(dlqArn).approximateNumberOfMessagesMoved());
        time.advance(ONE_NANO);

        // The worker parks for its next interval only after it has moved the message.
        assertEquals(Duration.ofSeconds(1), timing.awaitPark());
        assertEquals(List.of("a", "b"), bodies(sqs.peekMessages(destinationUrl, REGION)));
        assertEquals("RUNNING", moveTask(dlqArn).status());
        time.advance(Duration.ofSeconds(1));

        assertEquals(Duration.ofSeconds(1), timing.awaitPark());
        assertEquals(List.of("a", "b", "c"), bodies(sqs.peekMessages(destinationUrl, REGION)));
        assertEquals("RUNNING", moveTask(dlqArn).status(), "the worker finds the queue drained an interval later");
        time.advance(Duration.ofSeconds(1));

        moveTasks.join();
        SqsService.MoveTask finished = moveTask(dlqArn);
        assertEquals(task, finished.taskHandle());
        assertEquals("COMPLETED", finished.status());
        assertEquals(3, finished.approximateNumberOfMessagesMoved());
        assertEquals(3, finished.approximateNumberOfMessagesToMove());
    }

    /** MaxNumberOfMessagesPerSecond spaces the moves 1000 / rate milliseconds apart; 500 is the AWS maximum. */
    @ParameterizedTest
    @CsvSource({"1,1000", "4,250", "500,2"})
    void aThrottledMoveTaskWaitsOneRateIntervalBetweenMessages(int rate, long intervalMillis) {
        String dlqUrl = sqs.createQueue("interval-dlq", null, REGION).getQueueUrl();
        String dlqArn = arn("interval-dlq");
        sqs.createQueue("interval-source", Map.of("RedrivePolicy",
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"), REGION);
        String destinationUrl = sqs.createQueue("interval-destination", null, REGION).getQueueUrl();
        sqs.sendMessage(dlqUrl, "a", 0, REGION);
        sqs.sendMessage(dlqUrl, "b", 0, REGION);

        sqs.startMessageMoveTask(dlqArn, arn("interval-destination"), rate, REGION);

        assertEquals(Duration.ofMillis(intervalMillis), timing.awaitPark());
        time.advance(Duration.ofMillis(intervalMillis).minus(ONE_NANO));
        assertEquals(1, sqs.peekMessages(destinationUrl, REGION).size());
        time.advance(ONE_NANO);
        assertEquals(Duration.ofMillis(intervalMillis), timing.awaitPark());
        assertEquals(2, sqs.peekMessages(destinationUrl, REGION).size());
        time.advance(Duration.ofMillis(intervalMillis));
        moveTasks.join();
        assertEquals("COMPLETED", moveTask(dlqArn).status());
    }

    @Test
    void aSecondMoveTaskForTheSameSourceIsRejectedForOneSecondAfterTheFirstStarted() {
        sqs.createQueue("window-dlq", null, REGION);
        String dlqArn = arn("window-dlq");
        sqs.createQueue("window-source", Map.of("RedrivePolicy",
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"), REGION);
        sqs.createQueue("window-destination", null, REGION);
        String destinationArn = arn("window-destination");

        sqs.startMessageMoveTask(dlqArn, destinationArn, 0, REGION);
        moveTasks.join();
        assertEquals("COMPLETED", moveTask(dlqArn).status());

        time.advance(Duration.ofMillis(999));
        AwsException rejected = assertThrows(AwsException.class,
                () -> sqs.startMessageMoveTask(dlqArn, destinationArn, 0, REGION));
        assertEquals("InvalidParameterValue", rejected.getErrorCode());

        time.advance(Duration.ofMillis(1));
        sqs.startMessageMoveTask(dlqArn, destinationArn, 0, REGION);
        moveTasks.join();
        assertEquals(2, sqs.listMessageMoveTasks(dlqArn, REGION).size());
    }

    private CompletableFuture<List<Message>> receive(String url, int waitTimeSeconds) {
        CompletableFuture<List<Message>> received = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                received.complete(sqs.receiveMessage(url, 1, 30, waitTimeSeconds, REGION));
            } catch (Throwable failure) {
                received.completeExceptionally(failure);
            }
        });
        return received;
    }

    private String attribute(String url, String name) {
        return sqs.getQueueAttributes(url, List.of(name), REGION).get(name);
    }

    private SqsService.MoveTask moveTask(String sourceArn) {
        return sqs.listMessageMoveTasks(sourceArn, REGION).getFirst();
    }

    private static List<String> bodies(List<Message> messages) {
        return messages.stream().map(Message::getBody).toList();
    }

    private static String arn(String queueName) {
        return "arn:aws:sqs:" + REGION + ":000000000000:" + queueName;
    }
}

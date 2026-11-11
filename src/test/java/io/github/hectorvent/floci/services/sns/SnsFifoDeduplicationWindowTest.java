package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.SqsServiceFactory;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The five-minute deduplication window of a FIFO topic and the {@code Timestamp} of a notification
 * are read from the clock the service is built with. The subscriber is a standard queue, so only
 * the topic deduplicates.
 */
class SnsFifoDeduplicationWindowTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String BASE_URL = "http://localhost:4566";
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final String QUEUE_URL = BASE_URL + "/" + ACCOUNT + "/window-queue";

    private final TestTime time = TestTime.create();
    private SnsService snsService;
    private SqsService sqsService;
    private String topicArn;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        sqsService = SqsServiceFactory.createInMemory(BASE_URL, regionResolver);
        snsService = new SnsService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), regionResolver, sqsService, null, null, null, time.clock());
        sqsService.createQueue("window-queue", Map.of(), REGION);
        topicArn = snsService.createTopic("window.fifo", Map.of("FifoTopic", "true"), null, REGION).getTopicArn();
        snsService.subscribe(topicArn, "sqs", "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":window-queue", REGION, Map.of());
    }

    @Test
    void aDeduplicationIdIsADuplicateUntilFiveMinutesAfterItWasFirstPublished() {
        publish("first");
        assertEquals(List.of("first"), delivered());

        time.advance(WINDOW.minusMillis(1));
        publish("one millisecond before the window ends");
        assertEquals(List.of(), delivered());

        time.advance(Duration.ofMillis(1));
        publish("at the end of the window");
        assertEquals(List.of("at the end of the window"), delivered());
    }

    @Test
    void aBatchEntryIsADuplicateUntilFiveMinutesAfterItsIdWasFirstPublished() {
        publish("first");
        assertEquals(List.of("first"), delivered());

        time.advance(WINDOW.minusMillis(1));
        publishBatch("one millisecond before the window ends");
        assertEquals(List.of(), delivered());

        time.advance(Duration.ofMillis(1));
        publishBatch("at the end of the window");
        assertEquals(List.of("at the end of the window"), delivered());
    }

    @Test
    void aNotificationCarriesTheTimeOfTheServiceClock() {
        time.advance(Duration.ofMillis(90_061_007));

        publish("stamped");

        List<Message> messages = sqsService.receiveMessage(QUEUE_URL, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertThat(messages.getFirst().getBody(), containsString("\"Timestamp\":\"2026-01-02T01:01:01.007Z\""));
    }

    private void publish(String message) {
        snsService.publish(topicArn, null, null, message, null, null, "group", "same-id", REGION);
    }

    private void publishBatch(String message) {
        Map<String, Object> entry = Map.of("Id", "entry", "Message", message,
                "MessageGroupId", "group", "MessageDeduplicationId", "same-id");
        snsService.publishBatch(topicArn, List.of(entry), REGION);
    }

    /** The {@code Message} of every notification the queue received since the last call. */
    private List<String> delivered() {
        List<String> bodies = new ArrayList<>();
        for (Message message : sqsService.receiveMessage(QUEUE_URL, 10, 30, 0, REGION)) {
            String body = message.getBody();
            int start = body.indexOf("\"Message\":\"") + "\"Message\":\"".length();
            bodies.add(body.substring(start, body.indexOf('"', start)));
        }
        return bodies;
    }
}

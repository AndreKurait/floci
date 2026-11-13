package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongConsumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Exercises idle-time accounting at the production three-minute budget, on a timeline the test controls
 * rather than thread scheduling or wall-clock delays.
 */
class CustomResourceResponseStoreTest {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(3);
    private final TestTime time = TestTime.create();
    private final ScriptedResponse response = new ScriptedResponse();
    private final CustomResourceResponseStore store = new CustomResourceResponseStore(
            new ProviderFrameworkDetector(mock(LambdaService.class)), time::nanoTime, () -> response);
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode success() {
        ObjectNode node = mapper.createObjectNode();
        node.put("Status", "SUCCESS");
        return node;
    }

    private Duration elapsed() {
        return Duration.between(TestTime.DEFAULT_START, time.now());
    }

    private void heartbeatDuringWait(String token) {
        response.onWait(remaining -> {
            time.advance(Duration.ofNanos(remaining - 1));
            store.touch(token);
            time.advance(Duration.ofNanos(1));
        });
    }

    @Test
    void awaitOutlivesItsBudgetWhileTouchesKeepArriving() throws Exception {
        String token = store.register();
        for (int i = 0; i < 4; i++) {
            heartbeatDuringWait(token);
        }
        response.onWait(remaining -> store.complete(token, success()));

        JsonNode result = store.await(token, IDLE_TIMEOUT);

        assertEquals("SUCCESS", result.get("Status").asText());
        assertEquals(IDLE_TIMEOUT.multipliedBy(4).minusNanos(3), elapsed());
        assertEquals(List.of(IDLE_TIMEOUT.toNanos(), IDLE_TIMEOUT.toNanos() - 1,
                IDLE_TIMEOUT.toNanos() - 1, IDLE_TIMEOUT.toNanos() - 1,
                IDLE_TIMEOUT.toNanos() - 1), response.waits);
        assertThrows(IllegalStateException.class, () -> store.await(token, IDLE_TIMEOUT));
    }

    @Test
    void awaitStillTimesOutOnceTheTouchesStop() {
        String token = store.register();
        heartbeatDuringWait(token);
        heartbeatDuringWait(token);
        response.onWait(remaining -> time.advance(Duration.ofNanos(remaining)));

        assertThrows(TimeoutException.class, () -> store.await(token, IDLE_TIMEOUT));

        assertEquals(IDLE_TIMEOUT.multipliedBy(3).minusNanos(2), elapsed());
        assertEquals(3, response.waits.size());
        assertThrows(IllegalStateException.class, () -> store.await(token, IDLE_TIMEOUT));
    }

    @Test
    void awaitTimesOutWhenNothingEverHappens() {
        String token = store.register();
        response.onWait(remaining -> time.advance(Duration.ofNanos(remaining)));

        assertThrows(TimeoutException.class, () -> store.await(token, IDLE_TIMEOUT));

        assertEquals(List.of(IDLE_TIMEOUT.toNanos()), response.waits);
        assertEquals(IDLE_TIMEOUT, elapsed());
    }

    @Test
    void awaitReturnsAResponseThatArrivedBeforeTheBudgetWasChecked() throws Exception {
        String token = store.register();
        store.complete(token, success());

        JsonNode result = store.await(token, Duration.ZERO);

        assertEquals("SUCCESS", result.get("Status").asText());
        assertTrue(response.waits.isEmpty());
    }

    @Test
    void awaitBudgetsIdlenessFromWhenTheCallerStartsWaitingNotFromRegistration() throws Exception {
        String token = store.register();
        time.advance(IDLE_TIMEOUT.multipliedBy(4));
        response.onWait(remaining -> {
            time.advance(Duration.ofNanos(remaining - 1));
            store.complete(token, success());
        });

        JsonNode result = store.await(token, IDLE_TIMEOUT);

        assertEquals("SUCCESS", result.get("Status").asText());
        assertEquals(List.of(IDLE_TIMEOUT.toNanos()), response.waits);
    }

    @Test
    void touchForAnUnknownTokenIsIgnored() {
        assertDoesNotThrow(() -> store.touch("no-such-token"));
    }

    @Test
    void toleratedWaitSliceTimeoutIsLogged() throws Exception {
        Logger logger = Logger.getLogger(CustomResourceResponseStore.class.getName());
        Level previousLevel = logger.getLevel();
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            String token = store.register();
            heartbeatDuringWait(token);
            response.onWait(remaining -> store.complete(token, success()));

            store.await(token, IDLE_TIMEOUT);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        assertTrue(records.stream().anyMatch(record -> record.getMessage() != null
                        && record.getMessage().contains("wait slice")),
                "expected a log record for a tolerated wait-slice timeout, got: " + records);
    }

    private static final class ScriptedResponse extends CompletableFuture<JsonNode> {
        private final Deque<LongConsumer> events = new ArrayDeque<>();
        private final List<Long> waits = new ArrayList<>();

        private void onWait(LongConsumer event) {
            events.addLast(event);
        }

        @Override
        public JsonNode get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException,
                TimeoutException {
            assertTrue(timeout > 0, "wait slices must have a positive timeout");
            assertTrue(!events.isEmpty(), "unexpected additional wait slice");
            long nanos = unit.toNanos(timeout);
            waits.add(nanos);
            events.removeFirst().accept(nanos);
            return super.get(0, TimeUnit.NANOSECONDS);
        }
    }
}

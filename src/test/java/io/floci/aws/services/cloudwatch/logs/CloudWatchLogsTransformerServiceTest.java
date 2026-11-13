package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.logs.model.LogEvent;
import io.floci.aws.services.cloudwatch.logs.model.LogTransformer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchLogsTransformerServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GROUP = "/transform/unit";
    private static final String REGION = "us-east-1";

    @Test
    void invalidDefinitionsAndPersistenceFailureKeepThePriorDefinitionAndBehavior() throws Exception {
        FailingDefinitions definitions = new FailingDefinitions();
        AtomicLong time = new AtomicLong(100);
        CloudWatchLogsService logs = service(new InMemoryStorage<>(), definitions, time);
        logs.putTransformer(GROUP, definition("parsed"), REGION);
        LogTransformer first = logs.getTransformer(GROUP, REGION);
        for (String invalid : List.of("null", "[]", "[{}]", "[{\"parseJSON\":null}]",
                "[{\"parseJSON\":{\"destination\":7}}]", "[{\"parseJSON\":{\"destination\":\"a..b\"}}]",
                "[{\"parseJSON\":{\"source\":\"nested\"}}]", "[{\"parseJSON\":{\"unknown\":true}}]",
                "[{\"parseJSON\":{}},{\"parseJSON\":{}}]")) {
            assertThrows(AwsException.class, () -> logs.putTransformer(GROUP, MAPPER.readTree(invalid), REGION));
            assertEquals(first, logs.getTransformer(GROUP, REGION));
        }
        definitions.fail = true;
        time.set(200);
        assertThrows(IllegalStateException.class, () -> logs.putTransformer(GROUP, definition("rejected"), REGION));
        assertEquals(first, logs.getTransformer(GROUP, REGION));
        definitions.fail = false;
        emit(logs, "{\"value\":7}");
        assertEquals(7, MAPPER.readTree(events(logs).getFirst().getTransformedMessage()).path("parsed").path("value").intValue());
        logs.putTransformer(GROUP, definition("changed"), REGION);
        assertEquals(100, logs.getTransformer(GROUP, REGION).creationTime());
        assertEquals(200, logs.getTransformer(GROUP, REGION).lastModifiedTime());
    }

    @Test
    void rootParsingAndFailuresRetainRawVisibilityWithoutTransformedFilterSuccess() throws Exception {
        CloudWatchLogsService logs = service(new InMemoryStorage<>(), new InMemoryStorage<>(), new AtomicLong(100));
        logs.putTransformer(GROUP, MAPPER.readTree("[{\"parseJSON\":{}}]"), REGION);
        String root = "{\"n\":9007199254740993,\"a\":[true,null,{\"x\":\"雪\"}]}";
        String oversized = "{\"value\":\"" + "a".repeat(ParseJsonTransformer.MAX_EVENT_BYTES) + "\"}";
        for (String message : List.of(root, "[]", "false", "{\"bad\":", oversized)) {
            emit(logs, message);
        }
        List<LogEvent> events = events(logs);
        assertEquals(MAPPER.readTree(root), MAPPER.readTree(events.getFirst().getTransformedMessage()));
        for (LogEvent event : events.subList(1, events.size())) {
            assertNotNull(event.getMessage());
            assertNotNull(event.getTransformationError());
            assertNull(event.getTransformedMessage());
            assertNull(event.messageForFilter(true));
            assertEquals(event.getMessage(), event.messageForFilter(false));
        }
        LogEvent legacy = MAPPER.readValue(
                "{\"eventId\":\"legacy\",\"message\":\"original\",\"timestamp\":1,\"ingestionTime\":2}", LogEvent.class);
        assertNull(legacy.getTransformationError());
        assertNull(legacy.getTransformedMessage());
        assertEquals("original", legacy.messageForFilter(true));
    }

    @Test
    void extractedFieldLimitCountsNestedObjectsInsideArrays() throws Exception {
        ObjectNode fields = MAPPER.createObjectNode();
        for (int i = 0; i < 199; i++) {
            fields.put("field" + i, i);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("items").add(fields);
        LogTransformer transformer = ParseJsonTransformer.definition(definition("parsed"));
        ParseJsonTransformer.Outcome accepted = ParseJsonTransformer.transform(root.toString(), transformer);
        assertNull(accepted.error());
        assertEquals(199, MAPPER.readTree(accepted.message()).path("parsed").path("items").get(0).size());
        fields.put("field199", 199);
        ParseJsonTransformer.Outcome rejected = ParseJsonTransformer.transform(root.toString(), transformer);
        assertNull(rejected.message());
        assertTrue(rejected.error().contains("200"));
    }

    @Test
    void ingestionBatchFinishesBeforeConcurrentReplacementOrDeletion() throws Exception {
        BlockingEvents store = new BlockingEvents();
        CloudWatchLogsService logs = service(store, new InMemoryStorage<>(), new AtomicLong(100));
        logs.putTransformer(GROUP, definition("first"), REGION);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            store.block();
            Future<?> ingestion = executor.submit(() -> logs.putLogEvents(GROUP, "owned", List.of(
                    Map.of("timestamp", 1L, "message", "{\"value\":1}"),
                    Map.of("timestamp", 2L, "message", "{\"value\":2}")), REGION));
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            CountDownLatch attempted = new CountDownLatch(1);
            Future<?> replacement = executor.submit(() -> {
                attempted.countDown();
                logs.putTransformer(GROUP, definitionUnchecked("second"), REGION);
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> replacement.get(50, TimeUnit.MILLISECONDS));
            store.release.countDown();
            ingestion.get(5, TimeUnit.SECONDS);
            replacement.get(5, TimeUnit.SECONDS);
            for (LogEvent event : events(logs)) {
                assertTrue(MAPPER.readTree(event.getTransformedMessage()).has("first"));
            }
            store.block();
            Future<?> lastIngestion = executor.submit(() -> emit(logs, "{\"value\":3}"));
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            CountDownLatch deleting = new CountDownLatch(1);
            Future<?> deletion = executor.submit(() -> {
                deleting.countDown();
                logs.deleteLogGroup(GROUP, REGION);
            });
            assertTrue(deleting.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> deletion.get(50, TimeUnit.MILLISECONDS));
            store.release.countDown();
            lastIngestion.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
            assertFalse(logs.logGroupExists(GROUP, REGION));
            assertTrue(events(logs).isEmpty());
            assertThrows(AwsException.class, () -> logs.getTransformer(GROUP, REGION));
        } finally {
            store.release.countDown();
            executor.shutdownNow();
        }
    }

    private static JsonNode definition(String destination) throws Exception {
        return MAPPER.readTree("[{\"parseJSON\":{\"destination\":\"" + destination + "\"}}]");
    }

    private static JsonNode definitionUnchecked(String destination) {
        return MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                .set("parseJSON", MAPPER.createObjectNode().put("destination", destination)));
    }

    private static CloudWatchLogsService service(InMemoryStorage<String, LogEvent> events,
                                                InMemoryStorage<String, LogTransformer> definitions,
                                                AtomicLong time) {
        CloudWatchLogsService logs = new CloudWatchLogsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                events, new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), 1000, 1000,
                new RegionResolver(REGION, "111111111111"), 0, time::get, null, null, definitions);
        logs.createLogGroup(GROUP, null, null, REGION);
        logs.createLogStream(GROUP, "owned", REGION);
        return logs;
    }

    private static void emit(CloudWatchLogsService logs, String message) {
        logs.putLogEvents(GROUP, "owned", List.of(Map.of("timestamp", 100L, "message", message)), REGION);
    }

    private static List<LogEvent> events(CloudWatchLogsService logs) {
        return logs.getLogEvents(GROUP, "owned", null, null, 100, true, null, REGION).events();
    }

    private static final class FailingDefinitions extends InMemoryStorage<String, LogTransformer> {
        private boolean fail;

        @Override
        public void put(String key, LogTransformer value) {
            if (fail) {
                throw new IllegalStateException("deliberate persistence failure");
            }
            super.put(key, value);
        }
    }

    private static final class BlockingEvents extends InMemoryStorage<String, LogEvent> {
        private CountDownLatch entered = new CountDownLatch(0);
        private CountDownLatch release = new CountDownLatch(0);

        private void block() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Override
        public void putAll(Map<String, LogEvent> values) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release event persistence");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            super.putAll(values);
        }
    }
}

package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogTransformer;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class CloudWatchLogsTransformerStorageTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String GROUP = "/transform/reload";
    private static final long TIME = 1794510000000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void replacementRefusalOldViewsErrorsAndAccountBoundsSurviveRestart(String mode) throws Exception {
        LogTransformer definition;
        try (Services first = open(mode, ACCOUNT)) {
            first.group(GROUP);
            first.group(GROUP + "-sibling");
            first.emit("{\"phase\":\"before\"}", 0);
            first.logs.putTransformer(GROUP, configuration("parsed"), REGION);
            first.emit("{\"phase\":\"configured\"}", 1);
            first.emit("not JSON", 2);
            first.logs.putTransformer(GROUP, configuration("changed"), REGION);
            first.emit("{\"phase\":\"updated\"}", 3);
            definition = first.logs.getTransformer(GROUP, REGION);
            assertThrows(AwsException.class, () ->
                    first.logs.putTransformer(GROUP, configuration("too.many.path.components"), REGION));
            assertEquals(definition, first.logs.getTransformer(GROUP, REGION));
            assertNull(first.logs.getTransformer(GROUP + "-sibling", REGION));
        }
        try (Services other = open(mode, "222222222222")) {
            assertTrue(other.logs.describeLogGroups(null, REGION).isEmpty());
            other.group(GROUP);
            other.logs.putTransformer(GROUP, configuration("foreign"), REGION);
        }
        try (Services second = open(mode, ACCOUNT)) {
            assertEquals(definition, second.logs.getTransformer(GROUP, REGION));
            List<LogEvent> events = second.events();
            assertEquals(4, events.size());
            assertNull(events.get(0).getTransformedMessage());
            assertEquals("configured", MAPPER.readTree(events.get(1).getTransformedMessage())
                    .path("parsed").path("phase").asText());
            assertEquals("not JSON", events.get(2).getMessage());
            assertNotNull(events.get(2).getTransformationError());
            assertNull(events.get(2).getTransformedMessage());
            assertEquals("updated", MAPPER.readTree(events.get(3).getTransformedMessage())
                    .path("changed").path("phase").asText());
            String query = second.logs.startQuery(List.of(GROUP), TIME / 1000 - 60, TIME / 1000 + 60,
                    "fields @message, parsed.phase, changed.phase, @transformationError | sort @timestamp asc", 20, REGION);
            List<? extends Map<String, String>> rows = second.logs.getQueryResults(query).rows();
            assertEquals("configured", rows.get(1).get("parsed.phase"));
            assertEquals("updated", rows.get(3).get("changed.phase"));
            assertNotNull(rows.get(2).get("@transformationError"));
            second.logs.deleteTransformer(GROUP, REGION);
            second.emit("{\"phase\":\"after\"}", 4);
        }
        try (Services third = open(mode, ACCOUNT)) {
            assertNull(third.logs.getTransformer(GROUP, REGION));
            assertNotNull(third.events().get(1).getTransformedMessage());
            assertNull(third.events().get(4).getTransformedMessage());
            third.logs.deleteLogGroup(GROUP, REGION);
            third.group(GROUP);
            assertTrue(third.events().isEmpty());
            assertNull(third.logs.getTransformer(GROUP, REGION));
            assertTrue(third.logs.logGroupExists(GROUP + "-sibling", REGION));
        }
        try (Services other = open(mode, "222222222222")) {
            assertEquals("foreign", other.logs.getTransformer(GROUP, REGION).destination());
        }
    }

    static JsonNode configuration(String destination) throws Exception {
        return MAPPER.readTree("[{\"parseJSON\":{\"destination\":\"" + destination + "\"}}]");
    }

    private Services open(String mode, String account) {
        EmulatorConfig config = StorageFactories.config(directory, account);
        when(config.services().cloudwatchlogs().maxEventsPerQuery()).thenReturn(1000);
        when(config.services().cloudwatchlogs().maxStoredEvents()).thenReturn(1000);
        return new Services(StorageFactories.open(config, mode), config, account);
    }

    private static final class Services implements AutoCloseable {
        private final StorageFactory factory;
        private final CloudWatchLogsService logs;

        private Services(StorageFactory factory, EmulatorConfig config, String account) {
            this.factory = factory;
            logs = new CloudWatchLogsService(factory, config, new RegionResolver(REGION, account), null, null);
        }

        private void group(String name) {
            logs.createLogGroup(name, null, null, REGION);
            logs.createLogStream(name, "owned", REGION);
        }

        private void emit(String message, int order) {
            logs.putLogEvents(GROUP, "owned", List.of(Map.of("timestamp", TIME + order, "message", message)), REGION);
        }

        private List<LogEvent> events() {
            return logs.getLogEvents(GROUP, "owned", null, null, 100, true, null, REGION).events();
        }

        @Override
        public void close() {
            factory.flushAll();
            factory.shutdownAll();
        }
    }
}

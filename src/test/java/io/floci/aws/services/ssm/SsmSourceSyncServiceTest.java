package io.floci.aws.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class SsmSourceSyncServiceTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String ACCOUNT = "873033339999";
    private static final String REGION = "us-east-1";
    private static final String SOURCE = "SyncFromSource";

    @TempDir
    Path state;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void completeSourcesReloadThroughConfiguredStorageAndResetStaysEmpty(String mode) {
        JsonNode original;
        try (Fixture first = new Fixture(state, mode)) {
            first.service.create(configuration("source"), REGION);
            first.service.create(configuration("sibling"), "us-west-2");
            original = first.current();
        }
        try (Fixture restored = new Fixture(state, mode)) {
            assertEquals("memory".equals(mode) ? 0 : 1, restored.current().path("ResourceDataSyncItems").size());
            if (!"memory".equals(mode)) {
                assertEquals(original, restored.current());
                assertEquals(1, restored.service.list(list(), "us-west-2").path("ResourceDataSyncItems").size());
                assertFalse(restored.current().path("ResourceDataSyncItems").get(0).has("LastStatus"));
            }
            restored.service.beforeReset();
            restored.storage.clearAll();
            restored.service.clear();
            restored.service.afterReset();
            assertTrue(restored.current().path("ResourceDataSyncItems").isEmpty());
            assertTrue(restored.service.list(list(), "us-west-2").path("ResourceDataSyncItems").isEmpty());
        }
        try (Fixture afterReset = new Fixture(state, mode)) {
            assertTrue(afterReset.current().path("ResourceDataSyncItems").isEmpty());
            afterReset.service.create(configuration("after-reset"), REGION);
            assertEquals(1, afterReset.current().path("ResourceDataSyncItems").size());
        }
    }

    @Test
    void inputOutputAndReplacedSourceAreDetachedAndOmittedFutureBecomesFalse() {
        try (Fixture fixture = new Fixture(state, "memory")) {
            ObjectNode input = configuration("source");
            input.withObject("/SyncSource").put("State", "caller-invented-state");
            fixture.service.create(input, REGION);
            JsonNode original = fixture.current();
            input.withObject("/SyncSource").putArray("SourceRegions").add("caller-mutated-input");
            ObjectNode output = fixture.service.list(list(), REGION);
            ((ObjectNode) output.path("ResourceDataSyncItems").get(0)).put("LastStatus", "caller-invented-status");
            assertEquals(original, fixture.current());
            assertFalse(original.path("ResourceDataSyncItems").get(0).path("SyncSource").has("State"));

            ObjectNode replacement = configuration("source");
            replacement.withObject("/SyncSource").remove("IncludeFutureRegions");
            replacement.withObject("/SyncSource").putArray("SourceRegions").add("us-west-2");
            fixture.service.update(replacement, REGION);
            JsonNode changed = fixture.current().path("ResourceDataSyncItems").get(0);
            assertEquals(JSON.arrayNode().add("us-west-2"), changed.path("SyncSource").path("SourceRegions"));
            assertEquals(JSON.booleanNode(false), changed.path("SyncSource").get("IncludeFutureRegions"));
            assertEquals(JSON.booleanNode(false), changed.path("SyncSource").get("EnableAllOpsDataSources"));
            assertEquals(original.path("ResourceDataSyncItems").get(0).get("SyncCreatedTime"),
                    changed.get("SyncCreatedTime"));
            replacement.withObject("/SyncSource").put("IncludeFutureRegions", true);
            assertEquals(changed, fixture.current().path("ResourceDataSyncItems").get(0));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "delete"})
    void aRealPersistentCommitFailurePreservesEveryRowAndTheOperationCanResume(String operation) throws Exception {
        try (Fixture fixture = new Fixture(state, "persistent")) {
            fixture.service.create(configuration("source"), REGION);
            fixture.service.create(configuration("sibling"), REGION);
            JsonNode before = fixture.current();
            Path file = state.resolve("ssm-resource-data-sync.json");
            Path backup = state.resolve("saved-source-sync.json");
            Files.move(file, backup);
            Files.createDirectory(file);
            Path blocker = file.resolve("owned-failure-control");
            Files.writeString(blocker, "A nonempty directory refuses replacement by the atomic data file.");
            try {
                assertThrows(UncheckedIOException.class, () -> fixture.mutate(operation));
                assertEquals(before, fixture.current());
            } finally {
                Files.delete(blocker);
                Files.delete(file);
                Files.move(backup, file);
            }
            fixture.mutate(operation);
            assertEquals("create".equals(operation) ? 3 : "delete".equals(operation) ? 1 : 2,
                    fixture.current().path("ResourceDataSyncItems").size());
            assertEquals(item(before, "sibling"), item(fixture.current(), "sibling"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "delete"})
    void resetAndShutdownRefuseSourceMutationsWithoutChangingAnyRow(String operation) {
        try (Fixture fixture = new Fixture(state, "memory")) {
            fixture.service.create(configuration("source"), REGION);
            fixture.service.create(configuration("sibling"), REGION);
            JsonNode before = fixture.current();
            fixture.service.beforeReset();
            assertEquals(500, assertThrows(AwsException.class, () -> fixture.mutate(operation)).getHttpStatus());
            assertEquals(before, fixture.current());
            fixture.service.afterReset();
            fixture.mutate(operation);
            JsonNode resumed = fixture.current();
            fixture.service.shutdown();
            assertEquals(500, assertThrows(AwsException.class, () -> fixture.mutate(operation)).getHttpStatus());
            assertEquals(resumed, fixture.current());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reset", "shutdown"})
    void lifecycleDrainWaitsForTheActualAdmittedStoragePublication(String stopping) throws Exception {
        try (Fixture fixture = new Fixture(state, "memory");
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            fixture.service.create(configuration("source"), REGION);
            JsonNode original = fixture.current();
            CountDownLatch committing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch stopStarted = new CountDownLatch(1);
            doAnswer(invocation -> {
                committing.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(fixture.backend).putAllAtomically(anyMap());
            Future<?> publication = executor.submit(() -> fixture.mutate("update"));
            try {
                assertTrue(committing.await(5, TimeUnit.SECONDS));
                assertEquals(JSON.arrayNode().add(REGION),
                        fixture.backend.get(REGION + "::source").orElseThrow()
                                .path("SyncSource").path("SourceRegions"));
                Future<?> stop = executor.submit(() -> {
                    stopStarted.countDown();
                    if ("reset".equals(stopping)) {
                        fixture.service.beforeReset();
                    } else {
                        fixture.service.shutdown();
                    }
                });
                assertTrue(stopStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> stop.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                publication.get(5, TimeUnit.SECONDS);
                stop.get(5, TimeUnit.SECONDS);
                JsonNode changed = fixture.current();
                assertEquals(JSON.arrayNode().add("us-west-2"),
                        changed.path("ResourceDataSyncItems").get(0).path("SyncSource").path("SourceRegions"));
                assertEquals(original.path("ResourceDataSyncItems").get(0).get("SyncCreatedTime"),
                        changed.path("ResourceDataSyncItems").get(0).get("SyncCreatedTime"));
                assertThrows(AwsException.class, () -> fixture.service.create(configuration("blocked"), REGION));
                assertEquals(changed, fixture.current());
                if ("reset".equals(stopping)) {
                    fixture.service.afterReset();
                    fixture.service.create(configuration("resumed"), REGION);
                    assertEquals(2, fixture.current().path("ResourceDataSyncItems").size());
                }
            } finally {
                release.countDown();
            }
        }
    }

    private static ObjectNode configuration(String name) {
        ObjectNode request = JSON.objectNode().put("SyncName", name).put("SyncType", SOURCE);
        request.putObject("SyncSource").put("SourceType", "SingleAccountMultiRegions")
                .put("IncludeFutureRegions", true).put("EnableAllOpsDataSources", false)
                .putArray("SourceRegions").add(REGION);
        return request;
    }

    private static ObjectNode list() {
        return JSON.objectNode().put("SyncType", SOURCE);
    }

    private static JsonNode item(JsonNode collection, String name) {
        for (JsonNode item : collection.path("ResourceDataSyncItems")) {
            if (name.equals(item.path("SyncName").asText())) {
                return item;
            }
        }
        throw new AssertionError("Missing source configuration " + name);
    }

    private static final class Fixture implements AutoCloseable {
        private final StorageFactory storage;
        private final AccountAwareStorageBackend<ObjectNode> backend;
        private final SsmResourceDataSyncService service;

        private Fixture(Path state, String mode) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.storage().persistentPath()).thenReturn(state.toString());
            when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("ssm")).thenReturn(mode);
            when(access.storageFlushInterval("ssm")).thenReturn(60000L);
            storage = new StorageFactory(config, access);
            backend = spy(storage.create("ssm", "ssm-resource-data-sync.json",
                    new TypeReference<Map<String, ObjectNode>>() {}));
            StorageFactory supplied = mock(StorageFactory.class);
            doReturn(backend).when(supplied).create(eq("ssm"), eq("ssm-resource-data-sync.json"), any(TypeReference.class));
            RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);
            service = new SsmResourceDataSyncService(supplied, resolver);
        }

        private ObjectNode current() {
            return service.list(list(), REGION);
        }

        private void mutate(String operation) {
            switch (operation) {
                case "create" -> service.create(configuration("new-source"), REGION);
                case "update" -> {
                    ObjectNode replacement = configuration("source");
                    replacement.withObject("/SyncSource").putArray("SourceRegions").add("us-west-2");
                    service.update(replacement, REGION);
                }
                case "delete" -> service.delete(configuration("source"), REGION);
                default -> throw new IllegalArgumentException(operation);
            }
        }

        @Override
        public void close() {
            service.shutdown();
            storage.shutdownAll();
        }
    }
}

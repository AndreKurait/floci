package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SsmResourceDataSyncServiceTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String ACCOUNT = "830022221111";
    private static final String REGION = "us-east-1";
    private static final String INSTANCE = "i-1234567890abcdef0";
    private static final String BUCKET = "inventory-destination";
    private static final String NAME = "inventory-sync";

    @TempDir
    Path state;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void restoresConfigurationsAndAcknowledgedRevisionsInTheConfiguredStorage(String mode) {
        Destination destination = new Destination();
        JsonNode original;
        try (Fixture first = new Fixture(state, mode, destination)) {
            first.inventory.putInventory(inventory("first"), REGION);
            ObjectNode request = configuration();
            first.syncs.create(request, REGION);
            request.withObject("/S3Destination").put("BucketName", "caller-mutated-input");
            first.syncs.synchronizeAll();
            original = first.current();
            assertEquals("Successful", original.path("LastStatus").asText());
            assertEquals(BUCKET, original.path("S3Destination").path("BucketName").asText());
            assertEquals(1, destination.writes.size());
        }
        try (Fixture restored = new Fixture(state, mode, destination)) {
            assertEquals("memory".equals(mode) ? 0 : 1,
                    restored.syncs.list(JSON.objectNode(), REGION).path("ResourceDataSyncItems").size());
            restored.syncs.synchronizeAll();
            assertEquals(1, destination.writes.size(), "An acknowledged report must not be re-exported after restart.");
            if (!"memory".equals(mode)) {
                assertEquals(original, restored.current());
                restored.inventory.putInventory(inventory("updated-after-restart"), REGION);
                restored.syncs.synchronizeAll();
                assertEquals(2, destination.writes.size());
                assertTrue(destination.writes.getLast().contains("updated-after-restart"));
                assertEquals("Successful", restored.current().path("LastStatus").asText());
                restored.syncs.clear();
                restored.syncs.synchronizeAll();
                assertTrue(restored.syncs.list(JSON.objectNode(), REGION).path("ResourceDataSyncItems").isEmpty());
                assertEquals(2, destination.writes.size());
            }
        }
    }

    @Test
    void failedUpdatesRetainThePriorSuccessAndRetryWithoutAnotherUpload() {
        Destination destination = new Destination();
        try (Fixture fixture = new Fixture(state, "memory", destination)) {
            fixture.inventory.putInventory(inventory("first"), REGION);
            fixture.syncs.create(configuration(), REGION);
            fixture.syncs.synchronizeAll();
            JsonNode successfulTime = fixture.current().get("LastSuccessfulSyncTime");
            assertNotNull(successfulTime);

            destination.denied.set(true);
            fixture.inventory.putInventory(inventory("second"), REGION);
            fixture.syncs.synchronizeAll();
            JsonNode failed = fixture.current();
            assertEquals("Failed", failed.path("LastStatus").asText());
            assertTrue(failed.path("LastSyncStatusMessage").asText().contains("AccessDenied"));
            assertEquals(successfulTime, failed.get("LastSuccessfulSyncTime"));
            assertEquals(1, destination.writes.size());

            destination.denied.set(false);
            fixture.syncs.synchronizeAll();
            assertEquals("Successful", fixture.current().path("LastStatus").asText());
            assertEquals(2, destination.writes.size());
            assertTrue(destination.writes.getLast().contains("second"));
            fixture.syncs.synchronizeAll();
            assertEquals(2, destination.writes.size());
        }
    }

    @Test
    void partialNodeExportsAreNotAcknowledgedUntilEveryTypeIsWritten() {
        Destination destination = new Destination();
        AtomicBoolean denyCustom = new AtomicBoolean(true);
        doAnswer(invocation -> {
            String key = invocation.getArgument(1);
            if (key.contains("Custom:Details") && denyCustom.get()) {
                throw new AwsException("AccessDenied", "Custom inventory prefix is denied.", 403);
            }
            destination.writes.add(key);
            return null;
        }).when(destination.service).putObject(anyString(), anyString(), any(byte[].class),
                anyString(), anyMap(), any(PutObjectOptions.class));
        try (Fixture fixture = new Fixture(state, "memory", destination)) {
            ObjectNode input = inventory("application");
            input.withArray("/Items").addObject().put("TypeName", "Custom:Details").put("SchemaVersion", "1.0")
                    .put("CaptureTime", "2026-01-02T03:04:05Z").putArray("Content").addObject().put("Name", "details");
            fixture.inventory.putInventory(input, REGION);
            fixture.syncs.create(configuration(), REGION);
            fixture.syncs.synchronizeAll();
            assertEquals("Failed", fixture.current().path("LastStatus").asText());
            assertFalse(fixture.current().has("LastSuccessfulSyncTime"));
            assertTrue(destination.writes.stream().anyMatch(key -> key.contains("AWS:Application")));
            assertFalse(destination.writes.stream().anyMatch(key -> key.contains("Custom:Details")));

            denyCustom.set(false);
            fixture.syncs.synchronizeAll();
            assertEquals("Successful", fixture.current().path("LastStatus").asText());
            assertTrue(destination.writes.stream().anyMatch(key -> key.contains("Custom:Details")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "reset", "shutdown"})
    void deletionResetAndShutdownDrainAnActiveExport(String operation) throws Exception {
        Destination destination = new Destination();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopping = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            destination.writes.add("completed");
            return null;
        }).when(destination.service).putObject(anyString(), anyString(), any(byte[].class),
                anyString(), anyMap(), any(PutObjectOptions.class));
        try (Fixture fixture = new Fixture(state, "memory", destination);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            fixture.inventory.putInventory(inventory("first"), REGION);
            fixture.syncs.create(configuration(), REGION);
            Future<?> export = executor.submit(fixture.syncs::synchronizeAll);
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                Future<?> stop = executor.submit(() -> {
                    stopping.countDown();
                    switch (operation) {
                        case "delete" -> fixture.syncs.delete(JSON.objectNode().put("SyncName", NAME), REGION);
                        case "reset" -> fixture.syncs.beforeReset();
                        case "shutdown" -> fixture.syncs.shutdown();
                        default -> throw new IllegalArgumentException(operation);
                    }
                });
                assertTrue(stopping.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> stop.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                export.get(5, TimeUnit.SECONDS);
                stop.get(5, TimeUnit.SECONDS);
                fixture.inventory.putInventory(inventory("after-stop"), REGION);
                fixture.syncs.synchronizeAll();
                assertEquals(1, destination.writes.size());
                if ("reset".equals(operation)) {
                    assertThrows(AwsException.class, () -> fixture.syncs.create(
                            configuration().put("SyncName", "during-reset"), REGION));
                    fixture.syncs.afterReset();
                    fixture.syncs.synchronizeAll();
                    assertEquals(2, destination.writes.size(),
                            "An aborted emulator reset must allow the preserved configuration to resume.");
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void resetHooksQuiesceBeforeTheStorageWipeAndResumeAfterwards() {
        Destination destination = new Destination();
        try (Fixture fixture = new Fixture(state, "memory", destination)) {
            fixture.inventory.putInventory(inventory("old"), REGION);
            fixture.syncs.create(configuration(), REGION);
            fixture.syncs.beforeReset();
            fixture.storage.clearAll();
            fixture.syncs.clear();
            fixture.syncs.synchronizeAll();
            assertTrue(destination.writes.isEmpty());
            fixture.syncs.afterReset();
            fixture.syncs.synchronizeAll();
            assertTrue(destination.writes.isEmpty());

            fixture.inventory.putInventory(inventory("new"), REGION);
            fixture.syncs.create(configuration(), REGION);
            fixture.syncs.synchronizeAll();
            assertEquals(1, destination.writes.size());
            assertTrue(destination.writes.getFirst().contains("new"));
        }
    }

    private static ObjectNode configuration() {
        ObjectNode request = JSON.objectNode().put("SyncName", NAME);
        request.putObject("S3Destination").put("BucketName", BUCKET).put("Region", REGION)
                .put("SyncFormat", "JsonSerDe");
        return request;
    }

    private static ObjectNode inventory(String name) {
        ObjectNode input = JSON.objectNode().put("InstanceId", INSTANCE);
        input.putArray("Items").addObject().put("TypeName", "AWS:Application").put("SchemaVersion", "1.1")
                .put("CaptureTime", "2026-01-02T03:04:05Z")
                .putArray("Content").addObject().put("Name", name);
        return input;
    }

    private static final class Destination {
        private final S3Service service = mock(S3Service.class);
        private final List<String> writes = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean denied = new AtomicBoolean();

        private Destination() {
            when(service.getBucketRegion(BUCKET)).thenReturn(REGION);
            when(service.getBucketOwnerAccountId(BUCKET)).thenReturn(ACCOUNT);
            when(service.getObjectLockConfiguration(BUCKET)).thenThrow(
                    new AwsException("ObjectLockConfigurationNotFoundError", "No Object Lock configuration.", 404));
            doAnswer(invocation -> {
                if (denied.get()) {
                    throw new AwsException("AccessDenied", "Destination temporarily denies delivery.", 403);
                }
                PutObjectOptions options = invocation.getArgument(5);
                assertEquals("bucket-owner-full-control", options.getAcl());
                writes.add(new String(invocation.getArgument(2, byte[].class), StandardCharsets.UTF_8));
                return null;
            }).when(service).putObject(anyString(), anyString(), any(byte[].class),
                    anyString(), anyMap(), any(PutObjectOptions.class));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final StorageFactory storage;
        private final SsmInventoryService inventory;
        private final SsmResourceDataSyncService syncs;

        private Fixture(Path state, String mode, Destination destination) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.storage().persistentPath()).thenReturn(state.toString());
            when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
            when(config.services().s3().enabled()).thenReturn(true);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("ssm")).thenReturn(mode);
            when(access.storageFlushInterval("ssm")).thenReturn(60000L);
            storage = new StorageFactory(config, access);
            RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
            inventory = new SsmInventoryService(storage, mock(SsmCommandService.class), regionResolver);
            syncs = new SsmResourceDataSyncService(storage, inventory, destination.service,
                    mock(IamPolicyEvaluator.class), regionResolver, config, new ObjectMapper());
        }

        private JsonNode current() {
            return syncs.list(JSON.objectNode(), REGION).path("ResourceDataSyncItems").get(0);
        }

        @Override
        public void close() {
            syncs.shutdown();
            storage.shutdownAll();
        }
    }
}

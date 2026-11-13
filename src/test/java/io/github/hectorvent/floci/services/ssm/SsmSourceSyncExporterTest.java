package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.AccountEntry;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SsmSourceSyncExporterTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String ACCOUNT = "873033360000";
    private static final String REGION = "us-east-1";
    private static final String SOURCE = "SyncFromSource";
    private static final String DESTINATION = "SyncToDestination";

    @TempDir
    Path state;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void sourceRowsAreExcludedBeforeInventoryAndS3WorkIncludingAfterReload(String mode) {
        JsonNode original;
        try (Fixture fixture = new Fixture(state, mode)) {
            fixture.service.create(source("source"), REGION);
            original = fixture.sources();
            fixture.service.synchronizeAll();
            assertEquals(original, fixture.sources());
            verify(fixture.inventory, never()).inventories(anyString(), anyString());
            verifyNoInteractions(fixture.s3);
            assertFalse(fixture.backend.get(REGION + "::source").orElseThrow().has("Acknowledged"));
        }
        try (Fixture restored = new Fixture(state, mode)) {
            restored.service.synchronizeAll();
            assertEquals("memory".equals(mode) ? JSON.objectNode().set("ResourceDataSyncItems", JSON.arrayNode())
                    : original, restored.sources());
            verify(restored.inventory, never()).inventories(anyString(), anyString());
            verifyNoInteractions(restored.s3);
        }
    }

    @Test
    void destinationProgressesWhileAllSourceFieldsStayUnchanged() {
        try (Fixture fixture = new Fixture(state, "memory")) {
            fixture.service.create(source("source"), REGION);
            fixture.service.create(destination("destination"), REGION);
            JsonNode before = fixture.sources();
            fixture.inventory.putInventory(inventory("first"), REGION);
            fixture.service.synchronizeAll();
            assertEquals(before, fixture.sources());
            assertEquals("Successful", fixture.destinations().path("ResourceDataSyncItems").get(0).path("LastStatus").asText());
            assertEquals(1, fixture.writes.size());
            assertTrue(fixture.writes.getFirst().contains("first"));
            assertFalse(fixture.backend.get(REGION + "::source").orElseThrow().has("Acknowledged"));
            fixture.inventory.putInventory(inventory("second"), REGION);
            fixture.service.synchronizeAll();
            assertEquals(before, fixture.sources());
            assertEquals(2, fixture.writes.size());
            assertTrue(fixture.writes.getLast().contains("second"));
        }
    }

    @Test
    void aCapturedDestinationWhoseCurrentRowIsSourceIsExcludedBeforeCheckpointing() {
        try (Fixture fixture = new Fixture(state, "memory")) {
            fixture.service.create(destination("same-name"), REGION);
            List<AccountEntry<ObjectNode>> captured = fixture.backend.scanAllAccountEntries(key -> true);
            assertEquals(1, captured.size());
            assertEquals(DESTINATION, captured.getFirst().value().path("SyncType").asText());
            fixture.service.delete(destination("same-name"), REGION);
            fixture.service.create(source("same-name"), REGION);
            JsonNode before = fixture.sources();
            ObjectNode stored = fixture.backend.get(REGION + "::same-name").orElseThrow().deepCopy();
            doReturn(captured).when(fixture.backend).scanAllAccountEntries(any());
            clearInvocations(fixture.inventory, fixture.s3);
            fixture.service.synchronizeAll();
            assertEquals(before, fixture.sources());
            assertEquals(stored, fixture.backend.get(REGION + "::same-name").orElseThrow());
            verify(fixture.inventory, never()).inventories(anyString(), anyString());
            verifyNoInteractions(fixture.s3);
        }
    }

    @Test
    void eachTypeHasItsOwnFiveRowQuotaAndNamesRemainUniqueAcrossTypes() {
        try (Fixture fixture = new Fixture(state, "memory")) {
            for (int index = 0; index < 5; index++) {
                fixture.service.create(source("source-" + index), REGION);
                fixture.service.create(destination("destination-" + index), REGION);
            }
            JsonNode sources = fixture.sources();
            JsonNode destinations = fixture.destinations();
            assertEquals(5, sources.path("ResourceDataSyncItems").size());
            assertEquals(5, destinations.path("ResourceDataSyncItems").size());
            assertEquals("ResourceDataSyncCountExceededException", assertThrows(AwsException.class,
                    () -> fixture.service.create(source("sixth-source"), REGION)).getErrorCode());
            assertEquals("ResourceDataSyncCountExceededException", assertThrows(AwsException.class,
                    () -> fixture.service.create(destination("sixth-destination"), REGION)).getErrorCode());
            assertEquals("ResourceDataSyncAlreadyExistsException", assertThrows(AwsException.class,
                    () -> fixture.service.create(destination("source-0"), REGION)).getErrorCode());
            assertEquals("ResourceDataSyncAlreadyExistsException", assertThrows(AwsException.class,
                    () -> fixture.service.create(source("destination-0"), REGION)).getErrorCode());
            assertEquals("ResourceDataSyncNotFoundException", assertThrows(AwsException.class,
                    () -> fixture.service.delete(source("destination-0"), REGION)).getErrorCode());
            assertEquals(sources, fixture.sources());
            assertEquals(destinations, fixture.destinations());
        }
    }

    private static ObjectNode source(String name) {
        ObjectNode request = JSON.objectNode().put("SyncName", name).put("SyncType", SOURCE);
        request.putObject("SyncSource").put("SourceType", "SingleAccountMultiRegions")
                .put("IncludeFutureRegions", true).put("EnableAllOpsDataSources", false)
                .putArray("SourceRegions").add(REGION);
        return request;
    }

    private static ObjectNode destination(String name) {
        ObjectNode request = JSON.objectNode().put("SyncName", name).put("SyncType", DESTINATION);
        request.putObject("S3Destination").put("BucketName", "owned-destination")
                .put("Region", REGION).put("SyncFormat", "JsonSerDe");
        return request;
    }

    private static ObjectNode inventory(String name) {
        ObjectNode input = JSON.objectNode().put("InstanceId", "i-1234567890abcdef0");
        input.putArray("Items").addObject().put("TypeName", "AWS:Application").put("SchemaVersion", "1.1")
                .put("CaptureTime", "2026-01-02T03:04:05Z").putArray("Content").addObject().put("Name", name);
        return input;
    }

    private static final class Fixture implements AutoCloseable {
        private final StorageFactory storage;
        private final AccountAwareStorageBackend<ObjectNode> backend;
        private final SsmInventoryService inventory;
        private final S3Service s3 = mock(S3Service.class);
        private final SsmResourceDataSyncService service;
        private final List<String> writes = new ArrayList<>();

        private Fixture(Path path, String mode) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.storage().persistentPath()).thenReturn(path.toString());
            when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
            when(config.services().s3().enabled()).thenReturn(true);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("ssm")).thenReturn(mode);
            when(access.storageFlushInterval("ssm")).thenReturn(60000L);
            storage = new StorageFactory(config, access);
            backend = spy(storage.create("ssm", "ssm-resource-data-sync.json",
                    new TypeReference<Map<String, ObjectNode>>() {}));
            StorageFactory supplied = mock(StorageFactory.class);
            doReturn(backend).when(supplied).create(eq("ssm"), eq("ssm-resource-data-sync.json"), any(TypeReference.class));
            RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);
            inventory = spy(new SsmInventoryService(storage, mock(SsmCommandService.class), resolver));
            when(s3.getBucketRegion("owned-destination")).thenReturn(REGION);
            when(s3.getBucketOwnerAccountId("owned-destination")).thenReturn(ACCOUNT);
            when(s3.getObjectLockConfiguration("owned-destination")).thenThrow(
                    new AwsException("ObjectLockConfigurationNotFoundError", "No Object Lock configuration.", 404));
            doAnswer(invocation -> {
                writes.add(new String(invocation.getArgument(2, byte[].class), StandardCharsets.UTF_8));
                return null;
            }).when(s3).putObject(anyString(), anyString(), any(byte[].class), anyString(), anyMap(), any(PutObjectOptions.class));
            service = new SsmResourceDataSyncService(supplied, inventory, s3, mock(IamPolicyEvaluator.class),
                    resolver, config, new ObjectMapper());
        }

        private ObjectNode sources() { return service.list(JSON.objectNode().put("SyncType", SOURCE), REGION); }
        private ObjectNode destinations() { return service.list(JSON.objectNode().put("SyncType", DESTINATION), REGION); }

        @Override
        public void close() {
            service.shutdown();
            storage.shutdownAll();
        }
    }
}

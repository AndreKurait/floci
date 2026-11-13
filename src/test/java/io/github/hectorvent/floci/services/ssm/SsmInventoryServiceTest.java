package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SsmInventoryServiceTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String ACCOUNT = "830011112104";
    private static final String REGION = "us-east-1";
    private static final String INSTANCE = "i-1234567890abcdef0";
    private static final String TYPE = "Custom:Application";

    @TempDir
    Path state;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void inventoryUsesTheConfiguredStorageAndIsResettable(String mode) {
        StorageFactory first = storage(mode);
        try {
            SsmInventoryService service = new SsmInventoryService(
                    first, mock(SsmCommandService.class), new RegionResolver(REGION, ACCOUNT));
            ObjectNode input = request(TYPE);
            service.putInventory(input, REGION);
            input.withArray("/Items").removeAll();
            assertEquals("application", service.listInventoryEntries(read(TYPE), REGION)
                    .path("Entries").get(0).path("Name").asText());
            ObjectNode snapshot = service.inventories(ACCOUNT, REGION).getFirst();
            snapshot.withObject("/Items").removeAll();
            assertEquals(1, service.inventories(ACCOUNT, REGION).getFirst().path("Items").size());
            assertTrue(service.inventories("830011112105", REGION).isEmpty());
            assertTrue(service.inventories(ACCOUNT, "us-west-2").isEmpty());
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = storage(mode);
        try {
            SsmInventoryService restored = new SsmInventoryService(
                    second, mock(SsmCommandService.class), new RegionResolver(REGION, ACCOUNT));
            assertEquals("memory".equals(mode) ? 0 : 1,
                    restored.listInventoryEntries(read(TYPE), REGION).path("Entries").size());
            if (!"memory".equals(mode)) {
                ObjectNode refresh = request(TYPE);
                ((ObjectNode) refresh.withArray("/Items").get(0)).remove("Content");
                restored.putInventory(refresh, REGION);
                assertEquals(1, restored.listInventoryEntries(read(TYPE), REGION).path("Entries").size());
            }
            restored.clear();
            assertTrue(restored.listInventoryEntries(read(TYPE), REGION).path("Entries").isEmpty());
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void concurrentUploadsKeepEveryTypeOnTheManagedNode() throws Exception {
        SsmInventoryService service = service();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                String type = "Custom:Type" + index;
                futures.add(executor.submit(() -> service.putInventory(request(type), REGION)));
            }
            for (Future<?> result : futures) {
                result.get();
            }
        }
        assertEquals(20, service.inventories(ACCOUNT, REGION).getFirst().path("Items").size());
    }

    @Test
    void aHashCannotStandInForAnUnseenSchema() {
        SsmInventoryService service = service();
        ObjectNode initial = request(TYPE);
        service.putInventory(initial, REGION);
        ObjectNode refresh = request(TYPE);
        ObjectNode item = (ObjectNode) refresh.withArray("/Items").get(0);
        item.remove("Content");
        item.put("SchemaVersion", "2.0");
        AwsException error = assertThrows(AwsException.class, () -> service.putInventory(refresh, REGION));
        assertEquals("ItemContentMismatchException", error.getErrorCode());
        assertEquals("1.0", service.listInventoryEntries(read(TYPE), REGION).path("SchemaVersion").asText());
    }

    @Test
    void missingHashOnlyContentDoesNotCreateAnInventoryRecord() {
        SsmInventoryService service = service();
        ObjectNode input = request(TYPE);
        ((ObjectNode) input.withArray("/Items").get(0)).remove("Content");
        AwsException error = assertThrows(AwsException.class, () -> service.putInventory(input, REGION));
        assertEquals("ItemContentMismatchException", error.getErrorCode());
        assertTrue(service.inventories(ACCOUNT, REGION).isEmpty());
    }

    private StorageFactory storage(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(state.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("ssm")).thenReturn(mode);
        when(access.storageFlushInterval("ssm")).thenReturn(60000L);
        return new StorageFactory(config, access);
    }

    private static SsmInventoryService service() {
        return new SsmInventoryService(AccountAwareStorageBackend.inMemory(ACCOUNT),
                mock(SsmCommandService.class), new RegionResolver(REGION, ACCOUNT));
    }

    private static ObjectNode request(String type) {
        ObjectNode request = JSON.objectNode().put("InstanceId", INSTANCE);
        ObjectNode item = request.putArray("Items").addObject().put("TypeName", type)
                .put("SchemaVersion", "1.0").put("CaptureTime", "2026-01-02T03:04:05Z")
                .put("ContentHash", "stored-client-hash");
        item.putArray("Content").addObject().put("Name", "application");
        return request;
    }

    private static ObjectNode read(String type) {
        return JSON.objectNode().put("InstanceId", INSTANCE).put("TypeName", type);
    }
}

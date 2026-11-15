package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.KeyValueStore;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFrontKeyValueStorePersistenceTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void restartPreservesBothVersionsAtomicKeyBatchAndDeletion(String mode) {
        String arn;
        String oldControl;
        String control;
        String data;
        StorageFactory first = factory(mode);
        try {
            CloudFrontKeyValueStoreService service = service(first);
            KeyValueStore created = service.create("persisted", "original");
            arn = created.getArn();
            oldControl = created.getEtag();
            KeyValueStore firstWrite = service.putKey(arn, created.getDataEtag(), "remove", "old");
            KeyValueStore batch = service.updateKeys(arn, firstWrite.getDataEtag(),
                    Map.of("nested/key", "unicode-\u00e9", "second", ""), List.of("remove"));
            data = batch.getDataEtag();
            KeyValueStore changed = service.update("persisted", oldControl, "updated");
            control = changed.getEtag();
            assertEquals(data, changed.getDataEtag());
            assertNotEquals(oldControl, control);
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = factory(mode);
        try {
            CloudFrontKeyValueStoreService service = service(second);
            KeyValueStore restored = service.describe("persisted");
            assertEquals(arn, restored.getArn());
            assertEquals("updated", restored.getComment());
            assertEquals(control, restored.getEtag());
            assertEquals(data, restored.getDataEtag());
            assertEquals(Map.of("nested/key", "unicode-\u00e9", "second", ""), restored.getItems());
            AwsException stale = assertThrows(AwsException.class, () -> service.delete("persisted", oldControl));
            assertEquals("PreconditionFailed", stale.getErrorCode());
            assertEquals(412, stale.getHttpStatus());
            assertEquals(control, service.describe("persisted").getEtag());
            AwsException invalid = assertThrows(AwsException.class, () -> service.updateKeys(arn, data,
                    Map.of("would-be-written", "value", "oversize", "x".repeat(1025)), List.of("second")));
            assertEquals("ValidationException", invalid.getErrorCode());
            assertEquals(Map.of("nested/key", "unicode-\u00e9", "second", ""), service.describe("persisted").getItems());
            assertEquals(data, service.describe("persisted").getDataEtag());
            service.delete("persisted", control);
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = factory(mode);
        try {
            CloudFrontKeyValueStoreService service = service(third);
            AwsException missing = assertThrows(AwsException.class, () -> service.describe("persisted"));
            assertEquals("EntityNotFound", missing.getErrorCode());
            assertEquals(List.of(), service.list(null, 100, null));
            assertEquals(Optional.empty(), service.findByArn(arn));
        } finally {
            third.shutdownAll();
        }
    }

    private static CloudFrontKeyValueStoreService service(StorageFactory factory) {
        return new CloudFrontKeyValueStoreService(factory, new RegionResolver("us-east-1", "000000000000"));
    }

    private StorageFactory factory(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(60000L);
        return new StorageFactory(config, access);
    }
}

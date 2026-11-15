package io.github.hectorvent.floci.services.s3tables;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.s3tables.model.TableBucket;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3TablesMetricsPersistenceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void enabledDeletedAndReenabledStateSurvivesReload(String mode, @TempDir Path directory) {
        Path snapshot = directory.resolve("s3tables.json");
        StorageBackend<String, TableBucket> backend = open(mode, snapshot);
        String arn;
        try {
            S3TablesService service = service(backend);
            arn = service.createTableBucket("metrics-reload", null, null, Map.of("owner", "kept"), REGION).getArn();
            service.putTableBucketMetricsConfiguration(arn, REGION);
        } finally {
            close(backend);
        }
        for (boolean enabled : new boolean[] {true, false, true}) {
            backend = open(mode, snapshot);
            try {
                S3TablesService service = service(backend);
                if (enabled) {
                    assertEquals("EntireBucket", service.getTableBucketMetricsConfiguration(arn, REGION).get("id"));
                    service.deleteTableBucketMetricsConfiguration(arn, REGION);
                } else {
                    assertThrows(AwsException.class, () -> service.getTableBucketMetricsConfiguration(arn, REGION));
                    service.putTableBucketMetricsConfiguration(arn, REGION);
                }
                assertEquals(Map.of("owner", "kept"), service.getTableBucket(arn, REGION).getTags());
            } finally {
                close(backend);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusedDiskWritePreservesThePreviousLiveAndPersistedState(boolean originallyEnabled,
                                                                   @TempDir Path directory) throws IOException {
        Path snapshot = directory.resolve("s3tables.json");
        StorageBackend<String, TableBucket> backend = open("persistent", snapshot);
        S3TablesService service = service(backend);
        String arn = service.createTableBucket("metrics-failed-write", null, null, Map.of(), REGION).getArn();
        if (originallyEnabled) {
            service.putTableBucketMetricsConfiguration(arn, REGION);
        }
        TableBucket original = service.getTableBucket(arn, REGION);
        byte[] committed = Files.readAllBytes(snapshot);
        Files.delete(snapshot);
        Files.createDirectory(snapshot);
        Files.writeString(snapshot.resolve("block-replacement"), "owned test obstacle");
        try {
            assertThrows(UncheckedIOException.class, () -> {
                if (originallyEnabled) {
                    service.deleteTableBucketMetricsConfiguration(arn, REGION);
                } else {
                    service.putTableBucketMetricsConfiguration(arn, REGION);
                }
            });
            assertSame(original, service.getTableBucket(arn, REGION));
            assertEquals(originallyEnabled, original.isMetricsConfigurationEnabled());
        } finally {
            Files.delete(snapshot.resolve("block-replacement"));
            Files.delete(snapshot);
            Files.write(snapshot, committed);
        }
        StorageBackend<String, TableBucket> reloaded = open("persistent", snapshot);
        assertEquals(originallyEnabled, service(reloaded).getTableBucket(arn, REGION).isMetricsConfigurationEnabled());
        assertArrayEquals(committed, Files.readAllBytes(snapshot));
    }

    private static S3TablesService service(StorageBackend<String, TableBucket> backend) {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<TableBucket>create(anyString(), anyString(), any()))
                .thenReturn(new AccountAwareStorageBackend<>(backend, null, ACCOUNT));
        return new S3TablesService(factory, new RegionResolver(REGION, ACCOUNT));
    }

    private static StorageBackend<String, TableBucket> open(String mode, Path snapshot) {
        TypeReference<Map<String, TableBucket>> type = new TypeReference<>() {};
        StorageBackend<String, TableBucket> backend = switch (mode) {
            case "persistent" -> new PersistentStorage<>(snapshot, type);
            case "hybrid" -> new HybridStorage<>(snapshot, type, 60_000);
            case "wal" -> new WalStorage<>(snapshot, snapshot.resolveSibling("s3tables.wal"), type, 60_000);
            default -> throw new IllegalArgumentException(mode);
        };
        backend.load();
        return backend;
    }

    private static void close(StorageBackend<String, TableBucket> backend) {
        if (backend instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        } else if (backend instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        } else {
            backend.flush();
        }
    }
}

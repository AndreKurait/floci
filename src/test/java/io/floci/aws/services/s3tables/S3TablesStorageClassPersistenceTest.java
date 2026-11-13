package io.floci.aws.services.s3tables;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.HybridStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.s3tables.model.TableBucket;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class S3TablesStorageClassPersistenceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String KEY = ACCOUNT + "/" + REGION + "::warehouse";
    private static final Map<String, String> STANDARD = Map.of("storageClass", "STANDARD");
    private static final Map<String, String> INTELLIGENT_TIERING = Map.of("storageClass", "INTELLIGENT_TIERING");

    @ParameterizedTest
    @CsvSource({"persistent,false", "persistent,true", "hybrid,false", "hybrid,true", "wal,false", "wal,true"})
    void bucketAndSettledTableClassesSurviveReload(String mode, boolean legacyTable, @TempDir Path directory) {
        Path snapshot = directory.resolve("s3tables.json");
        String arn;
        StorageBackend<String, TableBucket> first = open(mode, snapshot);
        try {
            S3TablesService service = service(first);
            arn = service.createTableBucket("warehouse", null, null, Map.of(), REGION).getArn();
            service.createNamespace(arn, List.of("audit"), REGION);
            service.createTable(arn, "audit", "before_change", "ICEBERG", null, null, null, Map.of(), REGION);
            if (legacyTable) {
                TableBucket stored = first.get(KEY).orElseThrow();
                stored.setStorageClassConfiguration(null);
                stored.getNamespaces().get("audit").getTables().get("before_change").setStorageClassConfiguration(null);
                first.put(KEY, stored);
            }
            service.putTableBucketStorageClass(arn, INTELLIGENT_TIERING, REGION);
            service.createTable(arn, "audit", "after_change", "ICEBERG", null, null, null, Map.of(), REGION);
            assertEquals(STANDARD, service.getTableStorageClass(arn, "audit", "before_change", REGION));
        } finally {
            close(first);
        }

        StorageBackend<String, TableBucket> second = open(mode, snapshot);
        try {
            S3TablesService restarted = service(second);
            assertEquals(INTELLIGENT_TIERING, restarted.getTableBucketStorageClass(arn, REGION));
            assertEquals(STANDARD, restarted.getTableStorageClass(arn, "audit", "before_change", REGION));
            assertEquals(INTELLIGENT_TIERING, restarted.getTableStorageClass(arn, "audit", "after_change", REGION));
            restarted.putTableBucketStorageClass(arn, STANDARD, REGION);
            assertEquals(INTELLIGENT_TIERING, restarted.getTableStorageClass(arn, "audit", "after_change", REGION));
        } finally {
            close(second);
        }
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

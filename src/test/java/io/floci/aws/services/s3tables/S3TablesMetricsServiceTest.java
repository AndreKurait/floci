package io.floci.aws.services.s3tables;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3tables.model.TableBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3TablesMetricsServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";
    private S3TablesService service;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<TableBucket>create(anyString(), anyString(), any(TypeReference.class)))
                .thenReturn(new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT));
        service = new S3TablesService(factory, new RegionResolver(REGION, ACCOUNT));
    }

    @Test
    void togglesOneConfigurationWithAStableWholeBucketId() {
        String first = bucket("metrics-first");
        String second = bucket("metrics-second");
        absent(first);
        service.deleteTableBucketMetricsConfiguration(first, REGION);
        absent(first);
        service.putTableBucketMetricsConfiguration(first, REGION);
        service.putTableBucketMetricsConfiguration(first, REGION);
        assertEquals(Map.of("tableBucketARN", first, "id", "EntireBucket"),
                service.getTableBucketMetricsConfiguration(first, REGION));
        absent(second);
        service.putTableBucketMetricsConfiguration(second, REGION);
        service.deleteTableBucketMetricsConfiguration(first, REGION);
        service.deleteTableBucketMetricsConfiguration(first, REGION);
        absent(first);
        assertEquals("EntireBucket", service.getTableBucketMetricsConfiguration(second, REGION).get("id"));
        service.putTableBucketMetricsConfiguration(first, REGION);
        assertEquals("EntireBucket", service.getTableBucketMetricsConfiguration(first, REGION).get("id"));
    }

    @Test
    void replacementPreservesTheBucketsExistingMetadataAndTables() {
        Map<String, String> encryption = Map.of("sseAlgorithm", "AES256");
        Map<String, String> storageClass = Map.of("storageClass", "STANDARD");
        String arn = service.createTableBucket("metrics-metadata", encryption, storageClass,
                Map.of("owner", "audit"), REGION).getArn();
        service.createNamespace(arn, List.of("analytics"), REGION);
        service.createTable(arn, "analytics", "events", "ICEBERG", null, null, null, Map.of(), REGION);
        service.putTableBucketPolicy(arn, "{\"Statement\":[]}", REGION);
        service.putTableBucketMaintenance(arn, "icebergUnreferencedFileRemoval", Map.of("status", "enabled"), REGION);
        service.putTableBucketMetricsConfiguration(arn, REGION);
        service.deleteTableBucketMetricsConfiguration(arn, REGION);
        TableBucket after = service.getTableBucket(arn, REGION);
        assertEquals(encryption, after.getEncryptionConfiguration());
        assertEquals(storageClass, after.getStorageClassConfiguration());
        assertEquals(Map.of("owner", "audit"), after.getTags());
        assertEquals("{\"Statement\":[]}", after.getResourcePolicy());
        assertEquals(Map.of("status", "enabled"),
                after.getMaintenanceConfigurations().get("icebergUnreferencedFileRemoval"));
        assertEquals("events", service.getTable(arn, "analytics", "events", REGION).getName());
    }

    @Test
    void configurationDoesNotSurviveBucketDeletionAndRecreation() {
        String arn = bucket("metrics-recreated");
        service.putTableBucketMetricsConfiguration(arn, REGION);
        service.deleteTableBucket(arn, REGION);
        absent(arn);
        assertEquals(arn, bucket("metrics-recreated"));
        absent(arn);
    }

    @Test
    void concurrentPutsAndUnrelatedBucketUpdatesPreserveBothChanges() throws Exception {
        String arn = bucket("metrics-concurrent");
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                int writer = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    if (writer == 0) {
                        service.putTableBucketPolicy(arn, "{\"Statement\":[]}", REGION);
                    } else {
                        service.putTableBucketMetricsConfiguration(arn, REGION);
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
        }
        assertEquals("EntireBucket", service.getTableBucketMetricsConfiguration(arn, REGION).get("id"));
        assertEquals("{\"Statement\":[]}", service.getTableBucketPolicy(arn, REGION));
    }

    @Test
    void aConcurrentBucketDeleteCannotLeaveConfigurationBehind() throws Exception {
        String arn = bucket("metrics-delete-race");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> put = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    service.putTableBucketMetricsConfiguration(arn, REGION);
                } catch (AwsException exception) {
                    assertEquals("NotFoundException", exception.getErrorCode());
                }
                return null;
            });
            Future<?> delete = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                service.deleteTableBucket(arn, REGION);
                return null;
            });
            start.countDown();
            put.get(10, TimeUnit.SECONDS);
            delete.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
        }
        absent(arn);
        assertEquals(arn, bucket("metrics-delete-race"));
        absent(arn);
    }

    private String bucket(String name) {
        return service.createTableBucket(name, null, null, Map.of(), REGION).getArn();
    }

    private void absent(String arn) {
        AwsException exception = assertThrows(AwsException.class,
                () -> service.getTableBucketMetricsConfiguration(arn, REGION));
        assertEquals("NotFoundException", exception.getErrorCode());
    }
}

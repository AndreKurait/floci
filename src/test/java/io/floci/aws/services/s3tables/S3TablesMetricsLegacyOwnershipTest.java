package io.floci.aws.services.s3tables;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3tables.model.TableBucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3TablesMetricsLegacyOwnershipTest {
    private static final String REGION = "us-east-1";
    private static final String CALLER = "111111111111";
    private static final String OTHER = "222222222222";
    private static final String NAME = "metrics-legacy";
    private static final String KEY = REGION + "::" + NAME;

    @ParameterizedTest
    @ValueSource(strings = {"get", "put", "delete"})
    void foreignLegacyArnIsRefusedWithoutMovingOrChangingItsRecord(String operation) {
        InMemoryStorage<String, TableBucket> backend = new InMemoryStorage<>();
        TableBucket foreign = legacy(OTHER);
        backend.put(KEY, foreign);
        S3TablesService service = service(backend);
        assertRefused(service, operation, foreign.getArn());
        assertEquals(Set.of(KEY), backend.keys());
        assertSame(foreign, backend.get(KEY).orElseThrow());
        assertTrue(foreign.isMetricsConfigurationEnabled());
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "put", "delete"})
    void sameNameCallerArnDoesNotMigrateAnotherOwnersLegacyRecord(String operation) {
        InMemoryStorage<String, TableBucket> backend = new InMemoryStorage<>();
        TableBucket foreign = legacy(OTHER);
        backend.put(KEY, foreign);
        assertRefused(service(backend), operation, arn(CALLER));
        assertEquals(Set.of(KEY), backend.keys());
        assertSame(foreign, backend.get(KEY).orElseThrow());
        assertTrue(foreign.isMetricsConfigurationEnabled());
    }

    @Test
    void theActualOwnerCanMigrateItsLegacyBucketAndKeepConfiguration() {
        InMemoryStorage<String, TableBucket> backend = new InMemoryStorage<>();
        TableBucket owned = legacy(CALLER);
        backend.put(KEY, owned);
        S3TablesService service = service(backend);
        assertEquals("EntireBucket", service.getTableBucketMetricsConfiguration(owned.getArn(), REGION).get("id"));
        assertEquals(Set.of(CALLER + "/" + KEY), backend.keys());
        service.deleteTableBucketMetricsConfiguration(owned.getArn(), REGION);
        assertFalse(backend.get(CALLER + "/" + KEY).orElseThrow().isMetricsConfigurationEnabled());
        service.putTableBucketMetricsConfiguration(owned.getArn(), REGION);
        assertEquals("EntireBucket", service.getTableBucketMetricsConfiguration(owned.getArn(), REGION).get("id"));
        assertEquals(Map.of("owner", CALLER), service.getTableBucket(owned.getArn(), REGION).getTags());
    }

    private static void assertRefused(S3TablesService service, String operation, String arn) {
        AwsException exception = assertThrows(AwsException.class, () -> {
            switch (operation) {
                case "get" -> service.getTableBucketMetricsConfiguration(arn, REGION);
                case "put" -> service.putTableBucketMetricsConfiguration(arn, REGION);
                case "delete" -> service.deleteTableBucketMetricsConfiguration(arn, REGION);
                default -> throw new IllegalArgumentException(operation);
            }
        });
        assertEquals("NotFoundException", exception.getErrorCode());
    }

    private static TableBucket legacy(String account) {
        TableBucket bucket = new TableBucket(NAME, arn(account), "2026-01-01T00:00:00Z",
                account, null, null, Map.of("owner", account));
        bucket.setMetricsConfigurationEnabled(true);
        return bucket;
    }

    private static String arn(String account) {
        return AwsArnUtils.Arn.of("s3tables", REGION, account, "bucket/" + NAME).toString();
    }

    private static S3TablesService service(InMemoryStorage<String, TableBucket> backend) {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<TableBucket>create(anyString(), anyString(), any()))
                .thenReturn(new AccountAwareStorageBackend<>(backend, null, CALLER));
        return new S3TablesService(factory, new RegionResolver(REGION, CALLER));
    }
}

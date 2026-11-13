package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3DefaultEncryptionStorageTest {

    private static final String BUCKET = "default-encryption-storage";
    private static final String KMS_KEY = "arn:aws:kms:us-east-1:000000000000:key/default";
    private static final byte[] DATA = "stored inventory".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void defaultsAndObjectMetadataFollowTheStorageLifetime(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().mode()).thenReturn(mode);
        when(config.storage().services().s3().mode()).thenReturn(Optional.of(mode));
        when(config.storage().wal().compactionIntervalMs()).thenReturn(3_600_000L);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(3_600_000L);

        StorageFactory first = new StorageFactory(config, access);
        try {
            S3Service service = service(first, config);
            service.createBucket(BUCKET, "us-east-1");
            service.putBucketEncryption(BUCKET, """
                    <ServerSideEncryptionConfiguration><Rule>
                        <ApplyServerSideEncryptionByDefault>
                            <SSEAlgorithm>aws:kms</SSEAlgorithm>
                            <KMSMasterKeyID>%s</KMSMasterKeyID>
                        </ApplyServerSideEncryptionByDefault>
                    </Rule></ServerSideEncryptionConfiguration>
                    """.formatted(KMS_KEY));
            S3Object object = service.putObject(BUCKET, "before", DATA, "text/plain", Map.of());
            assertEquals("aws:kms", object.getServerSideEncryption());
            assertEquals(KMS_KEY, object.getSseKmsKeyId());
        } finally {
            first.shutdownAll();
        }

        StorageFactory second = new StorageFactory(config, access);
        try {
            S3Service reopened = service(second, config);
            if ("memory".equals(mode)) {
                assertTrue(reopened.listBuckets().isEmpty());
                return;
            }
            S3Object stored = reopened.getObject(BUCKET, "before");
            assertArrayEquals(DATA, stored.getData());
            assertEquals("aws:kms", stored.getServerSideEncryption());
            assertEquals(KMS_KEY, stored.getSseKmsKeyId());
            assertEquals(KMS_KEY, reopened.putObject(BUCKET, "after", DATA, "text/plain", Map.of()).getSseKmsKeyId());
            reopened.deleteBucketEncryption(BUCKET);
            assertEquals("AES256", reopened.putObject(BUCKET, "reset", DATA, "text/plain", Map.of())
                    .getServerSideEncryption());
            assertEquals(KMS_KEY, reopened.getObject(BUCKET, "before").getSseKmsKeyId());
        } finally {
            second.shutdownAll();
        }
    }

    private static S3Service service(StorageFactory factory, EmulatorConfig config) {
        return new S3Service(factory, config, null, null, null, null, null,
                new RegionResolver("us-east-1", "000000000000"), new ObjectMapper(), null);
    }
}

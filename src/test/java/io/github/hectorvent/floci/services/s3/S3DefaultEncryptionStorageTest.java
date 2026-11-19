package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3DefaultEncryptionStorageTest {

    private static final String BUCKET = "default-encryption-storage";
    private static final byte[] DATA = "stored inventory".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    @ParameterizedTest
    @MethodSource("streamVariants")
    void objectStreamsReturnPlaintextForEveryVersionForm(String mode, String versionForm, boolean encrypted)
            throws IOException {
        EmulatorConfig config = storageConfig(mode);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(3_600_000L);
        StorageFactory factory = new StorageFactory(config, access);
        try {
            S3Service service = service(factory, config);
            service.createBucket(BUCKET, "us-east-1");
            if (encrypted) {
                String keyArn = new KmsService(factory, new RegionResolver("us-east-1", "000000000000"))
                        .createKey("stream wrapping key", "us-east-1").getArn();
                service.putBucketEncryption(BUCKET, kmsDefault(keyArn));
            }
            if (!"null".equals(versionForm)) {
                service.putBucketVersioning(BUCKET, "Enabled");
            }
            S3Object original = service.putObject(BUCKET, "stream", DATA, "text/plain", Map.of());
            byte[] replacement = "replacement inventory".getBytes(StandardCharsets.UTF_8);
            service.putObject(BUCKET, "stream", replacement, "text/plain", Map.of());
            String versionId = switch (versionForm) {
                case "version" -> original.getVersionId();
                case "null" -> "null";
                default -> null;
            };
            byte[] expected = "version".equals(versionForm) ? DATA : replacement;
            try (InputStream stream = service.openObjectStream(BUCKET, "stream", versionId)) {
                assertArrayEquals(expected, stream.readAllBytes());
            }
        } finally {
            factory.shutdownAll();
        }
    }

    private static Stream<Arguments> streamVariants() {
        return Stream.of("memory", "persistent", "hybrid", "wal")
                .flatMap(mode -> Stream.of("current", "null", "version")
                        .flatMap(version -> Stream.of(false, true)
                                .map(encrypted -> Arguments.of(mode, version, encrypted))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void defaultsAndObjectMetadataFollowTheStorageLifetime(String mode) throws IOException {
        EmulatorConfig config = storageConfig(mode);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(3_600_000L);

        StorageFactory first = new StorageFactory(config, access);
        String keyArn;
        try {
            keyArn = new KmsService(first, new RegionResolver("us-east-1", "000000000000"))
                    .createKey("persistent wrapping key", "us-east-1").getArn();
            S3Service service = service(first, config);
            service.createBucket(BUCKET, "us-east-1");
            service.putBucketEncryption(BUCKET, kmsDefault(keyArn));
            S3Object object = service.putObject(BUCKET, "before", DATA, "text/plain", Map.of());
            assertEquals("aws:kms", object.getServerSideEncryption());
            assertEquals(keyArn, object.getSseKmsKeyId());
            assertNotNull(object.getKmsEnvelope());
            if (!"memory".equals(mode)) {
                try (Stream<Path> files = Files.walk(directory.resolve("s3"))) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
                                .contains(new String(DATA, StandardCharsets.UTF_8)), "Plaintext body persisted: " + file);
                    }
                }
            }
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
            assertEquals(keyArn, stored.getSseKmsKeyId());
            assertEquals(keyArn, reopened.putObject(BUCKET, "after", DATA, "text/plain", Map.of()).getSseKmsKeyId());
            reopened.deleteBucketEncryption(BUCKET);
            assertEquals("AES256", reopened.putObject(BUCKET, "reset", DATA, "text/plain", Map.of())
                    .getServerSideEncryption());
            assertEquals(keyArn, reopened.getObject(BUCKET, "before").getSseKmsKeyId());
        } finally {
            second.shutdownAll();
        }
    }

    private EmulatorConfig storageConfig(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().mode()).thenReturn(mode);
        when(config.storage().services().s3().mode()).thenReturn(Optional.of(mode));
        when(config.storage().wal().compactionIntervalMs()).thenReturn(3_600_000L);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        return config;
    }

    private static String kmsDefault(String keyArn) {
        return """
                <ServerSideEncryptionConfiguration><Rule><ApplyServerSideEncryptionByDefault>
                    <SSEAlgorithm>aws:kms</SSEAlgorithm>
                    <KMSMasterKeyID>%s</KMSMasterKeyID>
                </ApplyServerSideEncryptionByDefault></Rule></ServerSideEncryptionConfiguration>
                """.formatted(keyArn);
    }

    private static S3Service service(StorageFactory factory, EmulatorConfig config) {
        RegionResolver regions = new RegionResolver("us-east-1", "000000000000");
        S3KmsEncryption encryption = new S3KmsEncryption(new KmsService(factory, regions),
                mock(IamEnforcementFilter.class), new RequestContext(), regions, mock(IamService.class));
        return new S3Service(factory, config, null, null, null, null, null,
                regions, new ObjectMapper(), null, encryption);
    }
}

package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3KmsLegacyObjectTest {
    @TempDir
    Path directory;

    @Test
    void oldMetadataOnlyObjectIsRefusedWithoutRewritingItsBytes() throws Exception {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objects = new InMemoryStorage<>();
        S3Service service = new S3Service(buckets, objects, directory, false);
        service.createBucket("legacy", "us-east-1");
        byte[] original = "retained failed encryption evidence".getBytes(StandardCharsets.UTF_8);
        service.putObject("legacy", "object", original, "text/plain", Map.of());
        S3Object record = objects.scan(ignored -> true).getFirst();
        record.setServerSideEncryption("aws:kms");
        record.setSseKmsKeyId("unavailable-legacy-key");
        Map<Path, byte[]> before = new HashMap<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                before.put(file, Files.readAllBytes(file));
            }
        }
        AwsException failure = assertThrows(AwsException.class, () -> service.getObject("legacy", "object"));
        assertEquals("InvalidObjectState", failure.getErrorCode());
        assertThrows(AwsException.class, () -> service.copyObject("legacy", "object", "legacy", "copy"));
        try (Stream<Path> files = Files.walk(directory)) {
            assertEquals(before.keySet(), new HashSet<>(files.filter(Files::isRegularFile).toList()));
        }
        for (Map.Entry<Path, byte[]> entry : before.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
        }
    }
}

package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class S3ObjectKeyNamespaceTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletingEveryListedVersionLeavesAnEmptyBucket(boolean collide) {
        S3Service service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), directory, true);
        service.createBucket("delimiter-bucket", "us-east-1");
        service.putBucketVersioning("delimiter-bucket", "Enabled");
        service.putObject("delimiter-bucket", "k", "body".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        String marker = service.deleteObject("delimiter-bucket", "k", null).getVersionId();
        service.putBucketVersioning("delimiter-bucket", "Suspended");
        String otherKey = collide ? "k#v#" + marker : "another-key";
        service.putObject("delimiter-bucket", otherKey, "other".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        for (int attempt = 0; attempt < 2; attempt++) {
            List<S3Object> versions = service.listObjectVersions("delimiter-bucket", "", 100, null).versions();
            for (S3Object version : versions) {
                service.deleteObject("delimiter-bucket", version.getKey(),
                        version.getVersionId() == null ? "null" : version.getVersionId());
            }
        }
        assertAll(
                () -> assertEquals(List.of(),
                        service.listObjectVersions("delimiter-bucket", "", 100, null).versions().stream()
                                .map(version -> version.getKey() + ":" + version.getVersionId()).toList()),
                () -> assertDoesNotThrow(() -> service.deleteBucket("delimiter-bucket")));
    }
}

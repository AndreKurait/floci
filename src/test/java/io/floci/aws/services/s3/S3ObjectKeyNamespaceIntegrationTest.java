package io.floci.aws.services.s3;

import io.floci.aws.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class S3ObjectKeyNamespaceIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"notes#v#1.txt", "dir/notes#v#part", "plain.txt", "notes#V#1.txt"})
    void storedKeyRemainsVisibleInListing(String key) {
        String bucket = "delimiter-" + UUID.randomUUID();
        try (S3Client s3 = S3TestClients.pathStyle()) {
            s3.createBucket(request -> request.bucket(bucket));
            try {
                s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString("body"));
                assertEquals("body", s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String());
                assertEquals(List.of(key), s3.listObjectsV2(request -> request.bucket(bucket)).contents().stream()
                        .map(S3Object::key).toList());
            } finally {
                s3.deleteObject(request -> request.bucket(bucket).key(key));
                s3.deleteBucket(request -> request.bucket(bucket));
                assertFalse(s3.listBuckets().buckets().stream().anyMatch(value -> value.name().equals(bucket)));
            }
        }
    }
}

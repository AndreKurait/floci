package io.floci.aws.services.s3;

import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.s3.model.PutObjectOptions;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class S3StagedEncryptionSelectionTest {
    @TempDir
    Path directory;

    @Test
    void stagingDefaultsDoesNotChangeReusableRequestOptions() {
        S3Service service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), directory, false);
        service.createBucket("first", "us-east-1");
        service.createBucket("second", "us-east-1");
        PutObjectOptions options = new PutObjectOptions().withCacheControl("private")
                .withTagging(Map.of("owner", "request"));
        byte[] data = "reusable options".getBytes(StandardCharsets.UTF_8);

        for (String bucket : new String[]{"first", "second"}) {
            S3Object object = service.putObject(bucket, "object", new ByteArrayInputStream(data),
                    UploadChecksums.NONE, "text/plain", Map.of(), options);
            assertNull(options.getServerSideEncryption());
            assertNull(options.getSseKmsKeyId());
            assertEquals("AES256", object.getServerSideEncryption());
            assertEquals("private", object.getCacheControl());
            assertEquals(Map.of("owner", "request"), object.getTags());
            assertArrayEquals(data, service.getObject(bucket, "object").getData());
        }
    }

    @Test
    void bucketDefaultChangesDuringStagingDoNotChangeTheSelectedEncryption() {
        S3Service service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), directory, false);
        service.createBucket("changing", "us-east-1");
        byte[] data = "selection belongs to this upload".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream body = new ByteArrayInputStream(data) {
            private boolean changed;

            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                if (!changed) {
                    changed = true;
                    service.putBucketEncryption("changing", """
                            <ServerSideEncryptionConfiguration>
                              <Rule><ApplyServerSideEncryptionByDefault>
                                <SSEAlgorithm>aws:kms</SSEAlgorithm>
                                <KMSMasterKeyID>new-default-key</KMSMasterKeyID>
                              </ApplyServerSideEncryptionByDefault></Rule>
                            </ServerSideEncryptionConfiguration>
                            """);
                }
                return super.read(bytes, offset, length);
            }
        };

        S3Object object = service.putObject("changing", "object", body, UploadChecksums.NONE,
                "text/plain", Map.of(), new PutObjectOptions());
        assertEquals("AES256", object.getServerSideEncryption());
        assertNull(object.getSseKmsKeyId());
        assertArrayEquals(data, service.getObject("changing", "object").getData());
    }
}

package io.floci.aws.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.s3.model.CopyObjectOptions;
import io.floci.aws.services.s3.model.MultipartUpload;
import io.floci.aws.services.s3.model.PutObjectOptions;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An object written without encryption headers is encrypted with its bucket's default encryption:
 * the configured {@code ApplyServerSideEncryptionByDefault}, or SSE-S3 ({@code AES256}) for a bucket
 * with none. An explicit header and an SSE-C key keep taking precedence.
 */
class S3DefaultEncryptionServiceTest {

    private static final String BUCKET = "default-encryption-bucket";
    private static final String SOURCE = "copy-source-bucket";
    private String kmsKeyArn;
    private String otherKmsKeyArn;
    private String defaultKmsKeyArn;
    private static final byte[] DATA = "data".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private InMemoryStorage<String, S3Object> objectStore;
    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        objectStore = new InMemoryStorage<>();
        InMemoryStorageFactory factory = new InMemoryStorageFactory("000000000000")
                .withStore("s3-objects.json", objectStore);
        RegionResolver regions = new RegionResolver("us-east-1", "000000000000");
        KmsService kms = new KmsService(factory, regions);
        kmsKeyArn = kms.createKey("default bucket key", "us-east-1").getArn();
        otherKmsKeyArn = kms.createKey("explicit object key", "us-east-1").getArn();
        defaultKmsKeyArn = kms.describeKey("alias/aws/s3", "us-east-1").getArn();
        S3KmsEncryption encryption = new S3KmsEncryption(kms, mock(IamEnforcementFilter.class),
                new RequestContext(), regions, mock(IamService.class));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());
        when(config.storage().mode()).thenReturn("persistent");
        when(config.storage().services().s3().mode()).thenReturn(Optional.of("persistent"));
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        s3Service = new S3Service(factory, config, null, null, null, null, null,
                regions, new ObjectMapper(), null, Clock.systemUTC(), encryption);
        s3Service.createBucket(BUCKET, "us-east-1");
        s3Service.createBucket(SOURCE, "us-east-1");
    }

    @Test
    void aBucketWithoutConfigurationEncryptsWithSseS3() {
        S3Object put = put("plain.txt", new PutObjectOptions());

        assertEquals("AES256", put.getServerSideEncryption());
        assertNull(put.getSseKmsKeyId());
        assertEquals("AES256", s3Service.getObject(BUCKET, "plain.txt").getServerSideEncryption());
    }

    @Test
    void aNewBucketReportsAndEnforcesSseCustomerBlockingByDefault() throws Exception {
        assertTrue(s3Service.getBucketEncryption(BUCKET).contains(
                "<BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType></BlockedEncryptionTypes>"));

        AwsException denied = assertThrows(AwsException.class,
                () -> put("default-blocked.txt", customerKey(new PutObjectOptions())));

        assertEquals("AccessDenied", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
    }

    @Test
    void aPlainEncryptionRulePreservesSseCustomerBlocking() throws Exception {
        s3Service.putBucketEncryption(BUCKET, configuration("AES256", null));

        assertTrue(s3Service.getBucketEncryption(BUCKET).contains(
                "<BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType></BlockedEncryptionTypes>"));
        AwsException denied = assertThrows(AwsException.class,
                () -> put("still-blocked.txt", customerKey(new PutObjectOptions())));
        assertEquals("AccessDenied", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
    }

    @Test
    void configuredEncryptionReadbackPreservesOtherRuleFields() {
        s3Service.putBucketEncryption(BUCKET, "<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Rule><ApplyServerSideEncryptionByDefault><SSEAlgorithm>aws:kms</SSEAlgorithm>"
                + "<KMSMasterKeyID>alias/R&amp;D</KMSMasterKeyID></ApplyServerSideEncryptionByDefault>"
                + "<BucketKeyEnabled>true</BucketKeyEnabled></Rule></ServerSideEncryptionConfiguration>");

        String readback = s3Service.getBucketEncryption(BUCKET);
        assertTrue(readback.startsWith("<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"));
        assertTrue(readback.contains("<SSEAlgorithm>aws:kms</SSEAlgorithm>"));
        assertTrue(readback.contains("<KMSMasterKeyID>alias/R&amp;D</KMSMasterKeyID>"));
        assertTrue(readback.contains("<BucketKeyEnabled>true</BucketKeyEnabled>"));
        assertTrue(readback.contains("<BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType></BlockedEncryptionTypes>"));
    }

    @Test
    void bahrainAndUaeBucketsDoNotBlockSseCustomerEncryptionByDefault() throws Exception {
        for (String region : List.of("me-south-1", "me-central-1")) {
            String bucket = "sse-c-" + region;
            s3Service.createBucket(bucket, region);

            assertFalse(s3Service.getBucketEncryption(bucket).contains("BlockedEncryptionTypes"));
            S3Object written = s3Service.putObject(bucket, "allowed.txt", DATA, "text/plain", null,
                    customerKey(new PutObjectOptions()));
            assertEquals("AES256", written.getSseCustomerAlgorithm());

            s3Service.putBucketEncryption(bucket, blockedEncryption("SSE-C"));
            AwsException denied = assertThrows(AwsException.class,
                    () -> s3Service.putObject(bucket, "blocked.txt", DATA, "text/plain", null,
                            customerKey(new PutObjectOptions())));
            assertEquals("AccessDenied", denied.getErrorCode());
        }
    }

    @Test
    void aKmsDefaultIsAppliedWithItsKey() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn));

        S3Object put = put("kms.txt", new PutObjectOptions());

        assertEquals("aws:kms", put.getServerSideEncryption());
        assertEquals(kmsKeyArn, put.getSseKmsKeyId());
        S3Object stored = s3Service.getObject(BUCKET, "kms.txt");
        assertEquals("aws:kms", stored.getServerSideEncryption());
        assertEquals(kmsKeyArn, stored.getSseKmsKeyId());
        S3Object posted = s3Service.postObject(BUCKET, "posted.txt", DATA, "text/plain", null);
        assertEquals("aws:kms", posted.getServerSideEncryption());
        assertEquals(kmsKeyArn, posted.getSseKmsKeyId());
    }

    @Test
    void aKmsDefaultWithoutAKeyUsesTheManagedS3Key() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", null));

        S3Object put = put("kms-default-key.txt", new PutObjectOptions());

        assertEquals("aws:kms", put.getServerSideEncryption());
        assertEquals(defaultKmsKeyArn, put.getSseKmsKeyId());
        assertArrayEquals(DATA, s3Service.getObject(BUCKET, "kms-default-key.txt").getData());
    }

    @Test
    void aDualLayerKmsDefaultIsAppliedWithItsKey() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms:dsse", kmsKeyArn));

        S3Object put = put("dsse.txt", new PutObjectOptions());

        assertEquals("aws:kms:dsse", put.getServerSideEncryption());
        assertEquals(kmsKeyArn, put.getSseKmsKeyId());
    }

    @Test
    void anExplicitHeaderTakesPrecedenceOverTheDefault() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn));

        S3Object put = put("explicit.txt", new PutObjectOptions().withServerSideEncryption("aws:kms"));

        assertEquals("aws:kms", put.getServerSideEncryption());
        assertNotEquals(kmsKeyArn, put.getSseKmsKeyId());
    }

    @Test
    void anSseCustomerKeyUploadGetsNoDefault() throws Exception {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn, "NONE"));

        S3Object put = put("sse-c.txt", customerKey(new PutObjectOptions()));

        assertNull(put.getServerSideEncryption());
        assertNull(put.getSseKmsKeyId());
        assertEquals("AES256", put.getSseCustomerAlgorithm());
    }

    @Test
    void aBucketBlockingSseCustomerKeysRejectsPutObject() throws Exception {
        s3Service.putBucketEncryption(BUCKET, "<ServerSideEncryptionConfiguration>"
                + "<Rule><BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType>"
                + "</BlockedEncryptionTypes></Rule></ServerSideEncryptionConfiguration>");

        AwsException denied = assertThrows(AwsException.class,
                () -> put("blocked-sse-c.txt", customerKey(new PutObjectOptions())));

        assertEquals("AccessDenied", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
        assertEquals("AES256", put("allowed-sse-s3.txt", new PutObjectOptions()).getServerSideEncryption());
    }

    @Test
    void aBlockedStreamingPutFailsBeforeReadingItsBody() throws Exception {
        PutObjectOptions sseC = customerKey(new PutObjectOptions());
        InputStream unread = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("Blocked upload body must not be read");
            }
        };

        AwsException denied = assertThrows(AwsException.class,
                () -> s3Service.putObject(BUCKET, "streamed.txt", unread, UploadChecksums.NONE,
                        "text/plain", null, sseC));

        assertEquals("AccessDenied", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
    }

    @Test
    void aBucketBlockingSseCustomerKeysRejectsPostAndDestinationCopy() throws Exception {
        s3Service.putObject(SOURCE, "source.txt", DATA, "text/plain", null, new PutObjectOptions());
        s3Service.putBucketEncryption(BUCKET, blockedEncryption("SSE-C"));
        PutObjectOptions sseC = customerKey(new PutObjectOptions());

        AwsException post = assertThrows(AwsException.class,
                () -> s3Service.postObject(BUCKET, "posted.txt", DATA, "text/plain", null, sseC));
        AwsException copy = assertThrows(AwsException.class,
                () -> s3Service.copyObject(SOURCE, "source.txt", BUCKET, "copy.txt", null,
                        new CopyObjectOptions()
                                .withSseCustomerAlgorithm(sseC.getSseCustomerAlgorithm())
                                .withSseCustomerKey(sseC.getSseCustomerKey())
                                .withSseCustomerKeyMd5(sseC.getSseCustomerKeyMd5())));

        assertEquals("AccessDenied", post.getErrorCode());
        assertEquals(403, post.getHttpStatus());
        assertEquals("AccessDenied", copy.getErrorCode());
        assertEquals(403, copy.getHttpStatus());
        assertEquals("AES256", s3Service.copyObject(SOURCE, "source.txt", BUCKET, "allowed-copy.txt")
                .getServerSideEncryption());
    }

    @Test
    void aBucketBlockingSseCustomerKeysRejectsMultipartInitiation() throws Exception {
        s3Service.putBucketEncryption(BUCKET, blockedEncryption("SSE-C"));

        AwsException denied = assertThrows(AwsException.class,
                () -> s3Service.initiateMultipartUpload(BUCKET, "blocked.bin", "application/octet-stream",
                        Map.of(), null, customerKey(new PutObjectOptions())));

        assertEquals("AccessDenied", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
        MultipartUpload allowed = s3Service.initiateMultipartUpload(BUCKET, "allowed.bin",
                "application/octet-stream", Map.of(), null, new PutObjectOptions());
        assertEquals("AES256", allowed.effectiveServerSideEncryption());
    }

    @Test
    void existingSseCustomerObjectCanBeCopiedAsAReadableSourceAfterBlocking() throws Exception {
        PutObjectOptions sseC = customerKey(new PutObjectOptions());
        s3Service.putBucketEncryption(BUCKET, blockedEncryption("NONE"));
        put("existing.txt", sseC);
        s3Service.putBucketEncryption(BUCKET, blockedEncryption("SSE-C"));

        S3Object copy = s3Service.copyObject(BUCKET, "existing.txt", SOURCE, "copy.txt", null,
                new CopyObjectOptions()
                        .withCopySourceSseCustomerAlgorithm(sseC.getSseCustomerAlgorithm())
                        .withCopySourceSseCustomerKey(sseC.getSseCustomerKey())
                        .withCopySourceSseCustomerKeyMd5(sseC.getSseCustomerKeyMd5()));

        assertEquals("AES256", s3Service.getObject(BUCKET, "existing.txt").getSseCustomerAlgorithm());
        assertEquals("AES256", copy.getServerSideEncryption());
        assertNull(copy.getSseCustomerAlgorithm());
    }

    @Test
    void explicitNoneDoesNotBlockSseCustomerWrites() throws Exception {
        s3Service.putBucketEncryption(BUCKET, blockedEncryption("NONE"));

        S3Object written = put("allowed-sse-c.txt", customerKey(new PutObjectOptions()));

        assertEquals("AES256", written.getSseCustomerAlgorithm());
        assertNull(written.getServerSideEncryption());
    }

    @Test
    void objectsAlreadyStoredKeepTheirEncryption() {
        put("before.txt", new PutObjectOptions());
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn));
        put("after.txt", new PutObjectOptions());

        assertEquals("AES256", s3Service.getObject(BUCKET, "before.txt").getServerSideEncryption());
        assertEquals("aws:kms", s3Service.getObject(BUCKET, "after.txt").getServerSideEncryption());
    }

    @Test
    void aMultipartUploadCompletesIntoAnObjectWithTheDefault() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn));

        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "multipart.bin",
                "application/octet-stream", Map.of(), null, new PutObjectOptions());
        s3Service.uploadPart(BUCKET, "multipart.bin", upload.getUploadId(), 1, DATA);
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "multipart.bin", upload.getUploadId(),
                List.of(1), null, null);

        assertEquals("aws:kms", completed.getServerSideEncryption());
        assertEquals(kmsKeyArn, completed.getSseKmsKeyId());
        assertEquals("aws:kms", s3Service.getObject(BUCKET, "multipart.bin").getServerSideEncryption());
        assertArrayEquals(DATA, s3Service.getObject(BUCKET, "multipart.bin").getData());
    }

    @Test
    void anSseCustomerKeyMultipartUploadGetsNoDefault() throws Exception {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn, "NONE"));
        PutObjectOptions sseC = customerKey(new PutObjectOptions());
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "multipart-sse-c.bin",
                "application/octet-stream", Map.of(), null, sseC);
        s3Service.uploadPart(BUCKET, "multipart-sse-c.bin", upload.getUploadId(), 1, DATA,
                sseC.getSseCustomerAlgorithm(), sseC.getSseCustomerKey(), sseC.getSseCustomerKeyMd5());
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "multipart-sse-c.bin", upload.getUploadId(),
                List.of(1), null, null);

        assertNull(completed.getServerSideEncryption());
        assertNull(completed.getSseKmsKeyId());
        assertEquals("AES256", completed.getSseCustomerAlgorithm());
        assertNull(s3Service.getObject(BUCKET, "multipart-sse-c.bin").getServerSideEncryption());
        assertArrayEquals(DATA, s3Service.getObject(BUCKET, "multipart-sse-c.bin").getData());
    }

    @Test
    void aMultipartUploadTakesTheDefaultWhenItIsCreated() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn));
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "created.bin",
                "application/octet-stream", Map.of(), null, new PutObjectOptions());

        assertEquals("aws:kms", upload.effectiveServerSideEncryption());
        assertEquals(kmsKeyArn, upload.effectiveSseKmsKeyId());
        assertNull(upload.getServerSideEncryption());
        assertEquals(Map.of("s3:x-amz-server-side-encryption", List.of("aws:kms"),
                "s3:x-amz-server-side-encryption-aws-kms-key-id", List.of(kmsKeyArn)),
                s3Service.multipartEncryptionConditions(BUCKET, "created.bin", upload.getUploadId()));

        s3Service.putBucketEncryption(BUCKET, configuration("AES256", null));
        s3Service.uploadPart(BUCKET, "created.bin", upload.getUploadId(), 1, DATA);
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "created.bin", upload.getUploadId(),
                List.of(1), null, null);

        assertEquals("aws:kms", completed.getServerSideEncryption());
        assertEquals(kmsKeyArn, completed.getSseKmsKeyId());
    }

    @Test
    void aDualLayerKmsDefaultReachesAMultipartUploadWithItsKey() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms:dsse", kmsKeyArn));
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "dsse.bin",
                "application/octet-stream", Map.of(), null, new PutObjectOptions());
        s3Service.uploadPart(BUCKET, "dsse.bin", upload.getUploadId(), 1, DATA);
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "dsse.bin", upload.getUploadId(),
                List.of(1), null, null);

        assertEquals("aws:kms:dsse", upload.effectiveServerSideEncryption());
        assertEquals(kmsKeyArn, upload.effectiveSseKmsKeyId());
        assertEquals("aws:kms:dsse", completed.getServerSideEncryption());
        assertEquals(kmsKeyArn, completed.getSseKmsKeyId());
    }

    @Test
    void anExplicitDualLayerHeaderKeepsItsKeyId() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn));
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "explicit-dsse.bin",
                "application/octet-stream", Map.of(), null,
                new PutObjectOptions().withServerSideEncryption("aws:kms:dsse").withSseKmsKeyId(otherKmsKeyArn));
        s3Service.uploadPart(BUCKET, "explicit-dsse.bin", upload.getUploadId(), 1, DATA);
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "explicit-dsse.bin", upload.getUploadId(),
                List.of(1), null, null);

        assertEquals("aws:kms:dsse", upload.effectiveServerSideEncryption());
        assertEquals(otherKmsKeyArn, upload.effectiveSseKmsKeyId());
        assertEquals("aws:kms:dsse", completed.getServerSideEncryption());
        assertEquals(otherKmsKeyArn, completed.getSseKmsKeyId());
    }

    @Test
    void anUploadThatSendsItsOwnEncryptionRecordsNoDefault() throws Exception {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn, "NONE"));

        MultipartUpload explicit = s3Service.initiateMultipartUpload(BUCKET, "explicit.bin",
                "application/octet-stream", Map.of(), null, new PutObjectOptions().withServerSideEncryption("AES256"));
        MultipartUpload sseC = s3Service.initiateMultipartUpload(BUCKET, "sse-c.bin",
                "application/octet-stream", Map.of(), null, customerKey(new PutObjectOptions()));

        assertEquals("AES256", explicit.effectiveServerSideEncryption());
        assertNull(explicit.effectiveSseKmsKeyId());
        assertNull(explicit.getDefaultServerSideEncryption());
        assertNull(sseC.effectiveServerSideEncryption());
        assertNull(sseC.effectiveSseKmsKeyId());
        assertNull(sseC.getDefaultServerSideEncryption());
    }

    @Test
    void malformedConfigurationIsRejectedWithoutReplacingTheStoredDefault() {
        String valid = configuration("aws:kms", kmsKeyArn, "NONE");
        s3Service.putBucketEncryption(BUCKET, valid);
        for (String invalid : List.of("<ServerSideEncryptionConfiguration/>",
                configuration("aws:unknown", null), "not xml", valid + "<Broken>")) {
            assertThrows(AwsException.class, () -> s3Service.putBucketEncryption(BUCKET, invalid));
            assertEquals(valid, s3Service.getBucketEncryption(BUCKET));
        }
        S3Object object = put("retained-default.txt", new PutObjectOptions());
        assertEquals(kmsKeyArn, object.getSseKmsKeyId());
        assertArrayEquals(DATA, s3Service.getObject(BUCKET, "retained-default.txt").getData());
    }

    @Test
    void aDefaultOutsideTheRuleIsIgnored() {
        s3Service.putBucketEncryption(BUCKET, "<ServerSideEncryptionConfiguration>"
                + "<Elsewhere><SSEAlgorithm>AES256</SSEAlgorithm><KMSMasterKeyID>other-key</KMSMasterKeyID></Elsewhere>"
                + "<Rule><ApplyServerSideEncryptionByDefault><SSEAlgorithm>aws:kms</SSEAlgorithm>"
                + "<KMSMasterKeyID>" + kmsKeyArn + "</KMSMasterKeyID></ApplyServerSideEncryptionByDefault></Rule>"
                + "</ServerSideEncryptionConfiguration>");

        S3Object put = put("rule.txt", new PutObjectOptions());

        assertEquals("aws:kms", put.getServerSideEncryption());
        assertEquals(kmsKeyArn, put.getSseKmsKeyId());
    }

    @Test
    void aCopyOfASourceWithNoStoredEncryptionTakesTheDestinationBucketsDefault() throws Exception {
        s3Service.putObject(SOURCE, "old.txt", DATA, "text/plain", null, new PutObjectOptions());
        objectStore.scan(key -> true).forEach(object -> object.setServerSideEncryption(null));
        PutObjectOptions sseC = customerKey(new PutObjectOptions());
        s3Service.putBucketEncryption(SOURCE, blockedEncryption("NONE"));
        s3Service.putObject(SOURCE, "sse-c.txt", DATA, "text/plain", null, sseC);
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn));

        S3Object fromOld = s3Service.copyObject(SOURCE, "old.txt", BUCKET, "old-copy.txt");
        S3Object fromSseC = s3Service.copyObject(SOURCE, "sse-c.txt", BUCKET, "sse-c-copy.txt", null,
                new CopyObjectOptions()
                        .withCopySourceSseCustomerAlgorithm(sseC.getSseCustomerAlgorithm())
                        .withCopySourceSseCustomerKey(sseC.getSseCustomerKey())
                        .withCopySourceSseCustomerKeyMd5(sseC.getSseCustomerKeyMd5()));

        for (S3Object copy : List.of(fromOld, fromSseC)) {
            assertEquals("aws:kms", copy.getServerSideEncryption());
            assertEquals(kmsKeyArn, copy.getSseKmsKeyId());
            assertNull(copy.getSseCustomerAlgorithm());
        }
    }

    @Test
    void aCopyTakesTheDestinationBucketsDefaultNotTheSourcesEncryption() {
        s3Service.putBucketEncryption(SOURCE, configuration("aws:kms", kmsKeyArn));
        s3Service.putObject(SOURCE, "kms.txt", DATA, "text/plain", null, new PutObjectOptions());
        put("sse-s3.txt", new PutObjectOptions());

        S3Object intoPlainBucket = s3Service.copyObject(SOURCE, "kms.txt", BUCKET, "plain-copy.txt");

        assertEquals("AES256", intoPlainBucket.getServerSideEncryption());
        assertNull(intoPlainBucket.getSseKmsKeyId());

        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", otherKmsKeyArn));
        S3Object fromKmsSource = s3Service.copyObject(SOURCE, "kms.txt", BUCKET, "kms-copy.txt");
        S3Object fromSseS3Source = s3Service.copyObject(BUCKET, "sse-s3.txt", BUCKET, "sse-s3-copy.txt");

        for (S3Object copy : List.of(fromKmsSource, fromSseS3Source)) {
            assertEquals("aws:kms", copy.getServerSideEncryption());
            assertEquals(otherKmsKeyArn, copy.getSseKmsKeyId());
        }
        assertEquals(otherKmsKeyArn, s3Service.getObject(BUCKET, "kms-copy.txt").getSseKmsKeyId());
    }

    @Test
    void aCopyWithAnExplicitHeaderKeepsIt() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn));
        put("source.txt", new PutObjectOptions());

        S3Object aes = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "aes-copy.txt", null,
                new CopyObjectOptions().withServerSideEncryption("AES256"));
        S3Object kms = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "kms-copy.txt", null,
                new CopyObjectOptions().withServerSideEncryption("aws:kms").withSseKmsKeyId(otherKmsKeyArn));
        S3Object kmsWithoutKey = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "kms-no-key-copy.txt", null,
                new CopyObjectOptions().withServerSideEncryption("aws:kms"));

        assertEquals("AES256", aes.getServerSideEncryption());
        assertNull(aes.getSseKmsKeyId());
        assertEquals("aws:kms", kms.getServerSideEncryption());
        assertEquals(otherKmsKeyArn, kms.getSseKmsKeyId());
        assertEquals("aws:kms", kmsWithoutKey.getServerSideEncryption());
        assertNotEquals(kmsKeyArn, kmsWithoutKey.getSseKmsKeyId());
        S3Object dsse = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "dsse-copy.txt", null,
                new CopyObjectOptions().withServerSideEncryption("aws:kms:dsse").withSseKmsKeyId(otherKmsKeyArn));
        assertEquals(otherKmsKeyArn, dsse.getSseKmsKeyId());
        for (String key : List.of("aes-copy.txt", "kms-copy.txt", "kms-no-key-copy.txt", "dsse-copy.txt")) {
            assertArrayEquals(DATA, s3Service.getObject(BUCKET, key).getData(), key);
        }
    }

    @Test
    void aCopyEncryptedWithACustomerKeyGetsNoDefault() throws Exception {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", kmsKeyArn, "NONE"));
        put("source.txt", new PutObjectOptions());
        PutObjectOptions sseC = customerKey(new PutObjectOptions());

        S3Object copy = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "sse-c-copy.txt", null,
                new CopyObjectOptions()
                        .withSseCustomerAlgorithm(sseC.getSseCustomerAlgorithm())
                        .withSseCustomerKey(sseC.getSseCustomerKey())
                        .withSseCustomerKeyMd5(sseC.getSseCustomerKeyMd5()));

        assertNull(copy.getServerSideEncryption());
        assertEquals("AES256", copy.getSseCustomerAlgorithm());
        assertArrayEquals(DATA, s3Service.getObject(BUCKET, "sse-c-copy.txt").getData());
    }

    private S3Object put(String key, PutObjectOptions options) {
        return s3Service.putObject(BUCKET, key, DATA, "text/plain", null, options);
    }

    private static String configuration(String algorithm, String kmsKeyId) {
        return configuration(algorithm, kmsKeyId, null);
    }

    private static String configuration(String algorithm, String kmsKeyId, String blockedType) {
        return "<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule>"
                + "<ApplyServerSideEncryptionByDefault><SSEAlgorithm>" + algorithm + "</SSEAlgorithm>"
                + (kmsKeyId == null ? "" : "<KMSMasterKeyID>" + kmsKeyId + "</KMSMasterKeyID>")
                + "</ApplyServerSideEncryptionByDefault>"
                + (blockedType == null ? "" : "<BlockedEncryptionTypes><EncryptionType>" + blockedType
                        + "</EncryptionType></BlockedEncryptionTypes>")
                + "</Rule></ServerSideEncryptionConfiguration>";
    }

    private static String blockedEncryption(String type) {
        return "<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule>"
                + "<ApplyServerSideEncryptionByDefault><SSEAlgorithm>AES256</SSEAlgorithm>"
                + "</ApplyServerSideEncryptionByDefault><BlockedEncryptionTypes><EncryptionType>" + type
                + "</EncryptionType></BlockedEncryptionTypes></Rule></ServerSideEncryptionConfiguration>";
    }

    private static PutObjectOptions customerKey(PutObjectOptions options) throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        byte[] md5 = MessageDigest.getInstance("MD5").digest(key);
        return options.withSseCustomerAlgorithm("AES256")
                .withSseCustomerKey(Base64.getEncoder().encodeToString(key))
                .withSseCustomerKeyMd5(Base64.getEncoder().encodeToString(md5));
    }
}

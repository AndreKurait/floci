package io.floci.aws.services.s3;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.net.URI;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class S3DefaultEncryptionSdkIntegrationTest {

    private String firstKey;
    private String secondKey;

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private S3Client s3;
    private String bucket;

    @BeforeEach
    void createOwnedBucket() {
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "test", Region.US_EAST_1, endpoint);
        cleanup.register(s3::close);
        KmsClient kms = AwsTestClients.create(KmsClient.builder(), "test", Region.US_EAST_1, endpoint);
        cleanup.register(kms::close);
        firstKey = kms.createKey().keyMetadata().arn();
        cleanup.register(() -> kms.scheduleKeyDeletion(request -> request.keyId(firstKey).pendingWindowInDays(7)));
        secondKey = kms.createKey().keyMetadata().arn();
        cleanup.register(() -> kms.scheduleKeyDeletion(request -> request.keyId(secondKey).pendingWindowInDays(7)));
        bucket = "default-encryption-" + UUID.randomUUID();
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> {
            ListObjectVersionsResponse versions = s3.listObjectVersions(request -> request.bucket(bucket));
            for (ObjectVersion version : versions.versions()) {
                s3.deleteObject(request -> request.bucket(bucket).key(version.key()).versionId(version.versionId()));
            }
            for (DeleteMarkerEntry marker : versions.deleteMarkers()) {
                s3.deleteObject(request -> request.bucket(bucket).key(marker.key()).versionId(marker.versionId()));
            }
            s3.listMultipartUploads(request -> request.bucket(bucket)).uploads().forEach(upload ->
                    s3.abortMultipartUpload(request -> request.bucket(bucket).key(upload.key()).uploadId(upload.uploadId())));
            for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
                s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
            }
            s3.deleteBucket(request -> request.bucket(bucket));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storedObjectsReportTheDefaultSseS3Algorithm(boolean configured) {
        if (configured) {
            s3.putBucketEncryption(request -> request.bucket(bucket)
                    .serverSideEncryptionConfiguration(configuration -> configuration.rules(rule -> rule
                            .applyServerSideEncryptionByDefault(defaults ->
                                    defaults.sseAlgorithm(ServerSideEncryption.AES256)))));
        }
        s3.putObject(request -> request.bucket(bucket).key("report"),
                RequestBody.fromString("inventory data"));
        assertEquals("inventory data", s3.getObjectAsBytes(
                request -> request.bucket(bucket).key("report")).asUtf8String());
        assertEquals(ServerSideEncryption.AES256,
                s3.headObject(request -> request.bucket(bucket).key("report")).serverSideEncryption());
    }

    @Test
    void configuredKmsKeyIsCapturedInObjectMetadata() {
        defaults(ServerSideEncryption.AWS_KMS, firstKey);
        assertEquals(firstKey, s3.putObject(request -> request.bucket(bucket).key("report"),
                RequestBody.fromString("inventory data")).ssekmsKeyId());
        HeadObjectResponse object = head("report");
        assertEquals(ServerSideEncryption.AWS_KMS, object.serverSideEncryption());
        assertEquals(firstKey, object.ssekmsKeyId());
        assertEquals(firstKey, s3.getObjectAsBytes(
                request -> request.bucket(bucket).key("report")).response().ssekmsKeyId());
    }

    @Test
    void explicitObjectEncryptionOverridesTheBucketDefault() {
        defaults(ServerSideEncryption.AWS_KMS, firstKey);
        s3.putObject(request -> request.bucket(bucket).key("s3").serverSideEncryption(ServerSideEncryption.AES256),
                RequestBody.fromString("S3 encryption"));
        assertEquals(ServerSideEncryption.AES256, head("s3").serverSideEncryption());
        assertNull(head("s3").ssekmsKeyId());
        s3.putObject(request -> request.bucket(bucket).key("kms").serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(secondKey), RequestBody.fromString("explicit key"));
        assertEquals(secondKey, head("kms").ssekmsKeyId());
    }

    @Test
    void copiesUseTheDestinationDefaultInsteadOfTheSourceEncryption() {
        s3.putObject(request -> request.bucket(bucket).key("source").serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(firstKey), RequestBody.fromString("source data"));
        s3.copyObject(request -> request.bucket(bucket).key("s3-copy").copySource(bucket + "/source"));
        assertEquals(ServerSideEncryption.AES256, head("s3-copy").serverSideEncryption());
        assertNull(head("s3-copy").ssekmsKeyId());
        defaults(ServerSideEncryption.AWS_KMS, secondKey);
        s3.copyObject(request -> request.bucket(bucket).key("kms-copy").copySource(bucket + "/source"));
        assertEquals(secondKey, head("kms-copy").ssekmsKeyId());
        assertEquals(firstKey, head("source").ssekmsKeyId());
        s3.copyObject(request -> request.bucket(bucket).key("explicit-copy").copySource(bucket + "/source")
                .serverSideEncryption(ServerSideEncryption.AES256));
        assertEquals(ServerSideEncryption.AES256, head("explicit-copy").serverSideEncryption());
        assertNull(head("explicit-copy").ssekmsKeyId());
    }

    @Test
    void multipartCompletionKeepsTheDefaultSelectedAtInitiation() {
        defaults(ServerSideEncryption.AWS_KMS, firstKey);
        String upload = s3.createMultipartUpload(request -> request.bucket(bucket).key("multipart")
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)).uploadId();
        defaults(ServerSideEncryption.AES256, null);
        UploadPartResponse part = s3.uploadPart(request -> request.bucket(bucket).key("multipart")
                .uploadId(upload).partNumber(1), RequestBody.fromString("multipart data"));
        s3.completeMultipartUpload(request -> request.bucket(bucket).key("multipart").uploadId(upload)
                .multipartUpload(parts -> parts.parts(completed -> completed.partNumber(1)
                        .eTag(part.eTag()).checksumSHA256(part.checksumSHA256()))));
        assertEquals(ServerSideEncryption.AWS_KMS, head("multipart").serverSideEncryption());
        assertEquals(firstKey, head("multipart").ssekmsKeyId());
        assertEquals("multipart data", s3.getObjectAsBytes(
                request -> request.bucket(bucket).key("multipart")).asUtf8String());
    }

    @Test
    void changingOrRemovingDefaultsDoesNotRewriteExistingVersions() {
        s3.putBucketVersioning(request -> request.bucket(bucket)
                .versioningConfiguration(versioning -> versioning.status(BucketVersioningStatus.ENABLED)));
        String first = s3.putObject(request -> request.bucket(bucket).key("versioned"),
                RequestBody.fromString("first version")).versionId();
        defaults(ServerSideEncryption.AWS_KMS, firstKey);
        String second = s3.putObject(request -> request.bucket(bucket).key("versioned"),
                RequestBody.fromString("second version")).versionId();
        s3.deleteBucketEncryption(request -> request.bucket(bucket));
        assertEquals(ServerSideEncryption.AES256, s3.headObject(
                request -> request.bucket(bucket).key("versioned").versionId(first)).serverSideEncryption());
        assertEquals(firstKey, head("versioned").ssekmsKeyId());
        s3.putObject(request -> request.bucket(bucket).key("versioned"), RequestBody.fromString("third version"));
        assertEquals(ServerSideEncryption.AES256, head("versioned").serverSideEncryption());
        assertEquals(firstKey, s3.headObject(
                request -> request.bucket(bucket).key("versioned").versionId(second)).ssekmsKeyId());
    }

    @Test
    void customerProvidedKeysDoNotInheritTheBucketEncryption() throws Exception {
        s3.putBucketEncryption(request -> request.bucket(bucket)
                .serverSideEncryptionConfiguration(configuration -> configuration.rules(rule -> rule
                        .applyServerSideEncryptionByDefault(defaults -> defaults
                                .sseAlgorithm(ServerSideEncryption.AWS_KMS).kmsMasterKeyID(firstKey))
                        .blockedEncryptionTypes(blocked -> blocked.encryptionTypeWithStrings("NONE")))));
        byte[] bytes = new byte[32];
        String key = Base64.getEncoder().encodeToString(bytes);
        String md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes));
        s3.putObject(request -> request.bucket(bucket).key("customer").sseCustomerAlgorithm("AES256")
                .sseCustomerKey(key).sseCustomerKeyMD5(md5), RequestBody.fromString("customer encrypted"));
        HeadObjectResponse object = s3.headObject(request -> request.bucket(bucket).key("customer")
                .sseCustomerAlgorithm("AES256").sseCustomerKey(key).sseCustomerKeyMD5(md5));
        assertNull(object.serverSideEncryption());
        assertNull(object.ssekmsKeyId());
        assertEquals("AES256", object.sseCustomerAlgorithm());
        assertEquals(md5, object.sseCustomerKeyMD5());
    }

    @Test
    void malformedConfigurationDoesNotReplaceTheStoredDefault() {
        defaults(ServerSideEncryption.AWS_KMS, firstKey);
        given().body("""
                <ServerSideEncryptionConfiguration>
                    <Rule><ApplyServerSideEncryptionByDefault/></Rule>
                </ServerSideEncryptionConfiguration>
                """)
                .put("/" + bucket + "?encryption").then().statusCode(400);
        s3.putObject(request -> request.bucket(bucket).key("after-rejection"), RequestBody.fromString("unchanged"));
        assertEquals(firstKey, head("after-rejection").ssekmsKeyId());
    }

    @Test
    void configuredEncryptionBlockingIsEnforced() {
        defaults(ServerSideEncryption.AWS_KMS, firstKey);
        given().body("""
                <ServerSideEncryptionConfiguration>
                    <Rule><BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType></BlockedEncryptionTypes></Rule>
                </ServerSideEncryptionConfiguration>
                """)
                .put("/" + bucket + "?encryption").then().statusCode(200);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        S3Exception denied = assertThrows(S3Exception.class, () -> s3.putObject(request -> request.bucket(bucket)
                .key("blocked").sseCustomerAlgorithm("AES256").sseCustomerKey(key)
                .sseCustomerKeyMD5("cLyPS3KoaSFGi/joRB3OUQ=="), RequestBody.fromString("blocked")));
        assertEquals(403, denied.statusCode());
        assertEquals(404, assertThrows(S3Exception.class, () -> head("blocked")).statusCode());
        s3.putObject(request -> request.bucket(bucket).key("after-blocking"), RequestBody.fromString("allowed"));
        assertEquals(ServerSideEncryption.AES256, head("after-blocking").serverSideEncryption());
    }

    private void defaults(ServerSideEncryption algorithm, String kmsKeyId) {
        s3.putBucketEncryption(request -> request.bucket(bucket)
                .serverSideEncryptionConfiguration(configuration -> configuration.rules(rule -> rule
                        .applyServerSideEncryptionByDefault(defaults ->
                                defaults.sseAlgorithm(algorithm).kmsMasterKeyID(kmsKeyId)))));
    }

    private HeadObjectResponse head(String key) {
        return s3.headObject(request -> request.bucket(bucket).key(key));
    }
}

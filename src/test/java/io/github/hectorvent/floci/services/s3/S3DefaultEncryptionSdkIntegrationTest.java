package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
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

@QuarkusTest
class S3DefaultEncryptionSdkIntegrationTest {

    private static final String FIRST_KEY = "arn:aws:kms:us-east-1:000000000000:key/first";
    private static final String SECOND_KEY = "arn:aws:kms:us-east-1:000000000000:key/second";

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
        defaults(ServerSideEncryption.AWS_KMS, FIRST_KEY);
        assertEquals(FIRST_KEY, s3.putObject(request -> request.bucket(bucket).key("report"),
                RequestBody.fromString("inventory data")).ssekmsKeyId());
        HeadObjectResponse object = head("report");
        assertEquals(ServerSideEncryption.AWS_KMS, object.serverSideEncryption());
        assertEquals(FIRST_KEY, object.ssekmsKeyId());
        assertEquals(FIRST_KEY, s3.getObjectAsBytes(
                request -> request.bucket(bucket).key("report")).response().ssekmsKeyId());
    }

    @Test
    void explicitObjectEncryptionOverridesTheBucketDefault() {
        defaults(ServerSideEncryption.AWS_KMS, FIRST_KEY);
        s3.putObject(request -> request.bucket(bucket).key("s3").serverSideEncryption(ServerSideEncryption.AES256),
                RequestBody.fromString("S3 encryption"));
        assertEquals(ServerSideEncryption.AES256, head("s3").serverSideEncryption());
        assertNull(head("s3").ssekmsKeyId());
        s3.putObject(request -> request.bucket(bucket).key("kms").serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(SECOND_KEY), RequestBody.fromString("explicit key"));
        assertEquals(SECOND_KEY, head("kms").ssekmsKeyId());
    }

    @Test
    void copiesUseTheDestinationDefaultInsteadOfTheSourceEncryption() {
        s3.putObject(request -> request.bucket(bucket).key("source").serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(FIRST_KEY), RequestBody.fromString("source data"));
        s3.copyObject(request -> request.bucket(bucket).key("s3-copy").copySource(bucket + "/source"));
        assertEquals(ServerSideEncryption.AES256, head("s3-copy").serverSideEncryption());
        assertNull(head("s3-copy").ssekmsKeyId());
        defaults(ServerSideEncryption.AWS_KMS, SECOND_KEY);
        s3.copyObject(request -> request.bucket(bucket).key("kms-copy").copySource(bucket + "/source"));
        assertEquals(SECOND_KEY, head("kms-copy").ssekmsKeyId());
        assertEquals(FIRST_KEY, head("source").ssekmsKeyId());
        s3.copyObject(request -> request.bucket(bucket).key("explicit-copy").copySource(bucket + "/source")
                .serverSideEncryption(ServerSideEncryption.AES256));
        assertEquals(ServerSideEncryption.AES256, head("explicit-copy").serverSideEncryption());
        assertNull(head("explicit-copy").ssekmsKeyId());
    }

    @Test
    void multipartCompletionKeepsTheDefaultSelectedAtInitiation() {
        defaults(ServerSideEncryption.AWS_KMS, FIRST_KEY);
        String upload = s3.createMultipartUpload(request -> request.bucket(bucket).key("multipart")
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)).uploadId();
        defaults(ServerSideEncryption.AES256, null);
        UploadPartResponse part = s3.uploadPart(request -> request.bucket(bucket).key("multipart")
                .uploadId(upload).partNumber(1), RequestBody.fromString("multipart data"));
        s3.completeMultipartUpload(request -> request.bucket(bucket).key("multipart").uploadId(upload)
                .multipartUpload(parts -> parts.parts(completed -> completed.partNumber(1)
                        .eTag(part.eTag()).checksumSHA256(part.checksumSHA256()))));
        assertEquals(ServerSideEncryption.AWS_KMS, head("multipart").serverSideEncryption());
        assertEquals(FIRST_KEY, head("multipart").ssekmsKeyId());
        assertEquals("multipart data", s3.getObjectAsBytes(
                request -> request.bucket(bucket).key("multipart")).asUtf8String());
    }

    @Test
    void changingOrRemovingDefaultsDoesNotRewriteExistingVersions() {
        s3.putBucketVersioning(request -> request.bucket(bucket)
                .versioningConfiguration(versioning -> versioning.status(BucketVersioningStatus.ENABLED)));
        String first = s3.putObject(request -> request.bucket(bucket).key("versioned"),
                RequestBody.fromString("first version")).versionId();
        defaults(ServerSideEncryption.AWS_KMS, FIRST_KEY);
        String second = s3.putObject(request -> request.bucket(bucket).key("versioned"),
                RequestBody.fromString("second version")).versionId();
        s3.deleteBucketEncryption(request -> request.bucket(bucket));
        assertEquals(ServerSideEncryption.AES256, s3.headObject(
                request -> request.bucket(bucket).key("versioned").versionId(first)).serverSideEncryption());
        assertEquals(FIRST_KEY, head("versioned").ssekmsKeyId());
        s3.putObject(request -> request.bucket(bucket).key("versioned"), RequestBody.fromString("third version"));
        assertEquals(ServerSideEncryption.AES256, head("versioned").serverSideEncryption());
        assertEquals(FIRST_KEY, s3.headObject(
                request -> request.bucket(bucket).key("versioned").versionId(second)).ssekmsKeyId());
    }

    @Test
    void customerProvidedKeysDoNotInheritTheBucketEncryption() throws Exception {
        defaults(ServerSideEncryption.AWS_KMS, FIRST_KEY);
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
        defaults(ServerSideEncryption.AWS_KMS, FIRST_KEY);
        given().body("""
                <ServerSideEncryptionConfiguration>
                    <Rule><ApplyServerSideEncryptionByDefault/></Rule>
                </ServerSideEncryptionConfiguration>
                """)
                .put("/" + bucket + "?encryption").then().statusCode(400);
        s3.putObject(request -> request.bucket(bucket).key("after-rejection"), RequestBody.fromString("unchanged"));
        assertEquals(FIRST_KEY, head("after-rejection").ssekmsKeyId());
    }

    @Test
    void unsupportedEncryptionBlockingDoesNotPretendToBeEnforced() {
        defaults(ServerSideEncryption.AWS_KMS, FIRST_KEY);
        given().body("""
                <ServerSideEncryptionConfiguration>
                    <Rule><BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType></BlockedEncryptionTypes></Rule>
                </ServerSideEncryptionConfiguration>
                """)
                .put("/" + bucket + "?encryption").then().statusCode(501);
        s3.putObject(request -> request.bucket(bucket).key("after-unsupported"), RequestBody.fromString("unchanged"));
        assertEquals(FIRST_KEY, head("after-unsupported").ssekmsKeyId());
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

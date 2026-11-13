package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.ObjectOwnership;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3CannedAclConditionSdkIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private S3Client owner;
    private S3Client writer;
    private IamClient iam;
    private String user;
    private String bucket;

    @BeforeEach
    void prepareScopedWriter() {
        iam = AwsTestClients.create(IamClient.builder(), "test", Region.US_EAST_1, endpoint);
        cleanup.register(iam::close);
        user = "canned-acl-" + UUID.randomUUID();
        iam.createUser(request -> request.userName(user));
        cleanup.register(() -> iam.deleteUser(request -> request.userName(user)));
        bucket = "canned-acl-" + UUID.randomUUID();
        iam.putUserPolicy(request -> request.userName(user).policyName("output").policyDocument("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::%1$s/source"},
                  {"Effect":"Allow","Action":["s3:PutObject","s3:PutObjectAcl","s3:PutObjectVersionAcl"],
                   "Resource":"arn:aws:s3:::%1$s/reports/*",
                   "Condition":{"StringEquals":{"s3:x-amz-acl":"bucket-owner-full-control"}}}]}
                """.formatted(bucket)));
        cleanup.register(() -> iam.deleteUserPolicy(request -> request.userName(user).policyName("output")));
        AccessKey key = iam.createAccessKey(request -> request.userName(user)).accessKey();
        cleanup.register(() -> iam.deleteAccessKey(request -> request.userName(user).accessKeyId(key.accessKeyId())));
        writer = AwsTestClients.create(S3Client.builder().forcePathStyle(true),
                key.accessKeyId(), key.secretAccessKey(), Region.US_EAST_1, endpoint);
        cleanup.register(writer::close);
        owner = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "test", Region.US_EAST_1, endpoint);
        cleanup.register(owner::close);
        owner.createBucket(request -> request.bucket(bucket).objectOwnership(ObjectOwnership.OBJECT_WRITER));
        cleanup.register(() -> {
            owner.deleteBucketPolicy(request -> request.bucket(bucket));
            for (ObjectVersion version : owner.listObjectVersions(request -> request.bucket(bucket)).versions()) {
                owner.deleteObject(request -> request.bucket(bucket).key(version.key()).versionId(version.versionId()));
            }
            owner.deleteBucket(request -> request.bucket(bucket));
        });
        owner.putPublicAccessBlock(request -> request.bucket(bucket).publicAccessBlockConfiguration(configuration ->
                configuration.blockPublicAcls(false).blockPublicPolicy(false)
                        .ignorePublicAcls(false).restrictPublicBuckets(false)));
        owner.putBucketVersioning(request -> request.bucket(bucket)
                .versioningConfiguration(configuration -> configuration.status("Enabled")));
        for (String name : new String[]{"source", "reports/existing", "foreign/existing"}) {
            owner.putObject(request -> request.bucket(bucket).key(name), RequestBody.fromString("original"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"put", "copy", "multipart", "acl", "version-acl"})
    void matchingCannedAclAllowsOnlyTheSelectedPrefix(String operation) {
        assertDoesNotThrow(() -> write(operation, "reports/existing", ObjectCannedACL.BUCKET_OWNER_FULL_CONTROL));
        denied(() -> write(operation, "foreign/existing", ObjectCannedACL.BUCKET_OWNER_FULL_CONTROL));
        denied(() -> write(operation, "reports/existing", ObjectCannedACL.BUCKET_OWNER_READ));
        denied(() -> write(operation, "reports/existing", ObjectCannedACL.PUBLIC_READ));
        assertEquals("original", owner.getObjectAsBytes(
                request -> request.bucket(bucket).key("foreign/existing")).asUtf8String());
    }

    @ParameterizedTest
    @ValueSource(strings = {"put", "copy", "multipart"})
    void omittedOrPrivateAclDoesNotSatisfyTheRequiredCannedAcl(String operation) {
        denied(() -> write(operation, "reports/existing", null));
        denied(() -> write(operation, "reports/existing", ObjectCannedACL.PRIVATE));
        assertEquals("original", owner.getObjectAsBytes(
                request -> request.bucket(bucket).key("reports/existing")).asUtf8String());
    }

    @Test
    void bucketPolicyUsesTheHeaderForObjectCreationAndAclUpdates() {
        iam.putUserPolicy(request -> request.userName(user).policyName("output").policyDocument("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::%1$s/source"},
                  {"Effect":"Allow","Action":["s3:PutObject","s3:PutObjectAcl","s3:PutObjectVersionAcl"],
                   "Resource":"arn:aws:s3:::%1$s/reports/*"}]}
                """.formatted(bucket)));
        owner.putBucketPolicy(request -> request.bucket(bucket).policy("""
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Deny","Principal":"*",
                  "Action":["s3:PutObject","s3:PutObjectAcl","s3:PutObjectVersionAcl"],
                  "Resource":"arn:aws:s3:::%s/reports/*",
                  "Condition":{"StringNotEquals":{"s3:x-amz-acl":"bucket-owner-full-control"}}}]}
                """.formatted(bucket)));
        for (String operation : new String[]{"put", "copy", "multipart", "acl", "version-acl"}) {
            assertDoesNotThrow(() -> write(operation, "reports/existing", ObjectCannedACL.BUCKET_OWNER_FULL_CONTROL));
            denied(() -> write(operation, "reports/existing", ObjectCannedACL.PUBLIC_READ));
        }
    }

    private void write(String operation, String name, ObjectCannedACL acl) {
        switch (operation) {
            case "put" -> writer.putObject(request -> request.bucket(bucket).key(name).acl(acl),
                    RequestBody.fromString("output"));
            case "copy" -> writer.copyObject(request -> request.sourceBucket(bucket).sourceKey("source")
                    .destinationBucket(bucket).destinationKey(name).acl(acl));
            case "multipart" -> {
                String upload = writer.createMultipartUpload(request -> request.bucket(bucket).key(name)
                        .acl(acl)).uploadId();
                cleanup.register(() -> owner.abortMultipartUpload(
                        request -> request.bucket(bucket).key(name).uploadId(upload)));
            }
            case "acl" -> writer.putObjectAcl(request -> request.bucket(bucket).key(name).acl(acl));
            case "version-acl" -> {
                String version = owner.headObject(request -> request.bucket(bucket).key(name)).versionId();
                writer.putObjectAcl(request -> request.bucket(bucket).key(name).versionId(version).acl(acl));
            }
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private static void denied(Runnable operation) {
        S3Exception error = assertThrows(S3Exception.class, operation::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
    }
}

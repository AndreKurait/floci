package io.floci.aws.services.s3;

import io.floci.aws.core.common.auth.SigV4RequestValidator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.S3IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3PresignedPostKmsIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;
    private S3Client s3;
    private KmsClient kms;
    private String bucket;
    private String keyArn;
    private String user;
    private AccessKey access;

    @BeforeEach
    void setup() {
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "test", Region.US_EAST_1, endpoint);
        kms = AwsTestClients.create(KmsClient.builder(), "test", Region.US_EAST_1, endpoint);
        bucket = "kms-post-" + UUID.randomUUID();
        s3.createBucket(request -> request.bucket(bucket));
        keyArn = kms.createKey().keyMetadata().arn();
        s3.putBucketEncryption(request -> request.bucket(bucket).serverSideEncryptionConfiguration(config ->
            config.rules(rule -> rule.applyServerSideEncryptionByDefault(defaults ->
                defaults.sseAlgorithm(ServerSideEncryption.AWS_KMS).kmsMasterKeyID(keyArn)))));
        user = "KmsPost" + UUID.randomUUID();
        iam.createUser(user, "/");
        access = iam.createAccessKey(user);
        allow("s3:PutObject");
    }

    @AfterEach
    void cleanup() {
        try {
            s3.listObjectsV2(request -> request.bucket(bucket)).contents().forEach(object ->
                s3.deleteObject(request -> request.bucket(bucket).key(object.key())));
            s3.deleteBucket(request -> request.bucket(bucket));
            iam.deleteUserPolicy(user, "Upload");
            iam.deleteAccessKey(user, access.getAccessKeyId());
            iam.deleteUser(user);
            kms.scheduleKeyDeletion(request -> request.keyId(keyArn).pendingWindowInDays(7));
        } finally {
            s3.close();
            kms.close();
        }
    }

    @Test
    void signedFormUsesItsCallerForBucketDefaultKmsEncryption() throws Exception {
        allow("s3:PutObject", "kms:GenerateDataKey");
        upload("allowed", false, 204);
        assertEquals("form upload", s3.getObjectAsBytes(request -> request.bucket(bucket).key("allowed"))
            .asUtf8String());
        assertEquals(ServerSideEncryption.AWS_KMS,
            s3.headObject(request -> request.bucket(bucket).key("allowed")).serverSideEncryption());
        allow("s3:PutObject");
        upload("revoked", false, 403);
        assertEquals(1, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
    }

    @Test
    void formWithoutKmsPermissionDoesNotStoreAnObject() throws Exception {
        upload("denied", false, 403);
        assertTrue(s3.listObjectsV2(request -> request.bucket(bucket)).contents().isEmpty());
    }

    @Test
    void invalidFormSignatureCannotUseKmsPermission() throws Exception {
        allow("s3:PutObject", "kms:GenerateDataKey");
        upload("invalid", true, 403);
        assertTrue(s3.listObjectsV2(request -> request.bucket(bucket)).contents().isEmpty());
    }

    private void allow(String... actions) {
        iam.putUserPolicy(user, "Upload", "{\"Version\":\"2012-10-17\",\"Statement\":["
            + "{\"Effect\":\"Allow\",\"Action\":[\"" + String.join("\",\"", actions)
            + "\"],\"Resource\":\"*\"}]}");
    }

    private void upload(String key, boolean invalidSignature, int expectedStatus) throws Exception {
        String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        String date = amzDate.substring(0, 8);
        String credential = access.getAccessKeyId() + "/" + date + "/us-east-1/s3/aws4_request";
        String policy = Base64.getEncoder().encodeToString(("{\"expiration\":\""
            + Instant.now().plusSeconds(300) + "\",\"conditions\":[{\"bucket\":\"" + bucket
            + "\"},{\"key\":\"" + key + "\"},{\"x-amz-algorithm\":\"AWS4-HMAC-SHA256\"},"
            + "{\"x-amz-credential\":\"" + credential + "\"},{\"x-amz-date\":\"" + amzDate + "\"}]}")
            .getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = SigV4RequestValidator.deriveSigningKey(
            access.getSecretAccessKey(), date, "us-east-1", "s3");
        String signature = HexFormat.of().formatHex(SigV4RequestValidator.hmacSha256(signingKey, policy));
        given().multiPart("key", key).multiPart("policy", policy)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", credential).multiPart("x-amz-date", amzDate)
            .multiPart("x-amz-signature", invalidSignature ? "0".repeat(64) : signature)
            .multiPart("file", "upload.txt", "form upload".getBytes(StandardCharsets.UTF_8), "text/plain")
            .when().post(endpoint.resolve(bucket)).then().statusCode(expectedStatus);
    }
}

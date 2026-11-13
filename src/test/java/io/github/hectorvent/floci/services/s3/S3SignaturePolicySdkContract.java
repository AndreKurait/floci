package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the same SDK request through S3-only, IAM-only and combined policy enforcement. */
abstract class S3SignaturePolicySdkContract {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private S3Client s3;
    private S3Presigner presigner;
    private String bucket;
    private AccessKey keys;

    @BeforeEach
    void createOwnedBucketAndSdkCredentials() {
        IamClient iam = AwsTestClients.create(IamClient.builder(), "test", Region.US_EAST_1, endpoint);
        cleanup.register(iam::close);
        String user = "signature-policy-" + UUID.randomUUID();
        iam.createUser(request -> request.userName(user));
        cleanup.register(() -> iam.deleteUser(request -> request.userName(user)));
        iam.putUserPolicy(request -> request.userName(user).policyName("s3").policyDocument("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:*","Resource":"*"}]}
                """));
        cleanup.register(() -> iam.deleteUserPolicy(request -> request.userName(user).policyName("s3")));
        AccessKey key = iam.createAccessKey(request -> request.userName(user)).accessKey();
        keys = key;
        cleanup.register(() -> iam.deleteAccessKey(request -> request.userName(user).accessKeyId(key.accessKeyId())));
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true),
                key.accessKeyId(), key.secretAccessKey(), Region.US_EAST_1, endpoint);
        cleanup.register(s3::close);
        presigner = S3Presigner.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey())))
                .build();
        cleanup.register(presigner::close);
        bucket = "signature-policy-" + UUID.randomUUID();
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> {
            s3.deleteBucketPolicy(request -> request.bucket(bucket));
            for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
                s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
            }
            s3.deleteBucket(request -> request.bucket(bucket));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkSignedObjectSurvivesARequiredSignatureVersionPolicy(boolean presigned) throws Exception {
        s3.putBucketPolicy(request -> request.bucket(bucket).policy("""
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Deny","Principal":"*","Action":["s3:PutObject","s3:GetObject"],
                  "Resource":"arn:aws:s3:::%s/*",
                  "Condition":{"StringNotEquals":{"s3:signatureversion":"AWS4-HMAC-SHA256"}}}]}
                """.formatted(bucket)));
        String content = "inventory collected by an SDK-signed caller";
        if (presigned) {
            HttpResponse<String> response = presignedPut(content);
            assertEquals(200, response.statusCode(), response.body());
        } else {
            s3.putObject(request -> request.bucket(bucket).key("report.json"), RequestBody.fromString(content));
        }
        assertEquals(content, s3.getObjectAsBytes(
                request -> request.bucket(bucket).key("report.json")).asUtf8String());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void matchingSignatureVersionDenyStillBlocksTheSdkWrite(boolean presigned) throws Exception {
        denySignature("s3:PutObject", "StringEquals");
        if (presigned) {
            HttpResponse<String> response = presignedPut("must not be stored");
            assertEquals(403, response.statusCode(), response.body());
            assertTrue(response.body().contains("<Code>AccessDenied</Code>"), response.body());
        } else {
            denied(() -> s3.putObject(request -> request.bucket(bucket).key("report.json"),
                    RequestBody.fromString("must not be stored")));
        }
        assertEquals(0, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
    }

    @Test
    void copyTaggingAndBatchDeletionKeepTheSameSigningFacts() {
        denySignature("s3:*", "StringNotEquals");
        s3.putObject(request -> request.bucket(bucket).key("source").tagging("kind=inventory"),
                RequestBody.fromString("collected data"));
        s3.copyObject(request -> request.bucket(bucket).key("copy").copySource(bucket + "/source"));
        assertEquals("collected data", s3.getObjectAsBytes(
                request -> request.bucket(bucket).key("copy")).asUtf8String());
        assertEquals("inventory", s3.getObjectTagging(
                request -> request.bucket(bucket).key("copy")).tagSet().getFirst().value());
        DeleteObjectsResponse deleted = s3.deleteObjects(request -> request.bucket(bucket).delete(delete -> delete
                .objects(object -> object.key("source"), object -> object.key("copy"))));
        assertTrue(deleted.errors().isEmpty(), deleted.errors().toString());
        assertEquals(2, deleted.deleted().size());
        assertEquals(0, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
    }

    @Test
    void matchingSignatureDenyOnTheCopySourceIsEnforced() {
        s3.putObject(request -> request.bucket(bucket).key("source"), RequestBody.fromString("private data"));
        denySignature("s3:GetObject", "StringEquals");
        denied(() -> s3.copyObject(request -> request.bucket(bucket).key("copy").copySource(bucket + "/source")));
        assertEquals(List.of("source"), s3.listObjectsV2(request -> request.bucket(bucket))
                .contents().stream().map(S3Object::key).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void browserPostUsesTheSignedFormForBucketPolicyConditions(boolean deny) throws Exception {
        denySignature("s3:PutObject", deny ? "StringEquals" : "StringNotEquals");
        String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String credential = keys.accessKeyId() + "/" + date.substring(0, 8) + "/us-east-1/s3/aws4_request";
        String policy = Base64.getEncoder().encodeToString("""
                {"expiration":"%s","conditions":[{"bucket":"%s"},{"key":"report.json"},
                  {"x-amz-algorithm":"AWS4-HMAC-SHA256"},{"x-amz-credential":"%s"},{"x-amz-date":"%s"}]}
                """.formatted(Instant.now().plusSeconds(300), bucket, credential, date)
                .getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = ("AWS4" + keys.secretAccessKey()).getBytes(StandardCharsets.UTF_8);
        for (String part : List.of(date.substring(0, 8), "us-east-1", "s3", "aws4_request", policy)) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            signingKey = mac.doFinal(part.getBytes(StandardCharsets.UTF_8));
        }
        given().multiPart("key", "report.json")
                .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
                .multiPart("x-amz-credential", credential)
                .multiPart("x-amz-date", date)
                .multiPart("policy", policy)
                .multiPart("x-amz-signature", HexFormat.of().formatHex(signingKey))
                .multiPart("file", "report.json", "browser inventory".getBytes(StandardCharsets.UTF_8), "text/plain")
                .post("/" + bucket).then().statusCode(deny ? 403 : 204);
        assertEquals(deny ? 0 : 1, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
        if (!deny) {
            assertEquals("browser inventory", s3.getObjectAsBytes(
                    request -> request.bucket(bucket).key("report.json")).asUtf8String());
        }
    }

    private void denySignature(String action, String operator) {
        s3.putBucketPolicy(request -> request.bucket(bucket).policy("""
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Deny","Principal":"*","Action":"%s","Resource":"arn:aws:s3:::%s/*",
                  "Condition":{"%s":{"s3:signatureversion":"AWS4-HMAC-SHA256"}}}]}
                """.formatted(action, bucket, operator)));
    }

    private HttpResponse<String> presignedPut(String content) throws Exception {
        PresignedPutObjectRequest signed = presigner.presignPutObject(request -> request
                .signatureDuration(Duration.ofMinutes(5))
                .putObjectRequest(object -> object.bucket(bucket).key("report.json")));
        HttpRequest.Builder request = HttpRequest.newBuilder(signed.url().toURI())
                .timeout(Duration.ofSeconds(10)).PUT(HttpRequest.BodyPublishers.ofString(content));
        signed.signedHeaders().forEach((name, values) -> {
            if (!"host".equalsIgnoreCase(name)) {
                values.forEach(value -> request.header(name, value));
            }
        });
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void denied(Runnable operation) {
        S3Exception error = assertThrows(S3Exception.class, operation::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
    }
}

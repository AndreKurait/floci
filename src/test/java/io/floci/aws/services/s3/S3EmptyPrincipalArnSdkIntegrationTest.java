package io.floci.aws.services.s3;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.S3EnforceAuthProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(S3EnforceAuthProfile.class)
class S3EmptyPrincipalArnSdkIntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "[]", "[\"\"]"})
    void storingAnEmptyPrincipalConditionDoesNotGrantAnonymousObjectAccess(String values) {
        S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "test", Region.US_EAST_1);
        cleanup.register(s3::close);
        String bucket = "empty-principal-auth-" + UUID.randomUUID();
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> {
            s3.deleteBucketPolicy(request -> request.bucket(bucket));
            s3.deleteObject(request -> request.bucket(bucket).key("object"));
            s3.deleteBucket(request -> request.bucket(bucket));
        });
        s3.putBucketPolicy(request -> request.bucket(bucket).policy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"*"},
                 "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*",
                 "Condition":{"StringEquals":{"aws:PrincipalArn":%s}}}]}
                """.formatted(bucket, values)));
        assertTrue(s3.getPublicAccessBlock(request -> request.bucket(bucket))
                .publicAccessBlockConfiguration().blockPublicPolicy());
        assertFalse(s3.getBucketPolicyStatus(request -> request.bucket(bucket)).policyStatus().isPublic());
        given().when().get("/" + bucket + "/object")
                .then().statusCode(403).body(containsString("AccessDenied"));
        s3.putObject(request -> request.bucket(bucket).key("object"), RequestBody.fromString("owned SDK object"));
        assertEquals("owned SDK object",
                s3.getObjectAsBytes(request -> request.bucket(bucket).key("object")).asUtf8String());
        given().when().get("/" + bucket + "/object")
                .then().statusCode(403).body(containsString("AccessDenied"));
    }
}

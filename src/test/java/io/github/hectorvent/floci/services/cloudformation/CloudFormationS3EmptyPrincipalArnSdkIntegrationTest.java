package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudFormationS3EmptyPrincipalArnSdkIntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void defaultEmptyPrincipalListStoresTheRestrictivePolicyWithPublicAccessBlocked() throws Exception {
        CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        cleanup.register(cfn::close);
        S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "test", Region.US_EAST_1);
        cleanup.register(s3::close);
        String name = "empty-principal-" + UUID.randomUUID();
        cleanup.register(() -> {
            cfn.deleteStack(request -> request.stackName(name));
            CfnStackWaits.awaitStackDeleted(name);
        });
        cfn.createStack(request -> request.stackName(name).templateBody(template(name)));
        CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");

        assertEquals(BucketVersioningStatus.ENABLED,
                s3.getBucketVersioning(request -> request.bucket(name)).status());
        PublicAccessBlockConfiguration block = s3.getPublicAccessBlock(request -> request.bucket(name))
                .publicAccessBlockConfiguration();
        assertTrue(block.blockPublicPolicy());
        assertTrue(block.blockPublicAcls());
        assertTrue(block.ignorePublicAcls());
        assertTrue(block.restrictPublicBuckets());
        String stored = s3.getBucketPolicy(request -> request.bucket(name)).policy();
        JsonNode policy = new ObjectMapper().readTree(stored);
        assertEquals("false", policy.path("Statement").get(0).path("Condition")
                .path("BoolIfExists").path("aws:SecureTransport").asText());
        assertEquals("Deny", policy.path("Statement").get(0).path("Effect").asText());
        assertEquals("*", policy.path("Statement").get(1).path("Principal").path("AWS").asText());
        JsonNode values = policy.path("Statement").get(1).path("Condition")
                .path("StringEquals").path("aws:PrincipalArn");
        if (values.isArray()) {
            for (JsonNode value : values) {
                assertTrue(value.isTextual() && value.textValue().isEmpty());
            }
        } else {
            assertTrue(values.isTextual() && values.textValue().isEmpty());
        }
        assertFalse(s3.getBucketPolicyStatus(request -> request.bucket(name)).policyStatus().isPublic());
        S3Exception denied = assertThrows(S3Exception.class, () -> s3.putBucketPolicy(request ->
                request.bucket(name).policy("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",
                        "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}
                        """.formatted(name))));
        assertEquals(403, denied.statusCode());
        assertEquals("AccessDenied", denied.awsErrorDetails().errorCode());
        assertEquals(stored, s3.getBucketPolicy(request -> request.bucket(name)).policy());
    }

    private static String template(String bucket) {
        return """
                {"Parameters":{"ArtifactDecryptPrincipalArns":{"Type":"CommaDelimitedList","Default":""}},
                 "Resources":{
                   "Bucket":{"Type":"AWS::S3::Bucket","Properties":{
                     "BucketName":"%s","VersioningConfiguration":{"Status":"Enabled"}}},
                   "Policy":{"Type":"AWS::S3::BucketPolicy","Properties":{
                     "Bucket":{"Ref":"Bucket"},"PolicyDocument":{"Version":"2012-10-17","Statement":[
                       {"Effect":"Deny","Principal":"*","Action":"s3:*",
                        "Condition":{"BoolIfExists":{"aws:SecureTransport":"false"}},
                        "Resource":[{"Fn::GetAtt":["Bucket","Arn"]},{"Fn::Sub":"${Bucket.Arn}/*"}]},
                       {"Effect":"Allow","Principal":{"AWS":"*"},"Action":["s3:GetObject"],
                        "Resource":[{"Fn::Sub":"${Bucket.Arn}/*"}],
                        "Condition":{"StringEquals":{"aws:PrincipalArn":{"Ref":"ArtifactDecryptPrincipalArns"}}}}
                     ]}}}}}
                """.formatted(bucket);
    }
}

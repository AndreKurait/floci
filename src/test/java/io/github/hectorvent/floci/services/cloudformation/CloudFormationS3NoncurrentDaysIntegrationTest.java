package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoncurrentVersionExpiration;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationS3NoncurrentDaysIntegrationTest {

    private final String name = "noncurrent-days-" + UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private CloudFormationClient cfn;
    private S3Client s3;

    @BeforeEach
    void clients() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "000000000000", Region.EU_WEST_1);
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "000000000000", Region.EU_WEST_1);
    }

    @AfterEach
    void cleanup() {
        try {
            cfn.deleteStack(request -> request.stackName(name));
            await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                    .untilAsserted(() -> {
                CloudFormationException absent = assertThrows(CloudFormationException.class,
                        () -> cfn.describeStacks(request -> request.stackName(name)));
                assertEquals("ValidationError", absent.awsErrorDetails().errorCode());
                assertTrue(absent.getMessage().contains("does not exist"));
                S3Exception missingBucket = assertThrows(S3Exception.class,
                        () -> s3.headBucket(request -> request.bucket(name)));
                assertEquals(404, missingBucket.statusCode());
            });
        } finally {
            s3.close();
            cfn.close();
        }
    }

    @Test
    void legacyDaysReachTheS3LifecycleApi() {
        cfn.createStack(request -> request.stackName(name).templateBody(template(7, false, false)));
        awaitStatus("CREATE_COMPLETE");

        NoncurrentVersionExpiration expiration = expiration();
        assertEquals(7, expiration.noncurrentDays());
        assertNull(expiration.newerNoncurrentVersions());
    }

    @Test
    void parameterUpdatesAndModernFormKeepTheBucketAndReplaceTheRetentionAction() {
        cfn.createStack(request -> request.stackName(name).templateBody(template(7, true, false)));
        awaitStatus("CREATE_COMPLETE");
        String original = physicalId();
        assertEquals(7, expiration().noncurrentDays());

        update(template(14, true, false), 14);
        assertEquals(original, physicalId());
        assertEquals(14, expiration().noncurrentDays());

        update(template(21, true, true), 21);
        assertEquals(original, physicalId());
        assertEquals(21, expiration().noncurrentDays());
        assertEquals(3, expiration().newerNoncurrentVersions());

        update(template(30, true, false), 30);
        assertEquals(original, physicalId());
        assertEquals(30, expiration().noncurrentDays());
        assertNull(expiration().newerNoncurrentVersions());
    }

    private NoncurrentVersionExpiration expiration() {
        NoncurrentVersionExpiration expiration = s3.getBucketLifecycleConfiguration(request -> request
                .bucket(name)).rules().getFirst().noncurrentVersionExpiration();
        assertNotNull(expiration, "CloudFormation must preserve NoncurrentVersionExpirationInDays in S3");
        return expiration;
    }

    private String physicalId() {
        return cfn.describeStackResource(request -> request.stackName(name).logicalResourceId("Bucket"))
                .stackResourceDetail().physicalResourceId();
    }

    private void update(String body, int days) {
        cfn.updateStack(request -> request.stackName(name).templateBody(body)
                .parameters(parameter -> parameter.parameterKey("Days").parameterValue(Integer.toString(days))));
        awaitStatus("UPDATE_COMPLETE");
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() ->
                assertEquals(expected, cfn.describeStacks(request -> request.stackName(name))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private String template(int days, boolean reference, boolean modern) {
        ObjectNode template = mapper.createObjectNode();
        ObjectNode properties = template.putObject("Resources").putObject("Bucket")
                .put("Type", "AWS::S3::Bucket").putObject("Properties");
        properties.put("BucketName", name);
        properties.putObject("VersioningConfiguration").put("Status", "Enabled");
        ObjectNode rule = properties.putObject("LifecycleConfiguration").putArray("Rules").addObject()
                .put("Status", "Enabled");
        ObjectNode action = modern ? rule.putObject("NoncurrentVersionExpiration")
                .put("NewerNoncurrentVersions", 3) : rule;
        String field = modern ? "NoncurrentDays" : "NoncurrentVersionExpirationInDays";
        if (reference) {
            template.putObject("Parameters").putObject("Days").put("Type", "Number").put("Default", days);
            action.putObject(field).put("Ref", "Days");
        } else {
            action.put(field, days);
        }
        return template.toString();
    }
}

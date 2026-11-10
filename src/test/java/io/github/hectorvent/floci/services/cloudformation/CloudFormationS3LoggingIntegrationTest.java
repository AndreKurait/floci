package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.LoggingEnabled;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationS3LoggingIntegrationTest {
    private final String stack = "bucket-logging-" + UUID.randomUUID();
    private CloudFormationClient cfn;
    private S3Client s3;

    @BeforeEach
    void clients() {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), account, Region.US_EAST_1);
    }

    @AfterEach
    void cleanup() {
        try {
            if (cfn.describeStacks().stacks().stream().anyMatch(value -> stack.equals(value.stackName()))) {
                cfn.deleteStack(request -> request.stackName(stack));
                await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                        .untilAsserted(() ->
                        assertFalse(cfn.describeStacks().stacks().stream()
                                .anyMatch(value -> stack.equals(value.stackName()))));
            }
            assertTrue(s3.listBuckets().buckets().isEmpty(), "The stack must retire both owned buckets");
        } finally {
            s3.close();
            cfn.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SimplePrefix", "EventTime", "DeliveryTime"})
    void createsTheDeclaredDestinationPrefixAndObjectKeyFormat(String format) {
        create(template("logs/&<", format));
        LoggingEnabled logging = logging();
        assertEquals(outputs().get("Logs"), logging.targetBucket());
        assertEquals("logs/&<", logging.targetPrefix());
        if ("SimplePrefix".equals(format)) {
            assertNotNull(logging.targetObjectKeyFormat().simplePrefix());
            assertNull(logging.targetObjectKeyFormat().partitionedPrefix());
        } else {
            assertEquals(format, logging.targetObjectKeyFormat().partitionedPrefix().partitionDateSourceAsString());
        }
    }

    @Test
    void rollbackRemovalAndUnmanagedChangesFollowTheOwningTemplate() {
        create(template("original/", "SimplePrefix"));
        LoggingEnabled original = logging();
        ObjectNode failed = template("attempted/", "DeliveryTime");
        ObjectNode secret = failed.withObject("/Resources").putObject("Failure");
        secret.put("Type", "AWS::SecretsManager::Secret").put("DependsOn", "Bucket");
        secret.putObject("Properties").put("SecretString", "explicit")
                .putObject("GenerateSecretString").put("PasswordLength", 32);
        update(failed, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(original, logging(), "A later resource failure must restore the actual logging configuration");

        ObjectNode removed = template("unused/", "SimplePrefix");
        removed.withObject("/Resources/Bucket/Properties").remove("LoggingConfiguration");
        update(removed, "UPDATE_COMPLETE");
        assertNull(logging(), "Removing the declared property disables logging");
        Map<String, String> outputs = outputs();
        s3.putBucketLogging(request -> request.bucket(outputs.get("Bucket")).bucketLoggingStatus(status ->
                status.loggingEnabled(value -> value.targetBucket(outputs.get("Logs")).targetPrefix("external/"))));
        removed.withObject("/Resources/Bucket/Properties").putArray("Tags")
                .addObject().put("Key", "generation").put("Value", "next");
        update(removed, "UPDATE_COMPLETE");
        assertEquals("external/", logging().targetPrefix(), "An undeclared setting remains owned by its caller");
    }

    @Test
    void anEmptyLoggingConfigurationUsesTheSourceBucket() {
        ObjectNode template = template("unused/", "SimplePrefix");
        template.withObject("/Resources/Bucket/Properties").putObject("LoggingConfiguration");
        create(template);
        assertEquals(outputs().get("Bucket"), logging().targetBucket());
        assertEquals("", logging().targetPrefix());
    }

    private LoggingEnabled logging() {
        return s3.getBucketLogging(request -> request.bucket(outputs().get("Bucket"))).loggingEnabled();
    }

    private void create(ObjectNode template) {
        cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStatus("CREATE_COMPLETE");
    }

    private void update(ObjectNode template, String expected) {
        cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStatus(expected);
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() ->
                assertEquals(expected, cfn.describeStacks(request -> request.stackName(stack))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private Map<String, String> outputs() {
        return cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst().outputs()
                .stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static ObjectNode template(String prefix, String format) {
        ObjectNode template = new ObjectMapper().createObjectNode();
        ObjectNode resources = template.putObject("Resources");
        resources.putObject("Logs").put("Type", "AWS::S3::Bucket");
        ObjectNode bucket = resources.putObject("Bucket").put("Type", "AWS::S3::Bucket");
        ObjectNode logging = bucket.putObject("Properties").putObject("LoggingConfiguration");
        logging.putObject("DestinationBucketName").put("Ref", "Logs");
        logging.put("LogFilePrefix", prefix);
        ObjectNode keyFormat = logging.putObject("TargetObjectKeyFormat");
        if ("SimplePrefix".equals(format)) {
            keyFormat.putObject("SimplePrefix");
        } else {
            keyFormat.putObject("PartitionedPrefix").put("PartitionDateSource", format);
        }
        ObjectNode outputs = template.putObject("Outputs");
        outputs.putObject("Bucket").putObject("Value").put("Ref", "Bucket");
        outputs.putObject("Logs").putObject("Value").put("Ref", "Logs");
        return template;
    }
}

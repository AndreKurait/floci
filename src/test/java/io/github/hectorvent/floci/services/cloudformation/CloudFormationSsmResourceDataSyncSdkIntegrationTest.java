package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.InventoryItem;
import software.amazon.awssdk.services.ssm.model.ResourceDataSyncItem;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.hectorvent.floci.testing.CfnStackWaits.awaitStatus;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationSsmResourceDataSyncSdkIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong ACCOUNTS = new AtomicLong(830033330000L);

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"us-east-1, false", "us-east-1, true", "us-gov-west-1, false", "us-gov-west-1, true"})
    void stackOwnsSyncAndExportsUploadedInventory(String region, boolean nested) throws Exception {
        try (Scenario scenario = new Scenario(region)) {
            Stack stack = scenario.create(scenario.template(nested));
            assertEquals(scenario.name, output(stack, "SyncRef"),
                    "Ref must name the real SSM sync, not a CloudFormation placeholder");
            assertEquals(scenario.name, scenario.cfn.describeStackResource(request -> request
                    .stackName(scenario.name).logicalResourceId("Sync")).stackResourceDetail().physicalResourceId());
            ResourceDataSyncItem sync = scenario.sync(scenario.name);
            assertEquals("SyncToDestination", sync.syncType());
            assertEquals(scenario.bucket, sync.s3Destination().bucketName());
            assertEquals(region, sync.s3Destination().region());
            assertEquals("reports", sync.s3Destination().prefix());
            assertEquals("JsonSerDe", sync.s3Destination().syncFormatAsString());
            try (SsmClient foreign = ssm(Long.toString(ACCOUNTS.incrementAndGet()), region);
                 SsmClient otherRegion = ssm(scenario.account, "us-west-2")) {
                assertTrue(foreign.listResourceDataSync().resourceDataSyncItems().isEmpty());
                assertTrue(otherRegion.listResourceDataSync().resourceDataSyncItems().isEmpty());
            }
            scenario.upload("first");
            scenario.awaitExport("reports", "first");
            assertNotNull(scenario.sync(scenario.name).lastSuccessfulSyncTime());

            scenario.deleteStack();
            assertTrue(scenario.ssm.listResourceDataSync().resourceDataSyncItems().isEmpty());
            String retained = scenario.body("reports");
            scenario.upload("second");
            scenario.ssm.createResourceDataSync(request -> request.syncName("observer")
                    .s3Destination(destination -> destination.bucketName(scenario.bucket).region(region)
                            .prefix("observer").syncFormat("JsonSerDe")));
            scenario.awaitExport("observer", "second");
            assertEquals(retained, scenario.body("reports"),
                    "A worker cycle after stack deletion must preserve the old export without refreshing it");
        }
    }

    @Test
    void destinationChangeWithTheSameNameFailsWithoutChangingTheExistingSync() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            ObjectNode template = scenario.template(false);
            scenario.create(template);
            ResourceDataSyncItem before = scenario.sync(scenario.name);
            template.withObject("/Resources/Sync/Properties").put("BucketPrefix", "changed");
            scenario.assertReplacementPreview(template);

            scenario.update(template, "UPDATE_ROLLBACK_COMPLETE");

            ResourceDataSyncItem after = scenario.sync(scenario.name);
            assertEquals(before.syncCreatedTime(), after.syncCreatedTime());
            assertEquals(before.s3Destination(), after.s3Destination());
            assertEquals(1, scenario.ssm.listResourceDataSync().resourceDataSyncItems().size());
            assertTrue(scenario.failedReasons().contains("custom-named"), scenario.failedReasons());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void renamedReplacementHonorsRetentionAndNewDestination(boolean retain) throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            ObjectNode template = scenario.template(false);
            if (retain) {
                template.withObject("/Resources/Sync").put("UpdateReplacePolicy", "Retain");
            }
            scenario.create(template);
            String next = scenario.name + "-next";
            template.withObject("/Resources/Sync/Properties").put("SyncName", next)
                    .put("BucketPrefix", "next");
            scenario.assertReplacementPreview(template);

            Stack updated = scenario.update(template, "UPDATE_COMPLETE");

            assertEquals(next, output(updated, "SyncRef"));
            assertEquals("next", scenario.sync(next).s3Destination().prefix());
            assertEquals(retain, scenario.hasSync(scenario.name));
            scenario.upload("replacement");
            scenario.awaitExport("next", "replacement");
            scenario.deleteStack();
            assertFalse(scenario.hasSync(next));
            assertEquals(retain, scenario.hasSync(scenario.name));
        }
    }

    @Test
    void laterFailureRestoresThePreviousSyncAndAllowsTheReplacementToBeRetried() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            ObjectNode template = scenario.template(false);
            scenario.create(template);
            ResourceDataSyncItem before = scenario.sync(scenario.name);
            String next = scenario.name + "-next";
            template.withObject("/Resources/Sync/Properties").put("SyncName", next).put("BucketPrefix", "next");
            ObjectNode collision = template.withObject("/Resources/Sync").deepCopy();
            collision.put("DependsOn", "Sync");
            template.withObject("/Resources").set("Collision", collision);

            Stack restored = scenario.update(template, "UPDATE_ROLLBACK_COMPLETE");

            assertEquals(scenario.name, output(restored, "SyncRef"));
            assertEquals(before.syncCreatedTime(), scenario.sync(scenario.name).syncCreatedTime());
            assertEquals(before.s3Destination(), scenario.sync(scenario.name).s3Destination());
            assertFalse(scenario.hasSync(next));
            template.withObject("/Resources").remove("Collision");
            assertEquals(next, output(scenario.update(template, "UPDATE_COMPLETE"), "SyncRef"));
            assertFalse(scenario.hasSync(scenario.name));
        }
    }

    @Test
    void duplicateNameDoesNotAdoptOrDeleteAConfigurationOutsideTheStack() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            scenario.ssm.createResourceDataSync(request -> request.syncName(scenario.name)
                    .s3Destination(destination -> destination.bucketName(scenario.bucket).region(scenario.region)
                            .prefix("outside").syncFormat("JsonSerDe")));
            ResourceDataSyncItem before = scenario.sync(scenario.name);

            scenario.create(scenario.template(false), "ROLLBACK_COMPLETE");
            scenario.deleteStack();

            assertEquals(before.syncCreatedTime(), scenario.sync(scenario.name).syncCreatedTime());
            assertEquals("outside", scenario.sync(scenario.name).s3Destination().prefix());
        }
    }

    @Test
    void aFailedAsynchronousDestinationFailsAdmissionAndRemovesItsSync() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            ObjectNode template = scenario.template(false);
            template.withObject("/Resources/Sync/Properties").put("BucketRegion", "us-west-2");

            scenario.create(template, "ROLLBACK_COMPLETE");

            assertFalse(scenario.hasSync(scenario.name));
            assertTrue(scenario.failedReasons().contains("did not stabilize"), scenario.failedReasons());
            assertTrue(scenario.failedReasons().contains("specified Region"), scenario.failedReasons());
            assertTrue(scenario.s3.listObjectsV2(request -> request.bucket(scenario.bucket)).contents().isEmpty());
        }
    }

    @Test
    void ambiguousDestinationFailsWithoutCreatingASync() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            ObjectNode template = scenario.template(false);
            template.withObject("/Resources/Sync/Properties").putObject("S3Destination")
                    .put("BucketName", scenario.bucket).put("BucketRegion", scenario.region)
                    .put("SyncFormat", "JsonSerDe");

            scenario.create(template, "ROLLBACK_COMPLETE");

            assertFalse(scenario.hasSync(scenario.name));
            assertTrue(scenario.failedReasons().contains("Two configurations"), scenario.failedReasons());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void encryptionCannotBeSilentlyDroppedFromEitherDestinationForm(boolean nested) throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            ObjectNode template = scenario.template(nested);
            ObjectNode props = template.withObject("/Resources/Sync/Properties");
            ObjectNode destination = nested ? props.withObject("/S3Destination") : props;
            destination.put("KMSKeyArn", "arn:aws:kms:us-east-1:" + scenario.account + ":key/" + UUID.randomUUID());

            scenario.create(template, "ROLLBACK_COMPLETE");

            assertFalse(scenario.hasSync(scenario.name));
            assertTrue(scenario.failedReasons().contains("KMS-encrypted"), scenario.failedReasons());
        }
    }

    @Test
    void explorerCannotBecomeAnInventoryDestination() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            ObjectNode template = scenario.template(false);
            ObjectNode props = template.withObject("/Resources/Sync/Properties");
            props.remove(List.of("BucketName", "BucketRegion", "BucketPrefix", "SyncFormat"));
            props.put("SyncType", "SyncFromSource").putObject("SyncSource").put("SourceType", "AwsRegions")
                    .put("IncludeFutureRegions", true).putArray("SourceRegions").add(scenario.region);

            scenario.create(template, "ROLLBACK_COMPLETE");

            assertFalse(scenario.hasSync(scenario.name));
            assertTrue(scenario.failedReasons().contains("Explorer"), scenario.failedReasons());
        }
    }

    @Test
    void reportingConditionCreatesAndRemovesTheBackingSync() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            ObjectNode template = scenario.template(false);
            template.putObject("Parameters").putObject("Enabled").put("Type", "String").put("Default", "false");
            template.putObject("Conditions").putObject("Reporting").putArray("Fn::Equals")
                    .addObject().put("Ref", "Enabled");
            template.withObject("/Conditions/Reporting").withArray("Fn::Equals").add("true");
            template.withObject("/Resources/Sync").put("Condition", "Reporting");
            template.withObject("/Outputs/SyncRef").put("Condition", "Reporting");

            assertTrue(scenario.create(template).outputs().isEmpty());
            assertFalse(scenario.hasSync(scenario.name));
            scenario.cfn.updateStack(request -> request.stackName(scenario.name).templateBody(template.toString())
                    .parameters(Parameter.builder().parameterKey("Enabled").parameterValue("true").build()));
            assertEquals(scenario.name, output(awaitStatus(scenario.cfn, scenario.name, "UPDATE_COMPLETE"), "SyncRef"));
            scenario.upload("conditional");
            scenario.awaitExport("reports", "conditional");
            scenario.cfn.updateStack(request -> request.stackName(scenario.name).templateBody(template.toString())
                    .parameters(Parameter.builder().parameterKey("Enabled").parameterValue("false").build()));
            assertTrue(awaitStatus(scenario.cfn, scenario.name, "UPDATE_COMPLETE").outputs().isEmpty());
            assertFalse(scenario.hasSync(scenario.name));
            assertTrue(scenario.body("reports").contains("conditional"));
        }
    }

    private static String output(Stack stack, String key) {
        return stack.outputs().stream().filter(value -> key.equals(value.outputKey()))
                .findFirst().orElseThrow().outputValue();
    }

    private static final class Scenario implements AutoCloseable {
        private final String account = Long.toString(ACCOUNTS.incrementAndGet());
        private final String name = "cfn-sync-" + UUID.randomUUID();
        private final String bucket = "cfn-inventory-" + UUID.randomUUID();
        private final String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        private final String region;
        private final CloudFormationClient cfn;
        private final SsmClient ssm;
        private final S3Client s3;
        private boolean stackCreated;

        private Scenario(String region) {
            this.region = region;
            cfn = cfn(account, region);
            ssm = ssm(account, region);
            s3 = s3(account, region);
            s3.createBucket(request -> {
                request.bucket(bucket);
                if (!"us-east-1".equals(region)) {
                    request.createBucketConfiguration(configuration -> configuration.locationConstraint(region));
                }
            });
            given().header("X-Amz-Target", "AmazonSSM.UpdateInstanceInformation")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260102/" + region
                            + "/ssm/aws4_request, SignedHeaders=host, Signature=example")
                    .contentType("application/x-amz-json-1.1")
                    .body(Map.of("InstanceId", instance, "AgentName", "amazon-ssm-agent",
                            "PlatformType", "Linux", "PlatformName", "Example Linux", "AgentVersion", "3.3.0"))
                    .post("/").then().statusCode(200);
        }

        private ObjectNode template(boolean nested) throws Exception {
            ObjectNode template = (ObjectNode) JSON.readTree("""
                    {
                      "Resources": {
                        "Sync": {
                          "Type": "AWS::SSM::ResourceDataSync",
                          "Properties": {
                            "SyncName": {"Ref": "AWS::StackName"},
                            "BucketRegion": {"Ref": "AWS::Region"},
                            "BucketPrefix": "reports",
                            "SyncFormat": "JsonSerDe"
                          }
                        }
                      },
                      "Outputs": {"SyncRef": {"Value": {"Ref": "Sync"}}}
                    }
                    """);
            ObjectNode props = template.withObject("/Resources/Sync/Properties");
            props.put("BucketName", bucket);
            if (nested) {
                ObjectNode destination = props.putObject("S3Destination");
                for (String field : List.of("BucketName", "BucketRegion", "BucketPrefix", "SyncFormat")) {
                    destination.set(field, props.remove(field));
                }
                props.put("SyncType", "SyncToDestination");
            }
            return template;
        }

        private Stack create(ObjectNode template) {
            return create(template, "CREATE_COMPLETE");
        }

        private Stack create(ObjectNode template, String expected) {
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            stackCreated = true;
            return awaitStatus(cfn, name, expected);
        }

        private Stack update(ObjectNode template, String expected) {
            cfn.updateStack(request -> request.stackName(name).templateBody(template.toString()));
            return awaitStatus(cfn, name, expected);
        }

        private void assertReplacementPreview(ObjectNode template) {
            String change = "preview-" + UUID.randomUUID();
            cfn.createChangeSet(request -> request.stackName(name).changeSetName(change)
                    .changeSetType("UPDATE").templateBody(template.toString()));
            try {
                DescribeChangeSetResponse preview = cfn.describeChangeSet(request -> request.stackName(name)
                        .changeSetName(change));
                assertEquals("CREATE_COMPLETE", preview.statusAsString(), preview.statusReason());
                assertEquals("True", preview.changes().stream()
                        .filter(value -> "Sync".equals(value.resourceChange().logicalResourceId()))
                        .findFirst().orElseThrow().resourceChange().replacementAsString());
            } finally {
                cfn.deleteChangeSet(request -> request.stackName(name).changeSetName(change));
            }
        }

        private String failedReasons() {
            return cfn.describeStackEvents(request -> request.stackName(name)).stackEvents().stream()
                    .filter(event -> event.resourceStatusAsString().endsWith("FAILED"))
                    .map(event -> event.resourceStatusReason()).toList().toString();
        }

        private boolean hasSync(String syncName) {
            return ssm.listResourceDataSync().resourceDataSyncItems().stream()
                    .anyMatch(value -> syncName.equals(value.syncName()));
        }

        private ResourceDataSyncItem sync(String syncName) {
            return ssm.listResourceDataSync().resourceDataSyncItems().stream()
                    .filter(value -> syncName.equals(value.syncName())).findFirst().orElseThrow();
        }

        private void upload(String value) {
            ssm.putInventory(request -> request.instanceId(instance)
                    .items(InventoryItem.builder().typeName("AWS:Application").schemaVersion("1.1")
                            .captureTime("2026-01-02T03:04:05Z").contentHash(value)
                            .content(Map.of("Name", value)).build()));
        }

        private String body(String prefix) {
            String key = prefix + "/AWS:Application/accountid=" + account + "/region=" + region
                    + "/resourcetype=ManagedInstanceInventory/" + instance + ".json";
            return s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String();
        }

        private void awaitExport(String prefix, String value) {
            await().atMost(Duration.ofSeconds(10)).ignoreExceptions().untilAsserted(() -> {
                JsonNode row = JSON.readTree(body(prefix));
                assertEquals(value, row.path("Name").asText());
                assertEquals(instance, row.path("resourceId").asText());
            });
        }

        private void deleteStack() {
            if (!stackCreated) {
                return;
            }
            cfn.deleteStack(request -> request.stackName(name));
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                try {
                    Stack stack = cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst();
                    assertNotEquals("DELETE_FAILED", stack.stackStatusAsString(), stack.stackStatusReason());
                    return false;
                } catch (CloudFormationException error) {
                    assertEquals("ValidationError", error.awsErrorDetails().errorCode());
                    assertTrue(error.getMessage().contains("does not exist"), error.getMessage());
                    return true;
                }
            });
            stackCreated = false;
        }

        @Override
        public void close() {
            try {
                deleteStack();
                for (ResourceDataSyncItem sync : ssm.listResourceDataSync().resourceDataSyncItems()) {
                    ssm.deleteResourceDataSync(request -> request.syncName(sync.syncName()));
                }
                for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
                    s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
                }
                s3.deleteBucket(request -> request.bucket(bucket));
            } finally {
                cfn.close();
                ssm.close();
                s3.close();
            }
        }
    }

    private static CloudFormationClient cfn(String account, String region) {
        return CloudFormationClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static SsmClient ssm(String account, String region) {
        return SsmClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static S3Client s3(String account, String region) {
        return S3Client.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}

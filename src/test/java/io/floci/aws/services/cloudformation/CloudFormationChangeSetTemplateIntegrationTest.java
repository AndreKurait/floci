package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.GetTemplateResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationChangeSetTemplateIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final Region REGION = Region.US_EAST_1;
    private static final String SAM = """
            {"Transform":"AWS::Serverless-2016-10-31","Resources":{"Api":{
              "Type":"AWS::Serverless::Api","Properties":{"StageName":"preview","DefinitionBody":{
                "swagger":"2.0","info":{"title":"change-set-preview","version":"1"},
                "paths":{"/":{"get":{"responses":{"200":{"description":"ok"}}}}}
              }}
            }}}
            """;

    @TestHTTPResource
    URI endpoint;

    private CloudFormationClient cfn;
    private S3Client s3;
    private SsmClient ssm;
    private final List<String> stacks = new ArrayList<>();
    private final List<String> buckets = new ArrayList<>();

    @BeforeEach
    void clients() {
        cfn = client(ACCOUNT, REGION);
        StaticCredentialsProvider credentials = credentials(ACCOUNT);
        s3 = S3Client.builder().endpointOverride(endpoint).region(REGION)
                .credentialsProvider(credentials).forcePathStyle(true).build();
        ssm = SsmClient.builder().endpointOverride(endpoint).region(REGION)
                .credentialsProvider(credentials).build();
    }

    @AfterEach
    void cleanup() {
        List<Executable> cleanup = new ArrayList<>();
        for (String stack : stacks) {
            cleanup.add(() -> {
                cfn.deleteStack(request -> request.stackName(stack));
                await().atMost(Duration.ofSeconds(30)).until(() -> {
                    try {
                        cfn.describeStacks(request -> request.stackName(stack));
                        return false;
                    } catch (CloudFormationException error) {
                        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
                        assertTrue(error.awsErrorDetails().errorMessage().contains("does not exist"));
                        return true;
                    }
                });
            });
        }
        for (String bucket : buckets) {
            cleanup.add(() -> {
                s3.deleteObject(request -> request.bucket(bucket).key("properties.json"));
                s3.deleteBucket(request -> request.bucket(bucket));
                assertFalse(s3.listBuckets().buckets().stream().anyMatch(item -> bucket.equals(item.name())));
            });
        }
        try {
            assertAll(cleanup);
        } finally {
            cfn.close();
            s3.close();
            ssm.close();
        }
    }

    @Test
    void pendingCreateReturnsTheSelectedTemplateByNameOrExactArnWithoutExecuting() {
        String first = stack("first");
        String second = stack("second");
        String firstBody = parameter("/change-set-template/" + first, "first");
        String secondBody = parameter("/change-set-template/" + second, "second");
        String firstArn = changeSet(first, "shared-name", "CREATE", firstBody);
        String secondArn = changeSet(second, "shared-name", "CREATE", secondBody);

        assertEquals(secondBody, cfn.getTemplate(request -> request.changeSetName(secondArn)
                .templateStage("Original")).templateBody());
        assertEquals(firstBody, cfn.getTemplate(request -> request.stackName(first)
                .changeSetName("shared-name")).templateBody());
        GetTemplateResponse processed = cfn.getTemplate(request -> request.stackName(first)
                .changeSetName(firstArn).templateStage("Processed"));
        assertEquals(firstBody, processed.templateBody());
        assertEquals(Set.of("Original", "Processed"), Set.copyOf(processed.stagesAvailableAsStrings()));
        assertEquals("REVIEW_IN_PROGRESS", cfn.describeStacks(request -> request.stackName(first))
                .stacks().getFirst().stackStatusAsString());
        assertEquals("AVAILABLE", cfn.describeChangeSet(request -> request.changeSetName(secondArn))
                .executionStatusAsString());
        assertTrue(cfn.listStackResources(request -> request.stackName(first)).stackResourceSummaries().isEmpty());
    }

    @Test
    void processedSamTemplateIsAvailableBeforeExecutionAndOriginalRetainsTheTransform() throws Exception {
        String stack = stack("sam");
        String arn = changeSet(stack, "preview", "CREATE", SAM);
        assertEquals(SAM, cfn.getTemplate(request -> request.changeSetName(arn)
                .templateStage("Original")).templateBody());
        GetTemplateResponse processed = cfn.getTemplate(request -> request.changeSetName(arn)
                .templateStage("Processed"));
        JsonNode body = JSON.readTree(processed.templateBody());
        assertEquals("AWS::ApiGateway::RestApi", body.path("Resources").path("Api").path("Type").asText());
        assertFalse(body.has("Transform"));
        assertEquals(Set.of("Original", "Processed"), Set.copyOf(processed.stagesAvailableAsStrings()));
    }

    @Test
    void anUpdateReturnsItsProposedTemplateWhileTheStackStillReturnsItsDeployedTemplate() {
        String stack = stack("update");
        String name = "/change-set-template/" + stack;
        String original = parameter(name, "deployed");
        String proposed = parameter(name, "proposed");
        cfn.createStack(request -> request.stackName(stack).templateBody(original));
        awaitStatus(stack, "CREATE_COMPLETE");
        String arn = changeSet(stack, "preview", "UPDATE", proposed);

        assertEquals(original, cfn.getTemplate(request -> request.stackName(stack)).templateBody());
        assertEquals(proposed, cfn.getTemplate(request -> request.changeSetName(arn)
                .templateStage("Original")).templateBody());
        assertEquals(proposed, cfn.getTemplate(request -> request.stackName(stack)
                .changeSetName("preview").templateStage("Processed")).templateBody());
        assertEquals("deployed", ssm.getParameter(request -> request.name(name)).parameter().value());
        cfn.deleteChangeSet(request -> request.changeSetName(arn));
        CloudFormationException missing = assertThrows(CloudFormationException.class,
                () -> cfn.getTemplate(request -> request.stackName(stack).changeSetName(arn)));
        assertEquals("ChangeSetNotFoundException", missing.awsErrorDetails().errorCode());
        assertEquals(original, cfn.getTemplate(request -> request.stackName(stack)).templateBody());
    }

    @Test
    void processedIncludeIsTheSnapshotThatExecutionUsesEvenAfterTheS3ObjectChanges() throws Exception {
        String stack = stack("include");
        String bucket = "cfn-cs-template-" + UUID.randomUUID();
        String name = "/change-set-template/" + stack;
        s3.createBucket(request -> request.bucket(bucket));
        buckets.add(bucket);
        s3.putObject(request -> request.bucket(bucket).key("properties.json"),
                RequestBody.fromString("{\"Value\":\"approved\"}"));
        String original = """
                {"Resources":{"Parameter":{"Type":"AWS::SSM::Parameter","Properties":{
                  "Name":"%s","Type":"String",
                  "Fn::Transform":{"Name":"AWS::Include","Parameters":{"Location":"s3://%s/properties.json"}}
                }}}}
                """.formatted(name, bucket);
        String arn = changeSet(stack, "preview", "CREATE", original);
        s3.putObject(request -> request.bucket(bucket).key("properties.json"),
                RequestBody.fromString("{\"Value\":\"changed-after-preview\"}"));

        assertEquals(original, cfn.getTemplate(request -> request.changeSetName(arn)
                .templateStage("Original")).templateBody());
        JsonNode processed = JSON.readTree(cfn.getTemplate(request -> request.changeSetName(arn)
                .templateStage("Processed")).templateBody());
        assertEquals("approved", processed.path("Resources").path("Parameter").path("Properties").path("Value").asText());
        s3.deleteObject(request -> request.bucket(bucket).key("properties.json"));
        assertEquals(processed, JSON.readTree(cfn.getTemplate(request -> request.changeSetName(arn)
                .templateStage("Processed")).templateBody()));
        cfn.executeChangeSet(request -> request.changeSetName(arn));
        awaitStatus(stack, "CREATE_COMPLETE");
        assertEquals("approved", ssm.getParameter(request -> request.name(name)).parameter().value());
        assertEquals(original, cfn.getTemplate(request -> request.stackName(stack)
                .templateStage("Original")).templateBody());
        assertEquals(processed, JSON.readTree(cfn.getTemplate(request -> request.stackName(stack)
                .templateStage("Processed")).templateBody()));
    }

    @Test
    void anUnchangedFailedChangeSetStillHasItsSuccessfullyProcessedTemplate() {
        String stack = stack("unchanged");
        String original = parameter("/change-set-template/" + stack, "deployed");
        cfn.createStack(request -> request.stackName(stack).templateBody(original));
        awaitStatus(stack, "CREATE_COMPLETE");
        String arn = cfn.createChangeSet(request -> request.stackName(stack).changeSetName("unchanged")
                .changeSetType("UPDATE").templateBody(original)).id();

        assertEquals("FAILED", cfn.describeChangeSet(request -> request.changeSetName(arn)).statusAsString());
        GetTemplateResponse response = cfn.getTemplate(request -> request.changeSetName(arn)
                .templateStage("Processed"));
        assertEquals(original, response.templateBody());
        assertEquals(Set.of("Original", "Processed"), Set.copyOf(response.stagesAvailableAsStrings()));
    }

    @Test
    void aFailedTransformStillReturnsItsOriginalAndDoesNotAdvertiseAProcessedTemplate() {
        String stack = stack("failed");
        String original = """
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{"Api":{
                  "Type":"AWS::Serverless::Api","Properties":{"StageName":"preview","DefinitionUri":"./unpackaged.yaml"}
                }}}
                """;
        String arn = cfn.createChangeSet(request -> request.stackName(stack).changeSetName("preview")
                .changeSetType("CREATE").templateBody(original)).id();
        assertEquals("FAILED", cfn.describeChangeSet(request -> request.changeSetName(arn)).statusAsString());
        GetTemplateResponse response = cfn.getTemplate(request -> request.changeSetName(arn)
                .templateStage("Original"));
        assertEquals(original, response.templateBody());
        assertEquals(List.of("Original"), response.stagesAvailableAsStrings());
        CloudFormationException unavailable = assertThrows(CloudFormationException.class,
                () -> cfn.getTemplate(request -> request.changeSetName(arn).templateStage("Processed")));
        assertEquals("ValidationError", unavailable.awsErrorDetails().errorCode());
    }

    @Test
    void changeSetLookupKeepsTheExactUuidStackAccountAndRegionBoundaries() {
        String stack = stack("scope");
        String other = stack("other");
        String arn = changeSet(stack, "shared-name", "CREATE", parameter("/scope/" + stack, "owner"));
        changeSet(other, "shared-name", "CREATE", parameter("/scope/" + other, "other"));
        assertThrows(CloudFormationException.class,
                () -> cfn.getTemplate(request -> request.changeSetName(arn + "-wrong")));
        assertThrows(CloudFormationException.class,
                () -> cfn.getTemplate(request -> request.stackName(other).changeSetName(arn)));
        assertThrows(CloudFormationException.class,
                () -> cfn.getTemplate(request -> request.changeSetName("shared-name")));
        try (CloudFormationClient foreign = client("111122223333", REGION);
             CloudFormationClient otherRegion = client(ACCOUNT, Region.US_WEST_2)) {
            assertThrows(CloudFormationException.class,
                    () -> foreign.getTemplate(request -> request.changeSetName(arn)));
            assertThrows(CloudFormationException.class,
                    () -> otherRegion.getTemplate(request -> request.changeSetName(arn)));
        }
    }

    @Test
    void invalidTemplateStageIsValidatedBeforeLookingUpTheChangeSet() {
        CloudFormationException error = assertThrows(CloudFormationException.class,
                () -> cfn.getTemplate(request -> request.changeSetName("missing").templateStage("invalid")));
        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
        assertTrue(error.awsErrorDetails().errorMessage().contains("templateStage"));
    }

    private String stack(String label) {
        String name = "cfn-cs-template-" + label + "-" + UUID.randomUUID();
        stacks.add(name);
        return name;
    }

    private String changeSet(String stack, String name, String type, String template) {
        String arn = cfn.createChangeSet(request -> request.stackName(stack).changeSetName(name)
                .changeSetType(type).templateBody(template).capabilitiesWithStrings("CAPABILITY_AUTO_EXPAND")).id();
        assertEquals("CREATE_COMPLETE", cfn.describeChangeSet(request -> request.changeSetName(arn)).statusAsString());
        return arn;
    }

    private void awaitStatus(String stack, String expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(expected,
                cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst().stackStatusAsString()));
    }

    private static String parameter(String name, String value) {
        return """
                {"Resources":{"Parameter":{"Type":"AWS::SSM::Parameter",
                  "Properties":{"Name":"%s","Type":"String","Value":"%s"}}}}
                """.formatted(name, value);
    }

    private CloudFormationClient client(String account, Region region) {
        return CloudFormationClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(credentials(account)).build();
    }

    private static StaticCredentialsProvider credentials(String account) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"));
    }
}

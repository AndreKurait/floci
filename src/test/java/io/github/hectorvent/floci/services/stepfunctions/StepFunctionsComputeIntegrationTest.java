package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.StartSyncExecutionResponse;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Step Functions, signed public EC2/SSM APIs and IAM. Guest execution has a separate stock-agent gate. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsComputeIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "771661200003";
    private static final String OTHER_ACCOUNT = "771661200004";
    private static final String ACTIONS = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["ec2:*","ssm:*"],"Resource":"*"}]}
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"eu-west-2,aws", "cn-north-1,aws-cn"})
    void launchReplayDescribeAndTerminateUseTheActualEc2Api(String region, String partition) throws Exception {
        try (Cloud cloud = new Cloud(ACCOUNT, region, partition)) {
            String template = cloud.ec2.createLaunchTemplate(request -> request.launchTemplateName(cloud.name)
                    .launchTemplateData(data -> data.imageId("ami-1234567890abcdef0").instanceType("t3.micro")))
                    .launchTemplate().launchTemplateId();
            try {
                Map<String, Object> request = Map.of("MinCount", 1, "MaxCount", 1,
                        "LaunchTemplate", Map.of("LaunchTemplateId", template, "Version", "1"),
                        "ClientToken", cloud.name, "MetadataOptions", Map.of("HttpTokens", "required"),
                        "TagSpecifications", List.of(Map.of("ResourceType", "instance", "Tags",
                                List.of(Map.of("Key", "lowerCase", "Value", cloud.name)))));
                JsonNode launched = cloud.task("ec2", "runInstances", request);
                String instance = launched.path("Instances").path(0).path("InstanceId").asText();
                cloud.instances.add(instance);
                assertEquals(ACCOUNT, launched.path("OwnerId").asText());
                assertEquals(instance, cloud.task("ec2", "runInstances", request)
                        .path("Instances").path(0).path("InstanceId").asText());
                assertEquals(instance, cloud.ec2.describeInstances(r -> r.instanceIds(instance))
                        .reservations().getFirst().instances().getFirst().instanceId());
                JsonNode selected = cloud.task("ec2", "describeInstances", Map.of("Filters",
                        List.of(Map.of("Name", "tag:lowerCase", "Values", List.of(cloud.name)))))
                        .path("Reservations");
                assertEquals(1, selected.size());
                JsonNode observed = selected.path(0).path("Instances").path(0);
                assertEquals(instance, observed.path("InstanceId").asText());
                assertTrue(observed.path("State").path("Code").isIntegralNumber());
                assertEquals("required", observed.path("MetadataOptions").path("HttpTokens").asText());
                JsonNode ended = cloud.task("ec2", "terminateInstances", Map.of("InstanceIds", List.of(instance)));
                assertEquals(instance, ended.path("TerminatingInstances").path(0).path("InstanceId").asText());
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("terminated",
                        cloud.ec2.describeInstances(r -> r.instanceIds(instance))
                                .reservations().getFirst().instances().getFirst().state().nameAsString()));
            } finally {
                cloud.ec2.deleteLaunchTemplate(request -> request.launchTemplateId(template));
            }
        }
    }

    @Test
    void ssmReturnsPendingWorkAndCancellationInsteadOfClaimingExecutionSuccess() throws Exception {
        try (Cloud cloud = new Cloud(ACCOUNT, "eu-west-2", "aws")) {
            String instance = cloud.node();
            cloud.register(instance);
            cloud.register(cloud.node());
            JsonNode ready = cloud.task("ssm", "describeInstanceInformation", Map.of("Filters",
                    List.of(Map.of("Key", "InstanceIds", "Values", List.of(instance)))));
            assertEquals(1, ready.path("InstanceInformationList").size());
            assertEquals(instance, ready.path("InstanceInformationList").path(0).path("InstanceId").asText());
            assertTrue(ready.path("InstanceInformationList").path(0).path("LastPingDateTime").isTextual());
            JsonNode command = cloud.task("ssm", "sendCommand", Map.of("InstanceIds", List.of(instance),
                    "DocumentName", "AWS-RunShellScript", "Parameters", Map.of("commands", List.of("echo opaque")),
                    "TimeoutSeconds", 60)).path("Command");
            String id = command.path("CommandId").asText();
            assertFalse(id.isBlank());
            try {
                assertEquals("echo opaque", command.path("Parameters").path("commands").path(0).asText());
                assertTrue(command.path("RequestedDateTime").isTextual());
                assertEquals(1, command.path("TargetCount").asInt());
                assertFalse("Success".equals(command.path("Status").asText()));
                assertEquals(id, cloud.ssm.listCommands(r -> r.commandId(id)).commands().getFirst().commandId());
                assertEquals(id, cloud.task("ssm", "listCommands", Map.of("CommandId", id))
                        .path("Commands").path(0).path("CommandId").asText());
                assertEquals(instance, cloud.task("ssm", "listCommandInvocations", Map.of("CommandId", id,
                        "Details", true)).path("CommandInvocations").path(0).path("InstanceId").asText());
                JsonNode pending = cloud.task("ssm", "getCommandInvocation",
                        Map.of("CommandId", id, "InstanceId", instance));
                assertTrue(pending.path("ResponseCode").isIntegralNumber());
                assertFalse("Success".equals(pending.path("Status").asText()));
                assertTrue(cloud.task("ssm", "cancelCommand",
                        Map.of("CommandId", id, "InstanceIds", List.of(instance))).isEmpty());
                assertEquals("Cancelled", cloud.ssm.getCommandInvocation(r -> r.commandId(id).instanceId(instance))
                        .statusAsString());
            } finally {
                cloud.ssm.cancelCommand(r -> r.commandId(id).instanceIds(instance));
            }
        }
    }

    @Test
    void executionRolePermissionsAndTrustAreEvaluatedOnThePublicServicePath() throws Exception {
        try (Cloud cloud = new Cloud(ACCOUNT, "us-east-1", "aws")) {
            cloud.iam.deleteRolePolicy(request -> request.roleName(cloud.name).policyName("tasks"));
            JsonNode denied = cloud.task("ec2", "describeInstances", Map.of());
            assertEquals("Ec2.Ec2Exception", denied.path("Error").asText());
            assertTrue(denied.path("Cause").asText().contains("AccessDenied"));
            cloud.iam.putRolePolicy(request -> request.roleName(cloud.name).policyName("tasks").policyDocument(ACTIONS));
            assertTrue(cloud.task("ec2", "describeInstances", Map.of()).path("Reservations").isArray());
            cloud.iam.updateAssumeRolePolicy(request -> request.roleName(cloud.name).policyDocument("""
                    {"Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},
                    "Action":"sts:AssumeRole"}]}
                    """));
            assertEquals("States.Permissions", cloud.task("ssm", "describeInstanceInformation", Map.of())
                    .path("Error").asText());
        }
    }

    @Test
    void taskCredentialsSelectTheOtherAccountAndRequireItsTrust() throws Exception {
        try (Cloud owner = new Cloud(ACCOUNT, "us-east-1", "aws");
             Cloud other = new Cloud(OTHER_ACCOUNT, "us-east-1", "aws")) {
            String instance = other.node();
            other.iam.updateAssumeRolePolicy(request -> request.roleName(other.name).policyDocument(
                    "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + owner.role
                            + "\"},\"Action\":\"sts:AssumeRole\"}]}"));
            owner.iam.putRolePolicy(request -> request.roleName(owner.name).policyName("assume")
                    .policyDocument("{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"sts:AssumeRole\","
                            + "\"Resource\":\"" + other.role + "\"}]}"));
            try {
                JsonNode result = owner.task("ec2", "describeInstances", Map.of("InstanceIds", List.of(instance)),
                        other.role);
                assertEquals(OTHER_ACCOUNT, result.path("Reservations").path(0).path("OwnerId").asText());
                JsonNode refused = owner.task("ec2", "describeInstances", Map.of("InstanceIds", List.of(instance)));
                assertEquals("Ec2.Ec2Exception", refused.path("Error").asText());
                assertTrue(refused.path("Cause").asText().contains("InvalidInstanceID.NotFound"));
                owner.iam.putRolePolicy(request -> request.roleName(owner.name).policyName("assume")
                        .policyDocument("{\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"sts:AssumeRole\","
                                + "\"Resource\":\"*\"}]}"));
                assertEquals("States.Permissions", owner.task("ec2", "describeInstances",
                        Map.of("InstanceIds", List.of(instance)), other.role).path("Error").asText());
            } finally {
                owner.iam.deleteRolePolicy(request -> request.roleName(owner.name).policyName("assume"));
            }
        }
    }

    @Test
    void missingInvocationUsesTheCatchableSsmExceptionName() throws Exception {
        try (Cloud cloud = new Cloud(ACCOUNT, "eu-west-2", "aws")) {
            JsonNode failure = cloud.task("ssm", "getCommandInvocation", Map.of("CommandId", UUID.randomUUID().toString(),
                    "InstanceId", "i-0123456789abcdef0"));
            assertEquals("Ssm.InvocationDoesNotExistException", failure.path("Error").asText());
        }
    }

    @ParameterizedTest
    @CsvSource({"STANDARD,base", "STANDARD,version", "STANDARD,alias",
            "EXPRESS,base", "EXPRESS,version", "EXPRESS,alias"})
    void qualifiedExecutionsUseTheBaseMachineForServiceTrustAndContext(String type, String selection) throws Exception {
        try (Cloud cloud = new Cloud(ACCOUNT, "eu-west-2", "aws")) {
            String definition = """
                    {"StartAt":"Call","States":{
                      "Call":{"Type":"Task","Resource":"arn:aws:states:::aws-sdk:ec2:describeInstances",
                        "Parameters":{},"ResultPath":"$.response","Next":"Done",
                        "Catch":[{"ErrorEquals":["States.ALL"],"ResultPath":"$.response","Next":"Done"}]},
                      "Done":{"Type":"Pass","Parameters":{"machine.$":"$$.StateMachine.Id","response.$":"$.response"},
                        "End":true}}}
                    """;
            String arn = cloud.sfn.createStateMachine(request -> request.name(cloud.name)
                    .roleArn(cloud.role).type(type).definition(definition).publish(true)).stateMachineArn();
            String version = arn + ":1";
            String alias = null;
            try {
                String selected = "version".equals(selection) ? version : arn;
                if ("alias".equals(selection)) {
                    alias = cloud.sfn.createStateMachineAlias(request -> request.name("stable")
                            .routingConfiguration(route -> route.stateMachineVersionArn(version).weight(100)))
                            .stateMachineAliasArn();
                    selected = alias;
                }
                cloud.trustSource(arn, ACCOUNT);
                JsonNode accepted = cloud.execute(selected, type);
                assertTrue(accepted.path("response").path("Reservations").isArray(), accepted.toString());
                assertEquals(arn, accepted.path("machine").asText());
                assertEquals(version, cloud.sfn.describeStateMachine(request -> request.stateMachineArn(version))
                        .stateMachineArn());

                cloud.trustSource(arn + "-other", ACCOUNT);
                assertEquals("States.Permissions", cloud.execute(selected, type).path("response").path("Error").asText());
                cloud.trustSource(arn, OTHER_ACCOUNT);
                assertEquals("States.Permissions", cloud.execute(selected, type).path("response").path("Error").asText());
                cloud.trustSource(arn, ACCOUNT);
                assertTrue(cloud.execute(selected, type).path("response").path("Reservations").isArray());
            } finally {
                if (alias != null) {
                    String ownedAlias = alias;
                    cloud.sfn.deleteStateMachineAlias(request -> request.stateMachineAliasArn(ownedAlias));
                }
                cloud.sfn.deleteStateMachine(request -> request.stateMachineArn(arn));
            }
        }
    }

    private static final class Cloud implements AutoCloseable {
        private final String account;
        private final String region;
        private final String partition;
        private final String name = "compute-" + UUID.randomUUID();
        private final Ec2Client ec2;
        private final SsmClient ssm;
        private final IamClient iam;
        private final SfnClient sfn;
        private final String role;
        private final List<String> instances = new ArrayList<>();

        private Cloud(String account, String region, String partition) {
            this.account = account;
            this.region = region;
            this.partition = partition;
            URI endpoint = URI.create("http://localhost:" + RestAssured.port);
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(account, "test"));
            ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            iam = IamClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            sfn = SfnClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .overrideConfiguration(config -> config.putAdvancedOption(
                            SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true))
                    .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            role = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                    {"Statement":[{"Effect":"Allow","Principal":{"Service":"states.amazonaws.com"},
                    "Action":"sts:AssumeRole"}]}
                    """)).role().arn();
            iam.putRolePolicy(request -> request.roleName(name).policyName("tasks").policyDocument(ACTIONS));
        }

        private String node() {
            String id = ec2.runInstances(request -> request.imageId("ami-1234567890abcdef0")
                    .instanceType("t3.micro").minCount(1).maxCount(1)).instances().getFirst().instanceId();
            instances.add(id);
            return id;
        }

        private void register(String instance) {
            // Agent-only API absent from the public Java SDK. It advertises
            // readiness; this fixture never sends a successful command reply.
            given().contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "AmazonSSM.UpdateInstanceInformation")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20261105/" + region
                            + "/ssm/aws4_request, SignedHeaders=host, Signature=example")
                    .body(Map.of("InstanceId", instance, "AgentVersion", "3.3.5700.0", "PlatformType", "Linux"))
                    .post("/").then().statusCode(200);
        }

        private JsonNode task(String service, String action, Map<String, Object> parameters) throws Exception {
            return task(service, action, parameters, null);
        }

        private JsonNode task(String service, String action, Map<String, Object> parameters, String credentials)
                throws Exception {
            ObjectNode task = MAPPER.createObjectNode().put("Type", "Task").put("Next", "Done")
                    .put("Resource", "arn:" + partition + ":states:::aws-sdk:" + service + ":" + action);
            task.set("Parameters", MAPPER.valueToTree(parameters));
            task.set("Catch", MAPPER.valueToTree(List.of(Map.of("ErrorEquals", List.of("States.ALL"), "Next", "Done"))));
            if (credentials != null) {
                task.putObject("Credentials").put("RoleArn", credentials);
            }
            String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Call", "States",
                    Map.of("Call", task, "Done", Map.of("Type", "Pass", "End", true))));
            String arn = sfn.createStateMachine(request -> request.name("sdk-" + UUID.randomUUID())
                    .roleArn(role).definition(definition)).stateMachineArn();
            try {
                return execute(arn, "STANDARD");
            } finally {
                sfn.deleteStateMachine(request -> request.stateMachineArn(arn));
            }
        }

        private JsonNode execute(String arn, String type) throws Exception {
            if ("EXPRESS".equals(type)) {
                StartSyncExecutionResponse result = sfn.startSyncExecution(request -> request
                        .stateMachineArn(arn).input("{}"));
                assertEquals("SUCCEEDED", result.statusAsString(), result.toString());
                return MAPPER.readTree(result.output());
            }
            String execution = sfn.startExecution(request -> request.stateMachineArn(arn).input("{}")).executionArn();
            await().atMost(Duration.ofSeconds(15)).until(() -> !"RUNNING".equals(
                    sfn.describeExecution(request -> request.executionArn(execution)).statusAsString()));
            DescribeExecutionResponse result = sfn.describeExecution(request -> request.executionArn(execution));
            assertEquals("SUCCEEDED", result.statusAsString(), result.toString());
            return MAPPER.readTree(result.output());
        }

        private void trustSource(String source, String sourceAccount) {
            iam.updateAssumeRolePolicy(request -> request.roleName(name).policyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Principal":{"Service":"states.amazonaws.com"},"Action":"sts:AssumeRole",
                    "Condition":{"ArnEquals":{"aws:SourceArn":"%s"},"StringEquals":{"aws:SourceAccount":"%s"}}}]}
                    """.formatted(source, sourceAccount)));
        }

        @Override
        public void close() {
            try {
                if (!instances.isEmpty()) {
                    ec2.terminateInstances(request -> request.instanceIds(instances));
                }
                for (String policy : iam.listRolePolicies(request -> request.roleName(name)).policyNames()) {
                    iam.deleteRolePolicy(request -> request.roleName(name).policyName(policy));
                }
                iam.deleteRole(request -> request.roleName(name));
            } finally {
                sfn.close();
                iam.close();
                ssm.close();
                ec2.close();
            }
        }
    }
}

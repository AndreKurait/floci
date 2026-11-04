package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.DeploymentGroupInfo;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudFormationWaitConditionHandleIntegrationTest.StrictResources.class)
class CloudFormationCodeDeployIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String stack = "codedeploy-cfn-" + UUID.randomUUID();
    private String account;
    private CloudFormationClient cfn;
    private CodeDeployClient deployments;

    @BeforeEach
    void clients() {
        account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        deployments = AwsTestClients.create(CodeDeployClient.builder(), account, Region.US_EAST_1);
    }

    @AfterEach
    void cleanup() {
        try {
            if (cfn.describeStacks().stacks().stream().anyMatch(value -> stack.equals(value.stackName()))) {
                await().atMost(Duration.ofSeconds(15)).until(() -> !status().endsWith("_IN_PROGRESS"));
                cfn.deleteStack(request -> request.stackName(stack));
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        assertFalse(cfn.describeStacks().stacks().stream()
                                .anyMatch(value -> stack.equals(value.stackName()))));
            }
            assertTrue(deployments.listApplications().applications().isEmpty());
        } finally {
            deployments.close();
            cfn.close();
        }
    }

    @Test
    void createsRealApplicationAndGroupWithResolvedReferences() {
        cfn.createStack(request -> request.stackName(stack).capabilitiesWithStrings("CAPABILITY_IAM")
                .templateBody("""
                        {"Resources":{
                          "Application":{"Type":"AWS::CodeDeploy::Application","Properties":{
                            "ComputePlatform":"Server"}},
                          "Role":{"Type":"AWS::IAM::Role","Properties":{
                            "AssumeRolePolicyDocument":{"Version":"2012-10-17","Statement":[{
                              "Effect":"Allow","Principal":{"Service":"codedeploy.amazonaws.com"},
                              "Action":"sts:AssumeRole"}]}}},
                          "Group":{"Type":"AWS::CodeDeploy::DeploymentGroup","Properties":{
                            "ApplicationName":{"Ref":"Application"},
                            "ServiceRoleArn":{"Fn::GetAtt":["Role","Arn"]},
                            "DeploymentConfigName":"CodeDeployDefault.AllAtOnce"}}
                        },"Outputs":{
                          "Application":{"Value":{"Ref":"Application"}},
                          "Group":{"Value":{"Ref":"Group"}},
                          "Role":{"Value":{"Fn::GetAtt":["Role","Arn"]}}
                        }}
                        """));
        await().atMost(Duration.ofSeconds(15)).until(() -> !status().endsWith("_IN_PROGRESS"));
        assertEquals("CREATE_COMPLETE", status(), () ->
                cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents().toString());
        Map<String, String> outputs = cfn.describeStacks(request -> request.stackName(stack)).stacks()
                .getFirst().outputs().stream().collect(Collectors.toMap(value -> value.outputKey(),
                        value -> value.outputValue()));
        assertEquals("Server", deployments.getApplication(request -> request
                .applicationName(outputs.get("Application"))).application().computePlatformAsString());
        DeploymentGroupInfo group = deployments.getDeploymentGroup(request -> request
                .applicationName(outputs.get("Application")).deploymentGroupName(outputs.get("Group")))
                .deploymentGroupInfo();
        assertEquals(outputs.get("Application"), group.applicationName());
        assertEquals(outputs.get("Group"), group.deploymentGroupName());
        assertEquals(outputs.get("Role"), group.serviceRoleArn());
        assertEquals("CodeDeployDefault.AllAtOnce", group.deploymentConfigName());
        assertNotNull(group.deploymentGroupId());
    }

    @Test
    void updatesNestedConfigurationAndClearsDroppedFieldsWithoutReplacingTheGroup() throws Exception {
        ObjectNode template = template();
        ObjectNode properties = properties(template, "Group");
        properties.set("AutoScalingGroups", MAPPER.readTree("[{\"Ref\":\"Fleet\"}]"));
        properties.set("AlarmConfiguration", MAPPER.readTree("""
                {"Enabled":"true","IgnorePollAlarmFailure":false,"Alarms":[{"Name":"deployment-alarm"}]}
                """));
        properties.set("AutoRollbackConfiguration", MAPPER.readTree("""
                {"Enabled":true,"Events":["DEPLOYMENT_FAILURE"]}
                """));
        properties.set("LoadBalancerInfo", MAPPER.readTree("""
                {"ElbInfoList":[{"Name":"frontend"}],"TargetGroupInfoList":[]}
                """));
        properties.set("Ec2TagSet", MAPPER.readTree("""
                {"Ec2TagSetList":[{"Ec2TagGroup":[{"Key":"stage","Value":"local","Type":"KEY_AND_VALUE"}]}]}
                """));
        properties.set("Tags", MAPPER.readTree("[{\"Key\":\"old\",\"Value\":\"remove\"}]"));
        create(template);
        Map<String, String> original = outputs(cfn);
        DeploymentGroupInfo before = group(deployments, original);
        assertEquals(List.of(original.get("Fleet")), before.autoScalingGroups().stream()
                .map(value -> value.name()).toList());
        assertTrue(before.alarmConfiguration().enabled());
        assertEquals("deployment-alarm", before.alarmConfiguration().alarms().getFirst().name());
        assertEquals("frontend", before.loadBalancerInfo().elbInfoList().getFirst().name());
        assertEquals("stage", before.ec2TagSet().ec2TagSetList().getFirst().getFirst().key());
        assertEquals("local", before.ec2TagSet().ec2TagSetList().getFirst().getFirst().value());
        assertEquals("Server", before.computePlatformAsString());

        properties.remove(List.of("AutoScalingGroups", "AlarmConfiguration", "AutoRollbackConfiguration",
                "LoadBalancerInfo", "Ec2TagSet", "Tags"));
        properties.put("DeploymentConfigName", "CodeDeployDefault.HalfAtATime");
        properties.put("OutdatedInstancesStrategy", "IGNORE");
        update(template, "UPDATE_COMPLETE");
        DeploymentGroupInfo after = group(deployments, outputs(cfn));
        assertEquals(before.deploymentGroupId(), after.deploymentGroupId());
        assertEquals(original, outputs(cfn));
        assertTrue(after.autoScalingGroups().isEmpty());
        assertEquals(null, after.alarmConfiguration());
        assertEquals(null, after.autoRollbackConfiguration());
        assertEquals(null, after.loadBalancerInfo());
        assertEquals(null, after.ec2TagSet());
        assertEquals("CodeDeployDefault.HalfAtATime", after.deploymentConfigName());
        assertEquals("IGNORE", after.outdatedInstancesStrategyAsString());
        assertTrue(deployments.listTagsForResource(request -> request.resourceArn(groupArn(original)))
                .tags().isEmpty());
    }

    @Test
    void restoresUpdatedConfigurationAndTagsWhenALaterResourceFails() throws Exception {
        ObjectNode template = template();
        properties(template, "Application").set("Tags", MAPPER.readTree("[{\"Key\":\"app\",\"Value\":\"before\"}]"));
        properties(template, "Group").set("Tags", MAPPER.readTree("[{\"Key\":\"group\",\"Value\":\"before\"}]"));
        create(template);
        Map<String, String> original = outputs(cfn);
        String groupId = group(deployments, original).deploymentGroupId();
        properties(template, "Application").remove("Tags");
        properties(template, "Group").remove("Tags");
        properties(template, "Group").put("DeploymentConfigName", "CodeDeployDefault.HalfAtATime");
        failAfterGroup(template);
        update(template, "UPDATE_ROLLBACK_COMPLETE");
        DeploymentGroupInfo restored = group(deployments, original);
        assertEquals(groupId, restored.deploymentGroupId());
        assertEquals("CodeDeployDefault.AllAtOnce", restored.deploymentConfigName());
        assertEquals("before", deployments.listTagsForResource(request -> request
                .resourceArn(groupArn(original))).tags().getFirst().value());
        assertEquals("before", deployments.listTagsForResource(request -> request.resourceArn(
                "arn:aws:codedeploy:us-east-1:" + account + ":application:" + original.get("Application")))
                .tags().getFirst().value());
    }

    @Test
    void replacesGeneratedApplicationAndGroupThenDeletesOnlyTheDisplacedEntities() throws Exception {
        ObjectNode template = template();
        create(template);
        Map<String, String> before = outputs(cfn);
        properties(template, "Application").put("ComputePlatform", "Lambda");
        update(template, "UPDATE_COMPLETE");
        Map<String, String> after = outputs(cfn);
        assertNotEquals(before.get("Application"), after.get("Application"));
        assertNotEquals(before.get("Group"), after.get("Group"));
        assertEquals(List.of(after.get("Application")), deployments.listApplications().applications());
        assertEquals("Lambda", group(deployments, after).computePlatformAsString());
        assertEquals(List.of(after.get("Group")), deployments.listDeploymentGroups(request -> request
                .applicationName(after.get("Application"))).deploymentGroups());
    }

    @Test
    void restoresTheOriginalApplicationAndGroupAfterAFailedReplacement() throws Exception {
        ObjectNode template = template();
        create(template);
        Map<String, String> before = outputs(cfn);
        String groupId = group(deployments, before).deploymentGroupId();
        properties(template, "Application").put("ComputePlatform", "Lambda");
        failAfterGroup(template);
        update(template, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(before, outputs(cfn));
        assertEquals(groupId, group(deployments, before).deploymentGroupId());
        assertEquals(List.of(before.get("Application")), deployments.listApplications().applications());
        assertEquals(List.of(before.get("Group")), deployments.listDeploymentGroups(request -> request
                .applicationName(before.get("Application"))).deploymentGroups());
    }

    @Test
    void refusesReplacementOfAnExplicitNameWithoutChangingTheExistingEntity() throws Exception {
        ObjectNode template = template();
        properties(template, "Application").put("ApplicationName", stack);
        create(template);
        Map<String, String> before = outputs(cfn);
        String applicationId = deployments.getApplication(request -> request
                .applicationName(stack)).application().applicationId();
        properties(template, "Application").put("ComputePlatform", "Lambda");
        update(template, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(before, outputs(cfn));
        assertEquals(applicationId, deployments.getApplication(request -> request
                .applicationName(stack)).application().applicationId());
        assertEquals("Server", group(deployments, before).computePlatformAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Deployment", "Ec2DeploymentOptions"})
    void refusesUnimplementedDeploymentExecutionBeforeCreatingAGroup(String property) throws Exception {
        ObjectNode template = template();
        properties(template, "Group").putObject(property);
        cfn.createStack(request -> request.stackName(stack).capabilitiesWithStrings("CAPABILITY_IAM")
                .templateBody(template.toString()));
        awaitStatus(cfn, "ROLLBACK_COMPLETE");
        assertTrue(deployments.listApplications().applications().isEmpty());
        assertTrue(cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents().stream()
                .anyMatch(event -> "CREATE_FAILED".equals(event.resourceStatusAsString())
                        && event.resourceStatusReason().contains(property)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identicalNamesRemainIndependentAcrossAccountsAndRegions(boolean otherRegion) throws Exception {
        ObjectNode template = template();
        properties(template, "Application").put("ApplicationName", stack);
        properties(template, "Group").put("DeploymentGroupName", "group");
        create(template);
        String foreignAccount = otherRegion ? account
                : String.format(Locale.ROOT, "%012d", (Long.parseLong(account) + 1) % 1_000_000_000_000L);
        Region region = otherRegion ? Region.US_WEST_2 : Region.US_EAST_1;
        try (CloudFormationClient foreignCfn = AwsTestClients.create(
                CloudFormationClient.builder(), foreignAccount, region);
             CodeDeployClient foreignDeployments = AwsTestClients.create(
                     CodeDeployClient.builder(), foreignAccount, region)) {
            try {
                foreignCfn.createStack(request -> request.stackName(stack).capabilitiesWithStrings("CAPABILITY_IAM")
                        .templateBody(template.toString()));
                awaitStatus(foreignCfn, "CREATE_COMPLETE");
                Map<String, String> foreignOutputs = outputs(foreignCfn);
                String foreignId = group(foreignDeployments, foreignOutputs).deploymentGroupId();
                assertNotEquals(group(deployments, outputs(cfn)).deploymentGroupId(), foreignId);
                deleteStack(cfn);
                assertEquals(foreignId, group(foreignDeployments, foreignOutputs).deploymentGroupId());
            } finally {
                deleteStack(foreignCfn);
                assertTrue(foreignDeployments.listApplications().applications().isEmpty());
            }
        }
    }

    @Test
    void deletingAndRecreatingTheSameNamesDoesNotInheritOldTags() throws Exception {
        ObjectNode template = template();
        properties(template, "Application").put("ApplicationName", stack);
        properties(template, "Group").put("DeploymentGroupName", "group");
        for (String logicalId : List.of("Application", "Group")) {
            properties(template, logicalId).set("Tags", MAPPER.readTree("[{\"Key\":\"old\",\"Value\":\"remove\"}]"));
        }
        create(template);
        String originalGroup = group(deployments, outputs(cfn)).deploymentGroupId();
        deleteStack(cfn);
        for (String logicalId : List.of("Application", "Group")) {
            properties(template, logicalId).remove("Tags");
        }
        create(template);
        Map<String, String> current = outputs(cfn);
        assertNotEquals(originalGroup, group(deployments, current).deploymentGroupId());
        assertTrue(deployments.listTagsForResource(request -> request.resourceArn(groupArn(current))).tags().isEmpty());
        assertTrue(deployments.listTagsForResource(request -> request.resourceArn(
                "arn:aws:codedeploy:us-east-1:" + account + ":application:" + stack)).tags().isEmpty());
    }

    private ObjectNode template() throws JsonProcessingException {
        return (ObjectNode) MAPPER.readTree("""
                {"Resources":{
                  "Application":{"Type":"AWS::CodeDeploy::Application","Properties":{}},
                  "Role":{"Type":"AWS::IAM::Role","Properties":{
                    "AssumeRolePolicyDocument":{"Version":"2012-10-17","Statement":[{
                      "Effect":"Allow","Principal":{"Service":"codedeploy.amazonaws.com"},
                      "Action":"sts:AssumeRole"}]}}},
                  "Launch":{"Type":"AWS::AutoScaling::LaunchConfiguration","Properties":{
                    "ImageId":"ami-codedeploy","InstanceType":"t3.nano"}},
                  "Fleet":{"Type":"AWS::AutoScaling::AutoScalingGroup","Properties":{
                    "LaunchConfigurationName":{"Ref":"Launch"},"MinSize":0,"MaxSize":1,
                    "AvailabilityZones":[{"Fn::Sub":"${AWS::Region}a"}]}},
                  "Group":{"Type":"AWS::CodeDeploy::DeploymentGroup","Properties":{
                    "ApplicationName":{"Ref":"Application"},"ServiceRoleArn":{"Fn::GetAtt":["Role","Arn"]},
                    "DeploymentConfigName":"CodeDeployDefault.AllAtOnce"}}
                },"Outputs":{
                  "Application":{"Value":{"Ref":"Application"}},"Group":{"Value":{"Ref":"Group"}},
                  "Fleet":{"Value":{"Ref":"Fleet"}},"Role":{"Value":{"Fn::GetAtt":["Role","Arn"]}}
                }}
                """);
    }

    private static ObjectNode properties(ObjectNode template, String logicalId) {
        return (ObjectNode) template.path("Resources").path(logicalId).path("Properties");
    }

    private static void failAfterGroup(ObjectNode template) {
        ((ObjectNode) template.path("Resources")).putObject("Failure")
                .put("Type", "AWS::FlociTest::Unavailable").put("DependsOn", "Group");
    }

    private void create(ObjectNode template) {
        cfn.createStack(request -> request.stackName(stack).capabilitiesWithStrings("CAPABILITY_IAM")
                .templateBody(template.toString()));
        awaitStatus(cfn, "CREATE_COMPLETE");
    }

    private void update(ObjectNode template, String expected) {
        cfn.updateStack(request -> request.stackName(stack).capabilitiesWithStrings("CAPABILITY_IAM")
                .templateBody(template.toString()));
        awaitStatus(cfn, expected);
    }

    private void awaitStatus(CloudFormationClient client, String expected) {
        await().atMost(Duration.ofSeconds(15)).until(() ->
                !client.describeStacks(request -> request.stackName(stack)).stacks()
                        .getFirst().stackStatusAsString().endsWith("_IN_PROGRESS"));
        assertEquals(expected, client.describeStacks(request -> request.stackName(stack)).stacks()
                .getFirst().stackStatusAsString(), () ->
                client.describeStackEvents(request -> request.stackName(stack)).stackEvents().toString());
    }

    private void deleteStack(CloudFormationClient client) {
        if (client.describeStacks().stacks().stream().noneMatch(value -> stack.equals(value.stackName()))) {
            return;
        }
        await().atMost(Duration.ofSeconds(15)).until(() -> !client.describeStacks(request -> request.stackName(stack))
                .stacks().getFirst().stackStatusAsString().endsWith("_IN_PROGRESS"));
        client.deleteStack(request -> request.stackName(stack));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertFalse(client.describeStacks().stacks().stream()
                .anyMatch(value -> stack.equals(value.stackName()))));
    }

    private Map<String, String> outputs(CloudFormationClient client) {
        return client.describeStacks(request -> request.stackName(stack)).stacks().getFirst().outputs().stream()
                .collect(Collectors.toMap(value -> value.outputKey(), value -> value.outputValue()));
    }

    private static DeploymentGroupInfo group(CodeDeployClient client, Map<String, String> outputs) {
        return client.getDeploymentGroup(request -> request.applicationName(outputs.get("Application"))
                .deploymentGroupName(outputs.get("Group"))).deploymentGroupInfo();
    }

    private String groupArn(Map<String, String> outputs) {
        return "arn:aws:codedeploy:us-east-1:" + account + ":deploymentgroup:"
                + outputs.get("Application") + "/" + outputs.get("Group");
    }

    private String status() {
        return cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst().stackStatusAsString();
    }
}

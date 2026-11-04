package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationSecurityGroupRollbackIntegrationTest {

    private final String name = "cfn-group-rollback-" + UUID.randomUUID();
    private CloudFormationClient cfn;
    private Ec2Client ec2;
    private String vpcId;
    private String outsideGroupId;
    private String stackId;

    @BeforeEach
    void createFoundation() {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
        vpcId = ec2.createVpc(request -> request.cidrBlock("10.91.0.0/16")).vpc().vpcId();
        outsideGroupId = ec2.createSecurityGroup(request -> request.vpcId(vpcId)
                .groupName(name + "-outside").description("External group")).groupId();
    }

    @AfterEach
    void cleanup() {
        try {
            if (stackId != null) {
                cfn.deleteStack(request -> request.stackName(stackId));
                awaitStatus("DELETE_COMPLETE");
            }
            // These belong to this test's VPC, including anything a regression leaked.
            for (SecurityGroup group : groups()) {
                ec2.deleteSecurityGroup(request -> request.groupId(group.groupId()));
            }
            ec2.deleteVpc(request -> request.vpcId(vpcId));
        } finally {
            ec2.close();
            cfn.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SecurityGroupIngress", "SecurityGroupEgress"})
    void failedInlineRuleDoesNotLeaveAnAllocatedGroup(String failedProperty) {
        stackId = cfn.createStack(request -> request.stackName(name)
                .templateBody(template(name + "-created", failedProperty))).stackId();
        awaitStatus("ROLLBACK_COMPLETE");

        assertEquals(List.of(outsideGroupId), groupIds(),
                "A failed inline rule must not leak its newly allocated group");
        assertMissingExportFailure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SecurityGroupIngress", "SecurityGroupEgress"})
    void failedReplacementRemovesTheNewGroupAndPreservesTheOriginal(String failedProperty) {
        stackId = cfn.createStack(request -> request.stackName(name)
                .templateBody(template(name + "-original", null))).stackId();
        awaitStatus("CREATE_COMPLETE");
        String originalId = cfn.describeStackResource(request -> request.stackName(stackId)
                .logicalResourceId("Web")).stackResourceDetail().physicalResourceId();
        SecurityGroup original = ec2.describeSecurityGroups(request -> request.groupIds(originalId))
                .securityGroups().getFirst();

        cfn.updateStack(request -> request.stackName(stackId)
                .templateBody(template(name + "-replacement", failedProperty)));
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");

        assertEquals(List.of(originalId, outsideGroupId).stream().sorted().toList(), groupIds(),
                "Rollback must retain the original and external groups, without the failed replacement");
        SecurityGroup restored = ec2.describeSecurityGroups(request -> request.groupIds(originalId))
                .securityGroups().getFirst();
        assertEquals(original, restored);
        assertEquals(originalId, cfn.describeStackResource(request -> request.stackName(stackId)
                .logicalResourceId("Web")).stackResourceDetail().physicalResourceId());
        assertMissingExportFailure();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulReplacementRetiresTheOldGroupUnlessRetained(boolean retain) {
        stackId = cfn.createStack(request -> request.stackName(name)
                .templateBody(template(name + "-original", null))).stackId();
        awaitStatus("CREATE_COMPLETE");
        String originalId = cfn.describeStackResource(request -> request.stackName(stackId)
                .logicalResourceId("Web")).stackResourceDetail().physicalResourceId();
        List<String> expected = new ArrayList<>(List.of(outsideGroupId));
        if (retain) {
            expected.add(originalId);
        }

        String previousId = originalId;
        for (int update = 1; update <= 2; update++) {
            String body = template(name + "-replacement-" + update, null);
            if (retain) {
                body = body.replace("\"Web\":{", "\"Web\":{\"UpdateReplacePolicy\":\"Retain\",");
            }
            String templateBody = body;
            cfn.updateStack(request -> request.stackName(stackId).templateBody(templateBody));
            awaitStatus("UPDATE_COMPLETE");
            String currentId = cfn.describeStackResource(request -> request.stackName(stackId)
                    .logicalResourceId("Web")).stackResourceDetail().physicalResourceId();
            assertNotEquals(previousId, currentId);
            expected.add(currentId);
            assertEquals(expected.stream().sorted().toList(), groupIds(),
                    "Replacement cleanup must honor retention and preserve the external group");
            if (!retain) {
                expected.remove(currentId);
            }
            previousId = currentId;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterFailureRestoresTheOriginalGroupEvenWithRetention(boolean retain) {
        stackId = cfn.createStack(request -> request.stackName(name)
                .templateBody(template(name + "-original", null))).stackId();
        awaitStatus("CREATE_COMPLETE");
        String originalId = cfn.describeStackResource(request -> request.stackName(stackId)
                .logicalResourceId("Web")).stackResourceDetail().physicalResourceId();
        SecurityGroup original = ec2.describeSecurityGroups(request -> request.groupIds(originalId))
                .securityGroups().getFirst();
        String body = template(name + "-replacement", null);
        if (retain) {
            body = body.replace("\"Web\":{", "\"Web\":{\"UpdateReplacePolicy\":\"Retain\",");
        }
        String replacement = body.stripTrailing();
        String failingUpdate = replacement.substring(0, replacement.length() - 2) + """
                ,"Later":{"Type":"AWS::SSM::Parameter","DependsOn":"Web","Properties":{
                  "Name":"%s-later","Type":"String","Value":{"Fn::ImportValue":"%s-missing"}}}}}
                """.formatted(name, name);

        cfn.updateStack(request -> request.stackName(stackId).templateBody(failingUpdate));
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");

        assertEquals(List.of(originalId, outsideGroupId).stream().sorted().toList(), groupIds());
        assertEquals(original, ec2.describeSecurityGroups(request -> request.groupIds(originalId))
                .securityGroups().getFirst());
        assertEquals(originalId, cfn.describeStackResource(request -> request.stackName(stackId)
                .logicalResourceId("Web")).stackResourceDetail().physicalResourceId());
        assertMissingExportFailure();
    }

    private void assertMissingExportFailure() {
        assertTrue(cfn.describeStackEvents(request -> request.stackName(stackId)).stackEvents().stream()
                .anyMatch(event -> event.resourceStatusReason() != null
                        && event.resourceStatusReason().contains(name + "-missing")),
                "The failure must be the unresolved inline-rule export");
    }

    private List<String> groupIds() {
        return groups().stream().map(SecurityGroup::groupId).sorted().toList();
    }

    private List<SecurityGroup> groups() {
        return ec2.describeSecurityGroups(request -> request.filters(filter ->
                        filter.name("vpc-id").values(vpcId))).securityGroups().stream()
                .filter(group -> !"default".equals(group.groupName())).toList();
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(expected, cfn.describeStacks(request -> request.stackName(stackId))
                        .stacks().getFirst().stackStatusAsString()));
        if ("DELETE_COMPLETE".equals(expected)) {
            CloudFormationException missing = assertThrows(CloudFormationException.class,
                    () -> cfn.describeStacks(request -> request.stackName(name)));
            assertEquals("ValidationError", missing.awsErrorDetails().errorCode());
        }
    }

    private String template(String groupName, String failedProperty) {
        String ingress = """
                {"IpProtocol":"tcp","FromPort":443,"ToPort":443,"CidrIp":"192.0.2.1/32"}
                """;
        String egress = """
                {"IpProtocol":"tcp","FromPort":443,"ToPort":443,"CidrIp":"198.51.100.1/32"}
                """;
        if (failedProperty != null) {
            String peer = "SecurityGroupIngress".equals(failedProperty)
                    ? "SourceSecurityGroupId" : "DestinationSecurityGroupId";
            String broken = """
                    {"IpProtocol":"tcp","FromPort":443,"ToPort":443,"%s":{"Fn::ImportValue":"%s-missing"}}
                    """.formatted(peer, name);
            if ("SecurityGroupIngress".equals(failedProperty)) {
                ingress = broken;
            } else {
                egress = broken;
            }
        }
        return """
                {"Resources":{"Web":{"Type":"AWS::EC2::SecurityGroup","Properties":{
                  "VpcId":"%s","GroupName":"%s","GroupDescription":"Web tier",
                  "SecurityGroupIngress":[%s],"SecurityGroupEgress":[%s]}}}}
                """.formatted(vpcId, groupName, ingress, egress);
    }
}

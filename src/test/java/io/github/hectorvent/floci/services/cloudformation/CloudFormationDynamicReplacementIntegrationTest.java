package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.ResourceChange;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationDynamicReplacementIntegrationTest {

    private static final String ORIGINAL_CIDR = "192.0.2.10/32";
    private static final String CHANGED_CIDR = "192.0.2.11/32";
    private final String name = "cfn-dynamic-replacement-" + UUID.randomUUID();
    private final List<String> stacks = new ArrayList<>();
    private CloudFormationClient cfn;
    private Ec2Client ec2;
    private String vpcId;
    private String groupId;
    private String producer;

    private void createFoundation(String region) {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.of(region));
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.of(region));
        vpcId = ec2.createVpc(request -> request.cidrBlock("10.91.0.0/16")).vpc().vpcId();
        groupId = ec2.createSecurityGroup(request -> request.vpcId(vpcId)
                .groupName(name).description("External rule target")).groupId();
        producer = name + "-source";
    }

    @AfterEach
    void cleanup() {
        try {
            if (cfn != null) {
                for (String stack : stacks.reversed()) {
                    cfn.deleteStack(request -> request.stackName(stack));
                    awaitStatus(stack, "DELETE_COMPLETE");
                }
            }
            if (ec2 != null) {
                if (groupId != null) {
                    ec2.deleteSecurityGroup(request -> request.groupId(groupId));
                }
                if (vpcId != null) {
                    ec2.deleteVpc(request -> request.vpcId(vpcId));
                }
            }
        } finally {
            if (ec2 != null) {
                ec2.close();
            }
            if (cfn != null) {
                cfn.close();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "us-east-1, GetStackOutput, false",
            "us-east-1, GetStackOutput, true",
            "us-gov-west-1, GetStackOutput, false",
            "us-gov-west-1, GetStackOutput, true",
            "us-east-1, ImportValue, false",
            "us-east-1, ImportValue, true"
    })
    void outputReferenceReplacementDependsOnItsValueAtExecution(String region, String function, boolean changed) {
        createFoundation(region);
        String value = changed ? CHANGED_CIDR : ORIGINAL_CIDR;
        create(producer, producerTemplate(value));
        String consumer = name + "-consumer";
        create(consumer, ruleTemplate(quoted(ORIGINAL_CIDR)));
        SecurityGroupRule before = rule(physicalId(consumer, "Rule"));

        DescribeChangeSetResponse preview = preview(consumer, ruleTemplate(outputReference(function)));
        ResourceChange change = change(preview, "Rule");
        assertEquals("Modify", change.actionAsString());
        assertEquals("Conditional", change.replacementAsString());
        assertEquals(before.securityGroupRuleId(), change.physicalResourceId());
        assertEquals(before, rule(before.securityGroupRuleId()), "Preview must not change access");

        execute(consumer, preview);
        SecurityGroupRule after = rule(physicalId(consumer, "Rule"));
        assertEquals(value, after.cidrIpv4());
        if (changed) {
            assertNotEquals(before.securityGroupRuleId(), after.securityGroupRuleId());
            assertTrue(ec2.describeSecurityGroupRules(request ->
                            request.filters(filter -> filter.name("group-id").values(groupId)))
                    .securityGroupRules().stream()
                    .noneMatch(item -> before.securityGroupRuleId().equals(item.securityGroupRuleId())));
        } else {
            assertEquals(before, after);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staticExpressionsRetainStaticReplacementCertainty(boolean changed) {
        createFoundation("us-east-1");
        String consumer = name + "-consumer";
        create(consumer, ruleTemplate(quoted(ORIGINAL_CIDR)));
        String original = physicalId(consumer, "Rule");
        String expression = changed ? quoted(CHANGED_CIDR)
                : "{\"Fn::Join\":[\"\",[\"192.0.2.\",\"10/32\"]]}";
        String template = ruleTemplate(expression).replace("\"original\"", "\"updated\"");

        DescribeChangeSetResponse preview = preview(consumer, template);
        assertEquals(changed ? "True" : "False", change(preview, "Rule").replacementAsString());
        assertEquals("original", rule(original).description());
        execute(consumer, preview);
        String current = physicalId(consumer, "Rule");
        if (changed) {
            assertNotEquals(original, current);
        } else {
            assertEquals(original, current);
        }
        assertEquals("updated", rule(current).description());
    }

    @Test
    void unchangedWeakExpressionCanReadANewValueDuringAnUpdate() {
        createFoundation("us-east-1");
        create(producer, producerTemplate(ORIGINAL_CIDR));
        String consumer = name + "-consumer";
        String template = ruleTemplate(outputReference("GetStackOutput"));
        create(consumer, template);
        String original = physicalId(consumer, "Rule");

        cfn.updateStack(request -> request.stackName(producer).templateBody(producerTemplate(CHANGED_CIDR)));
        awaitStatus(producer, "UPDATE_COMPLETE");
        assertEquals(ORIGINAL_CIDR, rule(original).cidrIpv4(), "The source update does not update the consumer");
        DescribeChangeSetResponse preview = preview(consumer, template.replace("\"original\"", "\"updated\""));
        assertEquals("Conditional", change(preview, "Rule").replacementAsString());
        assertEquals(ORIGINAL_CIDR, rule(original).cidrIpv4());

        execute(consumer, preview);
        String current = physicalId(consumer, "Rule");
        assertNotEquals(original, current);
        assertEquals(CHANGED_CIDR, rule(current).cidrIpv4());
        assertEquals("updated", rule(current).description());
    }

    @Test
    void unavailableOutputIsDeferredToExecutionWithoutChangingTheExistingPermission() {
        createFoundation("us-east-1");
        create(producer, producerTemplate(ORIGINAL_CIDR).replace("\"PublishedValue\"", "\"Unavailable\""));
        String consumer = name + "-consumer";
        create(consumer, ruleTemplate(quoted(ORIGINAL_CIDR)));
        SecurityGroupRule original = rule(physicalId(consumer, "Rule"));

        DescribeChangeSetResponse preview = preview(consumer, ruleTemplate(outputReference("GetStackOutput")));
        assertEquals("Conditional", change(preview, "Rule").replacementAsString());
        assertEquals(original, rule(original.securityGroupRuleId()));
        cfn.executeChangeSet(request -> request.changeSetName(preview.changeSetId()));
        awaitStatus(consumer, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(original.securityGroupRuleId(), physicalId(consumer, "Rule"));
        assertEquals(original, rule(original.securityGroupRuleId()));
        assertTrue(cfn.describeStackEvents(request -> request.stackName(consumer)).stackEvents().stream()
                .anyMatch(event -> event.resourceStatusReason() != null
                        && event.resourceStatusReason().contains("PublishedValue")));
    }

    @Test
    void addingACreateOnlyPropertyIsADefiniteReplacement() {
        createFoundation("us-east-1");
        String consumer = name + "-queue";
        create(consumer, "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}");
        String original = physicalId(consumer, "Queue");
        DescribeChangeSetResponse preview = preview(consumer, """
                {"Resources":{"Queue":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"%s-explicit"}}}}
                """.formatted(name));
        assertEquals("True", change(preview, "Queue").replacementAsString());
        execute(consumer, preview);
        assertNotEquals(original, physicalId(consumer, "Queue"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dependencyReplacementKeepsItsCertaintyEvenWhenTheDependentAlreadyChanges(boolean definite) {
        createFoundation("us-east-1");
        create(producer, producerTemplate(vpcId));
        String consumer = name + "-dependent";
        create(consumer, dependentTemplate(quoted(vpcId), name + "-managed", "original"));
        String originalGroup = physicalId(consumer, "Group");
        String originalRule = physicalId(consumer, "Rule");

        DescribeChangeSetResponse preview = preview(consumer, dependentTemplate(outputReference("GetStackOutput"),
                name + (definite ? "-replacement" : "-managed"), "updated"));
        String expected = definite ? "True" : "Conditional";
        assertEquals(expected, change(preview, "Group").replacementAsString());
        assertEquals(expected, change(preview, "Rule").replacementAsString());
        assertEquals("original", rule(originalRule).description());

        execute(consumer, preview);
        if (definite) {
            assertNotEquals(originalGroup, physicalId(consumer, "Group"));
            assertNotEquals(originalRule, physicalId(consumer, "Rule"));
            assertTrue(ec2.describeSecurityGroups(request -> request.filters(
                            filter -> filter.name("vpc-id").values(vpcId))).securityGroups().stream()
                    .noneMatch(group -> originalGroup.equals(group.groupId())),
                    "The completed update must retire the displaced group");
        } else {
            assertEquals(originalGroup, physicalId(consumer, "Group"));
            assertEquals(originalRule, physicalId(consumer, "Rule"));
        }
        assertEquals("updated", rule(physicalId(consumer, "Rule")).description());
    }

    private String producerTemplate(String value) {
        return """
                {"Resources":{"Marker":{"Type":"AWS::SSM::Parameter",
                  "Properties":{"Name":"%s-marker","Type":"String","Value":"source"}}},
                 "Outputs":{"PublishedValue":{"Value":"%s","Export":{"Name":"%s-value"}}}}
                """.formatted(producer, value, producer);
    }

    private String outputReference(String function) {
        return "GetStackOutput".equals(function)
                ? "{\"Fn::GetStackOutput\":{\"StackName\":\"" + producer + "\",\"OutputName\":\"PublishedValue\"}}"
                : "{\"Fn::ImportValue\":\"" + producer + "-value\"}";
    }

    private String ruleTemplate(String cidr) {
        return """
                {"Resources":{"Rule":{"Type":"AWS::EC2::SecurityGroupIngress",
                  "Properties":{"GroupId":"%s","IpProtocol":"tcp","FromPort":443,"ToPort":443,
                    "CidrIp":%s,"Description":"original"}}}}
                """.formatted(groupId, cidr);
    }

    private String dependentTemplate(String vpc, String groupName, String description) {
        return """
                {"Resources":{
                  "Group":{"Type":"AWS::EC2::SecurityGroup","Properties":{
                    "VpcId":%s,"GroupName":"%s","GroupDescription":"Managed group"}},
                  "Rule":{"Type":"AWS::EC2::SecurityGroupIngress","Properties":{
                    "GroupId":{"Ref":"Group"},"IpProtocol":"tcp","FromPort":443,"ToPort":443,
                    "CidrIp":"192.0.2.10/32","Description":"%s"}}}}
                """.formatted(vpc, groupName, description);
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private void create(String stack, String template) {
        stacks.add(cfn.createStack(request -> request.stackName(stack).templateBody(template)).stackId());
        awaitStatus(stack, "CREATE_COMPLETE");
    }

    private DescribeChangeSetResponse preview(String stack, String template) {
        String id = cfn.createChangeSet(request -> request.stackName(stack).changeSetName("preview")
                .changeSetType("UPDATE").templateBody(template)).id();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertEquals("CREATE_COMPLETE",
                        cfn.describeChangeSet(request -> request.changeSetName(id)).statusAsString()));
        return cfn.describeChangeSet(request -> request.changeSetName(id));
    }

    private static ResourceChange change(DescribeChangeSetResponse preview, String logicalId) {
        Map<String, ResourceChange> changes = preview.changes().stream().map(item -> item.resourceChange())
                .collect(Collectors.toMap(ResourceChange::logicalResourceId, item -> item));
        assertTrue(changes.containsKey(logicalId), () -> "Missing resource in preview: " + changes);
        return changes.get(logicalId);
    }

    private void execute(String stack, DescribeChangeSetResponse preview) {
        cfn.executeChangeSet(request -> request.changeSetName(preview.changeSetId()));
        awaitStatus(stack, "UPDATE_COMPLETE");
    }

    private String physicalId(String stack, String logicalId) {
        return cfn.describeStackResource(request -> request.stackName(stack).logicalResourceId(logicalId))
                .stackResourceDetail().physicalResourceId();
    }

    private SecurityGroupRule rule(String ruleId) {
        return ec2.describeSecurityGroupRules(request -> request.securityGroupRuleIds(ruleId))
                .securityGroupRules().getFirst();
    }

    private void awaitStatus(String stack, String status) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertEquals(status,
                        cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst()
                                .stackStatusAsString()));
    }
}

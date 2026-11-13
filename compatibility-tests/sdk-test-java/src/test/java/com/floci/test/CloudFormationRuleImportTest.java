package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CreateChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.ResourceToImport;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudFormation existing security-group rule import")
class CloudFormationRuleImportTest {

    private CloudFormationClient cfn;
    private Ec2Client ec2;
    private String name;
    private String stack;
    private String group;
    private String vpc;

    @BeforeEach
    void clients() {
        cfn = TestFixtures.cloudFormationClient();
        ec2 = TestFixtures.ec2Client();
        name = TestFixtures.uniqueName("compat-rule-import");
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        try {
            if (stack != null) {
                cfn.deleteStack(request -> request.stackName(stack));
                awaitStatus("DELETE_COMPLETE");
            }
            if (group != null) {
                ec2.deleteSecurityGroup(request -> request.groupId(group));
            }
            if (vpc != null) {
                ec2.deleteVpc(request -> request.vpcId(vpc));
            }
        } finally {
            cfn.close();
            ec2.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("adopts the original rule ID and deletes it only when its stack is deleted")
    void adoptsAndDeletesTheOriginalRule(boolean egress) throws InterruptedException {
        vpc = ec2.createVpc(request -> request.cidrBlock("10.93.0.0/16")).vpc().vpcId();
        group = ec2.createSecurityGroup(request -> request.vpcId(vpc).groupName(name)
                .description("Existing permission import")).groupId();
        IpPermission permission = IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                .ipRanges(IpRange.builder().cidrIp("192.0.2.27/32").build()).build();
        String id = (egress
                ? ec2.authorizeSecurityGroupEgress(request -> request.groupId(group).ipPermissions(permission))
                        .securityGroupRules()
                : ec2.authorizeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(permission))
                        .securityGroupRules()).get(0).securityGroupRuleId();
        SecurityGroupRule before = ec2.describeSecurityGroupRules(request -> request.securityGroupRuleIds(id))
                .securityGroupRules().get(0);
        String type = "AWS::EC2::SecurityGroup" + (egress ? "Egress" : "Ingress");
        String template = """
                {"Resources":{"Rule":{"Type":"%s","DeletionPolicy":"Delete","Properties":{
                  "GroupId":"%s","IpProtocol":"tcp","FromPort":443,"ToPort":443,"CidrIp":"192.0.2.27/32"}}},
                 "Outputs":{"Id":{"Value":{"Fn::GetAtt":["Rule","Id"]}}}}
                """.formatted(type, group);
        assertThat(cfn.getTemplateSummary(request -> request.templateBody(template))
                .resourceIdentifierSummaries().get(0).resourceIdentifiers()).containsExactly("Id");
        CreateChangeSetResponse changeSet = cfn.createChangeSet(request -> request.stackName(name)
                .changeSetName("adopt").changeSetType("IMPORT").templateBody(template)
                .resourcesToImport(ResourceToImport.builder().resourceType(type).logicalResourceId("Rule")
                        .resourceIdentifier(Map.of("Id", id)).build()));
        stack = changeSet.stackId();
        TestFixtures.await(() -> {
            DescribeChangeSetResponse state = cfn.describeChangeSet(request -> request.changeSetName(changeSet.id()));
            if ("FAILED".equals(state.statusAsString())) {
                throw new AssertionError(state.statusReason());
            }
            return "CREATE_COMPLETE".equals(state.statusAsString());
        }, name, "import change set", 120_000_000_000L, TestFixtures.isRealAws() ? 5000 : 100);
        assertThat(cfn.describeChangeSet(request -> request.changeSetName(changeSet.id()))
                .changes().get(0).resourceChange().actionAsString()).isEqualTo("Import");
        cfn.executeChangeSet(request -> request.changeSetName(changeSet.id()));
        awaitStatus("IMPORT_COMPLETE");
        SecurityGroupRule after = ec2.describeSecurityGroupRules(request -> request.securityGroupRuleIds(id))
                .securityGroupRules().get(0);
        // CloudFormation can add ownership tags; importing must preserve the existing permission.
        assertThat(after).isEqualTo(before.toBuilder().tags(after.tags()).build());
        assertThat(after.tags()).containsAll(before.tags());
        assertThat(cfn.describeStackResource(request -> request.stackName(stack).logicalResourceId("Rule"))
                .stackResourceDetail().physicalResourceId()).isEqualTo(id);
        assertThat(cfn.describeStacks(request -> request.stackName(stack)).stacks().get(0)
                .outputs().get(0).outputValue()).isEqualTo(id);

        cfn.deleteStack(request -> request.stackName(stack));
        awaitStatus("DELETE_COMPLETE");
        assertThat(ec2.describeSecurityGroupRules(request -> request.filters(
                filter -> filter.name("group-id").values(group))).securityGroupRules())
                .noneMatch(rule -> id.equals(rule.securityGroupRuleId()));
        stack = null;
    }

    private void awaitStatus(String expected) throws InterruptedException {
        TestFixtures.await(() -> {
            String status = cfn.describeStacks(request -> request.stackName(stack))
                    .stacks().get(0).stackStatusAsString();
            if (!status.endsWith("_IN_PROGRESS") && !expected.equals(status)) {
                throw new AssertionError(name + " settled at " + status + ", expected " + expected);
            }
            return expected.equals(status);
        }, name, expected, 120_000_000_000L, TestFixtures.isRealAws() ? 5000 : 100);
    }
}

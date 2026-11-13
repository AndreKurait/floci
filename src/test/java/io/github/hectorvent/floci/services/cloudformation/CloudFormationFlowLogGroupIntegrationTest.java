package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.FlowLog;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationFlowLogGroupIntegrationTest {
    @ParameterizedTest
    @CsvSource({"eu-west-1,true", "eu-west-1,false", "us-gov-west-1,true", "us-gov-west-1,false"})
    void nativeGroupBindingSurvivesTagUpdateAndDeletesWithItsStack(String region, boolean namedGroup) {
        String stack = "flow-group-" + UUID.randomUUID();
        try (CloudFormationClient cfn = AwsTestClients.create(
                CloudFormationClient.builder(), "867530900002", Region.of(region));
                Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "867530900002", Region.of(region))) {
            cfn.createStack(request -> request.stackName(stack).templateBody(template(namedGroup))
                    .capabilitiesWithStrings("CAPABILITY_IAM"));
            String flowId = null;
            try {
                Map<String, String> outputs = CfnStackWaits.awaitStatus(cfn, stack, "CREATE_COMPLETE").outputs()
                        .stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
                flowId = outputs.get("FlowId");
                assertFlow(ec2, flowId, outputs, "initial");
                cfn.updateStack(request -> request.stackName(stack).templateBody(template(namedGroup))
                        .capabilitiesWithStrings("CAPABILITY_IAM")
                        .parameters(parameter -> parameter.parameterKey("Marker").parameterValue("updated")));
                Map<String, String> updated = CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_COMPLETE").outputs()
                        .stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
                assertEquals(outputs, updated);
                assertFlow(ec2, flowId, outputs, "updated");
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                cfn.waiter().waitUntilStackDeleteComplete(request -> request.stackName(stack));
                if (flowId != null) {
                    String deletedId = flowId;
                    assertTrue(ec2.describeFlowLogs(request -> request.flowLogIds(deletedId)).flowLogs().isEmpty());
                }
            }
        }
    }

    private static void assertFlow(Ec2Client ec2, String id, Map<String, String> outputs, String marker) {
        FlowLog flow = ec2.describeFlowLogs(request -> request.flowLogIds(id)).flowLogs().getFirst();
        assertEquals(outputs.get("GroupName"), flow.logGroupName());
        assertEquals("cloud-watch-logs", flow.logDestinationTypeAsString());
        assertEquals(outputs.get("RoleArn"), flow.deliverLogsPermissionArn());
        assertEquals(marker, flow.tags().stream().filter(tag -> tag.key().equals("marker"))
                .findFirst().orElseThrow().value());
    }

    private static String template(boolean namedGroup) {
        String destination = namedGroup ? "\"LogGroupName\":{\"Ref\":\"Group\"}"
                : "\"LogDestination\":{\"Fn::GetAtt\":[\"Group\",\"Arn\"]}";
        return """
                {
                  "Parameters":{"Marker":{"Type":"String","Default":"initial"}},
                  "Resources":{
                    "Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.44.0.0/16"}},
                    "Group":{"Type":"AWS::Logs::LogGroup","Properties":{"RetentionInDays":365}},
                    "Role":{"Type":"AWS::IAM::Role","Properties":{
                      "AssumeRolePolicyDocument":{"Version":"2012-10-17","Statement":[{
                        "Effect":"Allow","Principal":{"Service":"vpc-flow-logs.amazonaws.com"},
                        "Action":"sts:AssumeRole"}]},
                      "Policies":[{"PolicyName":"delivery","PolicyDocument":{
                        "Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Action":["logs:CreateLogStream","logs:PutLogEvents"],"Resource":"*"}]}}]}},
                    "Flow":{"Type":"AWS::EC2::FlowLog","Properties":{
                      "ResourceId":{"Ref":"Vpc"},"ResourceType":"VPC","TrafficType":"ALL",
                      "DeliverLogsPermissionArn":{"Fn::GetAtt":["Role","Arn"]},
                      "Tags":[{"Key":"marker","Value":{"Ref":"Marker"}}],%s}}
                  },
                  "Outputs":{"FlowId":{"Value":{"Ref":"Flow"}},"GroupName":{"Value":{"Ref":"Group"}},
                    "RoleArn":{"Value":{"Fn::GetAtt":["Role","Arn"]}}}
                }
                """.formatted(destination);
    }
}

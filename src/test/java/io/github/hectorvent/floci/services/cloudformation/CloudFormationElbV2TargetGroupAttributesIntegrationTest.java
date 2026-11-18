package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroupAttribute;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A target group's {@code TargetGroupAttributes} reach the target group on create and update, a rolled-back update
 * puts them back, and an attribute the template stops declaring keeps its value. Before, the provisioner ignored the
 * property, so a Network Load Balancer target group declared with proxy protocol v2 and client IP preservation
 * reported both off.
 */
@QuarkusTest
class CloudFormationElbV2TargetGroupAttributesIntegrationTest {
    private static final String PROXY = "proxy_protocol_v2.enabled";
    private static final String CLIENT_IP = "preserve_client_ip.enabled";
    private CloudFormationClient cfn;
    private ElasticLoadBalancingV2Client elb;
    private String stackName;

    @AfterEach
    void removeStack() throws InterruptedException {
        if (stackName == null) {
            return;
        }
        try {
            cfn.deleteStack(request -> request.stackName(stackName));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                try {
                    cfn.describeStacks(request -> request.stackName(stackName));
                } catch (CloudFormationException error) {
                    if ("ValidationError".equals(error.awsErrorDetails().errorCode())) {
                        return;
                    }
                    throw error;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("Stack deletion did not finish: " + stackName);
        } finally {
            cfn.close();
            elb.close();
        }
    }

    @Test
    void declaredAttributesAreAppliedUpdatedRolledBackAndRetainedWhenOmitted() throws InterruptedException {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "000000000000", Region.US_EAST_1);
        elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1);
        stackName = "cfn-tg-" + UUID.randomUUID().toString().substring(0, 12);

        cfn.createStack(request -> request.stackName(stackName).templateBody(template(true, false)));
        String arn = await("CREATE_COMPLETE").outputs().getFirst().outputValue();
        assertThat(attributes(arn)).containsEntry(PROXY, "true").containsEntry(CLIENT_IP, "true");

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(true, false))
                .parameters(parameter -> parameter.parameterKey("Proxy").parameterValue("false")));
        await("UPDATE_COMPLETE");
        assertThat(attributes(arn)).containsEntry(PROXY, "false").containsEntry(CLIENT_IP, "true");

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(true, true))
                .parameters(parameter -> parameter.parameterKey("Proxy").parameterValue("true")));
        await("UPDATE_ROLLBACK_COMPLETE");
        assertThat(attributes(arn)).containsEntry(PROXY, "false").containsEntry(CLIENT_IP, "true");

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(false, false)));
        await("UPDATE_COMPLETE");
        assertThat(attributes(arn)).containsEntry(PROXY, "false").containsEntry(CLIENT_IP, "true");
    }

    private String template(boolean declare, boolean fail) {
        String attributes = declare ? """
            ,"TargetGroupAttributes":[{"Key":"proxy_protocol_v2.enabled","Value":{"Ref":"Proxy"}},
                                      {"Key":"preserve_client_ip.enabled","Value":"true"}]
            """ : "";
        String failure = fail ? """
            ,"Failure":{"Type":"AWS::ElasticLoadBalancingV2::TargetGroup","DependsOn":"Group",
            "Properties":{"Protocol":"TCP","Port":0,"TargetType":"ip","VpcId":{"Ref":"Vpc"}}}
            """ : "";
        return """
            {
              "Parameters":{"Proxy":{"Type":"String","Default":"true"}},
              "Resources":{
                "Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.94.0.0/16"}},
                "Group":{"Type":"AWS::ElasticLoadBalancingV2::TargetGroup","Properties":{
                  "Protocol":"TCP","Port":443,"TargetType":"ip","VpcId":{"Ref":"Vpc"}%s
                }}%s
              },
              "Outputs":{"Group":{"Value":{"Ref":"Group"}}}
            }
            """.formatted(attributes, failure);
    }

    private Map<String, String> attributes(String arn) {
        return elb.describeTargetGroupAttributes(request -> request.targetGroupArn(arn)).attributes()
                .stream().collect(Collectors.toMap(TargetGroupAttribute::key, TargetGroupAttribute::value));
    }

    private Stack await(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Stack stack = cfn.describeStacks(request -> request.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (expected.equals(status)) {
                return stack;
            }
            if (status.endsWith("_FAILED")) {
                throw new AssertionError(stackName + " reached " + status + ": " + stack.stackStatusReason());
            }
            Thread.sleep(100);
        }
        throw new AssertionError(stackName + " did not reach " + expected);
    }
}

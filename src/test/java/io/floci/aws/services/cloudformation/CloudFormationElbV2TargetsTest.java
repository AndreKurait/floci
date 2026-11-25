package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class CloudFormationElbV2TargetsTest {
    private CloudFormationClient cfn;
    private ElasticLoadBalancingV2Client elb;
    private String stackName;

    @BeforeEach
    void clients() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "000000000000", Region.US_EAST_1);
        elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1);
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        try {
            removeStack();
        } finally {
            cfn.close();
            elb.close();
        }
    }

    void removeStack() throws InterruptedException {
        if (stackName == null) {
            return;
        }
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
    }

    @Test
    void targetLifecycle() throws InterruptedException {
        stackName = "cfn-targets-" + UUID.randomUUID().toString().substring(0, 12);
        cfn.createStack(request -> request.stackName(stackName)
                .templateBody(template("10.97.0.8", 8080, true, false)));
        Map<String, String> created = outputs(await("CREATE_COMPLETE"));
        String arn = created.get("Group");
        assertThat(arn).endsWith(created.get("FullName"));
        assertThat(registered(arn)).containsExactly(target("10.97.0.8", 8080));
        TargetDescription outside = target("10.97.0.8", 9000);
        elb.registerTargets(request -> request.targetGroupArn(arn).targets(outside));

        cfn.updateStack(request -> request.stackName(stackName)
                .templateBody(template("10.97.0.9", 8090, true, false))
                .parameters(parameter -> parameter.parameterKey("TargetAddress").parameterValue("10.97.0.9")));
        assertThat(outputs(await("UPDATE_COMPLETE")).get("Group")).isEqualTo(arn);
        assertThat(registered(arn)).containsExactlyInAnyOrder(outside, target("10.97.0.9", 8090));

        cfn.updateStack(request -> request.stackName(stackName)
                .templateBody(template("10.97.0.10", 8100, true, true))
                .parameters(parameter -> parameter.parameterKey("TargetAddress").parameterValue("10.97.0.10")));
        await("UPDATE_ROLLBACK_COMPLETE");
        assertThat(registered(arn)).containsExactlyInAnyOrder(outside, target("10.97.0.9", 8090));

        cfn.updateStack(request -> request.stackName(stackName)
                .templateBody(template("10.97.0.9", 8090, false, false)));
        await("UPDATE_COMPLETE");
        assertThat(registered(arn)).containsExactly(outside);
        removeStack();
        stackName = null;
        ElasticLoadBalancingV2Exception deleted = assertThrows(ElasticLoadBalancingV2Exception.class,
                () -> elb.describeTargetGroups(request -> request.targetGroupArns(arn)));
        assertThat(deleted.awsErrorDetails().errorCode()).isEqualTo("TargetGroupNotFound");
    }

    @Test
    void invalidRegistrationLeavesNoTargetGroup() throws InterruptedException {
        stackName = "cfn-invalid-target-" + UUID.randomUUID().toString().substring(0, 12);
        cfn.createStack(request -> request.stackName(stackName)
                .templateBody(template("10.97.0.8", 0, true, false)));
        await("ROLLBACK_COMPLETE");
        String groupName = stackName;
        ElasticLoadBalancingV2Exception missing = assertThrows(ElasticLoadBalancingV2Exception.class,
                () -> elb.describeTargetGroups(request -> request.names(groupName)));
        assertThat(missing.awsErrorDetails().errorCode()).isEqualTo("TargetGroupNotFound");
    }

    private String template(String address, int port, boolean enabled, boolean fail) {
        String failure = fail ? """
            ,"Failure":{"Type":"AWS::ElasticLoadBalancingV2::TargetGroup","DependsOn":"Group",
            "Properties":{"Protocol":"HTTP","Port":0,"TargetType":"ip","VpcId":{"Ref":"Vpc"}}}
            """ : "";
        return """
            {
              "Parameters":{"TargetAddress":{"Type":"String","Default":"%s"}},
              "Conditions":{"Enabled":{"Fn::Equals":["%s","true"]}},
              "Resources":{
                "Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.97.0.0/16"}},
                "Group":{"Type":"AWS::ElasticLoadBalancingV2::TargetGroup","Properties":{
                  "Name":"%s","Protocol":"HTTP","Port":8080,"TargetType":"ip","VpcId":{"Ref":"Vpc"},
                  "Targets":{"Fn::If":["Enabled",
                    [{"Id":{"Ref":"TargetAddress"},"Port":%d,"AvailabilityZone":"all"}],
                    {"Ref":"AWS::NoValue"}]}
                }}%s
              },
              "Outputs":{"Group":{"Value":{"Ref":"Group"}},
                         "FullName":{"Value":{"Fn::GetAtt":["Group","TargetGroupFullName"]}}}
            }
            """.formatted(address, enabled, stackName, port, failure);
    }

    private static TargetDescription target(String id, int port) {
        return TargetDescription.builder().id(id).port(port).availabilityZone("all").build();
    }

    private List<TargetDescription> registered(String arn) {
        return elb.describeTargetHealth(request -> request.targetGroupArn(arn)).targetHealthDescriptions()
                .stream().map(TargetHealthDescription::target).toList();
    }

    private static Map<String, String> outputs(Stack stack) {
        return stack.outputs().stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
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

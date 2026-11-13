package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerAttribute;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CloudFormationElbV2AttributesTest {
    private static final String HOST = "routing.http.preserve_host_header.enabled";
    private static CloudFormationClient cfn;
    private static ElasticLoadBalancingV2Client elb;
    private String stackName;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        elb = TestFixtures.elbV2Client();
    }

    @AfterAll
    static void close() {
        cfn.close();
        elb.close();
    }

    @AfterEach
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
    void createUpdateRollbackAndOmissionRetainUnchangedAttributes() throws InterruptedException {
        stackName = TestFixtures.uniqueName("cfn-lb-attrs");
        cfn.createStack(request -> request.stackName(stackName)
                .templateBody(template(true, true, false)));
        Map<String, String> outputs = await("CREATE_COMPLETE").outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
        String arn = outputs.get("Balancer");
        assertThat(elb.describeLoadBalancers(request -> request.loadBalancerArns(arn))
                .loadBalancers().get(0).dnsName()).isEqualTo(outputs.get("DNSName"));
        assertThat(attributes(arn)).containsEntry(HOST, "true").containsEntry("idle_timeout.timeout_seconds", "120");
        elb.modifyLoadBalancerAttributes(request -> request.loadBalancerArn(arn)
                .attributes(attribute -> attribute.key("client_keep_alive.seconds").value("7200")));

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(true, false, false))
                .parameters(parameter -> parameter.parameterKey("PreserveHost").parameterValue("false")));
        await("UPDATE_COMPLETE");
        assertThat(attributes(arn)).containsEntry(HOST, "false")
                .containsEntry("idle_timeout.timeout_seconds", "120")
                .containsEntry("client_keep_alive.seconds", "7200");

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(true, false, true))
                .parameters(parameter -> parameter.parameterKey("PreserveHost").parameterValue("true")));
        await("UPDATE_ROLLBACK_COMPLETE");
        assertThat(attributes(arn)).containsEntry(HOST, "false")
                .containsEntry("idle_timeout.timeout_seconds", "120")
                .containsEntry("client_keep_alive.seconds", "7200");

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(false, false, false)));
        await("UPDATE_COMPLETE");
        assertThat(attributes(arn)).containsEntry(HOST, "false")
                .containsEntry("idle_timeout.timeout_seconds", "120")
                .containsEntry("client_keep_alive.seconds", "7200");
    }

    private String template(boolean enabled, boolean idle, boolean fail) {
        String extra = idle ? ",{\"Key\":\"idle_timeout.timeout_seconds\",\"Value\":120}" : "";
        String failure = fail ? """
            ,"Failure":{"Type":"AWS::ElasticLoadBalancingV2::TargetGroup","DependsOn":"Balancer",
            "Properties":{"Protocol":"HTTP","Port":0,"TargetType":"ip","VpcId":{"Ref":"Vpc"}}}
            """ : "";
        return """
            {
              "Parameters":{"PreserveHost":{"Type":"String","Default":"true"}},
              "Conditions":{"Enabled":{"Fn::Equals":["%s","true"]}},
              "Resources":{
                "Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.98.0.0/16"}},
                "SubnetA":{"Type":"AWS::EC2::Subnet","Properties":{
                  "VpcId":{"Ref":"Vpc"},"CidrBlock":"10.98.0.0/24",
                  "AvailabilityZone":{"Fn::Select":[0,{"Fn::GetAZs":""}]}}},
                "SubnetB":{"Type":"AWS::EC2::Subnet","Properties":{
                  "VpcId":{"Ref":"Vpc"},"CidrBlock":"10.98.1.0/24",
                  "AvailabilityZone":{"Fn::Select":[1,{"Fn::GetAZs":""}]}}},
                "Balancer":{"Type":"AWS::ElasticLoadBalancingV2::LoadBalancer","Properties":{
                  "Name":"%s","Type":"application","Scheme":"internal",
                  "Subnets":[{"Ref":"SubnetA"},{"Ref":"SubnetB"}],
                  "LoadBalancerAttributes":{"Fn::If":["Enabled",
                    [{"Key":"routing.http.preserve_host_header.enabled","Value":{"Ref":"PreserveHost"}}%s],
                    {"Ref":"AWS::NoValue"}]}
                }}%s
              },
              "Outputs":{"Balancer":{"Value":{"Ref":"Balancer"}},
                         "DNSName":{"Value":{"Fn::GetAtt":["Balancer","DNSName"]}}}
            }
            """.formatted(enabled, stackName, extra, failure);
    }

    private static Map<String, String> attributes(String arn) {
        return elb.describeLoadBalancerAttributes(request -> request.loadBalancerArn(arn)).attributes()
                .stream().collect(Collectors.toMap(LoadBalancerAttribute::key, LoadBalancerAttribute::value));
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

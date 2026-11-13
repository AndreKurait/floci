package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerAttribute;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CloudFormationElbV2AttributesIntegrationTest {
    private static final String HOST = "routing.http.preserve_host_header.enabled";
    private CloudFormationClient cfn;
    private ElasticLoadBalancingV2Client elb;
    private String stackName;

    private void clients(String region) {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "000000000000", Region.of(region));
        elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), "000000000000", Region.of(region));
        stackName = "cfn-lb-" + UUID.randomUUID().toString().substring(0, 12);
    }

    @AfterEach
    void removeStack() throws InterruptedException {
        if (stackName == null) {
            return;
        }
        try {
            deleteStack();
        } finally {
            cfn.close();
            elb.close();
        }
    }

    private void deleteStack() throws InterruptedException {
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

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void createUpdateRollbackAndOmissionRetainUnchangedAttributes(String region) throws InterruptedException {
        clients(region);
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

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void rejectedAttributeUpdatePreservesTheExistingLoadBalancer(String region) throws InterruptedException {
        clients(region);
        cfn.createStack(request -> request.stackName(stackName)
                .templateBody(template(true, true, false)));
        String arn = await("CREATE_COMPLETE").outputs().stream()
                .filter(output -> "Balancer".equals(output.outputKey())).findFirst().orElseThrow().outputValue();
        String dnsName = elb.describeLoadBalancers(request -> request.loadBalancerArns(arn))
                .loadBalancers().getFirst().dnsName();
        cfn.updateStack(request -> request.stackName(stackName)
                .templateBody(template(true, true, false).replace(HOST, "routing.http.xff_header_processing.mode"))
                .parameters(parameter -> parameter.parameterKey("PreserveHost").parameterValue("not-a-mode")));
        await("UPDATE_ROLLBACK_COMPLETE");
        assertThat(elb.describeLoadBalancers(request -> request.loadBalancerArns(arn))
                .loadBalancers().getFirst().dnsName()).isEqualTo(dnsName);
        assertThat(attributes(arn)).containsEntry(HOST, "true").containsEntry("idle_timeout.timeout_seconds", "120");
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

    private Map<String, String> attributes(String arn) {
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

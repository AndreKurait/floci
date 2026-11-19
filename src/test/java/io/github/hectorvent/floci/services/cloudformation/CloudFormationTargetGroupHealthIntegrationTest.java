package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Matcher;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudFormationTargetGroupHealthIntegrationTest {

    private CloudFormationClient cfn;
    private ElasticLoadBalancingV2Client elb;
    private String stackName;

    @BeforeEach
    void clients() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "000000000000", Region.US_EAST_1);
        elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1);
        stackName = "cfn-tg-health-" + UUID.randomUUID().toString().substring(0, 12);
    }

    @AfterEach
    void cleanup() {
        try {
            if (stackName != null && !stackAbsent()) {
                cfn.deleteStack(request -> request.stackName(stackName));
                CfnStackWaits.awaitState(Duration.ofSeconds(30), this::stackAbsent,
                        Boolean::booleanValue, absent -> "Stack deletion did not finish: " + stackName);
            }
        } finally {
            if (elb != null) {
                elb.close();
            }
            if (cfn != null) {
                cfn.close();
            }
        }
    }

    private boolean stackAbsent() {
        try {
            cfn.describeStacks(request -> request.stackName(stackName));
            return false;
        } catch (CloudFormationException error) {
            if ("ValidationError".equals(error.awsErrorDetails().errorCode())
                    && error.awsErrorDetails().errorMessage().contains("does not exist")) {
                return true;
            }
            throw error;
        }
    }

    private String create(String template) {
        cfn.createStack(request -> request.stackName(stackName).templateBody(template));
        return CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE")
                .outputs().getFirst().outputValue();
    }

    private void update(String template, String status) {
        cfn.updateStack(request -> request.stackName(stackName).templateBody(template));
        CfnStackWaits.awaitStatus(cfn, stackName, status);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUpdateRestoresHealthAndRetainsTheGroup(boolean laterResourceFails) {
        String arn = create(template(false));
        TargetGroup before = elb.describeTargetGroups(request -> request.targetGroupArns(arn))
                .targetGroups().getFirst();
        update(template(true, laterResourceFails, !laterResourceFails), "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(before, elb.describeTargetGroups(request -> request.targetGroupArns(arn))
                .targetGroups().getFirst());
        assertEquals(arn, physicalId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updatesHealthSettingsInPlace(boolean directApiControl) {
        String arn = create(template(false));
        TargetGroup before = elb.describeTargetGroups(request -> request.targetGroupArns(arn))
                .targetGroups().getFirst();
        assertEquals("TCP", before.healthCheckProtocolAsString());
        assertEquals("7777", before.healthCheckPort());

        if (directApiControl) {
            elb.modifyTargetGroup(request -> request.targetGroupArn(arn)
                    .healthCheckProtocol("HTTP").healthCheckPort("8888")
                    .healthCheckPath("/ready").healthCheckIntervalSeconds(15)
                    .healthCheckTimeoutSeconds(4).healthyThresholdCount(4).unhealthyThresholdCount(5)
                    .matcher(Matcher.builder().httpCode("200-299").build()));
        } else {
            update(template(true), "UPDATE_COMPLETE");
        }

        TargetGroup after = elb.describeTargetGroups(request -> request.targetGroupArns(arn))
                .targetGroups().getFirst();
        assertAll(
                () -> assertEquals(arn, physicalId()),
                () -> assertEquals("HTTP", after.healthCheckProtocolAsString()),
                () -> assertEquals("8888", after.healthCheckPort()),
                () -> assertEquals("/ready", after.healthCheckPath()),
                () -> assertEquals(15, after.healthCheckIntervalSeconds()),
                () -> assertEquals(4, after.healthCheckTimeoutSeconds()),
                () -> assertEquals(4, after.healthyThresholdCount()),
                () -> assertEquals(5, after.unhealthyThresholdCount()),
                () -> assertEquals("200-299", after.matcher().httpCode()));
    }

    private String physicalId() {
        return cfn.describeStackResource(request ->
                request.stackName(stackName).logicalResourceId("Group")).stackResourceDetail().physicalResourceId();
    }

    private static String template(boolean updated) {
        return template(updated, false, false);
    }

    private static String template(boolean updated, boolean laterFails, boolean invalidTarget) {
        String health = updated ? """
                "HealthCheckProtocol":"HTTP","HealthCheckPort":"8888","HealthCheckPath":"/ready",
                "HealthCheckIntervalSeconds":15,"HealthCheckTimeoutSeconds":4,
                "HealthyThresholdCount":4,"UnhealthyThresholdCount":5,"Matcher":{"HttpCode":"200-299"}
                """ : """
                "HealthCheckProtocol":"TCP","HealthCheckPort":"7777"
                """;
        return """
                {"Resources":{
                  "Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.99.0.0/16"}},
                  "Group":{"Type":"AWS::ElasticLoadBalancingV2::TargetGroup","Properties":{
                    "Protocol":"TCP","Port":25,"TargetType":"ip","VpcId":{"Ref":"Vpc"},%s%s}}%s
                }, "Outputs":{"GroupArn":{"Value":{"Ref":"Group"}}}}
                """.formatted(health,
                invalidTarget ? ",\"Targets\":[{\"Id\":\"10.99.0.10\",\"Port\":70000}]" : "",
                laterFails ? """
                        ,"Failure":{"Type":"AWS::ElasticLoadBalancingV2::TargetGroup","DependsOn":"Group",
                        "Properties":{"Protocol":"TCP","Port":0,"TargetType":"ip","VpcId":{"Ref":"Vpc"}}}
                        """ : "");
    }
}

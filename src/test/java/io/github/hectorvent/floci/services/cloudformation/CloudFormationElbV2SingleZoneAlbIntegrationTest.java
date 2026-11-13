package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code floci.services.elbv2.single-zone-alb-accounts} on the CloudFormation path: a stack provisions
 * on a background thread under its own account's request scope, so a stack in a listed account creates
 * an Application Load Balancer on one subnet, and the same template in another account fails as on AWS.
 */
@QuarkusTest
@TestProfile(CloudFormationElbV2SingleZoneAlbIntegrationTest.SingleZoneAlbProfile.class)
class CloudFormationElbV2SingleZoneAlbIntegrationTest {

    private static final String GRANTED = "111111111111";
    private static final String OTHER = "222222222222";

    public static class SingleZoneAlbProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elbv2.single-zone-alb-accounts", GRANTED);
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aStackInAGrantedAccountCreatesAOneSubnetApplicationLoadBalancer() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "alb-one-zone-" + suffix;
        String name = "alb-one-" + suffix;
        try {
            request(GRANTED, "cloudformation", "CreateStack", "StackName", stack, "TemplateBody", template(name))
                    .statusCode(200);
            CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(GRANTED, "cloudformation"));
            request(GRANTED, "elasticloadbalancing", "DescribeLoadBalancers", "Names.member.1", name)
                    .statusCode(200)
                    .body(containsString("<ZoneName>us-east-1a</ZoneName>"));
        } finally {
            request(GRANTED, "cloudformation", "DeleteStack", "StackName", stack);
        }
    }

    @Test
    void theSameStackInAnotherAccountFails() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "alb-one-zone-" + suffix;
        String name = "alb-one-" + suffix;
        try {
            request(OTHER, "cloudformation", "CreateStack", "StackName", stack, "TemplateBody", template(name))
                    .statusCode(200);
            CfnStackWaits.awaitStatus(stack, "ROLLBACK_COMPLETE", auth(OTHER, "cloudformation"));
            String events = request(OTHER, "cloudformation", "DescribeStackEvents", "StackName", stack)
                    .statusCode(200).extract().asString();
            List<String> reasons = XmlParser.extractAll(events, "ResourceStatusReason");
            assertTrue(reasons.stream().anyMatch(r -> r.contains(
                    "At least two subnets in two different Availability Zones must be specified")),
                    String.join(" | ", reasons));
        } finally {
            request(OTHER, "cloudformation", "DeleteStack", "StackName", stack);
        }
    }

    private static String template(String name) {
        return """
            {
              "Resources": {
                "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.98.0.0/16"}},
                "SubnetA": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": {"Ref": "Vpc"},
                            "CidrBlock": "10.98.1.0/24", "AvailabilityZone": "us-east-1a"}},
                "Alb": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": {"Name": "%s", "Scheme": "internal", "Subnets": [{"Ref": "SubnetA"}]}
                }
              }
            }
            """.formatted(name);
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261104/us-east-1/" + service + "/aws4_request";
    }

    private static ValidatableResponse request(String account, String service, String action, String... values) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", action);
        for (int i = 0; i < values.length; i += 2) {
            parameters.put(values[i], values[i + 1]);
        }
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", auth(account, service))
                .formParams(parameters).when().post("/").then().log().ifValidationFails();
    }
}

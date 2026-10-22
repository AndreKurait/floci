package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ElbV2ProvisionedCapacityIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String ACTION = "DescribeProvisionedCapacity";
    private static final String RESULT =
            "DescribeProvisionedCapacityResponse.DescribeProvisionedCapacityResult";

    @TestHTTPResource
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void extensionReportsSyntheticDisabledStatusAndLeavesPublicReservationReadUnchanged() {
        String arn = loadBalancer();
        query(ACCOUNT, ACTION).formParam("LoadBalancerArn", arn).post("/").then()
                .statusCode(200).contentType("application/xml")
                .body(RESULT + ".ProvisionedCapacity.Status", equalTo("disabled"))
                .body("DescribeProvisionedCapacityResponse.ResponseMetadata.RequestId", not(emptyOrNullString()));
        query(ACCOUNT, "DescribeCapacityReservation").formParam("LoadBalancerArn", arn).post("/").then()
                .statusCode(200)
                .body("DescribeCapacityReservationResponse.DescribeCapacityReservationResult"
                        + ".MinimumLoadBalancerCapacity.size()", equalTo(0))
                .body("DescribeCapacityReservationResponse.DescribeCapacityReservationResult"
                        + ".CapacityReservationState.size()", equalTo(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Action", "Operation"})
    void iamChecksTheNamedLoadBalancerForBothQuerySelectors(String selector) {
        String arn = loadBalancer();
        String other = loadBalancer();
        String userName = "capacity-" + UUID.randomUUID();
        String iamAdmin = auth(ACCOUNT, "iam");
        String key = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), iamAdmin, userName).accessKeyId();
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), iamAdmin, userName, "read", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"elasticloadbalancing:DescribeProvisionedCapacity","Resource":"%s"}
                ]}
                """.formatted(arn));
        selectedQuery(key, selector, arn).statusCode(200).body(RESULT + ".ProvisionedCapacity.Status",
                equalTo("disabled"));
        selectedQuery(key, selector, other).statusCode(403).body("ErrorResponse.Error.Code", equalTo("AccessDenied"));

        EnforcementFixtures.putUserPolicy(endpoint.getPort(), iamAdmin, userName, "read", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"elasticloadbalancing:DescribeProvisionedCapacity","Resource":"*"},
                  {"Effect":"Deny","Action":"elasticloadbalancing:DescribeProvisionedCapacity","Resource":"%s"}
                ]}
                """.formatted(arn));
        selectedQuery(key, selector, arn).statusCode(403).body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
        selectedQuery(key, selector, other).statusCode(200);
    }

    @Test
    void anotherAccountCannotReadAnExistingOwnersLoadBalancer() {
        String arn = loadBalancer();
        query("222222222222", ACTION).formParam("LoadBalancerArn", arn).post("/").then()
                .statusCode(400).body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
        query(ACCOUNT, ACTION).formParam("LoadBalancerArn", arn).post("/").then().statusCode(200);
    }

    @Test
    void deletedLoadBalancerIsNotReportedAsDisabled() {
        String arn = loadBalancer();
        query(ACCOUNT, "DeleteLoadBalancer").formParam("LoadBalancerArn", arn).post("/").then().statusCode(200);
        query(ACCOUNT, ACTION).formParam("LoadBalancerArn", arn).post("/").then()
                .statusCode(400).body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
    }

    @Test
    void missingOrDuplicateLoadBalancerArnIsRejected() {
        String arn = loadBalancer();
        query(ACCOUNT, ACTION).post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
        query(ACCOUNT, ACTION).formParam("LoadBalancerArn", List.of(arn, arn)).post("/").then()
                .statusCode(400).body("ErrorResponse.Error.Code", equalTo("ValidationError"));
        query(ACCOUNT, ACTION).formParam("LoadBalancerArn", "").post("/").then()
                .statusCode(400).body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong", "duplicate"})
    void extensionRequiresExactlyOneModernVersion(String versionCase) {
        RequestSpecification request = given().port(endpoint.getPort())
                .header("Authorization", auth(ACCOUNT, "elasticloadbalancing"))
                .formParam("Action", ACTION)
                .formParam("LoadBalancerArn",
                        "arn:aws:elasticloadbalancing:us-east-1:111111111111:"
                                + "loadbalancer/app/missing/0123456789abcdef");
        if ("wrong".equals(versionCase)) {
            request.formParam("Version", "2016-01-01");
        } else if ("duplicate".equals(versionCase)) {
            request.formParam("Version", List.of("2015-12-01", "2015-12-01"));
        }
        request.post("/").then().statusCode(400).body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    private ValidatableResponse selectedQuery(String key, String selector, String arn) {
        return given().port(endpoint.getPort())
                .header("Authorization", auth(key, "elasticloadbalancing"))
                .formParam(selector, ACTION).formParam("Version", "2015-12-01")
                .formParam("LoadBalancerArn", arn).post("/").then();
    }

    private RequestSpecification query(String key, String action) {
        return given().port(endpoint.getPort()).header("Authorization", auth(key, "elasticloadbalancing"))
                .formParam("Action", action).formParam("Version", "2015-12-01");
    }

    private String loadBalancer() {
        String name = "capacity-" + UUID.randomUUID().toString().substring(0, 8);
        cleanup.register(() -> {
            Response described = query(ACCOUNT, "DescribeLoadBalancers").formParam("Names.member.1", name).post("/");
            if (described.statusCode() == 200) {
                for (String arn : described.xmlPath().getList(
                        "DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member"
                                + ".LoadBalancerArn", String.class)) {
                    query(ACCOUNT, "DeleteLoadBalancer").formParam("LoadBalancerArn", arn).post("/").then()
                            .statusCode(200);
                }
            } else {
                described.then().statusCode(400).body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
            }
        });
        return query(ACCOUNT, "CreateLoadBalancer").formParam("Name", name)
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId(REGION, "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId(REGION, "b"))
                .post("/").then().statusCode(200).extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }
}

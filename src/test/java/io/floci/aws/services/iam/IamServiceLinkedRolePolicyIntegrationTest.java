package io.floci.aws.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The permissions policy IAM attaches to a service-linked role, read back over the wire. The linked
 * service assumes its role and calls other services with it; a role with a trust policy and nothing
 * else is denied there under IAM enforcement.
 *
 * <p>Runs in an account of its own, so a role another class created in the default account cannot
 * collide with these names, and deletes what it creates.
 */
@QuarkusTest
class IamServiceLinkedRolePolicyIntegrationTest {

    private static final String ACCOUNT = "000000000351";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261108/us-east-1/iam/aws4_request";

    @Test
    void theOpenSearchRoleListsItsManagedPolicy() {
        String roleName = create("opensearchservice.amazonaws.com");
        try {
            given()
                .formParam("Action", "ListAttachedRolePolicies")
                .formParam("RoleName", roleName)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.AttachedPolicies.member.size()",
                        equalTo(1))
                .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.AttachedPolicies.member"
                        + ".PolicyName", equalTo("AmazonOpenSearchServiceRolePolicy"))
                .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.AttachedPolicies.member"
                        + ".PolicyArn",
                        equalTo("arn:aws:iam::aws:policy/aws-service-role/AmazonOpenSearchServiceRolePolicy"));
        } finally {
            delete(roleName);
        }
    }

    private static String create(String awsServiceName) {
        return given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", awsServiceName)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName");
    }

    private static void delete(String roleName) {
        given()
            .formParam("Action", "DeleteServiceLinkedRole")
            .formParam("RoleName", roleName)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}

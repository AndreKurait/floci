package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/requester-managed-eni.html, "a requester-managed network
 * interface is a network interface that an AWS service creates in your VPC on your behalf", and the
 * {@code requester-managed} filter of DescribeNetworkInterfaces finds it. A service acts in an account through its
 * service-linked role (https://docs.aws.amazon.com/IAM/latest/UserGuide/using-service-linked-roles.html), so an
 * interface a session of that role creates reports {@code requesterManaged} true; one the account or any other role
 * creates does not.
 */
@QuarkusTest
class Ec2ServiceLinkedRoleRequesterManagedIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String subnetId() {
        String vpcId = given()
            .formParam("Action", "DescribeVpcs").formParam("Filter.1.Name", "is-default")
            .formParam("Filter.1.Value.1", "true").header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().path("DescribeVpcsResponse.vpcSet.item[0].vpcId");
        return given()
            .formParam("Action", "DescribeSubnets").formParam("Filter.1.Name", "vpc-id")
            .formParam("Filter.1.Value.1", vpcId).header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().path("DescribeSubnetsResponse.subnetSet.item[0].subnetId");
    }

    /** The access key and session token of a session of {@code roleArn}. */
    private static String[] session(String roleArn, String name) {
        XmlPath assumed = given()
            .formParam("Action", "AssumeRole").formParam("RoleArn", roleArn).formParam("RoleSessionName", name)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sts/aws4_request")
        .when().post("/").then().statusCode(200).extract().xmlPath();
        String result = "AssumeRoleResponse.AssumeRoleResult.Credentials.";
        return new String[] {assumed.getString(result + "AccessKeyId"), assumed.getString(result + "SessionToken")};
    }

    private static String serviceLinkedRoleArn() {
        Response created = given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "elasticloadbalancing.amazonaws.com")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request")
        .when().post("/").then().extract().response();
        if (created.statusCode() == 200) {
            return created.xmlPath().getString("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.Arn");
        }
        return given()
            .formParam("Action", "GetRole").formParam("RoleName", "AWSServiceRoleForElasticLoadBalancing")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request")
        .when().post("/").then().statusCode(200).extract().xmlPath().getString("GetRoleResponse.GetRoleResult.Role.Arn");
    }

    private static Response create(String subnetId, String description, String[] session) {
        RequestSpecification request = given().formParam("Action", "CreateNetworkInterface").formParam("SubnetId", subnetId)
                .formParam("Description", description);
        request = session == null ? request.header("Authorization", AUTH_HEADER)
                : request.header("Authorization", "AWS4-HMAC-SHA256 Credential=" + session[0]
                        + "/20260205/us-east-1/ec2/aws4_request, SignedHeaders=host, Signature=abc")
                        .header("X-Amz-Security-Token", session[1]);
        return request.when().post("/").then().statusCode(200).extract().response();
    }

    private static List<String> requesterManaged(String description, String value) {
        return XmlPath.from(given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "description").formParam("Filter.1.Value.1", description)
            .formParam("Filter.2.Name", "requester-managed").formParam("Filter.2.Value.1", value)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().asString())
            .getList("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId");
    }

    @Test
    void anInterfaceAServiceLinkedRoleSessionCreatesIsRequesterManaged() {
        String subnetId = subnetId();
        String roleArn = serviceLinkedRoleArn();
        assertTrue(roleArn.contains(":role/aws-service-role/elasticloadbalancing.amazonaws.com/"), roleArn);
        Response created = create(subnetId, "slr-created-interface", session(roleArn, "slr-session"));
        assertEquals("true", created.path("CreateNetworkInterfaceResponse.networkInterface.requesterManaged"));
        String eniId = created.path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        String described = given()
            .formParam("Action", "DescribeNetworkInterfaces").formParam("NetworkInterfaceId.1", eniId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.requesterManaged", equalTo("true"))
            .extract().asString();
        // AWS names the creating service's own alias or account as requesterId, which floci cannot know.
        assertFalse(described.contains("<requesterId>"), described);
        assertEquals(List.of(eniId), requesterManaged("slr-created-interface", "true"));
        assertEquals(List.of(), requesterManaged("slr-created-interface", "false"));
    }

    @Test
    void anInterfaceTheAccountOrAnotherRoleCreatesIsNot() {
        String subnetId = subnetId();
        Response byAccount = create(subnetId, "account-created-interface", null);
        assertEquals("false", byAccount.path("CreateNetworkInterfaceResponse.networkInterface.requesterManaged"));

        String roleArn = given()
            .formParam("Action", "CreateRole").formParam("RoleName", "ordinary-interface-creator")
            .formParam("Path", "/service-role/")
            .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"Service\":\"elasticloadbalancing.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request")
        .when().post("/").then().statusCode(200).extract().xmlPath()
            .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        Response byRole = create(subnetId, "role-created-interface", session(roleArn, "ordinary-session"));
        assertEquals("false", byRole.path("CreateNetworkInterfaceResponse.networkInterface.requesterManaged"));

        assertEquals(List.of(), requesterManaged("account-created-interface", "true"));
        assertEquals(List.of(), requesterManaged("role-created-interface", "true"));
        assertFalse(requesterManaged("role-created-interface", "false").isEmpty());
    }
}

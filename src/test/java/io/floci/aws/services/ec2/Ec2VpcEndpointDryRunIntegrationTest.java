package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;

/**
 * DryRun on the VPC endpoint actions: AWS answers 412 DryRunOperation ("Request would have succeeded, but DryRun
 * flag is set.") and changes nothing, as the EC2 API reference says of DryRun on every action.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateVpcEndpoint.html">CreateVpcEndpoint</a>
 */
@QuarkusTest
class Ec2VpcEndpointDryRunIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static RequestSpecification ec2(String action, String... formParams) {
        RequestSpecification req = given().formParam("Action", action).header("Authorization", AUTH_HEADER);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req;
    }

    private static String ec2Value(String action, String element, String... formParams) {
        return ec2(action, formParams).when().post("/").then().statusCode(200).extract().path(element);
    }

    private static void expectDryRunOperation(String action, String... formParams) {
        ec2(action, formParams).formParam("DryRun", "true")
            .when().post("/")
            .then()
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
    }

    private static String createEndpoint(String vpcId, String subnetId, String... extra) {
        String[] params = {"VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ecs",
            "VpcEndpointType", "Interface", "SubnetId.1", subnetId};
        String[] all = new String[params.length + extra.length];
        System.arraycopy(params, 0, all, 0, params.length);
        System.arraycopy(extra, 0, all, params.length, extra.length);
        return ec2Value("CreateVpcEndpoint", "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId", all);
    }

    private static String vpc(String cidr) {
        return ec2Value("CreateVpc", "CreateVpcResponse.vpc.vpcId", "CidrBlock", cidr);
    }

    private static String subnet(String vpcId, String cidr) {
        return ec2Value("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpcId, "CidrBlock", cidr, "AvailabilityZone", "us-east-1a");
    }

    @Test
    void createDryRunAnswersDryRunOperationAndCreatesNothing() {
        String vpcId = vpc("10.91.0.0/16");
        String subnetId = subnet(vpcId, "10.91.1.0/24");

        expectDryRunOperation("CreateVpcEndpoint", "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ecs",
                "VpcEndpointType", "Interface", "SubnetId.1", subnetId,
                "TagSpecification.1.ResourceType", "vpc-endpoint",
                "TagSpecification.1.Tag.1.Key", "Name", "TagSpecification.1.Tag.1.Value", "dry-run");

        ec2("DescribeVpcEndpoints", "Filter.1.Name", "vpc-id", "Filter.1.Value.1", vpcId)
            .when().post("/")
            .then()
                .statusCode(200)
                .body("DescribeVpcEndpointsResponse.vpcEndpointSet", emptyOrNullString());
    }

    @Test
    void modifyDryRunAnswersDryRunOperationAndLeavesThePolicy() {
        String vpcId = vpc("10.92.0.0/16");
        String policy = "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"*\",\"Resource\":\"*\"}]}";
        String endpointId = createEndpoint(vpcId, subnet(vpcId, "10.92.1.0/24"), "PolicyDocument", policy);

        expectDryRunOperation("ModifyVpcEndpoint", "VpcEndpointId", endpointId,
                "PolicyDocument", "{\"Statement\":[]}");

        ec2("DescribeVpcEndpoints", "VpcEndpointId.1", endpointId)
            .when().post("/")
            .then()
                .statusCode(200)
                .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.vpcEndpointId", equalTo(endpointId))
                .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.policyDocument", equalTo(policy));
    }

    @Test
    void deleteDryRunAnswersDryRunOperationAndKeepsTheEndpoint() {
        String vpcId = vpc("10.93.0.0/16");
        String endpointId = createEndpoint(vpcId, subnet(vpcId, "10.93.1.0/24"));

        expectDryRunOperation("DeleteVpcEndpoints", "VpcEndpointId.1", endpointId);

        ec2("DescribeVpcEndpoints", "VpcEndpointId.1", endpointId)
            .when().post("/")
            .then()
                .statusCode(200)
                .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.vpcEndpointId", equalTo(endpointId));
    }

    @Test
    void describeDryRunAnswersDryRunOperation() {
        expectDryRunOperation("DescribeVpcEndpoints");
    }
}

package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2SubnetVpcRangeIntegrationTest {
    private static RequestSpecification ec2(String action) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=855555555555/20261011/us-east-1/ec2/aws4_request")
                .formParam("Action", action);
    }

    @Test
    void subnetRangeMustFitPrimaryOrAssociatedSecondaryVpcBlock() {
        String vpcId = ec2("CreateVpc").formParam("CidrBlock", "10.0.0.0/16").post("/").then()
                .statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        ec2("CreateSubnet").formParam("VpcId", vpcId).formParam("CidrBlock", "192.168.1.0/24")
                .post("/").then().statusCode(400).body("Response.Errors.Error.Code", equalTo("InvalidSubnet.Range"));
        ec2("AssociateVpcCidrBlock").formParam("VpcId", vpcId).formParam("CidrBlock", "192.168.0.0/16")
                .post("/").then().statusCode(200);
        String subnetId = ec2("CreateSubnet").formParam("VpcId", vpcId).formParam("CidrBlock", "192.168.1.9/24")
                .post("/").then().statusCode(200).body("CreateSubnetResponse.subnet.cidrBlock", equalTo("192.168.1.0/24"))
                .extract().path("CreateSubnetResponse.subnet.subnetId");
        ec2("DeleteSubnet").formParam("SubnetId", subnetId).post("/").then().statusCode(200);
        ec2("DeleteVpc").formParam("VpcId", vpcId).post("/").then().statusCode(200);
    }
}

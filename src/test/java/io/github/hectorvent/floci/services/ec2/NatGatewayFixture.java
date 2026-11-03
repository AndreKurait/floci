package io.github.hectorvent.floci.services.ec2;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/** A private NAT gateway created through EC2 APIs, together with its owned subnet. */
final class NatGatewayFixture implements AutoCloseable {

    private final String authorization;
    private final String baseUri;
    private final int port;
    private String subnet;
    private String gateway;

    NatGatewayFixture(String authorization, String vpc, String cidr) {
        this.authorization = authorization;
        // Quarkus resets RestAssured defaults between tests, before class cleanup.
        baseUri = RestAssured.baseURI;
        port = RestAssured.port;
        try {
            subnet = ec2().formParam("Action", "CreateSubnet")
                    .formParam("VpcId", vpc).formParam("CidrBlock", cidr)
                    .post("/").then().statusCode(200)
                    .extract().path("CreateSubnetResponse.subnet.subnetId");
            gateway = ec2().formParam("Action", "CreateNatGateway")
                    .formParam("SubnetId", subnet).formParam("ConnectivityType", "private")
                    .post("/").then().statusCode(200)
                    .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");
        } catch (RuntimeException | AssertionError failure) {
            try {
                close();
            } catch (RuntimeException | AssertionError cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    String id() {
        return gateway;
    }

    private RequestSpecification ec2() {
        return given().baseUri(baseUri).port(port).header("Authorization", authorization);
    }

    @Override
    public void close() {
        try {
            if (gateway != null) {
                ec2().formParam("Action", "DeleteNatGateway").formParam("NatGatewayId", gateway)
                        .post("/").then().statusCode(200);
            }
        } finally {
            if (subnet != null) {
                ec2().formParam("Action", "DeleteSubnet").formParam("SubnetId", subnet)
                        .post("/").then().statusCode(200);
            }
        }
    }
}

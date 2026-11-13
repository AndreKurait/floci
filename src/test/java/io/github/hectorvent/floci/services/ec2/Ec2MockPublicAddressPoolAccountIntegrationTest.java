package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.hectorvent.floci.services.ec2.Ec2MockPublicAddressSettingIntegrationTest.publicIp;
import static io.github.hectorvent.floci.services.ec2.Ec2MockPublicAddressSettingIntegrationTest.request;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(Ec2MockPublicAddressPoolAccountIntegrationTest.SingleAddress.class)
class Ec2MockPublicAddressPoolAccountIntegrationTest {

    public static class SingleAddress implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "true",
                    "floci.services.ec2.mock-public-addresses", "pool",
                    "floci.services.ec2.mock-public-address-pool", "192.0.2.7/32");
        }
    }

    @Test
    void anotherAccountsAddressCountsUntilItsInstanceTerminates() {
        String owner = "724000000001";
        String other = "724000000002";
        Network first = network(owner, "10.97");
        Network second = network(other, "10.98");
        String instance = null;
        String replacement = null;
        try {
            instance = launch(owner, first.subnet());
            assertEquals("192.0.2.7", publicIp(owner, instance));
            request("RunInstances", other).formParam("ImageId", "ami-amazonlinux2023")
                    .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                    .formParam("SubnetId", second.subnet()).post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("AddressLimitExceeded"));
            terminate(owner, instance);
            instance = null;
            replacement = launch(other, second.subnet());
            assertEquals("192.0.2.7", publicIp(other, replacement));
        } finally {
            terminate(owner, instance);
            terminate(other, replacement);
            delete(owner, first);
            delete(other, second);
        }
    }

    private static Network network(String owner, String prefix) {
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", prefix + ".0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", prefix + ".1.0/24").formParam("AvailabilityZone", "us-east-1a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        request("ModifySubnetAttribute", owner).formParam("SubnetId", subnet)
                .formParam("MapPublicIpOnLaunch.Value", true).post("/").then().statusCode(200);
        return new Network(vpc, subnet);
    }

    private static String launch(String owner, String subnet) {
        return request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("SubnetId", subnet).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    private static void terminate(String owner, String instance) {
        if (instance != null) {
            request("TerminateInstances", owner).formParam("InstanceId.1", instance)
                    .post("/").then().statusCode(200);
        }
    }

    private static void delete(String owner, Network network) {
        request("DeleteSubnet", owner).formParam("SubnetId", network.subnet()).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", network.vpc()).post("/").then().statusCode(200);
    }

    private record Network(String vpc, String subnet) {}
}

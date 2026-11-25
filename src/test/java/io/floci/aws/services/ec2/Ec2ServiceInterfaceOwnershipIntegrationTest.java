package io.floci.aws.services.ec2;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2ServiceInterfaceOwnershipIntegrationTest {
    private static final String REGION = "us-east-1";
    private static final String OWNER = "111111119871";
    private static final String FOREIGN = "222222229871";
    private static final String TOKEN = "classic-ownership-wire";

    @Inject
    Ec2Service service;

    @Test
    void internalOwnershipIsAccountScopedAndAbsentFromTheWire() {
        try (Ec2Client owner = AwsTestClients.create(Ec2Client.builder(), OWNER, Region.US_EAST_1);
             Ec2Client foreign = AwsTestClients.create(Ec2Client.builder(), FOREIGN, Region.US_EAST_1)) {
            List<String> ownedIds = new ArrayList<>();
            List<String> foreignIds = new ArrayList<>();
            try {
                String subnet = owner.describeSubnets().subnets().getFirst().subnetId();
                NetworkInterface owned = RequestScopes.callAs(OWNER, REGION,
                        () -> service.createServiceNetworkInterface(REGION, subnet, "Classic frontend",
                                List.of(), TOKEN));
                ownedIds.add(owned.getNetworkInterfaceId());
                String foreignSubnet = foreign.describeSubnets().subnets().getFirst().subnetId();
                NetworkInterface other = RequestScopes.callAs(FOREIGN, REGION,
                        () -> service.createServiceNetworkInterface(REGION, foreignSubnet, "Classic frontend",
                                List.of(), TOKEN));
                foreignIds.add(other.getNetworkInterfaceId());
                String forged = owner.createNetworkInterface(request -> request.subnetId(subnet)).networkInterface()
                        .networkInterfaceId();
                ownedIds.add(forged);
                owner.createTags(request -> request.resources(forged).tags(Tag.builder()
                        .key("floci:elb-classic:allocation").value(TOKEN).build()));
                assertEquals(List.of(owned.getNetworkInterfaceId()), find(OWNER));
                assertEquals(List.of(other.getNetworkInterfaceId()), find(FOREIGN));
                assertNull(RequestScopes.callAs(OWNER, REGION,
                        () -> service.describeNetworkInterfaces(REGION, List.of(forged), Map.of())
                                .networkInterfaces().getFirst().getServiceAllocationToken()));
                assertEquals(owned.getNetworkInterfaceId(), owner.describeNetworkInterfaces(request ->
                        request.networkInterfaceIds(owned.getNetworkInterfaceId())).networkInterfaces()
                        .getFirst().networkInterfaceId());
                given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + OWNER
                                + "/20261119/" + REGION + "/ec2/aws4_request")
                        .formParam("Action", "DescribeNetworkInterfaces")
                        .formParam("NetworkInterfaceId.1", owned.getNetworkInterfaceId())
                        .when().post("/").then().statusCode(200)
                        .body(not(containsString("serviceAllocationToken")))
                        .body(not(containsString(TOKEN)));
            } finally {
                for (String id : ownedIds.reversed()) {
                    owner.deleteNetworkInterface(request -> request.networkInterfaceId(id));
                }
                for (String id : foreignIds.reversed()) {
                    foreign.deleteNetworkInterface(request -> request.networkInterfaceId(id));
                }
            }
            assertTrue(find(OWNER).isEmpty());
            assertTrue(find(FOREIGN).isEmpty());
        }
    }

    private List<String> find(String account) {
        return RequestScopes.callAs(account, REGION, () -> service.findServiceNetworkInterfaces(REGION, TOKEN)
                .stream().map(NetworkInterface::getNetworkInterfaceId).toList());
    }
}

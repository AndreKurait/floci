package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.Ec2Requests.ec2;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Query requests deliberately control ClientToken instead of relying on an SDK-generated token. */
@QuarkusTest
class Ec2NatGatewayClientTokenIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String CREATE = "CreateNatGatewayResponse.";

    @Test
    void suppliedClientTokenIsEchoedByTheCreateResponse() {
        try (Owned owned = Owned.open()) {
            String token = "nat-echo-" + UUID.randomUUID();
            Response response = owned.create(Map.of("ConnectivityType", "private", "ClientToken", token));
            response.then().statusCode(200);
            assertEquals(token, response.path(CREATE + "clientToken"));
        }
    }

    @Test
    void identicalPublicReplayKeepsTheGatewayInterfaceElasticIpAndCreationTags() {
        try (Owned owned = Owned.open()) {
            String allocation = owned.allocate();
            Map<String, String> request = Map.of(
                    "AllocationId", allocation,
                    "ClientToken", "nat-replay-" + UUID.randomUUID(),
                    "TagSpecification.1.ResourceType", "natgateway",
                    "TagSpecification.1.Tag.1.Key", "audit-owner",
                    "TagSpecification.1.Tag.1.Value", owned.marker);
            Response first = owned.create(request).then().statusCode(200).extract().response();
            String firstId = first.path(CREATE + "natGateway.natGatewayId");
            String firstInterface = first.path(CREATE + "natGateway.natGatewayAddressSet.item.networkInterfaceId");
            String firstAssociation = first.path(CREATE + "natGateway.natGatewayAddressSet.item.associationId");
            Map<String, String> before = owned.snapshot();
            Response repeated = owned.create(request).then().statusCode(200).extract().response();
            Map<String, String> after = owned.snapshot();
            assertAll(
                    () -> assertEquals(firstId, repeated.path(CREATE + "natGateway.natGatewayId")),
                    () -> assertEquals("available", repeated.path(CREATE + "natGateway.state")),
                    () -> assertEquals(firstInterface,
                            repeated.path(CREATE + "natGateway.natGatewayAddressSet.item.networkInterfaceId")),
                    () -> assertEquals(firstAssociation,
                            repeated.path(CREATE + "natGateway.natGatewayAddressSet.item.associationId")),
                    () -> assertEquals(List.of(firstId), owned.gatewayIds()),
                    () -> assertEquals(before, after));
        }
    }

    @Test
    void changedPrivateAddressWithTheSameTokenIsRefusedWithoutChangingOwnedState() {
        try (Owned owned = Owned.open()) {
            String token = "nat-mismatch-" + UUID.randomUUID();
            owned.create(Map.of("ConnectivityType", "private", "PrivateIpAddress", "10.196.1.50",
                    "ClientToken", token)).then().statusCode(200);
            Map<String, String> before = owned.snapshot();
            Response changed = owned.create(Map.of("ConnectivityType", "private",
                    "PrivateIpAddress", "10.196.1.51", "ClientToken", token));
            Map<String, String> after = owned.snapshot();
            assertAll(
                    () -> assertEquals(400, changed.statusCode()),
                    () -> assertEquals("IdempotentParameterMismatch", changed.path("Response.Errors.Error.Code")),
                    () -> assertEquals(before, after));
        }
    }

    @Test
    void omittedTokensCreateDistinctGatewaysAndAreNotEchoed() {
        try (Owned owned = Owned.open()) {
            Response first = owned.create(Map.of("ConnectivityType", "private"))
                    .then().statusCode(200).extract().response();
            Response second = owned.create(Map.of("ConnectivityType", "private"))
                    .then().statusCode(200).extract().response();
            assertAll(
                    () -> assertNotEquals(first.<String>path(CREATE + "natGateway.natGatewayId"),
                            second.<String>path(CREATE + "natGateway.natGatewayId")),
                    () -> assertFalse(first.asString().contains("<clientToken")),
                    () -> assertFalse(second.asString().contains("<clientToken")),
                    () -> assertEquals(2, owned.gatewayIds().size()));
        }
    }

    private static final class Owned implements AutoCloseable {
        private final String marker = "nat-client-token-" + UUID.randomUUID();
        private final List<String> allocations = new ArrayList<>();
        private String vpc;
        private String subnet;

        static Owned open() {
            Owned owned = new Owned();
            try {
                owned.vpc = ec2("CreateVpc", ACCOUNT, REGION).formParam("CidrBlock", "10.196.0.0/16")
                        .formParam("TagSpecification.1.ResourceType", "vpc")
                        .formParam("TagSpecification.1.Tag.1.Key", "audit-owner")
                        .formParam("TagSpecification.1.Tag.1.Value", owned.marker).post("/")
                        .then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
                owned.subnet = ec2("CreateSubnet", ACCOUNT, REGION).formParam("VpcId", owned.vpc)
                        .formParam("CidrBlock", "10.196.1.0/24").post("/")
                        .then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
                return owned;
            } catch (RuntimeException | Error failure) {
                try {
                    owned.close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        String allocate() {
            String id = ec2("AllocateAddress", ACCOUNT, REGION).formParam("Domain", "vpc").post("/")
                    .then().statusCode(200).extract().path("AllocateAddressResponse.allocationId");
            allocations.add(id);
            return id;
        }

        Response create(Map<String, String> parameters) {
            return ec2("CreateNatGateway", ACCOUNT, REGION).formParam("SubnetId", subnet)
                    .formParams(parameters).post("/");
        }

        List<String> gatewayIds() {
            return gateways().xmlPath().getList(
                    "DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayId", String.class);
        }

        private Response gateways() {
            return ec2("DescribeNatGateways", ACCOUNT, REGION)
                    .formParam("Filter.1.Name", "subnet-id").formParam("Filter.1.Value.1", subnet).post("/")
                    .then().statusCode(200).extract().response();
        }

        Map<String, String> snapshot() {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("gateways", withoutRequestId(gateways()));
            result.put("interfaces", withoutRequestId(ec2("DescribeNetworkInterfaces", ACCOUNT, REGION)
                    .formParam("Filter.1.Name", "subnet-id").formParam("Filter.1.Value.1", subnet).post("/")
                    .then().statusCode(200).extract().response()));
            for (String allocation : allocations) {
                result.put(allocation, withoutRequestId(ec2("DescribeAddresses", ACCOUNT, REGION)
                        .formParam("AllocationId.1", allocation).post("/")
                        .then().statusCode(200).extract().response()));
            }
            return result;
        }

        private static String withoutRequestId(Response response) {
            // Preserve the complete resource response; only the new transport request ID differs.
            return response.asString().replaceAll("<requestId>[^<]*</requestId>", "<requestId/>");
        }

        @Override
        public void close() {
            List<Throwable> failures = new ArrayList<>();
            if (subnet != null) {
                cleanup(failures, () -> {
                    // Include unexpected resources produced by the failing BEFORE implementation.
                    for (String gateway : gatewayIds()) {
                        cleanup(failures, () -> ec2("DeleteNatGateway", ACCOUNT, REGION)
                                .formParam("NatGatewayId", gateway).post("/").then().statusCode(200));
                    }
                    assertEquals(List.of(), gatewayIds());
                });
            }
            for (String allocation : allocations) {
                cleanup(failures, () -> ec2("ReleaseAddress", ACCOUNT, REGION)
                        .formParam("AllocationId", allocation).post("/").then().statusCode(200));
            }
            if (subnet != null) {
                cleanup(failures, () -> ec2("DeleteSubnet", ACCOUNT, REGION)
                        .formParam("SubnetId", subnet).post("/").then().statusCode(200));
            }
            if (vpc != null) {
                cleanup(failures, () -> ec2("DeleteVpc", ACCOUNT, REGION)
                        .formParam("VpcId", vpc).post("/").then().statusCode(200));
            }
            if (!failures.isEmpty()) {
                AssertionError failure = new AssertionError("Owned NAT fixture cleanup failed");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }

        private static void cleanup(List<Throwable> failures, Runnable action) {
            try {
                action.run();
            } catch (RuntimeException | Error failure) {
                failures.add(failure);
            }
        }
    }
}

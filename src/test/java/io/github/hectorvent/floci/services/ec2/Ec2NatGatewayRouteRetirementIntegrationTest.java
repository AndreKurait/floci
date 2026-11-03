package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Route;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class Ec2NatGatewayRouteRetirementIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"public", "private"})
    void deletingGatewayRetainsBlackholesUntilRoutesAreReplacedOrDeleted(String connectivity) {
        try (Topology network = new Topology("814530000001", Region.US_EAST_1)) {
            String deleted = network.gateway(connectivity);
            String surviving = network.gateway(connectivity);
            String first = network.routeTable();
            String second = network.routeTable();
            network.route(first, "0.0.0.0/0", deleted);
            network.route(second, "0.0.0.0/0", deleted);
            network.route(first, "198.51.100.0/24", surviving);
            assertEquals("active", network.route(first, "0.0.0.0/0").stateAsString());

            network.deleteGateway(deleted);

            for (String table : List.of(first, second)) {
                Route route = network.route(table, "0.0.0.0/0");
                assertEquals("blackhole", route.stateAsString());
                assertEquals(deleted, route.natGatewayId());
                assertEquals("CreateRoute", route.originAsString());
                assertEquals("active", network.route(table, "10.134.0.0/16").stateAsString());
            }
            assertEquals("active", network.route(first, "198.51.100.0/24").stateAsString());
            assertEquals(surviving, network.route(first, "198.51.100.0/24").natGatewayId());

            network.client.replaceRoute(request -> request.routeTableId(first)
                    .destinationCidrBlock("0.0.0.0/0").natGatewayId(surviving));
            assertEquals("active", network.route(first, "0.0.0.0/0").stateAsString());
            assertEquals(surviving, network.route(first, "0.0.0.0/0").natGatewayId());
            network.client.deleteRoute(request -> request.routeTableId(second).destinationCidrBlock("0.0.0.0/0"));
            assertFalse(network.routes(second).stream().anyMatch(route -> "0.0.0.0/0".equals(route.destinationCidrBlock())));
        }
    }

    @Test
    void anotherAccountOrRegionCannotRetireTheGatewayOrItsRoutes() {
        try (Topology network = new Topology("814530000002", Region.US_EAST_1);
             Ec2Client otherAccount = AwsTestClients.create(Ec2Client.builder(), "814530000003", Region.US_EAST_1);
             Ec2Client otherRegion = AwsTestClients.create(Ec2Client.builder(), "814530000002", Region.EU_WEST_1)) {
            String gateway = network.gateway("private");
            String table = network.routeTable();
            network.route(table, "0.0.0.0/0", gateway);
            for (Ec2Client client : List.of(otherAccount, otherRegion)) {
                Ec2Exception failure = assertThrows(Ec2Exception.class,
                        () -> client.deleteNatGateway(request -> request.natGatewayId(gateway)));
                assertEquals("NatGatewayNotFound", failure.awsErrorDetails().errorCode());
                assertEquals("active", network.route(table, "0.0.0.0/0").stateAsString());
            }
            network.deleteGateway(gateway);
            assertEquals("blackhole", network.route(table, "0.0.0.0/0").stateAsString());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "create, missing", "replace, missing",
            "create, deleted", "replace, deleted",
            "create, other-account", "replace, other-account",
            "create, other-region", "replace, other-region"
    })
    void routeMutationRejectsUnavailableNatTargetsWithoutChangingTheTable(String operation, String unavailable) {
        try (Topology network = new Topology("814540000001", Region.US_EAST_1);
             Topology otherAccount = new Topology("814540000002", Region.US_EAST_1);
             Topology otherRegion = new Topology("814540000001", Region.EU_WEST_1)) {
            String table = network.routeTable();
            network.route(table, "0.0.0.0/0", network.gateway("private"));
            String target = switch (unavailable) {
                case "missing" -> "nat-fffffffffffffffff";
                case "deleted" -> {
                    String deleted = network.gateway("private");
                    network.deleteGateway(deleted);
                    yield deleted;
                }
                case "other-account" -> otherAccount.gateway("private");
                case "other-region" -> otherRegion.gateway("private");
                default -> throw new IllegalArgumentException(unavailable);
            };
            List<Route> before = network.routes(table);

            Ec2Exception failure = assertThrows(Ec2Exception.class, () -> {
                if ("create".equals(operation)) {
                    network.route(table, "192.0.2.0/24", target);
                } else {
                    network.client.replaceRoute(request -> request.routeTableId(table)
                            .destinationCidrBlock("0.0.0.0/0").natGatewayId(target));
                }
            });

            assertEquals("NatGatewayNotFound", failure.awsErrorDetails().errorCode());
            assertEquals(400, failure.statusCode());
            assertEquals(before, network.routes(table), "A rejected target must leave every route unchanged");
        }
    }

    private static final class Topology implements AutoCloseable {
        private final Ec2Client client;
        private final String vpc;
        private final String subnet;
        private final String internetGateway;
        private final List<String> gateways = new ArrayList<>();
        private final List<String> allocations = new ArrayList<>();
        private final List<String> tables = new ArrayList<>();

        private Topology(String account, Region region) {
            client = AwsTestClients.create(Ec2Client.builder(), account, region);
            vpc = client.createVpc(request -> request.cidrBlock("10.134.0.0/16")).vpc().vpcId();
            subnet = client.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.134.1.0/24"))
                    .subnet().subnetId();
            internetGateway = client.createInternetGateway(request -> {}).internetGateway().internetGatewayId();
            client.attachInternetGateway(request -> request.internetGatewayId(internetGateway).vpcId(vpc));
        }

        private String gateway(String connectivity) {
            String allocation = null;
            if ("public".equals(connectivity)) {
                allocation = client.allocateAddress(request -> request.domain("vpc")).allocationId();
                allocations.add(allocation);
            }
            String allocationId = allocation;
            String gateway = client.createNatGateway(request -> request.subnetId(subnet)
                    .connectivityType(connectivity).allocationId(allocationId)).natGateway().natGatewayId();
            gateways.add(gateway);
            return gateway;
        }

        private String routeTable() {
            String table = client.createRouteTable(request -> request.vpcId(vpc)).routeTable().routeTableId();
            tables.add(table);
            return table;
        }

        private void route(String table, String destination, String gateway) {
            client.createRoute(request -> request.routeTableId(table)
                    .destinationCidrBlock(destination).natGatewayId(gateway));
        }

        private List<Route> routes(String table) {
            return client.describeRouteTables(request -> request.routeTableIds(table)).routeTables().getFirst().routes();
        }

        private Route route(String table, String destination) {
            return routes(table).stream().filter(route -> destination.equals(route.destinationCidrBlock()))
                    .findFirst().orElseThrow();
        }

        private void deleteGateway(String gateway) {
            client.deleteNatGateway(request -> request.natGatewayId(gateway));
            gateways.remove(gateway);
        }

        @Override
        public void close() {
            try {
                for (String gateway : List.copyOf(gateways)) {
                    deleteGateway(gateway);
                }
                for (String table : tables) {
                    client.deleteRouteTable(request -> request.routeTableId(table));
                }
                for (String allocation : allocations) {
                    client.releaseAddress(request -> request.allocationId(allocation));
                }
                client.detachInternetGateway(request -> request.internetGatewayId(internetGateway).vpcId(vpc));
                client.deleteInternetGateway(request -> request.internetGatewayId(internetGateway));
                client.deleteSubnet(request -> request.subnetId(subnet));
                client.deleteVpc(request -> request.vpcId(vpc));
            } finally {
                client.close();
            }
        }
    }
}

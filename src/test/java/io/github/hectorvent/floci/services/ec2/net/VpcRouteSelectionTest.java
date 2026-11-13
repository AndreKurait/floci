package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.InternetGatewayAttachment;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.NatGatewayAddress;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.RouteTableAssociation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpcRouteSelectionTest {
    @Test
    void explicitSubnetAssociationReplacesMainUntilDisassociated() {
        Route mainRoute = route("0.0.0.0/0", "igw-main");
        Route explicitRoute = route("0.0.0.0/0", "igw-explicit");
        RouteTable main = table("vpc-own", null, mainRoute);
        RouteTable explicit = table("vpc-own", "subnet-own", explicitRoute);
        RouteTable foreign = table("vpc-other", "subnet-own", route("198.51.100.7/32", "igw-foreign"));
        List<RouteTable> tables = List.of(main, foreign, explicit);
        assertSame(explicitRoute, selected(tables));
        explicit.getAssociations().getFirst().setAssociationState("disassociated");
        assertSame(mainRoute, selected(tables));
        assertTrue(VpcRouteSelection.entries("vpc-missing", "subnet-own", tables, ignored -> List.of()).isEmpty());
    }

    @Test
    void aMoreSpecificBlackholeDoesNotFallThroughToTheDefaultRoute() {
        Route internet = route("0.0.0.0/0", "igw-main");
        Route blocked = route("198.51.100.0/24", "igw-deleted");
        blocked.setState("blackhole");
        assertSame(blocked, selected(List.of(table("vpc-own", null, internet, blocked))));
        assertFalse(VpcRouteSelection.internet(blocked, "vpc-own", List.of(gateway("igw-deleted", "vpc-own"))));
    }

    @Test
    void ambiguousAssociationsAndEqualPrefixTargetsFailClosed() {
        RouteTable first = table("vpc-own", null, route("0.0.0.0/0", "igw-one"));
        RouteTable second = table("vpc-own", null, route("0.0.0.0/0", "igw-two"));
        assertThrows(IllegalStateException.class, () -> selected(List.of(first, second)));
        first.getRoutes().addAll(second.getRoutes());
        assertThrows(IllegalStateException.class, () -> selected(List.of(first)));
    }

    @Test
    void prefixListDestinationsParticipateInLongestPrefixSelection() {
        Route prefix = new Route();
        prefix.setDestinationPrefixListId("pl-local");
        prefix.setGatewayId("vpce-service");
        RouteTable table = table("vpc-own", null, route("0.0.0.0/0", "igw-main"), prefix);
        List<VpcRouteSelection.Entry> entries = VpcRouteSelection.entries("vpc-own", "subnet-own", List.of(table),
                id -> {
                    assertEquals("pl-local", id);
                    return List.of("198.51.100.0/24", "2001:db8::/32");
                });
        assertSame(prefix, VpcRouteSelection.to("198.51.100.7", entries).orElseThrow());
        assertEquals("igw-main", VpcRouteSelection.to("192.0.2.1", entries).orElseThrow().getGatewayId());
    }

    @Test
    void internetRoutingRequiresAnAttachmentToTheSameVpc() {
        Route route = route("0.0.0.0/0", "igw-main");
        assertFalse(VpcRouteSelection.internet(route, "vpc-own", List.of(gateway("igw-main", "vpc-other"))));
        InternetGateway gateway = gateway("igw-main", "vpc-own");
        assertTrue(VpcRouteSelection.internet(route, "vpc-own", List.of(gateway)));
        gateway.getAttachments().getFirst().setState("detached");
        assertFalse(VpcRouteSelection.internet(route, "vpc-own", List.of(gateway)));
        route.setNatGatewayId("nat-ambiguous");
        assertFalse(VpcRouteSelection.internet(route, "vpc-own", List.of(gateway("igw-main", "vpc-own"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "failed", "deleting", "deleted"})
    void unavailableNatGatewaysDoNotAdmitRoutes(String state) {
        NatGateway gateway = nat();
        gateway.setState(state);
        assertTrue(VpcRouteSelection.nat(natRoute(), "vpc-own", List.of(gateway)).isEmpty());
    }

    @Test
    void natRequiresTheOwningVpcAndAnActualPublicAddress() {
        NatGateway gateway = nat();
        assertSame(gateway, VpcRouteSelection.nat(natRoute(), "vpc-own", List.of(gateway)).orElseThrow());
        assertTrue(VpcRouteSelection.nat(natRoute(), "vpc-other", List.of(gateway)).isEmpty());
        gateway.setConnectivityType("private");
        assertTrue(VpcRouteSelection.nat(natRoute(), "vpc-own", List.of(gateway)).isEmpty());
        gateway.setConnectivityType("public");
        gateway.getNatGatewayAddresses().getFirst().setPublicIp(null);
        assertTrue(VpcRouteSelection.nat(natRoute(), "vpc-own", List.of(gateway)).isEmpty());
    }

    private Route selected(List<RouteTable> tables) {
        return VpcRouteSelection.to("198.51.100.7",
                VpcRouteSelection.entries("vpc-own", "subnet-own", tables, ignored -> List.of())).orElseThrow();
    }

    private RouteTable table(String vpc, String subnet, Route... routes) {
        RouteTable table = new RouteTable();
        table.setVpcId(vpc);
        table.setRoutes(new ArrayList<>(List.of(routes)));
        RouteTableAssociation association = new RouteTableAssociation();
        association.setMain(subnet == null);
        association.setSubnetId(subnet);
        table.getAssociations().add(association);
        return table;
    }

    private Route route(String destination, String target) {
        return new Route(destination, target, "CreateRoute");
    }

    private Route natRoute() {
        Route route = route("0.0.0.0/0", null);
        route.setNatGatewayId("nat-own");
        return route;
    }

    private NatGateway nat() {
        NatGateway gateway = new NatGateway();
        gateway.setNatGatewayId("nat-own");
        gateway.setVpcId("vpc-own");
        NatGatewayAddress address = new NatGatewayAddress();
        address.setPrivateIp("10.20.1.10");
        address.setPublicIp("203.0.113.1");
        gateway.getNatGatewayAddresses().add(address);
        return gateway;
    }

    private InternetGateway gateway(String id, String vpc) {
        InternetGateway gateway = new InternetGateway();
        gateway.setInternetGatewayId(id);
        gateway.getAttachments().add(new InternetGatewayAttachment(vpc, "available"));
        return gateway;
    }
}

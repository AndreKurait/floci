package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.InternetGatewayAttachment;
import io.github.hectorvent.floci.services.ec2.model.Route;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VpcFrontendIngressTest {
    @Test
    void moreSpecificBlackholeAndLocalRoutesPrecedeTheInternetDefault() {
        Route blocked = route("192.0.2.0/24", "igw");
        blocked.setState("blackhole");
        List<VpcFrontendIngress.Decision> decisions = decisions(
                route("0.0.0.0/0", "igw"), blocked, route("10.0.0.0/16", "local"));
        VpcFrontendIngress frontend = new VpcFrontendIngress("240.0.0.2", "eth2", Set.of(443), decisions);
        assertEquals(List.of(
                new VpcFrontendIngress.Decision(VpcRouteSelection.cidr("192.0.2.0/24"), false),
                new VpcFrontendIngress.Decision(VpcRouteSelection.cidr("10.0.0.0/16"), false),
                new VpcFrontendIngress.Decision(VpcRouteSelection.cidr("0.0.0.0/0"), true)), frontend.routes());
    }

    @Test
    void aNatRouteCannotAdmitAnInternetFrontend() {
        Route nat = route("0.0.0.0/0", null);
        nat.setNatGatewayId("nat");
        assertFalse(decisions(nat).getFirst().internet());
    }

    @Test
    void anInternetGatewayMustRemainAttachedToTheFrontendVpc() {
        Route internet = route("0.0.0.0/0", "igw");
        InternetGateway gateway = gateway("other-vpc");
        List<VpcRouteSelection.Entry> entries = entries(internet);
        assertFalse(VpcFrontendIngress.decisions("vpc", entries, List.of(gateway)).getFirst().internet());
        gateway.getAttachments().getFirst().setVpcId("vpc");
        assertTrue(VpcFrontendIngress.decisions("vpc", entries, List.of(gateway)).getFirst().internet());
        gateway.getAttachments().getFirst().setState("detached");
        assertFalse(VpcFrontendIngress.decisions("vpc", entries, List.of(gateway)).getFirst().internet());
    }

    @Test
    void equalPrefixAmbiguityIsRejectedEvenUnderAMoreSpecificRoute() {
        assertThrows(IllegalStateException.class, () -> decisions(route("0.0.0.0/0", "igw"),
                route("0.0.0.0/0", "other-igw"), route("0.0.0.0/1", "igw")));
        assertEquals(1, decisions(route("0.0.0.0/0", "igw"), route("0.0.0.0/0", "igw")).size());
    }

    @Test
    void onlyTheOwnedDestinationAndPublishedPortsGetReplyAdmission() {
        VpcFrontendIngress frontend = new VpcFrontendIngress("240.0.0.2", "eth2", Set.of(443, 80),
                decisions(route("0.0.0.0/0", "igw")));
        StringBuilder rules = new StringBuilder();
        frontend.appendIngress(rules);
        frontend.appendReply(rules);
        assertTrue(rules.toString().contains("iifname \"eth2\" ip daddr 240.0.0.2 tcp dport { 80, 443 }"));
        assertTrue(rules.toString().contains("ct direction reply ct original ip daddr 240.0.0.2"
                + " ct original proto-dst { 80, 443 } ip daddr 0.0.0.0/0 counter accept"));
        assertTrue(rules.toString().contains("ingress iifname \"eth2\" ip daddr 240.0.0.2 counter drop"));
    }

    @Test
    void withdrawingAllPortsRetainsTheTransportDenial() {
        VpcFrontendIngress frontend = new VpcFrontendIngress("240.0.0.2", "eth2", Set.of(),
                decisions(route("0.0.0.0/0", "igw")));
        StringBuilder rules = new StringBuilder();
        frontend.appendIngress(rules);
        frontend.appendReply(rules);
        assertFalse(rules.toString().contains("accept"));
        assertTrue(rules.toString().contains("ip daddr 240.0.0.2 counter drop"));
        assertTrue(rules.toString().contains("ct original ip daddr 240.0.0.2 counter drop"));
        assertFalse(rules.toString().contains("{  }"));
    }

    @Test
    void publishedPortsAreCopiedAndConfigurationCannotInjectCommands() {
        Set<Integer> ports = new HashSet<>(Set.of(443));
        VpcFrontendIngress frontend = new VpcFrontendIngress("240.0.0.2", "eth2", ports, List.of());
        ports.clear();
        assertEquals(Set.of(443), frontend.ports());
        assertThrows(IllegalArgumentException.class,
                () -> new VpcFrontendIngress("host.example.test", "eth2", Set.of(443), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new VpcFrontendIngress("240.0.0.2", "eth2; true", Set.of(443), List.of()));
        assertThrows(IllegalArgumentException.class, () -> VpcFrontendIngress.checkedPorts(Set.of(0)));
        assertThrows(IllegalArgumentException.class, () -> VpcFrontendIngress.checkedPorts(Set.of(65536)));
    }

    private static List<VpcFrontendIngress.Decision> decisions(Route... routes) {
        return VpcFrontendIngress.decisions("vpc", entries(routes), List.of(gateway("vpc")));
    }

    private static List<VpcRouteSelection.Entry> entries(Route... routes) {
        return List.of(routes).stream().map(route ->
                new VpcRouteSelection.Entry(VpcRouteSelection.cidr(route.getDestinationCidrBlock()), route)).toList();
    }

    private static Route route(String cidr, String gateway) {
        return new Route(cidr, gateway, "CreateRoute");
    }

    private static InternetGateway gateway(String vpc) {
        InternetGateway gateway = new InternetGateway();
        gateway.setInternetGatewayId("igw");
        gateway.getAttachments().add(new InternetGatewayAttachment(vpc, "available"));
        return gateway;
    }
}

package io.floci.aws.services.ec2.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VpcPublicInstanceIngressTest {
    @Test
    void onlyTranslatedPublicConnectionsGainInternetReplyAdmission() {
        StringBuilder rules = new StringBuilder();
        new VpcPublicInstanceIngress("172.18.0.2", "10.0.1.10", "eth0",
                List.of(decision("0.0.0.0/0", true))).append(rules);
        assertTrue(rules.toString().contains("ingress iifname \"eth0\" ip daddr 10.0.1.10"
                + " ct direction original ct status dnat ct original ip daddr 172.18.0.2"
                + " ip saddr 0.0.0.0/0 counter accept"));
        assertTrue(rules.toString().contains("egress oifname \"eth0\" ip saddr 10.0.1.10"
                + " ct direction reply ct status dnat ct original ip daddr 172.18.0.2"
                + " ip daddr 0.0.0.0/0 counter accept"));
        assertFalse(rules.toString().contains("ct state established,related counter accept"));
        assertFalse(rules.toString().contains("egress ip daddr 0.0.0.0/0 counter accept"));
    }

    @Test
    void moreSpecificNonInternetRoutesPrecedeTheDefaultInBothDirections() {
        StringBuilder rules = new StringBuilder();
        new VpcPublicInstanceIngress("172.18.0.2", "10.0.1.10", "eth0",
                List.of(decision("0.0.0.0/0", true), decision("192.0.2.0/24", false))).append(rules);
        String result = rules.toString();
        assertTrue(result.indexOf("ip saddr 192.0.2.0/24 counter drop")
                < result.indexOf("ip saddr 0.0.0.0/0 counter accept"));
        assertTrue(result.indexOf("ip daddr 192.0.2.0/24 counter drop")
                < result.indexOf("ip daddr 0.0.0.0/0 counter accept"));
    }

    @Test
    void withdrawnRoutesKeepPublicRepliesDenied() {
        StringBuilder rules = new StringBuilder();
        new VpcPublicInstanceIngress("172.18.0.2", "10.0.1.10", "eth0", List.of()).append(rules);
        assertFalse(rules.toString().contains("accept"));
        assertTrue(rules.toString().contains("ct direction reply ct status dnat"
                + " ct original ip daddr 172.18.0.2 counter drop"));
    }

    @Test
    void nativeAddressPolicyPrecedesBroaderLocalAndControlRoutes() {
        VpcPublicInstanceIngress ingress = new VpcPublicInstanceIngress(
                "172.18.0.2", "10.0.1.10", "eth0", List.of());
        String script = VpcRoutingCommands.endpoint("10.0.1.10", "eth1",
                List.of(new VpcRoutingCommands.LocalRoute(VpcRouteSelection.cidr("10.0.0.0/16"), true)),
                List.of(), List.of(), List.of(), List.of(), false, null, List.of(), ingress);
        assertTrue(script.contains("ct original ip daddr 172.18.0.2 counter drop"),
                "The native-address deny rule must exist");
        assertTrue(script.contains("egress ip daddr 10.0.0.0/16 counter accept"),
                "The broader local-route allow rule must exist");
        assertTrue(script.indexOf("ct original ip daddr 172.18.0.2 counter drop")
                < script.indexOf("egress ip daddr 10.0.0.0/16 counter accept"));
    }

    @Test
    void nativeAndDedicatedPeerInterfacesBothTranslateWithoutChangingSourceRoutes() {
        String script = VpcRoutingCommands.publicInstanceSourceInterfaces("10.0.1.10", "172.18.0.2",
                List.of("eth0", "eth2"), List.of(
                        new VpcRoutingCommands.Hop("198.51.100.2", "172.30.0.2", "eth2", false),
                        new VpcRoutingCommands.Hop("198.18.0.1", "172.18.0.3", "eth0", false)));
        assertTrue(script.contains("incoming iifname \"eth0\" ip daddr 172.18.0.2 dnat to 10.0.1.10"));
        assertTrue(script.contains("incoming iifname \"eth2\" ip daddr 172.18.0.2 dnat to 10.0.1.10"));
        assertTrue(script.contains("outgoing oifname \"eth2\" ip saddr 10.0.1.10"
                + " ip daddr 198.51.100.2 snat to 172.18.0.2"));
        assertTrue(script.contains("outgoing oifname \"eth0\" ip saddr 10.0.1.10"
                + " ip daddr 198.18.0.1 snat to 172.18.0.2"));
    }

    private static VpcFrontendIngress.Decision decision(String cidr, boolean internet) {
        return new VpcFrontendIngress.Decision(VpcRouteSelection.cidr(cidr), internet);
    }
}

package io.github.hectorvent.floci.services.ec2.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpcPublicApiCommandsTest {
    @Test
    void nativePublicSourceFollowsEachSelectedDeviceAndWithdrawalFlushesBoth() {
        VpcRoutingCommands.Hop api = new VpcRoutingCommands.Hop("192.0.2.1", "172.18.0.254", "eth0", false);
        VpcRoutingCommands.Hop peer = new VpcRoutingCommands.Hop("172.18.0.3", "240.0.0.3", "eth2", false);
        String script = VpcRoutingCommands.publicInstanceSources(
                "10.0.2.10", "172.18.0.2", List.of(api, peer, api));
        assertEquals(List.of(
                "add rule ip floci_instance_api outgoing oifname \"eth2\""
                        + " ip saddr 10.0.2.10 ip daddr 172.18.0.3 snat to 172.18.0.2",
                "add rule ip floci_instance_api outgoing oifname \"eth0\""
                        + " ip saddr 10.0.2.10 ip daddr 192.0.2.1 snat to 172.18.0.2"),
                script.lines().filter(line -> line.startsWith("add rule ip floci_instance_api")).toList());
        String withdrawn = VpcRoutingCommands.publicInstanceSources("10.0.2.10", "172.18.0.2", List.of());
        assertTrue(withdrawn.contains("flush chain ip floci_instance_api outgoing"));
        assertFalse(withdrawn.contains("snat to"));
    }

    @Test
    void nativePublicApiCallerRetainsItsExactSourceOnTheSelectedBridgeInterface() {
        String script = VpcRoutingCommands.publicApi("192.0.2.1", "172.18.0.1", 14566, 14566, "eth1",
                List.of(new VpcRoutingCommands.Hop("172.18.0.2", "172.18.0.2", "eth0", true)));
        assertTrue(script.contains("ip -4 route replace 172.18.0.2/32 via 172.18.0.2 dev eth0 src 192.0.2.1"));
        assertTrue(script.contains("ingress iifname \"eth0\" ip saddr 172.18.0.2"
                + " ip daddr 192.0.2.1 tcp dport { 80, 443 } counter accept"));
        assertTrue(script.contains("egress oifname \"eth0\" ip saddr 192.0.2.1 ip daddr 172.18.0.2"
                + " tcp sport { 80, 443 } ct state established,related counter accept"));
        assertFalse(script.contains("ip saddr 240."));
    }

    @Test
    void bridgeAllowsOnlyTheDeclaredHttpAndHttpsUpstreamPorts() {
        String script = VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", 8080, 8443, "eth1", List.of());
        assertTrue(script.contains("policy drop"));
        List<String> outbound = script.lines().filter(line -> line.contains("egress ip daddr")).toList();
        assertEquals(List.of(
                "add rule inet floci_public_api egress ip daddr 172.20.0.5 tcp dport 8080 counter accept",
                "add rule inet floci_public_api egress ip daddr 172.20.0.5 tcp dport 8443 counter accept"), outbound);
        assertTrue(script.contains("tcp sport 8080 ct state established,related counter accept"));
        assertTrue(script.contains("tcp sport 8443 ct state established,related counter accept"));
    }

    @Test
    void defaultSharedListenerNeedsOnlyOneUpstreamPermission() {
        String script = VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", 4566, 4566, "eth1", List.of());
        assertEquals(1, script.lines().filter(line -> line.contains("egress ip daddr")).count());
    }

    @Test
    void invalidDestinationsCannotBecomeNamespaceCommands() {
        for (int port : List.of(0, -1, 65536)) {
            assertThrows(IllegalArgumentException.class,
                    () -> VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", port, 443, "eth1", List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", 80, port, "eth1", List.of()));
        }
        for (String address : List.of("192.0.2.1", "172.20.0.5;false", "0.0.0.0/0", "peer.example")) {
            assertThrows(IllegalArgumentException.class,
                    () -> VpcRoutingCommands.publicApi("192.0.2.1", address, 80, 443, "eth1", List.of()));
        }
    }
}

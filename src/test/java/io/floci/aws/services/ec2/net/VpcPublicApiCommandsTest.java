package io.floci.aws.services.ec2.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpcPublicApiCommandsTest {
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
    void publicInstanceTranslatesItsPublicAddressToThePrivateListener() {
        String script = VpcRoutingCommands.publicInstanceSource(
                "10.0.2.10", "172.18.0.2", "eth2", List.of("172.18.0.3"));
        assertTrue(script.contains("add rule ip floci_instance_api incoming iifname \"eth2\""
                + " ip daddr 172.18.0.2 dnat to 10.0.2.10"));
        assertTrue(script.contains("ip saddr 10.0.2.10 ip daddr 172.18.0.3 snat to 172.18.0.2"));
    }

    @Test
    void publicInstancePreservesSourceOnEachDeclaredRouteInterface() {
        String script = VpcRoutingCommands.publicInstanceSourceRoutes("10.0.2.10", "172.18.0.2", "eth2",
                List.of(new VpcRoutingCommands.Hop("172.18.0.3", "240.0.0.3", "eth2", false),
                        new VpcRoutingCommands.Hop("192.0.2.1", "172.18.0.4", "eth0", false),
                        new VpcRoutingCommands.Hop("203.0.113.1", "240.0.0.5", "eth2", true)));
        assertTrue(script.contains("outgoing oifname \"eth2\" ip saddr 10.0.2.10"
                + " ip daddr 172.18.0.3 snat to 172.18.0.2"));
        assertTrue(script.contains("outgoing oifname \"eth0\" ip saddr 10.0.2.10"
                + " ip daddr 192.0.2.1 snat to 172.18.0.2"));
        assertFalse(script.contains("ip daddr 203.0.113.1 snat"));
    }

    @Test
    void withdrawingThePublicAddressRemovesBothTranslationDirections() {
        String script = VpcRoutingCommands.publicInstanceSource(null, null, null, List.of());
        assertTrue(script.contains("flush chain ip floci_instance_api incoming"));
        assertTrue(script.contains("flush chain ip floci_instance_api outgoing"));
        assertFalse(script.contains("dnat to"));
        assertFalse(script.contains("snat to"));
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

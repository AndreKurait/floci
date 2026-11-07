package io.github.hectorvent.floci.services.ec2.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

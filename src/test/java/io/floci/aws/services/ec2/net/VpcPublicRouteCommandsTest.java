package io.floci.aws.services.ec2.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VpcPublicRouteCommandsTest {
    @Test
    void deniedDestinationReachesOutputAndDropsBeforeLocalAllow() {
        String script = VpcRoutingCommands.endpoint("10.0.1.10", "eth1",
                List.of(new VpcRoutingCommands.LocalRoute(VpcRouteSelection.cidr("10.0.0.0/16"), true)), List.of(),
                List.of("10.0.2.10"), List.of(), List.of(), false, null, List.of());
        assertTrue(script.contains("ip -4 route replace 10.0.2.10/32 dev eth1 src 10.0.1.10 proto 186"));
        String drop = "egress ip daddr 10.0.2.10 counter drop";
        assertTrue(script.contains(drop));
        assertTrue(script.indexOf(drop) < script.indexOf("egress ip daddr 10.0.0.0/16 counter accept"));
        assertFalse(script.contains("daddr 10.0.2.10 counter accept"));
    }

    @Test
    void deniedDestinationAndDeviceCannotInjectCommands() {
        assertThrows(IllegalArgumentException.class, () -> VpcRoutingCommands.endpoint(
                "10.0.1.10", "eth1", List.of(), List.of(), List.of("10.0.2.10;false"),
                List.of(), List.of(), false, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> VpcRoutingCommands.endpoint(
                "10.0.1.10", "eth1;false", List.of(), List.of(), List.of("10.0.2.10"),
                List.of(), List.of(), false, null, List.of()));
    }

    @Test
    void publicSourceTranslationIsDestinationSpecificAndDeterministic() {
        String script = VpcRoutingCommands.publicInstanceSource("10.0.1.10", "172.18.0.2", "eth2",
                List.of("172.18.0.4", "172.18.0.3", "172.18.0.4"));
        List<String> translations = script.lines().filter(line -> line.contains("snat to")).toList();
        assertEquals(List.of(
                "add rule ip floci_instance_api outgoing oifname \"eth2\" ip saddr 10.0.1.10"
                        + " ip daddr 172.18.0.3 snat to 172.18.0.2",
                "add rule ip floci_instance_api outgoing oifname \"eth2\" ip saddr 10.0.1.10"
                        + " ip daddr 172.18.0.4 snat to 172.18.0.2"), translations);
        String withdrawn = VpcRoutingCommands.publicInstanceSource(null, null, null);
        assertTrue(withdrawn.contains("flush chain ip floci_instance_api outgoing"));
        assertFalse(withdrawn.contains("snat to"));
    }

    @Test
    void apiBridgeRejectsUnsolicitedOutboundRouteAndKeepsBackendsDistinct() {
        assertThrows(IllegalArgumentException.class, () -> VpcRoutingCommands.publicApi(
                "192.0.2.1", "172.20.0.5", 8080, "172.20.0.6", 8443, "eth1",
                List.of(new VpcRoutingCommands.Hop("172.18.0.2", "240.0.0.2", "eth1", false))));
        String script = VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", 8080,
                "172.20.0.6", 8443, "eth1",
                List.of(new VpcRoutingCommands.Hop("172.18.0.2", "240.0.0.2", "eth1", true)));
        assertTrue(script.contains("egress ip daddr 172.20.0.5 tcp dport 8080 counter accept"));
        assertTrue(script.contains("egress ip daddr 172.20.0.6 tcp dport 8443 counter accept"));
        assertTrue(script.contains("tcp sport { 80, 443 } ct state established,related counter accept"));
        assertFalse(script.contains("egress ip daddr 172.20.0.5 tcp dport 8443"));
    }
}

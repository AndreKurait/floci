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
    void nativeTranslationScopesSourceNatToTheOriginalApiTuple() {
        String script = VpcRoutingCommands.nativePublicApi("10.0.1.10", "172.18.0.11",
                new VpcRoutingCommands.NativeApi("172.18.0.20", 8080, 8443, "eth0"));

        assertTrue(script.contains("type nat hook output priority dstnat;"));
        assertTrue(script.contains("ip daddr 192.0.2.1 tcp dport 80 dnat to 172.18.0.20:8080"));
        assertTrue(script.contains("ip daddr 192.0.2.1 tcp dport 443 dnat to 172.18.0.20:8443"));
        assertTrue(script.contains("oifname \"eth0\" ip saddr 10.0.1.10 ip daddr 172.18.0.20 tcp dport 8443"
                + " ct original ip daddr 192.0.2.1 ct original proto-dst 443 snat to 172.18.0.11"));
        assertFalse(script.contains("type filter hook output"),
                "Translation must not replace security-group or route permissions in OUTPUT");
        assertFalse(script.contains("hook prerouting"), "The caller namespace owns both directions of conntrack");
    }

    @Test
    void translatedOutputRouteChecksItsTupleWithoutUsingTheOriginalRoutesDevice() {
        VpcRoutingCommands.NativeApi api = new VpcRoutingCommands.NativeApi("172.18.0.20", 8080, 8443, "eth0");
        String script = VpcRoutingCommands.endpoint("10.0.1.10", "eth1", List.of(),
                List.of(new VpcRoutingCommands.Hop("192.0.2.1", "240.0.0.20", "eth2", false)),
                List.of(), List.of(), List.of(), false, null, List.of(), List.of(), api);

        String tuple = "meta l4proto tcp ct direction original ct status dnat ct original ip daddr 192.0.2.1";
        assertTrue(script.contains(tuple + " ct original proto-dst 443 ip daddr 172.18.0.20"
                + " tcp dport 8443 counter accept"));
        assertFalse(script.lines().filter(line -> line.contains(tuple)).anyMatch(line -> line.contains("oifname")),
                "OUTPUT's device still names the synthetic destination's original route after DNAT");
        assertTrue(script.contains(tuple + " counter drop"),
                "An existing translation must not fall through after its route or upstream changes");
        assertFalse(script.contains("egress ip daddr 172.18.0.20"),
                "Direct upstream traffic has no translation identity");
    }

    @Test
    void translatedEgressRequiresTheActualUpstreamDeviceBeforeSourceNat() {
        String script = VpcRoutingCommands.nativePublicApi("10.0.1.10", "172.18.0.11",
                new VpcRoutingCommands.NativeApi("172.18.0.20", 8080, 8443, "eth0"));
        String tuple = "meta l4proto tcp ct direction original ct status dnat ct original ip daddr 192.0.2.1";
        String rule = "add rule ip floci_native_api egress " + tuple;

        assertTrue(script.contains("type filter hook postrouting priority filter;"),
                "The actual device must be checked on every packet before source NAT priority srcnat");
        assertEquals(List.of(
                rule + " ct original proto-dst 80 ip daddr 172.18.0.20 tcp dport 8080"
                        + " oifname \"eth0\" counter accept",
                rule + " ct original proto-dst 443 ip daddr 172.18.0.20 tcp dport 8443"
                        + " oifname \"eth0\" counter accept",
                rule + " counter drop"),
                script.lines().filter(line -> line.startsWith("add rule ip floci_native_api egress ")).toList());
        assertFalse(script.contains("ct state established"),
                "An established translation must not bypass the current upstream device check");
    }

    @Test
    void withdrawalFlushesTranslationsAndDeniesExistingTranslatedEgress() {
        String script = VpcRoutingCommands.nativePublicApi(null, null, null);

        assertTrue(script.contains("flush chain ip floci_native_api destination"));
        assertTrue(script.contains("flush chain ip floci_native_api egress"));
        assertTrue(script.contains("flush chain ip floci_native_api source"));
        assertFalse(script.contains("counter accept"));
        assertEquals(List.of("add rule ip floci_native_api egress meta l4proto tcp ct direction original"
                        + " ct status dnat ct original ip daddr 192.0.2.1 counter drop"),
                script.lines().filter(line -> line.startsWith("add rule ")).toList());
    }

    @Test
    void nativeTranslationRejectsUnboundedTargetsBeforeGeneratingCommands() {
        for (int port : List.of(0, -1, 65536)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new VpcRoutingCommands.NativeApi("172.18.0.20", port, 443, "eth0"));
            assertThrows(IllegalArgumentException.class,
                    () -> new VpcRoutingCommands.NativeApi("172.18.0.20", 80, port, "eth0"));
        }
        for (String address : List.of("192.0.2.1", "192.0.2.2", "172.18.0.20;false", "0.0.0.0/0", "peer.example")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new VpcRoutingCommands.NativeApi(address, 80, 443, "eth0"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new VpcRoutingCommands.NativeApi("172.18.0.20", 80, 443, "lo"));
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
    void nativeAlbListenerKeepsItsOwn443OnlyCallersAndBackend() {
        VpcRoutingCommands.Hop apiCaller = new VpcRoutingCommands.Hop("172.18.1.10", "240.0.0.10", "eth1", true);
        VpcRoutingCommands.Hop albCaller = new VpcRoutingCommands.Hop("172.18.1.11", "240.0.0.11", "eth1", true);
        String script = VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", 80, "172.20.0.5", 443,
                "eth1", List.of(apiCaller), "172.20.0.6", List.of(albCaller));

        assertTrue(script.contains("ip -4 address replace 192.0.2.2/32 dev lo"));
        assertTrue(script.contains("ip saddr 172.18.1.10 ip daddr 192.0.2.1 tcp dport { 80, 443 }"));
        assertTrue(script.contains("ip saddr 172.18.1.11 ip daddr 192.0.2.2 tcp dport 443"));
        assertFalse(script.contains("ip saddr 172.18.1.10 ip daddr 192.0.2.2"));
        assertFalse(script.contains("ip saddr 172.18.1.11 ip daddr 192.0.2.1"));
        assertFalse(script.contains("ip daddr 192.0.2.2 tcp dport {"));
        assertTrue(script.contains("egress ip daddr 172.20.0.6 tcp dport 443 counter accept"));
        assertFalse(script.contains("egress ip daddr 172.20.0.6 tcp dport 80"));

        String withdrawn = VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", 80, "172.20.0.5", 443,
                "eth1", List.of(apiCaller), "172.20.0.6", List.of());
        assertTrue(withdrawn.contains("flush chain inet floci_public_api ingress"));
        assertTrue(withdrawn.contains("ip saddr 172.18.1.10 ip daddr 192.0.2.1"));
        assertFalse(withdrawn.contains("ip saddr 172.18.1.11"));
    }

    @Test
    void invalidDestinationsCannotBecomeNamespaceCommands() {
        for (int port : List.of(0, -1, 65536)) {
            assertThrows(IllegalArgumentException.class,
                    () -> VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", port, 443, "eth1", List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", 80, port, "eth1", List.of()));
        }
        for (String address : List.of("192.0.2.1", "192.0.2.2", "172.20.0.5;false", "0.0.0.0/0", "peer.example")) {
            assertThrows(IllegalArgumentException.class,
                    () -> VpcRoutingCommands.publicApi("192.0.2.1", address, 80, 443, "eth1", List.of()));
        }
    }

    @Test
    void nativeAlbDestinationCannotCollideWithTheApiOrAnUpstream() {
        IllegalArgumentException collision = assertThrows(IllegalArgumentException.class,
                () -> VpcRoutingCommands.publicApi("192.0.2.2", "172.20.0.5", 80, 443, "eth1", List.of()));
        assertTrue(collision.getMessage().contains("destinations must be distinct"));
        for (String address : List.of("192.0.2.1", "192.0.2.2")) {
            IllegalArgumentException upstream = assertThrows(IllegalArgumentException.class,
                    () -> VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", 80, "172.20.0.5", 443,
                            "eth1", List.of(), address, List.of()));
            assertTrue(upstream.getMessage().contains("Native ALB upstream"));
        }
        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class,
                () -> VpcRoutingCommands.publicApi("192.0.2.1", "172.20.0.5", 80, "172.20.0.5", 443,
                        "eth1", List.of(), null,
                        List.of(new VpcRoutingCommands.Hop("172.18.1.11", "240.0.0.11", "eth1", true))));
        assertTrue(absent.getMessage().contains("callers require their own upstream"));
    }
}

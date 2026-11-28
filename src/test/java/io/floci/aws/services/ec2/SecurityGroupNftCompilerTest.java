package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.Ipv6Range;
import io.floci.aws.services.ec2.model.PrefixListId;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupNftCompilerTest {

    @Test
    void isolatedVpcPoliciesDoNotImportAnotherVpcPrivateAddresses() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission allow = new IpPermission();
        allow.setIpProtocol("tcp");
        allow.setFromPort(443);
        allow.setToPort(443);
        IpRange range = new IpRange();
        range.setCidrIp("0.0.0.0/0");
        allow.getIpRanges().add(range);
        group.getIpPermissions().add(allow);
        group.getIpPermissionsEgress().add(allow);
        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        SecurityGroupNftCompiler.Endpoint foreign = new SecurityGroupNftCompiler.Endpoint(
                "222222222222", "us-east-1", "vpc-1", "eni-b", "10.0.0.3", "172.17.0.3",
                Set.of(), List.of(group), List.of(), true, Set.of("10.0.0.3"));

        String rules = SecurityGroupNftCompiler.compile(target, List.of(foreign), Map.of(),
                List.of("172.17.0.1"), true);

        assertFalse(rules.contains("ip saddr 10.0.0.3 "), "the foreign VPC cannot reserve a local source address");
        assertFalse(rules.contains("ip daddr 10.0.0.3 "), "the foreign VPC cannot reserve a local destination");
        assertTrue(rules.indexOf("ip daddr 172.17.0.3 drop")
                < rules.indexOf("ip daddr 0.0.0.0/0 tcp dport 443 accept"));
        assertTrue(rules.contains("ip daddr 172.17.0.1 meta l4proto { tcp, udp } th dport 53 snat to 172.17.0.2"));
        assertFalse(rules.contains("dport 443 snat"), "ordinary application traffic must retain its native source");
    }




    @Test
    void isolatedVpcKeepsDualStackControlTransportsBehindPeerPolicy() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        SecurityGroupNftCompiler.Endpoint foreign = new SecurityGroupNftCompiler.Endpoint(
                "222222222222", "us-east-1", "vpc-1", "eni-b", "10.0.0.3", List.of("2001:db8::3"),
                "172.17.0.3", "fd00::3", Set.of(), List.of(group), List.of(), true,
                Set.of("10.0.0.3", "2001:db8::3"));

        String rules = SecurityGroupNftCompiler.compile(target, List.of(foreign), Map.of(), List.of(), true);

        assertTrue(rules.contains("ip saddr 172.17.0.3 drop"));
        assertTrue(rules.contains("ip6 saddr fd00::3 drop"));
        assertTrue(rules.contains("ip6 daddr fd00::3 drop"));
        assertFalse(rules.contains("ip saddr 10.0.0.3 "));
        assertFalse(rules.contains("ip6 saddr 2001:db8::3 "));
    }

    @Test
    void isolatedVpcNativePeerIdentityPreservesProtocolFamilies() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission v4 = new IpPermission();
        v4.setIpProtocol("icmp");
        v4.setFromPort(8);
        v4.setToPort(0);
        IpRange range = new IpRange();
        range.setCidrIp("10.0.0.0/24");
        v4.getIpRanges().add(range);
        group.getIpPermissions().add(v4);
        IpPermission v6 = new IpPermission();
        v6.setIpProtocol("icmpv6");
        v6.setFromPort(128);
        v6.setToPort(0);
        Ipv6Range range6 = new Ipv6Range();
        range6.setCidrIpv6("2001:db8::/64");
        v6.getIpv6Ranges().add(range6);
        group.getIpPermissions().add(v6);
        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        SecurityGroupNftCompiler.Endpoint peer = new SecurityGroupNftCompiler.Endpoint(
                "000000000000", "us-east-1", "vpc-1", "eni-b", "10.0.0.3", List.of("2001:db8::3"),
                "172.17.0.3", "fd00::3", Set.of(), List.of(group), List.of(), true,
                Set.of("10.0.0.3", "2001:db8::3"));

        String rules = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of(), List.of(), true);

        assertTrue(rules.contains("ip saddr 10.0.0.3 icmp type 8 icmp code 0 accept"));
        assertTrue(rules.contains("ip6 saddr 2001:db8::3 icmpv6 type 128 icmpv6 code 0 accept"));
        assertTrue(rules.contains("ip saddr 172.17.0.3 icmp type 8 icmp code 0 accept"));
        assertTrue(rules.contains("ip6 saddr fd00::3 icmpv6 type 128 icmpv6 code 0 accept"));
        assertFalse(rules.contains("ip saddr 10.0.0.3 icmpv6"));
        assertFalse(rules.contains("ip6 saddr 2001:db8::3 icmp type"));

        String withheld = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of(), List.of(), true,
                candidate -> false);
        for (String address : List.of("10.0.0.3", "172.17.0.3", "2001:db8::3", "fd00::3")) {
            assertTrue(withheld.contains("saddr " + address + " drop"));
            assertFalse(withheld.contains("saddr " + address + " icmp"),
                    "unadmitted peers must retain drops without IPv4 or IPv6 accepts");
        }
    }

    @Test
    void isolatedVpcDnsNatRequiresIpv4ControlTransport() {
        SecurityGroup group = new SecurityGroup();
        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "fd00::2", group);

        assertThrows(IllegalArgumentException.class, () -> SecurityGroupNftCompiler.compile(
                target, List.of(), Map.of(), List.of("172.17.0.1"), true));
        String rules = SecurityGroupNftCompiler.compile(target, List.of(), Map.of(), List.of("fd00::53"));
        assertFalse(rules.contains("floci_vpc_dns"));
    }

    @Test
    void endpointPolicyAndIpv6RefreshRetainRuntimeControlIdentity() {
        SecurityGroupNftCompiler.ControlConnection control =
                new SecurityGroupNftCompiler.ControlConnection("172.18.0.2", 10042);
        List<SecurityGroupNftCompiler.ControlConnection> controls = new ArrayList<>(List.of(control));
        Set<String> extra = new LinkedHashSet<>(Set.of("172.19.0.2"));
        SecurityGroupNftCompiler.Endpoint original = new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc", "eni", "10.0.0.2", List.of("2001:db8::2"),
                "172.17.0.2", "fd00::2", Set.of("sg-old"), List.of(), controls, false, extra);
        controls.clear();
        extra.clear();

        SecurityGroupNftCompiler.Endpoint refreshed = original.withPolicy(Set.of("sg-new"), List.of(new SecurityGroup()))
                .withIpv6Addresses(List.of("2001:db8::3"));

        assertEquals(List.of(control), refreshed.controlConnections());
        assertFalse(refreshed.instanceMetadataAllowed());
        assertEquals(Set.of("172.19.0.2"), refreshed.additionalTransportAddresses());
        assertEquals(List.of("172.17.0.2", "fd00::2", "172.19.0.2"), refreshed.transports());
        assertEquals(Set.of("sg-new"), refreshed.groupIds());
        assertEquals(List.of("2001:db8::3"), refreshed.logicalIpv6Addresses());
        assertEquals(List.of("2001:db8::2"), original.logicalIpv6Addresses());
    }

    @Test
    void endpointRejectsNonliteralAdditionalTransport() {
        assertThrows(IllegalArgumentException.class, () -> new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc", "eni", "10.0.0.2", "172.17.0.2", Set.of(),
                List.of(), List.of(), false, Set.of("example.invalid")));
    }

    @Test
    void lambdaRuntimeControlDoesNotOpenOtherPortsOrInstanceMetadata() {
        SecurityGroup group = new SecurityGroup();
        SecurityGroupNftCompiler.Endpoint target = new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc-1", "eni-lambda", "10.0.1.10", "172.18.0.3",
                Set.of("sg-lambda"), List.of(group),
                List.of(new SecurityGroupNftCompiler.ControlConnection("172.18.0.2", 10042)), false, Set.of());
        String nft = SecurityGroupNftCompiler.compile(target, List.of(), Map.of());

        assertTrue(nft.contains("egress ip daddr 172.18.0.2 tcp dport 10042 accept"));
        assertFalse(nft.contains("daddr 172.18.0.2 accept"));
        assertFalse(nft.contains("dport 4566 accept"));
        assertFalse(nft.contains("daddr 169.254.169.254 accept"));
        assertTrue(nft.contains("egress ip daddr 169.254.169.254 drop"));
        assertTrue(nft.contains("ct original ip daddr 169.254.169.254 ct original proto-dst 80 drop"));
    }

    @Test
    void serviceControlListenerDoesNotOpenOtherIngressOrNewEgress() {
        SecurityGroupNftCompiler.Endpoint target = new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc-1", "eni-lb", "10.0.1.10", "172.18.0.3",
                Set.of("sg-lb"), List.of(new SecurityGroup()), List.of(), false, Set.of(),
                List.of(new SecurityGroupNftCompiler.ControlListener("172.18.0.2", 1080)));
        String nft = SecurityGroupNftCompiler.compile(target, List.of(), Map.of());

        assertTrue(nft.contains("ingress ip saddr 172.18.0.2 tcp dport 1080 accept"));
        assertFalse(nft.contains("saddr 172.18.0.2 accept"));
        assertFalse(nft.contains("egress ip daddr 172.18.0.2"));
        assertFalse(nft.contains("dport 4566 accept"));
        assertTrue(nft.contains("egress ip daddr 169.254.169.254 drop"));
    }

    @Test
    void serviceControlListenerRejectsNetworksNamesAndInvalidPorts() {
        for (String address : List.of("172.18.0.2/16", "controller.example", "172.18.0.2\naccept")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SecurityGroupNftCompiler.ControlListener(address, 1080));
        }
        for (int port : List.of(0, -1, 65536)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SecurityGroupNftCompiler.ControlListener("172.18.0.2", port));
        }
    }

    @Test
    void runtimeControlRequiresOneLiteralAddressAndPort() {
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityGroupNftCompiler.ControlConnection("172.18.0.2/16", 10042));
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityGroupNftCompiler.ControlConnection("runtime.example", 10042));
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityGroupNftCompiler.ControlConnection("172.18.0.2", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityGroupNftCompiler.ControlConnection("172.18.0.2", 65536));
    }

    @Test
    void managedPeersAreCheckedBeforeDockerAddressCidrs() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(443);
        permission.setToPort(443);
        IpRange cidr = new IpRange();
        cidr.setCidrIp("10.0.0.0/16");
        permission.getIpRanges().add(cidr);
        IpRange broadTransport = new IpRange();
        broadTransport.setCidrIp("172.17.0.0/16");
        permission.getIpRanges().add(broadTransport);
        group.getIpPermissions().add(permission);

        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        SecurityGroupNftCompiler.Endpoint allowed = endpoint("eni-b", "10.0.0.3", "172.17.0.3", group);
        SecurityGroupNftCompiler.Endpoint denied = endpoint("eni-c", "10.1.0.3", "172.17.0.4", group);
        String nft = SecurityGroupNftCompiler.compile(target, List.of(allowed, denied), Map.of());

        assertTrue(nft.contains("ip saddr 172.17.0.3 tcp dport 443 accept"));
        assertTrue(nft.indexOf("ip saddr 172.17.0.4 drop")
                < nft.indexOf("ip saddr 172.17.0.0/16 tcp dport 443 accept"));
    }

    @Test
    void icmpTypeAndCodeRepeatTheProtocolKeyword() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        // AWS maps IpPermission.FromPort to the ICMP type and ToPort to the ICMP code.
        IpPermission echo = new IpPermission();
        echo.setIpProtocol("icmp");
        echo.setFromPort(8);
        echo.setToPort(0);
        IpRange cidr = new IpRange();
        cidr.setCidrIp("10.0.0.0/16");
        echo.getIpRanges().add(cidr);
        group.getIpPermissions().add(echo);
        IpPermission anyIcmpv6 = new IpPermission();
        anyIcmpv6.setIpProtocol("icmpv6");
        anyIcmpv6.setFromPort(-1);
        anyIcmpv6.setToPort(-1);
        Ipv6Range allV6 = new Ipv6Range();
        allV6.setCidrIpv6("2001:db8::/64");
        anyIcmpv6.getIpv6Ranges().add(allV6);
        group.getIpPermissions().add(anyIcmpv6);

        String nft = SecurityGroupNftCompiler.compile(
                endpoint("eni-a", "10.0.0.2", "172.17.0.2", group), List.of(), Map.of());

        assertTrue(nft.contains("ip saddr 10.0.0.0/16 icmp type 8 icmp code 0 accept"));
        assertFalse(nft.contains("icmp type 8 code 0"));
        assertTrue(nft.contains("ip6 saddr 2001:db8::/64 meta l4proto icmpv6 accept"));
    }

    @Test
    void vpcResolversStayReachableWhenTheGroupsAllowNoEgress() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");

        String nft = SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of(), List.of("172.18.0.2", "fd00::53"));

        assertTrue(nft.contains("egress ip daddr 172.18.0.2 udp dport 53 accept"));
        assertTrue(nft.contains("egress ip daddr 172.18.0.2 tcp dport 53 accept"));
        assertTrue(nft.contains("egress ip6 daddr fd00::53 udp dport 53 accept"));
        assertFalse(nft.contains("daddr 172.18.0.2 accept"), "only DNS is allowed to the resolver");
    }

    @Test
    void translatedImdsAllowanceRequiresItsOriginalDestinationAndListener() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");

        String nft = SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of(), List.of("172.18.0.2"));

        assertTrue(nft.contains("add rule inet floci_sg egress meta l4proto tcp"
                + " ct original ip daddr 169.254.169.254 ct original proto-dst 80 accept\n"), nft);
        assertFalse(nft.contains("egress ip daddr 172.18.0.2 accept"), nft);
        assertFalse(nft.contains("egress meta l4proto tcp accept"), nft);
    }

    @Test
    void theEcsTaskMetadataEndpointStaysReachableOnItsOneListenerOnly() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");

        String nft = SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of(), List.of());

        assertTrue(nft.contains("add rule inet floci_sg egress ip daddr 169.254.170.2 tcp dport 80 accept\n"), nft);
        assertFalse(nft.contains("daddr 169.254.170.2 accept"), "only the endpoint's listener is allowed");
        assertFalse(nft.contains("saddr 169.254.170.2"), "replies ride the established rule; nothing opens ingress");
    }

    /** A group allowing tcp/443 from {@code cidrIpv6} only. */
    private static SecurityGroup ipv6Group(String cidrIpv6) {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(443);
        permission.setToPort(443);
        Ipv6Range range = new Ipv6Range();
        range.setCidrIpv6(cidrIpv6);
        permission.getIpv6Ranges().add(range);
        group.getIpPermissions().add(permission);
        return group;
    }

    private static SecurityGroupNftCompiler.Endpoint dualStack(String eni, String logical, List<String> logicalIpv6,
                                                               String transport, String transportIpv6,
                                                               SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1", eni, logical, logicalIpv6,
                transport, transportIpv6, Set.of("sg-1"), List.of(group));
    }

    // Catches: a rule allowing a peer's IPv6 range never matching it, because only its IPv4 address was an identity.
    @Test
    void aRuleOnAnIpv6RangeMatchesThePeerOnItsIpv6Transport() {
        SecurityGroup group = ipv6Group("2600:1f18:0:a600::/64");
        SecurityGroupNftCompiler.Endpoint target = dualStack("eni-a", "10.0.0.2", List.of("2600:1f18:0:a600::10"),
                "172.17.0.2", "fd00:d0c::2", group);
        SecurityGroupNftCompiler.Endpoint peer = dualStack("eni-b", "10.0.0.3",
                List.of("2600:1f18:0:a600::20", "2600:1f18:0:a600::21"), "172.17.0.3", "fd00:d0c::3", group);

        String nft = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of());

        assertTrue(nft.contains("ingress ip6 saddr fd00:d0c::3 tcp dport 443 accept"));
        // An IPv6-only permission never admits the peer's IPv4 transport.
        assertFalse(nft.contains("ip saddr 172.17.0.3 tcp dport 443 accept"));
        assertTrue(nft.contains("ingress ip saddr 172.17.0.3 drop"));
        assertTrue(nft.contains("ingress ip6 saddr fd00:d0c::3 drop"));
    }

    // Catches: a peer's IPv6 address paired with its IPv4 transport when its Docker network has no IPv6, which
    // would let IPv4 traffic through on a rule that only names an IPv6 range.
    @Test
    void aPeerWithoutAnIpv6TransportGetsNoIpv6Rule() {
        SecurityGroup group = ipv6Group("2600:1f18:0:a600::/64");
        SecurityGroupNftCompiler.Endpoint target = dualStack("eni-a", "10.0.0.2", List.of(), "172.17.0.2", null,
                group);
        SecurityGroupNftCompiler.Endpoint peer = dualStack("eni-b", "10.0.0.3", List.of("2600:1f18:0:a600::20"),
                "172.17.0.3", null, group);

        String nft = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of());

        assertFalse(nft.contains("172.17.0.3 tcp dport 443 accept"));
        assertTrue(nft.contains("ingress ip saddr 172.17.0.3 drop"));
    }

    // Catches: an IPv4-only ENI on an IPv6-enabled Docker network sending on its helper's IPv6 address past the
    // managed-peer drop, straight into a broad IPv6 CIDR rule.
    @Test
    void aPeersIpv6TransportIsDroppedEvenWithoutAnIpv6Address() {
        SecurityGroup group = ipv6Group("::/0");
        SecurityGroupNftCompiler.Endpoint target = dualStack("eni-a", "10.0.0.2", List.of(), "172.17.0.2",
                "fd00:d0c::2", group);
        SecurityGroupNftCompiler.Endpoint ipv4Only = dualStack("eni-b", "10.0.0.3", List.of(), "172.17.0.3",
                "fd00:d0c::3", group);

        String nft = SecurityGroupNftCompiler.compile(target, List.of(ipv4Only), Map.of());

        assertTrue(nft.contains("ingress ip6 saddr fd00:d0c::3 drop"));
        assertTrue(nft.indexOf("ingress ip6 saddr fd00:d0c::3 drop")
                < nft.indexOf("ingress ip6 saddr ::/0 tcp dport 443 accept"));
    }

    // Catches: a group-reference ICMP rule written against a peer's IPv6 transport, which nftables refuses
    // ("ip6 saddr ... icmp type"), failing the whole policy.
    @Test
    void anIcmpGroupRuleIsWrittenOnlyForThePeersIpv4Identity() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission echo = new IpPermission();
        echo.setIpProtocol("icmp");
        echo.setFromPort(8);
        echo.setToPort(0);
        UserIdGroupPair self = new UserIdGroupPair();
        self.setGroupId("sg-1");
        echo.getUserIdGroupPairs().add(self);
        group.getIpPermissions().add(echo);
        SecurityGroupNftCompiler.Endpoint target = dualStack("eni-a", "10.0.0.2", List.of("2600:1f18:0:a600::10"),
                "172.17.0.2", "fd00:d0c::2", group);
        SecurityGroupNftCompiler.Endpoint peer = dualStack("eni-b", "10.0.0.3", List.of("2600:1f18:0:a600::20"),
                "172.17.0.3", "fd00:d0c::3", group);

        String nft = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of());

        assertTrue(nft.contains("ingress ip saddr 172.17.0.3 icmp type 8 icmp code 0 accept"));
        assertFalse(nft.contains("ip6 saddr fd00:d0c::3 icmp"));
    }

    // Catches: an address change leaving the endpoint's identity as it was registered.
    @Test
    void anEndpointTakesNewIpv6AddressesKeepingItsPolicy() {
        SecurityGroup group = ipv6Group("2600:1f18:0:a600::/64");
        SecurityGroupNftCompiler.Endpoint peer = dualStack("eni-b", "10.0.0.3", List.of(), "172.17.0.3",
                "fd00:d0c::3", group);
        SecurityGroupNftCompiler.Endpoint target = dualStack("eni-a", "10.0.0.2", List.of(), "172.17.0.2",
                "fd00:d0c::2", group);

        String before = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of());
        String after = SecurityGroupNftCompiler.compile(target,
                List.of(peer.withIpv6Addresses(List.of("2600:1f18:0:a600::20"))), Map.of());

        assertFalse(before.contains("ip6 saddr fd00:d0c::3 tcp dport 443 accept"));
        assertTrue(after.contains("ip6 saddr fd00:d0c::3 tcp dport 443 accept"));
    }

    @Test
    void policyAndIpv6RefreshRetainTheDeclaredControlListener() {
        SecurityGroupNftCompiler.ControlListener listener =
                new SecurityGroupNftCompiler.ControlListener("fd00::2", 1080);
        SecurityGroupNftCompiler.Endpoint endpoint = new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc-1", "eni-lb", "10.0.1.10", List.of("fd01::10"),
                "172.18.0.3", "fd00::3", Set.of("sg-lb"), List.of(), List.of(), false, Set.of(),
                List.of(listener));
        SecurityGroupNftCompiler.Endpoint refreshed = endpoint.withPolicy(Set.of("sg-new"), List.of(new SecurityGroup()))
                .withIpv6Addresses(List.of("fd01::11"));

        assertEquals(List.of(listener), refreshed.controlListeners());
        assertEquals(List.of("fd01::11"), refreshed.logicalIpv6Addresses());
        assertEquals("fd00::3", refreshed.transportIpv6Address());
        assertTrue(SecurityGroupNftCompiler.compile(refreshed, List.of(), Map.of())
                .contains("ingress ip6 saddr fd00::2 tcp dport 1080 accept"));
    }

    private static SecurityGroupNftCompiler.Endpoint endpoint(String eni, String logical,
                                                               String transport, SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1",
                eni, logical, transport, Set.of("sg-1"), List.of(group));
    }

    @Test
    void ipv6UpdatesPreserveNativeControlAdmissionAndPolicy() {
        SecurityGroup group = new SecurityGroup();
        SecurityGroupNftCompiler.ControlConnection connection =
                new SecurityGroupNftCompiler.ControlConnection("172.17.0.20", 8443);
        SecurityGroupNftCompiler.ControlListener listener =
                new SecurityGroupNftCompiler.ControlListener("172.17.0.21", 9443);
        SecurityGroupNftCompiler.Endpoint original = new SecurityGroupNftCompiler.Endpoint(
                "000000000000", "us-east-1", "vpc-1", "eni-a", "10.0.0.2", List.of("2001:db8::2"),
                "172.17.0.2", "fd00::2", Set.of("sg-1"), List.of(group), List.of(connection), true,
                Set.of("10.0.0.2"), List.of(listener), Map.of("peer.example", connection));
        List<String> assigned = new ArrayList<>(List.of("2001:db8::3"));
        SecurityGroupNftCompiler.Endpoint updated = original.withIpv6Addresses(assigned);
        assigned.clear();

        assertEquals(List.of("2001:db8::2"), original.logicalIpv6Addresses());
        assertEquals(List.of("2001:db8::3"), updated.logicalIpv6Addresses());
        assertEquals(original.withIpv6Addresses(List.of("2001:db8::3")), updated);
        assertEquals(original, updated.withIpv6Addresses(original.logicalIpv6Addresses()));
        assertEquals(SecurityGroupNftCompiler.compile(original, List.of(), Map.of()),
                SecurityGroupNftCompiler.compile(updated, List.of(), Map.of()),
                "An address update must preserve the native control rules and existing policy");
        assertThrows(UnsupportedOperationException.class, () -> updated.logicalIpv6Addresses().clear());
    }

    @Test
    void policyUpdatesPreserveIpv6AndNativeEndpointIdentity() {
        SecurityGroup first = new SecurityGroup();
        SecurityGroup second = new SecurityGroup();
        SecurityGroupNftCompiler.ControlConnection connection =
                new SecurityGroupNftCompiler.ControlConnection("172.17.0.20", 8443);
        SecurityGroupNftCompiler.Endpoint original = new SecurityGroupNftCompiler.Endpoint(
                "000000000000", "us-east-1", "vpc-1", "eni-a", "10.0.0.2", List.of("2001:db8::2"),
                "172.17.0.2", "fd00::2", Set.of("sg-1"), List.of(first), List.of(connection), true,
                Set.of("10.0.0.2"), List.of(), Map.of("peer.example", connection));
        SecurityGroupNftCompiler.Endpoint updated = original.withPolicy(Set.of("sg-2"), List.of(second));

        assertEquals(Set.of("sg-2"), updated.groupIds());
        assertEquals(List.of(second), updated.groups());
        assertEquals(original, updated.withPolicy(original.groupIds(), original.groups()));
        assertEquals(List.of(new SecurityGroupNftCompiler.AddressIdentity("10.0.0.2", "172.17.0.2"),
                new SecurityGroupNftCompiler.AddressIdentity("2001:db8::2", "fd00::2")), updated.identities());
        assertEquals(List.of("172.17.0.2", "fd00::2", "10.0.0.2"), updated.transports());
        assertEquals(List.of("172.17.0.2", "10.0.0.2"), updated.transportAddresses());
    }

    @Test
    void legacyNativeConstructorKeepsItsControlsAndNoIpv6Identity() {
        SecurityGroupNftCompiler.ControlConnection connection =
                new SecurityGroupNftCompiler.ControlConnection("172.17.0.20", 8443);
        SecurityGroupNftCompiler.Endpoint original = new SecurityGroupNftCompiler.Endpoint(
                "000000000000", "us-east-1", "vpc-1", "eni-a", "10.0.0.2", "172.17.0.2",
                Set.of("sg-1"), List.of(new SecurityGroup()), List.of(connection), true,
                Set.of("10.0.0.2"), List.of(), Map.of("peer.example", connection));

        assertTrue(original.logicalIpv6Addresses().isEmpty());
        assertEquals(List.of(new SecurityGroupNftCompiler.AddressIdentity("10.0.0.2", "172.17.0.2")),
                original.identities());
        assertEquals(original, original.withPolicy(original.groupIds(), original.groups()));
        assertEquals(original, original.withIpv6Addresses(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SecurityGroupNftCompiler.Endpoint(
                original.accountId(), original.region(), original.vpcId(), original.eniId(),
                original.logicalAddress(), original.transportAddress(), original.groupIds(), original.groups(),
                List.of(), true, Set.of(), List.of(), original.nativePeerDependencies()));
    }

    @Test
    void translatedPublicApiUsesTheOriginalPortAndAddressBeforePhysicalCidrPermissions() {
        SecurityGroup group = new SecurityGroup();
        group.getIpPermissionsEgress().add(tcpEgress("192.0.2.1/32", 443));
        group.getIpPermissionsEgress().add(tcpEgress("172.17.0.0/16", 80));
        String rules = publicApiRules(group, Map.of());

        String original = "ct direction original ct status dnat ct original ip daddr 192.0.2.1";
        assertTrue(rules.contains(original + " ct original proto-dst 443 counter accept"));
        assertTrue(rules.contains(original + " ct original proto-dst 80 counter drop"));
        assertTrue(rules.indexOf(original + " ct original proto-dst 80 counter drop")
                < rules.indexOf("ip daddr 172.17.0.0/16 tcp dport 80 accept"));
        assertTrue(rules.indexOf("ct state established,related accept") < rules.indexOf(original),
                "Existing security-group connection tracking semantics stay intact");
    }

    @Test
    void translatedPublicApiRefreshesTheExistingManagedPrefixListPolicy() {
        SecurityGroup group = new SecurityGroup();
        IpPermission permission = tcpEgress("172.17.0.0/16", 443);
        PrefixListId reference = new PrefixListId();
        reference.setPrefixListId("pl-api");
        permission.getPrefixListIds().add(reference);
        group.getIpPermissionsEgress().add(permission);
        String original = "ct original ip daddr 192.0.2.1 ct original proto-dst 443";

        assertTrue(publicApiRules(group, Map.of("pl-api", List.of("192.0.2.1/32")))
                .contains(original + " counter accept"));
        assertTrue(publicApiRules(group, Map.of("pl-api", List.of("203.0.113.0/24")))
                .contains(original + " counter drop"),
                "The translated frontdoor CIDR must not override withdrawal of the original API destination");
    }

    @Test
    void translatedPublicApiPolicyIsInstalledBeforePublicAddressPublication() {
        SecurityGroup group = new SecurityGroup();
        group.getIpPermissionsEgress().add(tcpEgress("172.17.0.20/32", 8443));
        String rules = SecurityGroupNftCompiler.compile(
                endpoint("eni-a", "10.0.0.2", "172.17.0.2", group), List.of(), Map.of());

        String original = "ct direction original ct status dnat ct original ip daddr 192.0.2.1";
        String denyHttps = original + " ct original proto-dst 443 counter drop";
        String upstream = "ip daddr 172.17.0.20/32 tcp dport 8443 accept";
        assertTrue(rules.contains(denyHttps),
                "The initial policy must deny translated API traffic before a public address is published");
        assertTrue(rules.contains(original + " ct original proto-dst 80 counter drop"));
        assertTrue(rules.contains(upstream), "Untranslated traffic keeps its physical-tuple permission");
        assertTrue(rules.indexOf(denyHttps) < rules.indexOf(upstream),
                "An allowed upstream must not bypass the denied original API destination");
        assertEquals(rules, publicApiRules(group, Map.of()),
                "Publishing the address must not be needed to install the translated API policy");
    }

    private static String publicApiRules(SecurityGroup group, Map<String, List<String>> prefixLists) {
        return SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), prefixLists, List.of(), false, peer -> true, List.of(), List.of(),
                Set.of("172.17.0.2"));
    }

    private static IpPermission tcpEgress(String cidr, int port) {
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("6");
        permission.setFromPort(port);
        permission.setToPort(port);
        IpRange range = new IpRange();
        range.setCidrIp(cidr);
        permission.getIpRanges().add(range);
        return permission;
    }

    @Test
    void everyAttachedAddressUsesTheManagedPeersVpcBoundary() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(443);
        permission.setToPort(443);
        UserIdGroupPair member = new UserIdGroupPair();
        member.setGroupId("sg-1");
        permission.getUserIdGroupPairs().add(member);
        group.getIpPermissions().add(permission);
        group.getIpPermissionsEgress().add(permission);
        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        SecurityGroupNftCompiler.Endpoint allowed = new SecurityGroupNftCompiler.Endpoint(
                "000000000000", "us-east-1", "vpc-1", "eni-b", "10.0.0.3", "172.17.0.3",
                Set.of("sg-1"), List.of(group), Set.of("10.0.0.3"));
        SecurityGroupNftCompiler.Endpoint denied = new SecurityGroupNftCompiler.Endpoint(
                "000000000000", "us-east-1", "vpc-2", "eni-c", "10.1.0.3", "172.17.0.4",
                Set.of("sg-1"), List.of(group), Set.of("10.1.0.3"));

        String nft = SecurityGroupNftCompiler.compile(target, List.of(allowed, denied), Map.of());

        for (String direction : List.of("ingress ip saddr ", "egress ip daddr ")) {
            for (String address : List.of("172.17.0.3", "10.0.0.3")) {
                assertTrue(nft.contains(direction + address + " tcp dport 443 accept"));
            }
            for (String address : List.of("172.17.0.4", "10.1.0.3")) {
                assertFalse(nft.contains(direction + address + " tcp dport 443 accept"));
                assertTrue(nft.contains(direction + address + " drop"));
            }
        }
    }

    @Test
    void extraTransportAddressesCannotInjectNetworksOrNftExpressions() {
        for (String address : List.of("10.0.0.0/8", "private.example", "10.0.0.3 accept")) {
            assertThrows(IllegalArgumentException.class, () -> new SecurityGroupNftCompiler.Endpoint(
                    "000000000000", "us-east-1", "vpc-1", "eni-a", "10.0.0.2", "172.17.0.2",
                    Set.of(), List.of(), Set.of(address)));
        }
    }

    @Test
    void ipv6NeighborDiscoveryStaysOpenWhileTheGroupsFilterIpv6ByTheirRanges() {
        SecurityGroup group = new SecurityGroup();
        IpPermission transport = new IpPermission();
        transport.setIpProtocol("6");
        transport.setFromPort(9300);
        transport.setToPort(9300);
        transport.getIpv6Ranges().add(new Ipv6Range("2600:1f18:abcd:ef00::/56"));
        group.getIpPermissions().add(transport);

        String nft = SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of());

        for (String chain : List.of("ingress", "egress")) {
            assertTrue(nft.contains("add rule inet floci_sg " + chain
                    + " ip6 hoplimit 255 icmpv6 type { nd-neighbor-solicit, nd-neighbor-advert } accept\n"), nft);
        }
        assertTrue(nft.contains("ingress ip6 saddr 2600:1f18:abcd:ef00::/56 tcp dport 9300 accept"), nft);
        assertFalse(nft.contains("meta l4proto icmpv6 accept"), "no other ICMPv6 is admitted");
    }

    @Test
    void lambdaMetadataDenialPrecedesEveryEgressExemption() {
        SecurityGroupNftCompiler.Endpoint target = new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc-1", "eni-lambda", "10.0.1.10", "172.18.0.3",
                Set.of("sg-lambda"), List.of(new SecurityGroup()),
                List.of(new SecurityGroupNftCompiler.ControlConnection("169.254.169.254", 80),
                        new SecurityGroupNftCompiler.ControlConnection("fd00:ec2::254", 80)), false);
        String nft = SecurityGroupNftCompiler.compile(target, List.of(), Map.of(),
                List.of("169.254.169.254"));
        int firstEgressAccept = nft.indexOf("egress oifname lo accept");

        for (String denied : List.of("ip daddr 169.254.169.254 drop", "ip6 daddr fd00:ec2::254 drop",
                "ct original ip daddr 169.254.169.254 ct original proto-dst 80 drop",
                "ct original ip6 daddr fd00:ec2::254 ct original proto-dst 80 drop")) {
            assertTrue(nft.contains(denied), denied);
            assertTrue(nft.indexOf(denied) < firstEgressAccept, denied);
        }
    }

    @Test
    void loadBalancerTrafficFromFlociCarriesTheLoadBalancersSecurityGroups() {
        SecurityGroup target = groupAdmitting("sg-target", "sg-alb", 8080);
        SecurityGroupNftCompiler.Endpoint endpoint = new SecurityGroupNftCompiler.Endpoint("000000000000",
                "us-east-1", "vpc-1", "eni-a", "10.0.0.2", "172.17.0.2", Set.of("sg-target"), List.of(target));
        SecurityGroupNftCompiler.EmulatorOrigin alb = new SecurityGroupNftCompiler.EmulatorOrigin(
                "000000000000", "us-east-1", "vpc-1", Set.of("sg-alb"));

        String rules = SecurityGroupNftCompiler.compile(endpoint, List.of(), Map.of(), List.of("172.17.0.9"),
                false, peer -> true, List.of(alb), List.of("172.17.0.9"));

        assertTrue(rules.contains("add rule inet floci_sg ingress ip saddr 172.17.0.9 tcp dport 8080 accept\n"),
                rules);
        assertFalse(rules.contains("ip daddr 172.17.0.9 tcp dport 8080"), "only the target's ingress opens");
        assertFalse(rules.contains("172.17.0.9 tcp dport 22 "), "a port the group never opened stays closed");
    }

    @Test
    void flociIsRefusedWhenTheTargetAdmitsNeitherTheLoadBalancersGroupNorItsAddress() {
        SecurityGroup target = groupAdmitting("sg-target", "sg-other", 8080);
        IpRange vpcCidr = new IpRange();
        vpcCidr.setCidrIp("10.0.0.0/16");
        target.getIpPermissions().getFirst().getIpRanges().add(vpcCidr);
        SecurityGroupNftCompiler.Endpoint endpoint = new SecurityGroupNftCompiler.Endpoint("000000000000",
                "us-east-1", "vpc-1", "eni-a", "10.0.0.2", "172.17.0.2", Set.of("sg-target"), List.of(target));
        List<SecurityGroupNftCompiler.EmulatorOrigin> unrelated = List.of(
                new SecurityGroupNftCompiler.EmulatorOrigin("000000000000", "us-east-1", "vpc-1", Set.of("sg-alb")),
                new SecurityGroupNftCompiler.EmulatorOrigin("000000000000", "us-east-1", "vpc-2", Set.of("sg-other")),
                new SecurityGroupNftCompiler.EmulatorOrigin("111111111111", "us-east-1", "vpc-1", Set.of("sg-other")),
                new SecurityGroupNftCompiler.EmulatorOrigin("000000000000", "us-west-2", "vpc-1", Set.of("sg-other")));

        String rules = SecurityGroupNftCompiler.compile(endpoint, List.of(), Map.of(), List.of("172.17.0.9"),
                false, peer -> true, unrelated, List.of("172.17.0.9"));

        assertFalse(rules.contains("ip saddr 172.17.0.9 "), rules);
        assertTrue(rules.contains("ip saddr 10.0.0.0/16 tcp dport 8080 accept"), "CIDR rules are unchanged");
    }

    private static SecurityGroup groupAdmitting(String groupId, String sourceGroupId, int port) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId(groupId);
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(port);
        permission.setToPort(port);
        UserIdGroupPair source = new UserIdGroupPair();
        source.setGroupId(sourceGroupId);
        permission.getUserIdGroupPairs().add(source);
        group.getIpPermissions().add(permission);
        return group;
    }

    @Test
    void crossVpcPeersAreAdmittedByAddressRulesOnTheirLogicalAddress() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission fromList = permission(80);
        PrefixListId reference = new PrefixListId();
        reference.setPrefixListId("pl-1");
        fromList.getPrefixListIds().add(reference);
        group.getIpPermissions().add(fromList);
        IpPermission anywhere = permission(80);
        IpRange all = new IpRange();
        all.setCidrIp("0.0.0.0/0");
        anywhere.getIpRanges().add(all);
        group.getIpPermissionsEgress().add(anywhere);
        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        SecurityGroupNftCompiler.Endpoint other = new SecurityGroupNftCompiler.Endpoint(
                "222222222222", "us-east-1", "vpc-2", "eni-b", "10.9.128.34", "172.17.0.9",
                Set.of("sg-2"), List.of(group));

        String nft = SecurityGroupNftCompiler.compile(target, List.of(other),
                Map.of("pl-1", List.of("10.9.128.0/19")), List.of("172.17.0.1"), true);

        // Ingress from the prefix list's entry, egress from the 0.0.0.0/0 rule, both ahead of the peer's drop.
        assertTrue(nft.indexOf("ingress ip saddr 172.17.0.9 tcp dport 80 accept")
                < nft.indexOf("ingress ip saddr 172.17.0.9 drop"));
        assertTrue(nft.indexOf("egress ip daddr 172.17.0.9 tcp dport 80 accept")
                < nft.indexOf("egress ip daddr 172.17.0.9 drop"));
        assertTrue(nft.indexOf("egress ip daddr 172.17.0.9 tcp dport 80 accept") >= 0);
        assertFalse(nft.contains(" 10.9.128.34 "), "the other VPC's logical address is matched, never installed");
    }

    @Test
    void crossVpcSecurityGroupReferencesAreStillRefused() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission byGroup = permission(80);
        UserIdGroupPair member = new UserIdGroupPair();
        member.setGroupId("sg-2");
        member.setUserId("222222222222");
        byGroup.getUserIdGroupPairs().add(member);
        group.getIpPermissions().add(byGroup);
        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        SecurityGroupNftCompiler.Endpoint other = new SecurityGroupNftCompiler.Endpoint(
                "222222222222", "us-east-1", "vpc-2", "eni-b", "10.9.128.34", "172.17.0.9",
                Set.of("sg-2"), List.of(group));

        String nft = SecurityGroupNftCompiler.compile(target, List.of(other), Map.of(), List.of("172.17.0.1"), true);

        assertFalse(nft.contains("ingress ip saddr 172.17.0.9 tcp dport 80 accept"));
        assertTrue(nft.contains("ingress ip saddr 172.17.0.9 drop"));
    }

    @Test
    void crossVpcPeersMatchingNoRuleAreStillDropped() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission documentation = permission(80);
        IpRange range = new IpRange();
        range.setCidrIp("192.0.2.0/24");
        documentation.getIpRanges().add(range);
        group.getIpPermissions().add(documentation);
        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        SecurityGroupNftCompiler.Endpoint other = new SecurityGroupNftCompiler.Endpoint(
                "222222222222", "us-east-1", "vpc-2", "eni-b", "10.9.128.34", "172.17.0.9",
                Set.of("sg-2"), List.of(group));

        String nft = SecurityGroupNftCompiler.compile(target, List.of(other), Map.of(), List.of("172.17.0.1"), true);

        assertFalse(nft.contains("ingress ip saddr 172.17.0.9 tcp dport 80 accept"));
        assertTrue(nft.indexOf("ingress ip saddr 172.17.0.9 drop")
                < nft.indexOf("ingress ip saddr 192.0.2.0/24 tcp dport 80 accept"));
    }

    private static IpPermission permission(int port) {
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(port);
        permission.setToPort(port);
        return permission;
    }
}

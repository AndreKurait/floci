package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.Ipv6Range;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupNftCompilerIpv6Test {

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

    private static SecurityGroupNftCompiler.Endpoint endpoint(String eni, String logical,
                                                               String transport, SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1",
                eni, logical, transport, Set.of("sg-1"), List.of(group));
    }
}

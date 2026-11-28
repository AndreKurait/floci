package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.Ipv6Range;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupNftCompilerCompositionTest {

    @Test
    void nativeIpv6AddressesNeverInheritAnIpv4Permission() {
        SecurityGroup group = group();
        IpPermission permission = permission("tcp", 443, 443);
        permission.getIpRanges().add(new IpRange("10.0.0.0/24"));
        group.getIpPermissions().add(permission);
        String rules = compile(group, "vpc-home", true);

        assertTrue(rules.contains("ingress ip saddr 10.0.0.3 tcp dport 443 accept"));
        assertTrue(rules.contains("ingress ip saddr 172.18.0.3 tcp dport 443 accept"));
        for (String address : List.of("2001:db8::3", "fd00::3", "2001:db8:1::3")) {
            assertFalse(rules.contains("ingress ip6 saddr " + address + " tcp dport 443 accept"));
            assertTrue(rules.contains("ingress ip6 saddr " + address + " drop"));
        }
    }

    @Test
    void crossVpcIpv6PermissionUsesOnlyTheUniqueVisibleTransport() {
        SecurityGroup group = group();
        IpPermission permission = permission("icmpv6", 128, 0);
        Ipv6Range range = new Ipv6Range();
        range.setCidrIpv6("2001:db8::/64");
        permission.getIpv6Ranges().add(range);
        group.getIpPermissions().add(permission);
        String rules = compile(group, "vpc-foreign", true);

        assertTrue(rules.contains("ingress ip6 saddr fd00::3 icmpv6 type 128 icmpv6 code 0 accept"));
        assertFalse(rules.contains("saddr 2001:db8::3 "));
        assertFalse(rules.contains("daddr 2001:db8::3 "));
        assertFalse(rules.contains("saddr 2001:db8:1::3 "));
        assertFalse(rules.contains("saddr 10.0.0.3 "));
        assertFalse(rules.contains("ip saddr 172.18.0.3 icmpv6"));

        String withheld = compile(group, "vpc-foreign", false);
        assertFalse(withheld.contains("ingress ip6 saddr fd00::3 icmpv6"));
        assertTrue(withheld.contains("ingress ip6 saddr fd00::3 drop"));
    }

    @Test
    void emulatorOriginsAndExternalCidrsRespectIcmpAddressFamilies() {
        SecurityGroup group = group();
        IpPermission permission = permission("icmp", 8, 0);
        UserIdGroupPair reference = new UserIdGroupPair();
        reference.setGroupId("sg-alb");
        permission.getUserIdGroupPairs().add(reference);
        permission.getIpRanges().add(new IpRange("192.0.2.0/24"));
        Ipv6Range wrongFamily = new Ipv6Range();
        wrongFamily.setCidrIpv6("2001:db8::/64");
        permission.getIpv6Ranges().add(wrongFamily);
        group.getIpPermissions().add(permission);
        SecurityGroupNftCompiler.EmulatorOrigin origin = new SecurityGroupNftCompiler.EmulatorOrigin(
                "111111111111", "us-east-1", "vpc-home", Set.of("sg-alb"));
        String rules = SecurityGroupNftCompiler.compile(target(group), List.of(), Map.of(), List.of(), true,
                peer -> true, List.of(origin), List.of("172.18.0.9", "fd00::9"));

        assertTrue(rules.contains("ingress ip saddr 172.18.0.9 icmp type 8 icmp code 0 accept"));
        assertTrue(rules.contains("ingress ip saddr 192.0.2.0/24 icmp type 8 icmp code 0 accept"));
        assertFalse(rules.contains("ip6 saddr fd00::9 icmp"));
        assertFalse(rules.contains("ip6 saddr 2001:db8::/64 icmp"));
    }

    @Test
    void attachedInterfaceDoesNotInheritThePrimaryEcsCredentialExemption() {
        SecurityGroup group = group();
        SecurityGroupNftCompiler.Endpoint attached = new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc-home", "eni-attached", "10.0.0.4", "10.0.0.4",
                Set.of("sg-home"), List.of(group), List.of(), false);
        String rules = SecurityGroupNftCompiler.compileAttached(attached,
                new SecurityGroupNftCompiler.AttachedDevice("eth2", "eni-attached"),
                List.of(), Map.of(), peer -> true);

        assertFalse(rules.contains("daddr 169.254.170.2 tcp dport 80 accept"));
        assertTrue(SecurityGroupNftCompiler.compile(target(group), List.of(), Map.of())
                .contains("egress ip daddr 169.254.170.2 tcp dport 80 accept"));
    }

    private static String compile(SecurityGroup group, String peerVpc, boolean admitted) {
        SecurityGroupNftCompiler.Endpoint peer = new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", peerVpc, "eni-peer", "10.0.0.3", List.of("2001:db8::3"),
                "172.18.0.3", "fd00::3", Set.of("sg-home"), List.of(group), List.of(), true,
                Set.of("10.0.0.3", "2001:db8::3", "2001:db8:1::3"));
        return SecurityGroupNftCompiler.compile(target(group), List.of(peer), Map.of(), List.of(), true,
                candidate -> admitted);
    }

    private static SecurityGroupNftCompiler.Endpoint target(SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-home", "eni-target",
                "10.0.0.2", "172.18.0.2", Set.of("sg-home"), List.of(group));
    }

    private static SecurityGroup group() {
        SecurityGroup group = new SecurityGroup();
        group.setOwnerId("111111111111");
        group.setVpcId("vpc-home");
        group.setGroupId("sg-home");
        return group;
    }

    private static IpPermission permission(String protocol, int from, int to) {
        IpPermission permission = new IpPermission();
        permission.setIpProtocol(protocol);
        permission.setFromPort(from);
        permission.setToPort(to);
        return permission;
    }
}

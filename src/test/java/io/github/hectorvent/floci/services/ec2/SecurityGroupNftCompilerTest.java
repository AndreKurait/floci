package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
                Set.of(), List.of(group), Set.of("10.0.0.3"));

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
    void everyAttachedAddressUsesTheManagedPeersVpcBoundary() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(443);
        permission.setToPort(443);
        IpRange cidr = new IpRange();
        cidr.setCidrIp("0.0.0.0/0");
        permission.getIpRanges().add(cidr);
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
                String drop = direction + address + " drop";
                assertFalse(nft.contains(direction + address + " tcp dport 443 accept"));
                assertTrue(nft.contains(drop));
                assertTrue(nft.indexOf(drop) < nft.indexOf(direction + "0.0.0.0/0 tcp dport 443 accept"));
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
        IpRange allV4 = new IpRange();
        allV4.setCidrIp("10.1.0.0/16");
        anyIcmpv6.getIpRanges().add(allV4);
        group.getIpPermissions().add(anyIcmpv6);

        String nft = SecurityGroupNftCompiler.compile(
                endpoint("eni-a", "10.0.0.2", "172.17.0.2", group), List.of(), Map.of());

        assertTrue(nft.contains("ip saddr 10.0.0.0/16 icmp type 8 icmp code 0 accept"));
        assertFalse(nft.contains("icmp type 8 code 0"));
        assertTrue(nft.contains("ip saddr 10.1.0.0/16 meta l4proto icmpv6 accept"));
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
    void lambdaRuntimeControlDoesNotOpenOtherPortsOrInstanceMetadata() {
        SecurityGroup group = new SecurityGroup();
        SecurityGroupNftCompiler.Endpoint target = new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc-1", "eni-lambda", "10.0.1.10", "172.18.0.3",
                Set.of("sg-lambda"), List.of(group),
                List.of(new SecurityGroupNftCompiler.ControlConnection("172.18.0.2", 10042)), false);
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

    private static SecurityGroupNftCompiler.Endpoint endpoint(String eni, String logical,
                                                               String transport, SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1",
                eni, logical, transport, Set.of("sg-1"), List.of(group));
    }
}

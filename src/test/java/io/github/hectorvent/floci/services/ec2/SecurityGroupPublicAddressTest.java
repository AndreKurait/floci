package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecurityGroupPublicAddressTest {
    private static final String PUBLIC_ADDRESS = "172.18.0.3";
    private static final String PRIVATE_ADDRESS = "10.1.0.3";

    @ParameterizedTest
    @CsvSource({"true,true,false", "false,true,true", "true,false,true", "false,false,true"})
    void publicEgressRequiresBothRoutePublicationAndPeerAdmission(
            boolean published, boolean admitted, boolean managedDrop) {
        String rules = compile(published, admitted, PUBLIC_ADDRESS + "/32");
        assertEquals(managedDrop, rules.contains("egress ip daddr " + PUBLIC_ADDRESS + " drop"));
        assertTrue(rules.contains("egress ip daddr " + PUBLIC_ADDRESS + "/32 tcp dport 443 accept"));
        if (managedDrop) {
            assertTrue(rules.indexOf("egress ip daddr " + PUBLIC_ADDRESS + " drop")
                    < rules.indexOf("egress ip daddr " + PUBLIC_ADDRESS + "/32 tcp dport 443 accept"));
        }
        assertTrue(rules.contains("ingress ip saddr " + PUBLIC_ADDRESS + " drop"),
                "Publication must not change the identity of an incoming caller");
        assertTrue(rules.contains("egress ip daddr " + PRIVATE_ADDRESS + " drop"),
                "A public destination must not grant a private route between VPCs");
    }

    @Test
    void publishingAnAddressDoesNotCreateAnEgressGrant() {
        String rules = compile(true, true, "198.51.100.4/32");
        assertFalse(rules.contains("egress ip daddr " + PUBLIC_ADDRESS + " "));
        assertFalse(rules.contains("egress ip daddr " + PUBLIC_ADDRESS + "/32"));
        assertTrue(rules.contains("egress ip daddr 198.51.100.4/32 tcp dport 443 accept"));
        assertFalse(rules.contains("0.0.0.0/0"));
        assertTrue(SecurityGroupNftCompiler.initialRuleset().contains(
                "egress { type filter hook output priority -10; policy drop; }"));
    }

    private static String compile(boolean published, boolean admitted, String cidr) {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-caller");
        group.setOwnerId("111111111111");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(443);
        permission.setToPort(443);
        IpRange range = new IpRange();
        range.setCidrIp(cidr);
        permission.getIpRanges().add(range);
        group.getIpPermissionsEgress().add(permission);
        SecurityGroupNftCompiler.Endpoint caller = new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc-caller", "eni-caller", "10.0.1.10", "172.18.0.2",
                Set.of("sg-caller"), List.of(group), Set.of("10.0.1.10"));
        SecurityGroupNftCompiler.Endpoint server = new SecurityGroupNftCompiler.Endpoint(
                "222222222222", "us-east-1", "vpc-server", "eni-server", PRIVATE_ADDRESS, PUBLIC_ADDRESS,
                Set.of("sg-server"), List.of(), Set.of(PRIVATE_ADDRESS));
        return SecurityGroupNftCompiler.compile(caller, List.of(server), Map.of(), List.of(), false,
                peer -> admitted, List.of(), List.of(), published ? Set.of(PUBLIC_ADDRESS) : Set.of());
    }
}

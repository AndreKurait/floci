package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hot-attached interface of another VPC (EC2 User Guide, "Attach a network interface to an instance")
 * in a protected namespace is filtered by its own security groups, on its own device only.
 */
class SecurityGroupHotAttachPolicyTest {

    private static final String HOME = "111111111111";
    private static final String CUSTOMER = "222222222222";
    private static final String REGION = "us-east-1";

    /** Members of the group accept each other; port 443 is open to the VPC's range; all egress. */
    private static SecurityGroup group(String id, String vpc, String owner, String cidr) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId(id);
        group.setVpcId(vpc);
        group.setOwnerId(owner);
        IpPermission members = new IpPermission();
        members.setIpProtocol("-1");
        UserIdGroupPair self = new UserIdGroupPair();
        self.setGroupId(id);
        members.setUserIdGroupPairs(List.of(self));
        IpPermission https = new IpPermission();
        https.setIpProtocol("tcp");
        https.setFromPort(443);
        https.setToPort(443);
        https.setIpRanges(List.of(new IpRange(cidr)));
        group.setIpPermissions(List.of(members, https));
        IpPermission egress = new IpPermission();
        egress.setIpProtocol("-1");
        egress.setIpRanges(List.of(new IpRange("0.0.0.0/0")));
        group.setIpPermissionsEgress(List.of(egress));
        return group;
    }

    private static final SecurityGroup HOME_GROUP = group("sg-0home", "vpc-0home", HOME, "10.1.0.0/16");
    private static final SecurityGroup CUSTOMER_GROUP = group("sg-0customer", "vpc-0customer", CUSTOMER,
            "172.31.0.0/16");

    /** The instance's own interface: control transport plus its native address. */
    private static SecurityGroupNftCompiler.Endpoint primary() {
        return new SecurityGroupNftCompiler.Endpoint(HOME, REGION, "vpc-0home", "eni-0primary", "10.1.0.10",
                "172.18.0.2", Set.of("sg-0home"), List.of(HOME_GROUP), Set.of("10.1.0.10"));
    }

    /** The customer interface attached at device index 1: its native address is all its peers see. */
    private static SecurityGroupNftCompiler.Endpoint attached() {
        return new SecurityGroupNftCompiler.Endpoint(CUSTOMER, REGION, "vpc-0customer", "eni-0attached",
                "172.31.5.9", "172.31.5.9", Set.of("sg-0customer"), List.of(CUSTOMER_GROUP), List.of(), false,
                Set.of("172.31.5.9"), List.of(), Map.of());
    }

    /** A client in the customer VPC, in the customer group. */
    private static SecurityGroupNftCompiler.Endpoint client() {
        return new SecurityGroupNftCompiler.Endpoint(CUSTOMER, REGION, "vpc-0customer", "eni-0client", "172.31.5.20",
                "172.18.0.3", Set.of("sg-0customer"), List.of(CUSTOMER_GROUP), Set.of("172.31.5.20"));
    }

    private static final SecurityGroupNftCompiler.AttachedDevice DEVICE =
            new SecurityGroupNftCompiler.AttachedDevice("eth2", "eni-0attached");

    private static List<SecurityGroupNftCompiler.Endpoint> peers() {
        return List.of(attached(), client(), primary());
    }

    private static String primaryPolicy(List<SecurityGroupNftCompiler.AttachedDevice> devices) {
        return SecurityGroupNftCompiler.compile(primary(), peers(), Map.of(), List.of("172.18.0.1"), true,
                peer -> true, List.of(), List.of(), Set.of(), devices);
    }

    @Test
    void withoutAnAttachedDeviceThePrimaryPolicyIsExactlyTodays() {
        String today = SecurityGroupNftCompiler.compile(primary(), peers(), Map.of(), List.of("172.18.0.1"), true,
                peer -> true, List.of(), List.of(), Set.of());
        assertEquals(today, primaryPolicy(List.of()));
        assertFalse(today.contains("attached_"));
    }

    @Test
    void anAttachedDeviceEntersOnlyItsOwnChainsAndIsDroppedIfTheyReturn() {
        String without = primaryPolicy(List.of());
        String with = primaryPolicy(List.of(DEVICE));
        List<String> lines = with.lines().toList();
        assertEquals(List.of(
                "flush chain inet floci_sg ingress",
                "flush chain inet floci_sg egress",
                "add chain inet floci_sg attached_eni_0attached_ingress",
                "add chain inet floci_sg attached_eni_0attached_egress",
                "add rule inet floci_sg ingress iifname \"eth2\" jump attached_eni_0attached_ingress",
                "add rule inet floci_sg ingress iifname \"eth2\" drop",
                "add rule inet floci_sg egress oifname \"eth2\" jump attached_eni_0attached_egress",
                "add rule inet floci_sg egress oifname \"eth2\" drop"), lines.subList(0, 8));
        // The device's entry is the only difference: every rule of the primary interface is unchanged.
        List<String> rest = new ArrayList<>(lines.subList(0, 2));
        rest.addAll(lines.subList(8, lines.size()));
        assertEquals(without.lines().toList(), rest);
    }

    @Test
    void theAttachedPolicyIsItsOwnGroupsTowardItsOwnVpcInItsOwnChains() {
        String policy = SecurityGroupNftCompiler.compileAttached(attached(), DEVICE, peers(), Map.of(), peer -> true);
        List<String> lines = policy.lines().toList();
        assertEquals("add chain inet floci_sg attached_eni_0attached_ingress", lines.get(0));
        assertEquals("add chain inet floci_sg attached_eni_0attached_egress", lines.get(1));
        assertTrue(lines.contains("flush chain inet floci_sg attached_eni_0attached_ingress"));
        assertTrue(lines.contains("flush chain inet floci_sg attached_eni_0attached_egress"));
        for (String line : lines.subList(2, lines.size())) {
            assertTrue(line.startsWith("flush chain inet floci_sg attached_eni_0attached_")
                    || line.startsWith("add rule inet floci_sg attached_eni_0attached_"), line);
        }
        // Members of its group in its own VPC are admitted by their native address; the customer range by CIDR.
        assertTrue(lines.contains("add rule inet floci_sg attached_eni_0attached_ingress ip saddr 172.31.5.20 accept"));
        assertTrue(lines.contains(
                "add rule inet floci_sg attached_eni_0attached_ingress ip saddr 172.31.0.0/16 tcp dport 443 accept"));
        // The home VPC's interface is another VPC's: never admitted on this device.
        assertTrue(lines.contains("add rule inet floci_sg attached_eni_0attached_ingress ip saddr 172.18.0.2 drop"));
        assertFalse(policy.contains("10.1.0.0/16"));
        // No namespace admission travels with it: metadata is refused on this device, no resolver, no DNS SNAT.
        assertTrue(lines.contains("add rule inet floci_sg attached_eni_0attached_egress ip daddr 169.254.169.254 drop"));
        assertFalse(policy.contains("169.254.169.254 accept"));
        assertFalse(policy.contains("floci_vpc_dns"));
        assertFalse(policy.contains("dport 53"));
    }

    @Test
    void anAttachedPolicyNeverCarriesTheNamespacesAdmissions() {
        SecurityGroupNftCompiler.Endpoint withMetadata = new SecurityGroupNftCompiler.Endpoint(CUSTOMER, REGION,
                "vpc-0customer", "eni-0attached", "172.31.5.9", "172.31.5.9", Set.of("sg-0customer"),
                List.of(CUSTOMER_GROUP), List.of(), true, Set.of("172.31.5.9"), List.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () ->
                SecurityGroupNftCompiler.compileAttached(withMetadata, DEVICE, peers(), Map.of(), peer -> true));
        assertThrows(IllegalArgumentException.class, () -> SecurityGroupNftCompiler.compileAttached(attached(),
                new SecurityGroupNftCompiler.AttachedDevice("eth2", "eni-0other"), peers(), Map.of(), peer -> true));
    }

    @Test
    void attachedDevicesNeedPlainNamesAndDistinctDevices() {
        for (String bad : List.of("lo", "eth2;drop", "", "averyveryverylongname")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SecurityGroupNftCompiler.AttachedDevice(bad, "eni-0attached"), bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityGroupNftCompiler.AttachedDevice("eth2", "eni-0Attached\" drop"));
        assertThrows(IllegalArgumentException.class, () -> primaryPolicy(List.of(DEVICE,
                new SecurityGroupNftCompiler.AttachedDevice("eth2", "eni-0second"))));
    }
}

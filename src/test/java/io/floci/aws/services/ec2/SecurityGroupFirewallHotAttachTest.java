package io.floci.aws.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Isolated address spaces with hot attach: a customer interface attached to an instance is enforced with its
 * own security groups on its own device of the instance's protected namespace, and its VPC's peers admit it.
 */
class SecurityGroupFirewallHotAttachTest extends SecurityGroupFirewallPeerFixture {

    private static final String HOME = "111111111111";
    private static final String CUSTOMER = "222222222222";
    private static final String REGION = "us-east-1";

    private static SecurityGroup group(String id, String vpc, String owner) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId(id);
        group.setVpcId(vpc);
        group.setOwnerId(owner);
        IpPermission members = new IpPermission();
        members.setIpProtocol("-1");
        UserIdGroupPair self = new UserIdGroupPair();
        self.setGroupId(id);
        members.setUserIdGroupPairs(List.of(self));
        group.setIpPermissions(List.of(members));
        IpPermission egress = new IpPermission();
        egress.setIpProtocol("-1");
        egress.setIpRanges(List.of(new IpRange("0.0.0.0/0")));
        group.setIpPermissionsEgress(List.of(egress));
        return group;
    }

    private final SecurityGroup homeGroup = group("sg-0home", "vpc-0home", HOME);
    private final SecurityGroup customerGroup = group("sg-0customer", "vpc-0customer", CUSTOMER);

    private EmulatorConfig config(boolean hotAttach) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(writerDirectory.toString());
        when(config.services().ec2().vpcNetworks().isolateAddressSpaces()).thenReturn(true);
        when(config.services().ec2().vpcNetworks().hotAttachAcrossAddressSpaces()).thenReturn(hotAttach);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        return config;
    }

    private SecurityGroupFirewallManager manager(EmulatorConfig config) {
        EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        when(dns.getServerIp()).thenReturn(Optional.empty());
        return new SecurityGroupFirewallManager(docker(), mock(ContainerBuilder.class), lifecycle, config, dns,
                new PolicyWriterJournal(config));
    }

    private void register(SecurityGroupFirewallManager manager, EmulatorConfig config,
                          SecurityGroupNftCompiler.Endpoint endpoint, String helper) {
        containerNetworks.put(helper, Map.of(VpcNetworkManager.nativeNetworkName(config, endpoint.accountId(),
                endpoint.region(), endpoint.vpcId()), new ContainerNetwork()));
        freshHelper(helper);
        manager.register(endpoint, helper, Map.of());
    }

    private SecurityGroupNftCompiler.Endpoint primary() {
        return new SecurityGroupNftCompiler.Endpoint(HOME, REGION, "vpc-0home", "eni-0primary", "10.1.0.10",
                "172.18.0.2", Set.of("sg-0home"), List.of(homeGroup), Set.of("10.1.0.10"));
    }

    private SecurityGroupNftCompiler.Endpoint client() {
        return new SecurityGroupNftCompiler.Endpoint(CUSTOMER, REGION, "vpc-0customer", "eni-0client",
                "172.31.5.20", "172.18.0.3", Set.of("sg-0customer"), List.of(customerGroup), Set.of("172.31.5.20"));
    }

    private SecurityGroupNftCompiler.Endpoint attached(String vpc) {
        return attached(CUSTOMER, vpc);
    }

    private SecurityGroupNftCompiler.Endpoint attached(String account, String vpc) {
        return new SecurityGroupNftCompiler.Endpoint(account, REGION, vpc, "eni-0attached", "172.31.5.9",
                "172.31.5.9", Set.of("sg-0customer"), List.of(customerGroup), List.of(), false,
                Set.of("172.31.5.9"), List.of(), Map.of());
    }

    private static List<String> withoutAttachment(String script) {
        return script.lines().filter(line -> !line.contains("attached_") && !line.contains("\"eth2\"")
                && !line.contains("172.31.5.9")).toList();
    }

    @Test
    void attachedIpv6UpdateRollsBackFailureAndPreservesDevice() {
        EmulatorConfig config = config(true);
        SecurityGroupFirewallManager manager = manager(config);
        register(manager, config, primary(), "helper-0");
        register(manager, config, client(), "helper-1");
        manager.attachDevice("eni-0primary", attached("vpc-0customer"), "eth2", Map.of());
        Map<String, String> before = Map.copyOf(installed);
        failNextInstall.add("helper-0");

        assertThrows(IllegalStateException.class,
                () -> manager.updateIpv6Addresses("eni-0attached", List.of("2001:db8::30")));
        manager.reconcileAll();
        assertEquals(before, installed);

        manager.updateIpv6Addresses("eni-0attached", List.of("2001:db8::30"));
        assertEquals(Optional.of("eth2"), manager.attachedDevice("eni-0primary", "eni-0attached"));
        Map<String, String> changed = Map.copyOf(installed);
        manager.reconcileAll();
        assertEquals(changed, installed);
        manager.updateIpv6Addresses("eni-0attached", List.of());
        assertEquals(before, installed);
    }

    @Test
    void anAttachedInterfaceGetsItsOwnGroupsOnItsOwnDeviceAndItsVpcPeersAdmitIt() {
        EmulatorConfig config = config(true);
        SecurityGroupFirewallManager manager = manager(config);
        register(manager, config, primary(), "helper-0");
        register(manager, config, client(), "helper-1");
        String before = installed.get("helper-0");
        assertFalse(installed.get("helper-1").contains("172.31.5.9"));

        manager.attachDevice("eni-0primary", attached("vpc-0customer"), "eth2", Map.of());

        String namespace = installed.get("helper-0");
        assertTrue(namespace.contains("add rule inet floci_sg ingress iifname \"eth2\" jump attached_eni_0attached_ingress\n"
                + "add rule inet floci_sg ingress iifname \"eth2\" drop\n"), namespace);
        assertTrue(namespace.contains("add rule inet floci_sg attached_eni_0attached_ingress ip saddr 172.31.5.20 accept"));
        assertTrue(namespace.contains("add rule inet floci_sg attached_eni_0attached_egress ip daddr 172.31.5.20 accept"));
        // The primary interface's rules are the same, apart from the new peer's own drops.
        assertEquals(before.lines().toList(), withoutAttachment(namespace));
        assertTrue(installed.get("helper-1").contains("add rule inet floci_sg ingress ip saddr 172.31.5.9 accept"));
        assertEquals(Optional.of("eth2"), manager.attachedDevice("eni-0primary", "eni-0attached"));
        assertEquals(List.of(), violations);

        int beforeDetach = installs.size();
        manager.detachDevice("eni-0primary", "eni-0attached");

        String detached = installed.get("helper-0");
        assertFalse(detached.contains("jump attached_"), detached);
        // The first install after the detach flushes the device's chains; later ones no longer name them.
        assertTrue(List.copyOf(installs).subList(beforeDetach, installs.size()).stream()
                .filter(install -> "helper-0".equals(install[0])).findFirst().orElseThrow()[1]
                .contains("flush chain inet floci_sg attached_eni_0attached_ingress\n"
                        + "flush chain inet floci_sg attached_eni_0attached_egress\n"));
        assertFalse(installed.get("helper-1").contains("172.31.5.9"));
        assertEquals(Optional.empty(), manager.attachedDevice("eni-0primary", "eni-0attached"));
    }

    @Test
    void withTheSettingOffNothingIsAttachedAndNoPolicyChanges() {
        EmulatorConfig config = config(false);
        SecurityGroupFirewallManager manager = manager(config);
        register(manager, config, primary(), "helper-0");
        String before = installed.get("helper-0");
        int installs = this.installs.size();

        assertThrows(IllegalStateException.class,
                () -> manager.attachDevice("eni-0primary", attached("vpc-0customer"), "eth2", Map.of()));

        assertEquals(before, installed.get("helper-0"));
        assertEquals(installs, this.installs.size());
    }

    @Test
    void anAttachedInterfaceNeedsAProtectedPrimaryAnotherVpcAndAFreeDevice() {
        EmulatorConfig config = config(true);
        SecurityGroupFirewallManager manager = manager(config);
        register(manager, config, primary(), "helper-0");
        assertThrows(IllegalStateException.class,
                () -> manager.attachDevice("eni-0unknown", attached("vpc-0customer"), "eth2", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> manager.attachDevice("eni-0primary", attached(HOME, "vpc-0home"), "eth2", Map.of()));
        manager.attachDevice("eni-0primary", attached("vpc-0customer"), "eth2", Map.of());
        SecurityGroupNftCompiler.Endpoint second = new SecurityGroupNftCompiler.Endpoint(CUSTOMER, REGION,
                "vpc-0other", "eni-0second", "172.30.5.9", "172.30.5.9", Set.of("sg-0customer"),
                List.of(customerGroup), List.of(), false, Set.of("172.30.5.9"), List.of(), Map.of());
        assertThrows(IllegalStateException.class,
                () -> manager.attachDevice("eni-0primary", second, "eth2", Map.of()));
    }
}

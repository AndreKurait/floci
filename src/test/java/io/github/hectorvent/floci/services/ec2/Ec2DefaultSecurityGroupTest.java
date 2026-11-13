package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ec2DefaultSecurityGroupTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Test
    void defaultVpcGroupAllowsOnlyItsOwnMembersAndPersistsBothRules() {
        Ec2Service service = service();
        SecurityGroup group = defaultGroup(service);
        assertDefaultRules(service, group);
        service.ensureDefaultResources(REGION);
        assertDefaultRules(service, defaultGroup(service));
    }

    @Test
    void createdVpcGroupReferencesItsOwnGroupRatherThanTheRegionsDefault() {
        Ec2Service service = service();
        String vpc = service.createVpc(REGION, "10.22.0.0/16", false).getVpcId();
        SecurityGroup group = service.defaultSecurityGroupOf(REGION, vpc).orElseThrow();
        assertNotEquals(defaultGroup(service).getGroupId(), group.getGroupId());
        assertDefaultRules(service, group);
    }

    @Test
    void explicitlyRevokedDefaultIngressIsNotRecreatedByDiscovery() {
        Ec2Service service = service();
        SecurityGroup group = defaultGroup(service);
        service.revokeSecurityGroupIngress(REGION, group.getGroupId(), List.copyOf(group.getIpPermissions()));
        service.ensureDefaultResources(REGION);
        SecurityGroup observed = defaultGroup(service);
        assertTrue(observed.getIpPermissions().isEmpty());
        List<SecurityGroupRule> rules = service.describeSecurityGroupRules(
                REGION, List.of(observed.getGroupId()), List.of());
        assertEquals(1, rules.size());
        assertTrue(rules.getFirst().isEgress());
    }

    @Test
    void ordinaryNamedSecurityGroupsStillStartWithNoIngress() {
        Ec2Service service = service();
        SecurityGroup group = service.createSecurityGroup(REGION, "application", "application", null);
        assertTrue(group.getIpPermissions().isEmpty());
        List<SecurityGroupRule> rules = service.describeSecurityGroupRules(
                REGION, List.of(group.getGroupId()), List.of());
        assertEquals(1, rules.size());
        assertTrue(rules.getFirst().isEgress());
    }

    private static void assertDefaultRules(Ec2Service service, SecurityGroup group) {
        assertEquals(1, group.getIpPermissions().size());
        IpPermission ingress = group.getIpPermissions().getFirst();
        assertEquals("-1", ingress.getIpProtocol());
        assertTrue(ingress.getIpRanges().isEmpty());
        assertTrue(ingress.getIpv6Ranges().isEmpty());
        assertEquals(1, ingress.getUserIdGroupPairs().size());
        UserIdGroupPair reference = ingress.getUserIdGroupPairs().getFirst();
        assertEquals(group.getGroupId(), reference.getGroupId());
        assertEquals(group.getOwnerId(), reference.getUserId());
        List<SecurityGroupRule> rules = service.describeSecurityGroupRules(REGION, List.of(group.getGroupId()), List.of());
        assertEquals(2, rules.size());
        SecurityGroupRule inbound = rules.stream().filter(rule -> !rule.isEgress()).findFirst().orElseThrow();
        assertEquals("-1", inbound.getIpProtocol());
        assertEquals(group.getGroupId(), inbound.getReferencedGroupInfo().getGroupId());
        assertEquals(group.getOwnerId(), inbound.getReferencedGroupInfo().getUserId());
        assertNull(inbound.getCidrIpv4());
        SecurityGroupPolicy.Peer member = new SecurityGroupPolicy.Peer(
                "10.22.0.10", group.getOwnerId(), group.getVpcId(), Set.of(group.getGroupId()));
        for (String protocol : List.of("tcp", "udp", "icmp")) {
            assertTrue(SecurityGroupPolicy.allows(List.of(group), false, protocol, 8080, 0, member, Map.of()));
        }
        for (SecurityGroupPolicy.Peer other : List.of(
                new SecurityGroupPolicy.Peer("10.22.0.10", group.getOwnerId(), group.getVpcId(), Set.of("sg-other")),
                new SecurityGroupPolicy.Peer("10.22.0.10", "111111111111", group.getVpcId(), Set.of(group.getGroupId())),
                new SecurityGroupPolicy.Peer("10.22.0.10", group.getOwnerId(), "vpc-other", Set.of(group.getGroupId())))) {
            assertFalse(SecurityGroupPolicy.allows(List.of(group), false, "tcp", 8080, 0, other, Map.of()));
        }
    }

    private static SecurityGroup defaultGroup(Ec2Service service) {
        return service.describeSecurityGroups(REGION, List.of(), List.of("default"), Map.of()).stream()
                .filter(group -> group.getGroupId().equals(Ec2Service.defaultSecurityGroupId(REGION)))
                .findFirst().orElseThrow();
    }

    private static Ec2Service service() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(ACCOUNT));
    }
}

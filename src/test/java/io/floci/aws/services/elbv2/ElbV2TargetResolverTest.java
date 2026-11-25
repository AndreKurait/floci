package io.floci.aws.services.elbv2;

import io.floci.aws.services.ec2.Ec2InstancePorts;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.services.elbv2.model.TargetGroup;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class ElbV2TargetResolverTest {

    private static final String ALB =
            "arn:aws:elasticloadbalancing:us-east-1:111122223333:loadbalancer/app/alb/0123456789abcdef";
    private static final Set<String> NLB_GROUPS = Set.of("sg-nlb");
    private static final String NLB =
            "arn:aws:elasticloadbalancing:us-east-1:111122223333:loadbalancer/net/nlb/0123456789abcdef";

    @Test
    void instanceTargetResolvesToBackingContainerBridgeIp() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setInstanceId("i-1234567890abcdef0");
        instance.setContainerBridgeIp("172.18.0.42");
        when(ec2Service.findInstanceById("i-1234567890abcdef0")).thenReturn(instance);

        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetType("instance");
        TargetDescription target = new TargetDescription();
        target.setId("i-1234567890abcdef0");

        assertEquals("172.18.0.42", ElbV2TargetResolver.resolve(ec2Service, null, targetGroup, target.getId(), 80).host());
    }

    @Test
    void instanceTargetPassesTargetGroupAccountIdToEc2Service() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setInstanceId("i-1234567890abcdef0");
        instance.setContainerBridgeIp("172.18.0.42");
        when(ec2Service.findInstanceById("111122223333", "i-1234567890abcdef0")).thenReturn(instance);

        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetGroupArn("arn:aws:elasticloadbalancing:us-east-1:111122223333:targetgroup/tg/123456");
        targetGroup.setTargetType("instance");
        TargetDescription target = new TargetDescription();
        target.setId("i-1234567890abcdef0");

        assertEquals("172.18.0.42", ElbV2TargetResolver.resolve(ec2Service, null, targetGroup, target.getId(), 80).host());
    }

    @Test
    void ipTargetKeepsRegisteredAddress() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetType("ip");
        TargetDescription target = new TargetDescription();
        target.setId("10.0.0.12");

        assertEquals("10.0.0.12", ElbV2TargetResolver.resolve(ec2Service, null, targetGroup, target.getId(), 80).host());
    }

    @Test
    void instancePortPublishedByATaskResolvesToTheTaskContainer() {
        Ec2InstancePorts ports = new Ec2InstancePorts();
        ports.bind("111122223333", "i-0abc", 32768, new Ec2InstancePorts.Endpoint("172.19.0.7", 8080));
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetGroupArn("arn:aws:elasticloadbalancing:us-east-1:111122223333:targetgroup/tg/123456");
        targetGroup.setTargetType("instance");

        assertEquals(new Ec2InstancePorts.Endpoint("172.19.0.7", 8080),
                ElbV2TargetResolver.resolve(mock(Ec2Service.class), ports, targetGroup, "i-0abc", 32768));
    }

    @Test
    void instancePortNoTaskPublishesResolvesToTheInstanceContainer() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setContainerBridgeIp("172.18.0.42");
        when(ec2Service.findInstanceById("111122223333", "i-0abc")).thenReturn(instance);
        Ec2InstancePorts ports = new Ec2InstancePorts();
        ports.bind("111122223333", "i-0abc", 32768, new Ec2InstancePorts.Endpoint("172.19.0.7", 8080));
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetGroupArn("arn:aws:elasticloadbalancing:us-east-1:111122223333:targetgroup/tg/123456");
        targetGroup.setTargetType("instance");

        assertEquals(new Ec2InstancePorts.Endpoint("172.18.0.42", 22),
                ElbV2TargetResolver.resolve(ec2Service, ports, targetGroup, "i-0abc", 22));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SuppressWarnings("unchecked")
    void mappedTaskPortRequiresAdmissionOnTheInstancesPublishedPort(boolean admitted) {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setContainerBridgeIp("10.20.1.10");
        when(ec2Service.findInstanceById("111122223333", "i-0abc")).thenReturn(instance);
        Ec2InstancePorts ports = new Ec2InstancePorts();
        Ec2InstancePorts.Endpoint task = new Ec2InstancePorts.Endpoint("172.19.0.7", 80);
        ports.bind("111122223333", "i-0abc", 32768, task);
        SecurityGroupFirewallManager manager = mock(SecurityGroupFirewallManager.class);
        when(manager.enabled()).thenReturn(true);
        when(manager.emulatorRoute("111122223333", "us-east-1", "vpc-a", "10.20.1.10", 32768, NLB_GROUPS))
                .thenReturn(admitted ? "172.18.0.50" : null);
        jakarta.enterprise.inject.Instance<SecurityGroupFirewallManager> firewall =
                mock(jakarta.enterprise.inject.Instance.class);
        when(firewall.isResolvable()).thenReturn(true);
        when(firewall.get()).thenReturn(manager);
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetGroupArn("arn:aws:elasticloadbalancing:us-east-1:111122223333:targetgroup/tg/123456");
        targetGroup.setTargetType("instance");
        targetGroup.setVpcId("vpc-a");

        assertEquals(admitted ? task : new Ec2InstancePorts.Endpoint(ElbV2TargetResolver.UNREACHABLE, 32768),
                ElbV2TargetResolver.resolve(ec2Service, ports, firewall, targetGroup, "i-0abc", 32768, NLB, NLB_GROUPS));
        verify(manager).emulatorRoute("111122223333", "us-east-1", "vpc-a", "10.20.1.10", 32768, NLB_GROUPS);
        verify(manager, times(1)).emulatorRoute(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleTaskPortCannotMakeAnUnresolvedProtectedInstanceReachable() {
        Ec2InstancePorts ports = new Ec2InstancePorts();
        ports.bind("111122223333", "i-0abc", 32768, new Ec2InstancePorts.Endpoint("172.19.0.7", 80));
        SecurityGroupFirewallManager manager = mock(SecurityGroupFirewallManager.class);
        when(manager.enabled()).thenReturn(true);
        jakarta.enterprise.inject.Instance<SecurityGroupFirewallManager> firewall =
                mock(jakarta.enterprise.inject.Instance.class);
        when(firewall.isResolvable()).thenReturn(true);
        when(firewall.get()).thenReturn(manager);
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetGroupArn("arn:aws:elasticloadbalancing:us-east-1:111122223333:targetgroup/tg/123456");
        targetGroup.setTargetType("instance");
        targetGroup.setVpcId("vpc-a");

        assertEquals(new Ec2InstancePorts.Endpoint(ElbV2TargetResolver.UNREACHABLE, 32768),
                ElbV2TargetResolver.resolve(mock(Ec2Service.class), ports, firewall, targetGroup,
                        "i-0abc", 32768, NLB, NLB_GROUPS));
    }

    /**
     * Under security-group enforcement a protected instance is dialled at its transport address on
     * Floci's network, and a protected endpoint of another VPC or account is never dialled.
     */
    @Test
    @SuppressWarnings("unchecked")
    void protectedTargetsAreDialledThroughTheirTransportOnlyFromTheirOwnVpc() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setInstanceId("i-1234567890abcdef0");
        instance.setContainerBridgeIp("10.20.1.10");
        when(ec2Service.findInstanceById("111122223333", "i-1234567890abcdef0")).thenReturn(instance);
        SecurityGroupFirewallManager manager = mock(SecurityGroupFirewallManager.class);
        when(manager.enabled()).thenReturn(true);
        when(manager.emulatorRoute("111122223333", "us-east-1", "vpc-a", "10.20.1.10", 8080, NLB_GROUPS)).thenReturn("172.18.0.50");
        when(manager.emulatorRoute("111122223333", "us-east-1", "vpc-a", "172.18.0.60", 8080, NLB_GROUPS)).thenReturn(null);
        jakarta.enterprise.inject.Instance<SecurityGroupFirewallManager> firewall =
                mock(jakarta.enterprise.inject.Instance.class);
        when(firewall.isResolvable()).thenReturn(true);
        when(firewall.get()).thenReturn(manager);
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetGroupArn("arn:aws:elasticloadbalancing:us-east-1:111122223333:targetgroup/tg/123456");
        targetGroup.setTargetType("instance");
        targetGroup.setVpcId("vpc-a");

        assertEquals("172.18.0.50",
                ElbV2TargetResolver.resolveHost(ec2Service, firewall, targetGroup, "i-1234567890abcdef0", NLB, 8080, NLB_GROUPS));
        targetGroup.setTargetType("ip");
        String refused = ElbV2TargetResolver.resolveHost(ec2Service, firewall, targetGroup, "172.18.0.60", NLB, 8080, NLB_GROUPS);
        assertEquals(ElbV2TargetResolver.UNREACHABLE, refused);
        assertThrows(IOException.class, () -> ElbV2TargetResolver.resolveCheckedAddress(refused));
        assertEquals(ElbV2TargetResolver.UNREACHABLE,
                ElbV2TargetResolver.resolveHost(ec2Service, firewall, targetGroup, "helper.internal", NLB, 8080, NLB_GROUPS),
                "a host name could resolve to another VPC's protected endpoint");
        assertEquals("10.0.0.12", ElbV2TargetResolver.resolveHost(ec2Service, null, targetGroup, "10.0.0.12", NLB, 8080, NLB_GROUPS),
                "without enforcement the registered address is dialled");
    }

    /**
     * ALB traffic is reached from the ALB's own VPC interfaces, so Floci neither reroutes nor refuses
     * it, even for a target group an NLB also forwards to; the NLB's traffic is still decided as NLB.
     */
    @Test
    @SuppressWarnings("unchecked")
    void albTrafficIsLeftAloneAndAnNlbSharingTheTargetGroupIsStillChecked() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        SecurityGroupFirewallManager manager = mock(SecurityGroupFirewallManager.class);
        when(manager.enabled()).thenReturn(true);
        when(manager.emulatorRoute("111122223333", "us-east-1", "vpc-b", "172.18.0.60", 8080, NLB_GROUPS)).thenReturn(null);
        jakarta.enterprise.inject.Instance<SecurityGroupFirewallManager> firewall =
                mock(jakarta.enterprise.inject.Instance.class);
        when(firewall.isResolvable()).thenReturn(true);
        when(firewall.get()).thenReturn(manager);
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetGroupArn("arn:aws:elasticloadbalancing:us-east-1:111122223333:targetgroup/tg/123456");
        targetGroup.setTargetType("ip");
        targetGroup.setVpcId("vpc-b");
        targetGroup.setLoadBalancerArns(new ArrayList<>(List.of(ALB, NLB)));

        assertEquals("172.18.0.60", ElbV2TargetResolver.resolveHost(ec2Service, firewall, targetGroup, "172.18.0.60",
                ALB, 8080, NLB_GROUPS));
        assertEquals("172.18.0.60", ElbV2TargetResolver.resolveHost(ec2Service, firewall, targetGroup, "172.18.0.60",
                "arn:aws:elasticloadbalancing:us-east-1:111122223333:listener/app/alb/0123456789abcdef/1", 8080, NLB_GROUPS));
        assertEquals(ElbV2TargetResolver.UNREACHABLE, ElbV2TargetResolver.resolveHost(ec2Service, firewall,
                targetGroup, "172.18.0.60",
                "arn:aws:elasticloadbalancing:us-east-1:111122223333:listener/net/nlb/0123456789abcdef/1", 8080, NLB_GROUPS));
        assertEquals(ALB, ElbV2TargetResolver.healthCheckOrigin(targetGroup),
                "health checks use the transport ElbV2VpcNetworkManager picks: the first ARN, the ALB's");
        // Only the NLB listener's connection consulted the firewall.
        verify(manager, times(1)).emulatorRoute(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void checkedAddressReturnsAnIpLiteralForLoopbackAndPrivateTargets() throws IOException {
        assertEquals("127.0.0.1", ElbV2TargetResolver.resolveCheckedAddress("127.0.0.1"));
        assertEquals("10.0.0.12", ElbV2TargetResolver.resolveCheckedAddress("10.0.0.12"));
    }

    @Test
    void checkedAddressRejectsLinkLocalAndMetadataTargets() {
        for (String host : List.of("169.254.169.254", "169.254.170.2", "fe80::1", "fd00:ec2::254")) {
            IOException ex = assertThrows(IOException.class, () -> ElbV2TargetResolver.resolveCheckedAddress(host), host);
            assertTrue(ex.getMessage().contains("link-local or metadata address"), ex.getMessage());
        }
    }

}

package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.Test;

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

        assertEquals("172.18.0.42", ElbV2TargetResolver.resolveHost(ec2Service, targetGroup, target));
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

        assertEquals("172.18.0.42", ElbV2TargetResolver.resolveHost(ec2Service, targetGroup, target));
    }

    @Test
    void ipTargetKeepsRegisteredAddress() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetType("ip");
        TargetDescription target = new TargetDescription();
        target.setId("10.0.0.12");

        assertEquals("10.0.0.12", ElbV2TargetResolver.resolveHost(ec2Service, targetGroup, target));
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

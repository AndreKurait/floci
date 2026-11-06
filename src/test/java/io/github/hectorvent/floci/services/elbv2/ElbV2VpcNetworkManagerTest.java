package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.Test;

import java.net.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ElbV2VpcNetworkManagerTest {
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final VpcNetworkManager networks = mock(VpcNetworkManager.class);
    private final ElbV2VpcNetworkManager manager =
            new ElbV2VpcNetworkManager(config, ec2, firewall, lifecycle, networks);

    @Test
    void unreadyNativeAlbCannotUseTheEmulatorNetworkInstead() {
        when(firewall.enabled()).thenReturn(true);
        when(networks.enabled()).thenReturn(true);
        assertThrows(IllegalStateException.class,
                () -> manager.proxy("arn:aws:elasticloadbalancing:us-east-1:111111111111:loadbalancer/app/api/123"));
        verifyNoInteractions(ec2, lifecycle);
    }

    @Test
    void disabledEnforcementPreservesExistingDirectConnections() {
        assertEquals(Proxy.NO_PROXY, manager.proxy(
                "arn:aws:elasticloadbalancing:us-east-1:111111111111:loadbalancer/app/api/123").javaProxy());
        manager.reconcile(new LoadBalancer(), () -> {
            throw new AssertionError("Disabled networking cannot mutate the load balancer");
        });
        verifyNoInteractions(ec2, lifecycle);
    }

    @Test
    void networkLoadBalancersKeepTheirExistingTransport() {
        when(firewall.enabled()).thenReturn(true);
        when(networks.enabled()).thenReturn(true);
        assertEquals(Proxy.NO_PROXY, manager.proxy(
                "arn:aws:elasticloadbalancing:us-east-1:111111111111:loadbalancer/net/api/123").javaProxy());
    }

    @Test
    void proxyOnlyAcceptsTheControllerAndUsesTheNativeSourceAddress() {
        String config = ElbV2VpcNetworkManager.proxyConfiguration("172.18.0.2", "172.18.0.3", "10.0.1.10");
        assertTrue(config.contains("internal: 172.18.0.3 port = 1080\nexternal: 10.0.1.10\n"));
        assertTrue(config.contains("from: 172.18.0.2/32 to: 172.18.0.3/32"));
        assertTrue(config.contains("command: connect\n"));
        assertFalse(config.contains("command: bind"));
        assertFalse(config.contains("udp"));
        assertFalse(config.contains("from: 0.0.0.0"));
    }

    @Test
    void everyProxyAddressRejectsNamesNetworksAndConfigurationInjection() {
        for (String input : List.of("10.0.0.0/8", "controller.example", "10.0.0.1\nclient pass {")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ElbV2VpcNetworkManager.proxyConfiguration(input, "172.18.0.3", "10.0.1.10"));
            assertThrows(IllegalArgumentException.class,
                    () -> ElbV2VpcNetworkManager.proxyConfiguration("172.18.0.2", input, "10.0.1.10"));
            assertThrows(IllegalArgumentException.class,
                    () -> ElbV2VpcNetworkManager.proxyConfiguration("172.18.0.2", "172.18.0.3", input));
        }
    }
}

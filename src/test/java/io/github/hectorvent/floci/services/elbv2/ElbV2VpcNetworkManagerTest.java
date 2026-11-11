package io.github.hectorvent.floci.services.elbv2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.net.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void shutdownRemovesOnlyOwnedAlbHelpersWithoutDeletingStoredInterfaces() {
        DockerClient docker = nativeDocker();
        Container first = helper("first", "this-run/4566", "elbv2");
        Container second = helper("second", "this-run/4566", "elbv2");
        Container foreign = helper("foreign", "other-run/4566", "elbv2");
        Container ec2Helper = helper("ec2", "this-run/4566", "ec2");
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec())
                .thenReturn(List.of(first, second, foreign, ec2Helper), List.of());

        ContainerTeardown teardown = manager;
        teardown.stopManagedContainers();
        teardown.stopManagedContainers();

        verify(lifecycle).removeIfExistsStrict("first");
        verify(lifecycle).removeIfExistsStrict("second");
        verify(lifecycle, never()).removeIfExistsStrict("foreign");
        verify(lifecycle, never()).removeIfExistsStrict("ec2");
        verify(firewall).unregister("eni-first");
        verify(firewall).unregister("eni-second");
        verifyNoInteractions(ec2);
        assertTrue(manager.teardownOrder() < 0);
    }

    @Test
    void failedRemovalStaysVisibleAndDoesNotAbandonOtherHelpers() {
        DockerClient docker = nativeDocker();
        Container first = helper("first", "this-run/4566", "elbv2");
        Container second = helper("second", "this-run/4566", "elbv2");
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec())
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("Docker unavailable")).when(lifecycle).removeIfExistsStrict("first");

        assertThrows(IllegalStateException.class, manager::stopManagedContainers);

        verify(firewall).unregister("eni-first");
        verify(lifecycle).removeIfExistsStrict("second");
        verify(firewall).unregister("eni-second");
        verifyNoInteractions(ec2);
    }

    @Test
    void failedFirewallRetirementStillRemovesTheHelperAndContinues() {
        DockerClient docker = nativeDocker();
        Container first = helper("first", "this-run/4566", "elbv2");
        Container second = helper("second", "this-run/4566", "elbv2");
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec())
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("Peer refresh failed")).when(firewall).unregister("eni-first");

        assertThrows(IllegalStateException.class, manager::stopManagedContainers);

        InOrder ordered = inOrder(firewall, lifecycle);
        ordered.verify(firewall).unregister("eni-first");
        ordered.verify(lifecycle).removeIfExistsStrict("first");
        ordered.verify(firewall).unregister("eni-second");
        ordered.verify(lifecycle).removeIfExistsStrict("second");
        verifyNoInteractions(ec2);
    }

    @Test
    void resetQuiescesReconciliationAndReopensItAfterCleanup() {
        DockerClient docker = nativeDocker();
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(List.of());
        LoadBalancer balancer = new LoadBalancer();
        balancer.setType("application");
        balancer.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-east-1:111111111111:loadbalancer/app/api/123");
        balancer.setRegion("us-east-1");
        balancer.setAvailabilityZones(List.of());
        manager.beforeReset();
        assertThrows(IllegalStateException.class, () -> manager.reconcile(balancer, () -> {}));
        manager.clear();
        manager.afterReset();
        manager.reconcile(balancer, () -> {});
        verifyNoInteractions(ec2);
    }

    private DockerClient nativeDocker() {
        when(firewall.enabled()).thenReturn(true);
        when(networks.enabled()).thenReturn(true);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("this-run"));
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        return docker;
    }

    private Container helper(String id, String owner, String service) {
        Container helper = mock(Container.class);
        when(helper.getId()).thenReturn(id);
        when(helper.getLabels()).thenReturn(Map.of(ContainerStorageHelper.OWNER_LABEL, owner,
                ContainerStorageHelper.SERVICE_LABEL, service,
                ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true",
                ElbV2VpcNetworkManager.ENI_LABEL, "eni-" + id,
                ElbV2VpcNetworkManager.BALANCER_LABEL, "balancer"));
        return helper;
    }
}

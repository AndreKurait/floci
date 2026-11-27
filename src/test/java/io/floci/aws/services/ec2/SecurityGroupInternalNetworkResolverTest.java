package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectNetworkCmd;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Network;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.CurrentContainerNetworkResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityGroupInternalNetworkResolverTest {
    private final DockerClient docker = mock(DockerClient.class);
    private final EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
    private final CurrentContainerNetworkResolver current = mock(CurrentContainerNetworkResolver.class);
    private final InspectNetworkCmd inspectNetwork = mock(InspectNetworkCmd.class, RETURNS_SELF);
    private final InspectContainerCmd inspectContainer = mock(InspectContainerCmd.class);
    private final InspectContainerResponse inspected = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
    private final Network network = mock(Network.class);
    private final SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(docker,
            mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
            mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), dns);

    SecurityGroupInternalNetworkResolverTest() {
        manager.currentContainer = current;
        when(dns.getServerIp()).thenReturn(Optional.of("172.20.0.2"));
        when(current.resolveContainerId()).thenReturn(Optional.of("floci"));
        when(docker.inspectNetworkCmd()).thenReturn(inspectNetwork);
        when(inspectNetwork.exec()).thenReturn(network);
        when(network.getId()).thenReturn("control-id");
        when(docker.inspectContainerCmd("floci")).thenReturn(inspectContainer);
        when(inspectContainer.exec()).thenReturn(inspected);
    }

    @Test
    void internalNetworkUsesAnActuallyAttachedFlociAddress() {
        when(network.getInternal()).thenReturn(true);
        ContainerNetwork control = mock(ContainerNetwork.class);
        when(control.getNetworkID()).thenReturn("control-id");
        when(control.getIpAddress()).thenReturn("172.21.0.2");
        when(inspected.getNetworkSettings().getNetworks()).thenReturn(Map.of("control", control));
        when(dns.resolverOnLocalAddress("172.21.0.2")).thenReturn("172.21.0.2");

        assertEquals("172.21.0.2", manager.namespaceResolver("control"));
        verify(dns).resolverOnLocalAddress("172.21.0.2");
        verify(dns, never()).resolverOnLocalAddress("172.20.0.2");
    }

    @Test
    void ordinaryNetworkRetainsThePrimaryResolver() {
        when(network.getInternal()).thenReturn(false);

        assertEquals("172.20.0.2", manager.namespaceResolver("control"));
        verify(dns, never()).resolverOnLocalAddress(anyString());
        verify(docker, never()).inspectContainerCmd(anyString());
    }

    @Test
    void aNetworkFlociDidNotJoinCannotGainAResolver() {
        when(network.getInternal()).thenReturn(true);
        ContainerNetwork foreign = mock(ContainerNetwork.class);
        when(foreign.getNetworkID()).thenReturn("foreign-id");
        when(foreign.getIpAddress()).thenReturn("172.21.0.9");
        when(inspected.getNetworkSettings().getNetworks()).thenReturn(Map.of("control", foreign));

        assertThrows(IllegalStateException.class, () -> manager.namespaceResolver("control"));
        verify(dns, never()).resolverOnLocalAddress(anyString());
    }

    @Test
    void disabledDnsRemainsDisabled() {
        when(dns.getServerIp()).thenReturn(Optional.empty());

        assertEquals(null, manager.namespaceResolver("control"));
        verify(docker, never()).inspectNetworkCmd();
        verify(dns, never()).resolverOnLocalAddress(anyString());
    }

    @Test
    void dnsAdmissionIncludesBoundNetworkResolversWithoutReplacingThePrimary() {
        when(dns.networkResolverIps()).thenReturn(List.of("172.21.0.2", "172.20.0.2"));

        assertEquals(List.of("172.20.0.2", "172.21.0.2"), manager.vpcResolvers());
    }
}

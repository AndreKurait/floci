package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hot attach (EC2 User Guide, "Attach a network interface to an instance"): an interface attached
 * to a running instance becomes a device of the instance with the interface's MAC and primary
 * private IPv4, in the interface's own VPC.
 */
class Ec2HotAttachInterfaceTest {

    private static final String REGION = "us-east-1";
    private static final String MAC = "02:11:22:33:44:55";

    private final DockerClient docker = mock(DockerClient.class);
    private final VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
    private final SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);

    private Ec2ContainerManager manager() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.nativePeerDependencies()).thenReturn("[]");
        when(vpcNetworks.enabled()).thenReturn(true);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse guest = mock(InspectContainerResponse.class);
        when(guest.getHostConfig()).thenReturn(new HostConfig().withNetworkMode("container:helper-1"));
        when(inspect.exec()).thenReturn(guest);
        when(docker.inspectContainerCmd("guest-1")).thenReturn(inspect);
        return new Ec2ContainerManager(mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                docker, mock(PortAllocator.class), config, mock(Ec2MetadataServer.class),
                mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), vpcNetworks, mock(ContainerReachableEndpoint.class),
                firewall);
    }

    private static InstanceNetworkInterface device(String id, int index, String vpcId, String subnetId, String ip,
                                                   String mac) {
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId(id);
        eni.setDeviceIndex(index);
        eni.setVpcId(vpcId);
        eni.setSubnetId(subnetId);
        eni.setPrivateIpAddress(ip);
        eni.setMacAddress(mac);
        return eni;
    }

    private static Instance instanceWith(InstanceNetworkInterface... interfaces) {
        Instance instance = new Instance();
        instance.setInstanceId("i-0guest");
        instance.setRegion(REGION);
        instance.setDockerContainerId("guest-1");
        instance.setVpcId("vpc-home");
        instance.setNetworkInterfaces(new ArrayList<>(List.of(interfaces)));
        return instance;
    }

    private static InstanceNetworkInterface primary() {
        return device("eni-0primary", 0, "vpc-home", "subnet-home", "10.1.0.10", "02:aa:aa:aa:aa:aa");
    }

    private static InstanceNetworkInterface customer() {
        return device("eni-0customer", 1, "vpc-customer", "subnet-customer", "172.31.5.9", MAC);
    }

    @Test
    void anInterfaceInAnotherVpcBecomesADeviceOfTheNamespaceOwner() {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        Instance instance = instanceWith(primary(), attached);
        when(vpcNetworks.attachInterface(REGION, "vpc-customer", "subnet-customer", "helper-1", "172.31.5.9", MAC))
                .thenReturn(Optional.of("customer-network"));

        assertEquals(Optional.empty(), manager.plugNetworkInterface(instance, attached));

        verify(vpcNetworks).attachInterface(REGION, "vpc-customer", "subnet-customer", "helper-1", "172.31.5.9", MAC);
        manager.unplugNetworkInterface(instance, attached);
        verify(vpcNetworks).detachInterface(REGION, "vpc-customer", "helper-1");
    }

    @Test
    void securityGroupEnforcementKeepsTheInterfaceRecordOnly() {
        Ec2ContainerManager manager = manager();
        when(firewall.enabled()).thenReturn(true);
        InstanceNetworkInterface attached = customer();
        Instance instance = instanceWith(primary(), attached);

        Optional<String> reason = manager.plugNetworkInterface(instance, attached);

        assertTrue(reason.orElseThrow().contains("security-group enforcement"), reason.toString());
        manager.unplugNetworkInterface(instance, attached);
        verify(vpcNetworks, never()).attachInterface(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
        verify(vpcNetworks, never()).detachInterface(anyString(), anyString(), anyString());
    }

    @Test
    void isolatedAddressSpacesKeepTheInterfaceRecordOnly() {
        Ec2ContainerManager manager = manager();
        when(vpcNetworks.isolatesAddressSpaces()).thenReturn(true);
        InstanceNetworkInterface attached = customer();

        Optional<String> reason = manager.plugNetworkInterface(instanceWith(primary(), attached), attached);

        assertTrue(reason.orElseThrow().contains("isolated VPC address spaces"), reason.toString());
        verify(vpcNetworks, never()).attachInterface(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void aSecondInterfaceInAVpcTheNamespaceAlreadyHoldsStaysRecordOnly() {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface sameVpc = device("eni-0second", 1, "vpc-home", "subnet-home", "10.1.0.11", MAC);
        InstanceNetworkInterface customer = customer();
        InstanceNetworkInterface customerTwo = device("eni-0third", 2, "vpc-customer", "subnet-customer",
                "172.31.5.10", "02:33:33:33:33:33");

        assertTrue(manager.plugNetworkInterface(instanceWith(primary(), sameVpc), sameVpc).orElseThrow()
                .contains("one endpoint per network"));
        assertTrue(manager.plugNetworkInterface(instanceWith(primary(), customer, customerTwo), customerTwo)
                .orElseThrow().contains("one endpoint per network"));
        verify(vpcNetworks, never()).attachInterface(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void anInstanceWithoutAContainerOrNetworksKeepsTheInterfaceRecordOnly() {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        Instance mockMode = instanceWith(primary(), attached);
        mockMode.setDockerContainerId(null);

        assertTrue(manager.plugNetworkInterface(mockMode, attached).isPresent());
        when(vpcNetworks.enabled()).thenReturn(false);
        assertTrue(manager.plugNetworkInterface(instanceWith(primary(), attached), attached).isPresent());
        verify(vpcNetworks, never()).attachInterface(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void aRefusedPlugIsReportedAndTheAttachmentStands() {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        when(vpcNetworks.attachInterface(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("the attached device did not take the interface's MAC"));

        assertEquals(Optional.of("the attached device did not take the interface's MAC"),
                manager.plugNetworkInterface(instanceWith(primary(), attached), attached));
        doReturn(Optional.empty()).when(vpcNetworks).attachInterface(any(), any(), any(), any(), any(), any());
        assertTrue(manager.plugNetworkInterface(instanceWith(primary(), attached), attached).orElseThrow()
                .contains("not on a VPC network"));
    }
}

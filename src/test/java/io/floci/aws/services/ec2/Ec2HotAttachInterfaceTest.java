package io.floci.aws.services.ec2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
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
        return manager(true);
    }

    private Ec2ContainerManager manager(boolean physicalAttachment) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().extraLabels()).thenReturn(List.of());
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("hot-attach-unit"));
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.hotAttachNetworkInterfaces()).thenReturn(physicalAttachment);

        when(vpcNetworks.enabled()).thenReturn(true);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse guest = mock(InspectContainerResponse.class);
        when(guest.getHostConfig()).thenReturn(new HostConfig().withNetworkMode("container:helper-1"));
        when(inspect.exec()).thenReturn(guest);
        when(docker.inspectContainerCmd("guest-1")).thenReturn(inspect);
        InspectContainerCmd helperInspect = mock(InspectContainerCmd.class);
        Map<String, String> labels = Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.SERVICE_LABEL, "ec2",
                ContainerStorageHelper.RESOURCE_ID_LABEL, "i-0guest",
                ContainerStorageHelper.ACCOUNT_LABEL, "000000000000",
                ContainerStorageHelper.REGION_LABEL, REGION,
                Ec2ContainerManager.METADATA_HELPER_LABEL, "true");
        InspectContainerResponse helper = new ObjectMapper().convertValue(Map.of(
                "Config", Map.of("Labels", labels), "HostConfig", Map.of("NetworkMode", "bridge"), "NetworkSettings",
                Map.of("Networks", Map.of("bridge", Map.of("IPAddress", "172.17.0.2")))), InspectContainerResponse.class);
        when(helperInspect.exec()).thenReturn(helper);
        when(docker.inspectContainerCmd("helper-1")).thenReturn(helperInspect);
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
        instance.setOwnerId("000000000000");
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

    @ParameterizedTest
    @ValueSource(strings = {"io.floci.owner", "io.floci.account", "io.floci.region", "io.floci.resource-id"})
    void aHelperOutsideTheInstancesScopeCannotReceiveTheInterface(String key) {
        Ec2ContainerManager manager = manager();
        docker.inspectContainerCmd("helper-1").exec().getConfig().getLabels().put(key, "other");
        InstanceNetworkInterface eni = customer();
        assertTrue(manager.plugNetworkInterface(instanceWith(primary(), eni), eni).isPresent());
        verify(vpcNetworks, never()).attachInterface(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void retainedSecurityGroupRulesStillBlockPhysicalAttachmentAfterConfigIsDisabled() {
        Ec2ContainerManager manager = manager();
        docker.inspectContainerCmd("helper-1").exec().getConfig().getLabels()
                .put(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true");
        InstanceNetworkInterface eni = customer();
        assertTrue(manager.plugNetworkInterface(instanceWith(primary(), eni), eni).isPresent());
        verify(vpcNetworks, never()).attachInterface(any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "container:another"})
    void aHelperSharingAnotherNamespaceCannotReceiveTheInterface(String mode) {
        Ec2ContainerManager manager = manager();
        docker.inspectContainerCmd("helper-1").exec().getHostConfig().withNetworkMode(mode);
        InstanceNetworkInterface eni = customer();
        assertTrue(manager.plugNetworkInterface(instanceWith(primary(), eni), eni).isPresent());
        verify(vpcNetworks, never()).attachInterface(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordOnlyAttachmentCannotClaimAnExistingEndpointDuringRestoration() {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        Instance instance = instanceWith(primary(), attached);
        when(vpcNetworks.attachInterface("000000000000", REGION, "vpc-customer", "subnet-customer",
                "helper-1", "172.31.5.9", MAC)).thenReturn(Optional.empty());

        assertTrue(manager.plugNetworkInterface(instance, attached).isPresent());
        manager.restoreAttachedNetworkInterfaces(instance);

        verify(vpcNetworks, never()).restoreInterface(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void aSuccessfulAttachmentRecordsItsNamespaceOwner() {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        when(vpcNetworks.attachInterface("000000000000", REGION, "vpc-customer", "subnet-customer",
                "helper-1", "172.31.5.9", MAC)).thenReturn(Optional.of("customer-network"));

        assertEquals(Optional.empty(), manager.plugNetworkInterface(instanceWith(primary(), attached), attached));
        assertEquals("helper-1", attached.getSharedNetworkContainerId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"helper-1", "different-helper"})
    void interfaceSnapshotRetainsThePhysicalOwnershipNeededForRecovery(String owner) {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        attached.setSharedNetworkContainerId(owner);

        manager.restoreAttachedNetworkInterfaces(instanceWith(primary(), attached.copy()));

        if ("helper-1".equals(owner)) {
            verify(vpcNetworks).restoreInterface("000000000000", REGION, "vpc-customer",
                    "helper-1", "172.31.5.9", MAC);
        } else {
            verify(vpcNetworks, never()).restoreInterface(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString());
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"helper-1", "different-helper"})
    void restoredMetadataRepairsOnlyItsRecordedNamespace(String owner) throws Exception {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        attached.setSharedNetworkContainerId(owner);
        ObjectMapper mapper = new ObjectMapper();
        InstanceNetworkInterface restored = mapper.readValue(mapper.writeValueAsBytes(attached),
                InstanceNetworkInterface.class);

        manager.restoreAttachedNetworkInterfaces(instanceWith(primary(), restored));

        if ("helper-1".equals(owner)) {
            verify(vpcNetworks).restoreInterface("000000000000", REGION, "vpc-customer",
                    "helper-1", "172.31.5.9", MAC);
        } else {
            verify(vpcNetworks, never()).restoreInterface(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString());
        }
    }

    @Test
    void restorationUsesTheInterfaceOwnerAndNeverThePrimaryInterface() {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        attached.setOwnerId("111122223333");
        Instance instance = instanceWith(primary(), attached);
        when(vpcNetworks.attachInterface("111122223333", REGION, "vpc-customer", "subnet-customer",
                "helper-1", "172.31.5.9", MAC)).thenReturn(Optional.of("foreign-network"));
        assertEquals(Optional.empty(), manager.plugNetworkInterface(instance, attached));
        manager.restoreAttachedNetworkInterfaces(instance);
        verify(vpcNetworks).restoreInterface("111122223333", REGION, "vpc-customer", "helper-1", "172.31.5.9", MAC);
        verify(vpcNetworks, never()).restoreInterface(anyString(), anyString(), eq("vpc-home"),
                anyString(), anyString(), anyString());
    }

    @Test
    void physicalAttachmentRequiresExplicitOptIn() {
        Ec2ContainerManager manager = manager(false);
        InstanceNetworkInterface attached = customer();
        Instance instance = instanceWith(primary(), attached);
        assertEquals(Optional.of("physical network-interface attachment is disabled"),
                manager.plugNetworkInterface(instance, attached));
        manager.unplugNetworkInterface(instance, attached);
        verify(docker, never()).inspectContainerCmd(anyString());
    }

    @Test
    void permittedForeignInterfaceUsesItsOwnersNetworkOnAttachAndDetach() {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        attached.setOwnerId("111122223333");
        // Two accounts can have the same default VPC id without sharing a network.
        attached.setVpcId("vpc-home");
        Instance instance = instanceWith(primary(), attached);
        when(vpcNetworks.attachInterface("111122223333", REGION, "vpc-home", "subnet-customer",
                "helper-1", "172.31.5.9", MAC)).thenReturn(Optional.of("foreign-network"));
        assertEquals(Optional.empty(), manager.plugNetworkInterface(instance, attached));
        manager.unplugNetworkInterface(instance, attached);
        verify(vpcNetworks).detachInterface("111122223333", REGION, "vpc-home", "helper-1", "172.31.5.9", MAC);
    }

    @Test
    void anInterfaceInAnotherVpcBecomesADeviceOfTheNamespaceOwner() {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        Instance instance = instanceWith(primary(), attached);
        when(vpcNetworks.attachInterface("000000000000", REGION, "vpc-customer", "subnet-customer", "helper-1", "172.31.5.9", MAC))
                .thenReturn(Optional.of("customer-network"));

        assertEquals(Optional.empty(), manager.plugNetworkInterface(instance, attached));

        verify(vpcNetworks).attachInterface("000000000000", REGION, "vpc-customer", "subnet-customer", "helper-1", "172.31.5.9", MAC);
        manager.unplugNetworkInterface(instance, attached);
        verify(vpcNetworks).detachInterface("000000000000", REGION, "vpc-customer", "helper-1", "172.31.5.9", MAC);
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
                anyString(), anyString(), anyString());
        verify(vpcNetworks, never()).detachInterface(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
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
                anyString(), anyString(), anyString());
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
                anyString(), anyString(), anyString());
    }

    @Test
    void aRefusedPlugIsReportedAndTheAttachmentStands() {
        Ec2ContainerManager manager = manager();
        InstanceNetworkInterface attached = customer();
        when(vpcNetworks.attachInterface(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("endpoint MACs need Docker Engine 25 or later"));

        assertEquals(Optional.of("endpoint MACs need Docker Engine 25 or later"),
                manager.plugNetworkInterface(instanceWith(primary(), attached), attached));
        doReturn(Optional.empty()).when(vpcNetworks).attachInterface(any(), any(), any(), any(), any(), any(), any());
        assertTrue(manager.plugNetworkInterface(instanceWith(primary(), attached), attached).orElseThrow()
                .contains("not on a VPC network"));
        when(vpcNetworks.attachInterface(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException());
        assertEquals(Optional.of("IllegalStateException"),
                manager.plugNetworkInterface(instanceWith(primary(), attached), attached));
    }
}

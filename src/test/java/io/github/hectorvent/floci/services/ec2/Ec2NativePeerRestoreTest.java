package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2NativePeerRestoreTest {
    private static final String HELPER = "a".repeat(64);
    private static final String WORKER = "b".repeat(64);
    private static final String POLICY = """
            [{"hostname":"api.peer.example","address":"172.18.0.2","port":443}]
            """;

    @Test
    void persistentRestoreUsesCurrentPolicyAndRemovalWithdrawsItsControls() {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        when(firewall.enabled()).thenReturn(true);
        RegionResolver region = mock(RegionResolver.class);
        when(region.getAccountId()).thenReturn("111111111111");
        Instance instance = new Instance();
        instance.setInstanceId("i-11111111111111111");
        instance.setDockerContainerId(WORKER);
        instance.setVpcId("vpc-test");
        instance.setRegion("us-east-1");
        instance.setContainerBridgeIp("10.0.1.10");
        instance.setLogicalPrivateIpAddress("10.0.1.10");
        GroupIdentifier groupId = new GroupIdentifier();
        groupId.setGroupId("sg-test");
        instance.setSecurityGroups(List.of(groupId));
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId("eni-test");
        eni.setPrivateIpAddress("10.0.1.10");
        instance.setNetworkInterfaces(List.of(eni));
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg-test");
        ObjectMapper mapper = new ObjectMapper();
        InspectContainerResponse worker = mapper.convertValue(Map.of("Id", WORKER,
                "HostConfig", Map.of("NetworkMode", "container:" + HELPER),
                "Config", Map.of("Labels", Map.of())), InspectContainerResponse.class);
        when(docker.inspectContainerCmd(WORKER).exec()).thenReturn(worker);
        for (String declaration : List.of(POLICY, "[]")) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().ec2().nativePeerDependencies()).thenReturn(declaration);
            when(config.docker().resourceNamespace()).thenReturn(Optional.of("native-peer-restore"));
            InspectContainerResponse helper = mapper.convertValue(Map.of("Id", HELPER,
                    "HostConfig", Map.of("NetworkMode", "control"),
                    "Config", Map.of("Labels", Map.of(
                            ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true",
                            ContainerStorageHelper.SERVICE_LABEL, "ec2",
                            ContainerStorageHelper.RESOURCE_ID_LABEL, instance.getInstanceId(),
                            ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config))),
                    "NetworkSettings", Map.of("Networks", Map.of(
                            "control", Map.of("NetworkID", "control-id", "IPAddress", "172.18.0.3"),
                            "vpc", Map.of("NetworkID", "vpc-id", "IPAddress", "10.0.1.10")))),
                    InspectContainerResponse.class);
            when(docker.inspectContainerCmd(HELPER).exec()).thenReturn(helper);
            Ec2ContainerManager manager = new Ec2ContainerManager(
                    mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                    mock(DockerHostResolver.class), docker, mock(PortAllocator.class), config,
                    mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), region,
                    mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                    mock(ContainerReachableEndpoint.class), firewall, mock(Ec2InstanceTypeCatalog.class));
            try {
                manager.restoreSecurityGroups(instance, "us-east-1", List.of(group), Map.of());
            } finally {
                manager.stop();
            }
        }
        ArgumentCaptor<SecurityGroupNftCompiler.Endpoint> captured =
                ArgumentCaptor.forClass(SecurityGroupNftCompiler.Endpoint.class);
        verify(firewall, times(2)).register(captured.capture(), eq(HELPER), eq(Map.of()));
        SecurityGroupNftCompiler.Endpoint configured = captured.getAllValues().getFirst();
        assertEquals(new Ec2NativePeerDependencies(POLICY).dependencies(), configured.nativePeerDependencies());
        assertEquals(new Ec2NativePeerDependencies(POLICY).connections(), configured.controlConnections());
        SecurityGroupNftCompiler.Endpoint removed = captured.getAllValues().getLast();
        assertTrue(removed.nativePeerDependencies().isEmpty());
        assertTrue(removed.controlConnections().isEmpty());
        assertEquals(configured.transportAddress(), removed.transportAddress());
        assertEquals(configured.logicalAddress(), removed.logicalAddress());
    }
}

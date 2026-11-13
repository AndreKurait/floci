package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfaceListResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VpcPublicInstanceRecoveryTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String PUBLIC_IP = "172.18.0.3";
    private static final String TRANSPORT = "public-transport-id";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final Ec2Service ec2 = mock(Ec2Service.class, RETURNS_DEEP_STUBS);
    private final VpcNetworkManager vpcs = mock(VpcNetworkManager.class);
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, RETURNS_SELF);
    private final Map<String, ContainerNetwork> attachments = new LinkedHashMap<>();
    private final SecurityGroupNftCompiler.Endpoint identity = new SecurityGroupNftCompiler.Endpoint(
            ACCOUNT, REGION, "vpc", "eni", "10.0.1.10", "10.0.1.10",
            Set.of("sg"), List.of(), List.of(), false, Set.of("10.0.1.10"));
    private final VpcRoutingManager manager = spy(new VpcRoutingManager(
            config, ec2, vpcs, docker, mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
            JSON, mock(ContainerReachableEndpoint.class)));

    @BeforeEach
    void prepareRetainedNamespace() {
        when(config.network().vpcRouting().enabled()).thenReturn(true);
        when(config.network().vpcRouting().publicAwsApi()).thenReturn(true);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("public-recovery"));
        when(vpcs.enabled()).thenReturn(true);
        NetworkInterface eni = new NetworkInterface();
        eni.setVpcId("vpc");
        eni.setSubnetId("subnet");
        eni.setPrivateIpAddress("10.0.1.10");
        when(ec2.describeNetworkInterfaces(ACCOUNT, REGION, List.of("eni"), Map.of()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));
        attachments.put("control", attachment("internal-id", PUBLIC_IP));
        attachments.put("vpc", attachment("vpc-id", "10.0.1.10"));
        when(docker.inspectContainerCmd("helper").exec().getNetworkSettings().getNetworks()).thenReturn(attachments);
        Network internal = mock(Network.class);
        when(internal.getInternal()).thenReturn(true);
        when(docker.inspectNetworkCmd().withNetworkId("internal-id").exec()).thenReturn(internal);
        Network transport = mock(Network.class);
        when(transport.getId()).thenReturn(TRANSPORT);
        String owner = ContainerStorageHelper.ownerIdentity(config);
        when(transport.getLabels()).thenReturn(Map.of(
                ContainerStorageHelper.OWNER_LABEL, owner,
                ContainerStorageHelper.COMPONENT_LABEL, "vpc-public-transport"));
        when(transport.getOptions()).thenReturn(Map.of("com.docker.network.bridge.enable_ip_masquerade", "false"));
        String name = ContainerStorageHelper.resourceName(config, "vpc-public-transport", null, "network");
        when(docker.inspectNetworkCmd().withNetworkId(name).exec()).thenReturn(transport);
        when(docker.inspectNetworkCmd().withNetworkId(TRANSPORT).exec()).thenReturn(transport);
        when(docker.connectToNetworkCmd()).thenReturn(connect);
        doAnswer(invocation -> {
            if (attachments.values().stream().anyMatch(network -> TRANSPORT.equals(network.getNetworkID()))) {
                throw new DockerException("endpoint already exists in network", 403);
            }
            attachments.put("public-transport", attachment(TRANSPORT, "172.19.0.3"));
            return null;
        }).when(connect).exec();
        ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("helper-exec");
        when(create.exec()).thenReturn(created);
        when(docker.execCreateCmd("helper")).thenReturn(create);
        when(docker.inspectExecCmd("helper-exec").exec().getExitCodeLong()).thenReturn(0L);
        when(docker.execStartCmd("helper-exec").exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            String output = """
                    [{"ifname":"eth0","addr_info":[{"local":"172.18.0.3"}]},
                     {"ifname":"eth1","addr_info":[{"local":"10.0.1.10"}]},
                     {"ifname":"eth2","addr_info":[{"local":"172.19.0.3"}]}]
                    """;
            callback.onNext(new Frame(StreamType.STDOUT, output.getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        });
        doNothing().when(manager).reconcile();
        manager.register(identity, "helper", List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"public-transport", TRANSPORT})
    void freshManagerReusesTheRetainedTransportByNetworkId(String name) {
        attachments.put(name, attachment(TRANSPORT, "172.19.0.3"));

        assertDoesNotThrow(() -> manager.attachPublicInstance(identity, "helper", PUBLIC_IP));

        verifyNoInteractions(connect);
        verify(manager, times(2)).reconcile();
    }

    @Test
    void missingTransportConnectsOnceAcrossRepeatedAdmission() {
        manager.attachPublicInstance(identity, "helper", PUBLIC_IP);
        manager.attachPublicInstance(identity, "helper", PUBLIC_IP);

        verify(connect).withNetworkId(TRANSPORT);
        verify(connect).withContainerId("helper");
        verify(connect).exec();
        verify(manager, times(3)).reconcile();
    }

    @Test
    void forbiddenFreshConnectionStillFails() {
        DockerException denied = new DockerException("permission denied", 403);
        doThrow(denied).when(connect).exec();

        assertSame(denied, assertThrows(DockerException.class,
                () -> manager.attachPublicInstance(identity, "helper", PUBLIC_IP)));

        verify(connect).exec();
        verify(docker, never()).disconnectFromNetworkCmd();
    }

    private static ContainerNetwork attachment(String id, String address) {
        return JSON.convertValue(Map.of("NetworkID", id, "IPAddress", address), ContainerNetwork.class);
    }
}

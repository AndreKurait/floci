package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RenameContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.NetworkSettings;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@QuarkusTest
class Ec2ReplaceRootVolumeRequestScopeIntegrationTest {

    @Inject
    RegionResolver resolver;

    @Test
    void replacedRootPublishesTheGuestInTheOwnersAddressSpace() throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor();
             ExecutorService retries = Executors.newSingleThreadExecutor()) {
            Scope caller = new Scope("999900001111", "ap-southeast-1", "aws");
            Scope expected = new Scope("444455556666", "us-gov-east-1", "aws-us-gov");
            Instance instance = new Instance();
            instance.setInstanceId("i-protected-replace");
            instance.setDockerContainerId("container-old");
            instance.setOwnerId(expected.account());
            instance.setRegion(expected.region());
            instance.setState(InstanceState.running());
            instance.setVpcId("vpc-protected");
            instance.setSubnetId("subnet-protected");
            instance.setContainerBridgeIp("10.0.0.7");
            instance.setPrivateIpAddress("10.0.0.7");

            DockerClient docker = mock(DockerClient.class);
            InspectContainerResponse workload = mock(InspectContainerResponse.class);
            when(workload.getHostConfig()).thenReturn(HostConfig.newHostConfig().withNetworkMode("container:helper"));
            when(workload.getConfig()).thenReturn(new ContainerConfig().withLabels(Map.of(
                    "io.floci.service", "ec2", "io.floci.resource-id", instance.getInstanceId(),
                    "io.floci.owner", "4566")));
            for (String id : new String[] {"container-old", "container-new"}) {
                InspectContainerCmd inspect = mock(InspectContainerCmd.class);
                when(docker.inspectContainerCmd(id)).thenReturn(inspect);
                when(inspect.exec()).thenReturn(workload);
            }
            InspectContainerCmd inspectHelper = mock(InspectContainerCmd.class);
            InspectContainerResponse helper = mock(InspectContainerResponse.class);
            when(docker.inspectContainerCmd("helper")).thenReturn(inspectHelper);
            when(inspectHelper.exec()).thenReturn(helper);
            NetworkSettings helperNetworks = mock(NetworkSettings.class);
            when(helper.getNetworkSettings()).thenReturn(helperNetworks);
            when(helperNetworks.getNetworks()).thenReturn(Map.of());
            when(helper.getConfig()).thenReturn(new ContainerConfig().withLabels(Map.of(
                    Ec2MetadataNamespace.HELPER_LABEL, "true", "io.floci.service", "ec2",
                    "io.floci.resource-id", instance.getInstanceId(), "io.floci.owner", "4566")));
            RenameContainerCmd rename = mock(RenameContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            when(docker.renameContainerCmd("container-old")).thenReturn(rename);
            StopContainerCmd stop = mock(StopContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            when(docker.stopContainerCmd("container-old")).thenReturn(stop);

            ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class,
                    withSettings().defaultAnswer(RETURNS_SELF));
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(new ContainerSpec("ubuntu:26.04"));
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            when(lifecycle.create(any(ContainerSpec.class))).thenReturn("container-new");
            when(lifecycle.isContainerRunning("container-new")).thenReturn(true);
            ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
            when(endpoint.baseUrl()).thenReturn("http://floci:4566");

            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.port()).thenReturn(4566);
            VpcNetworkManager networks = mock(VpcNetworkManager.class);
            when(networks.isolatesAddressSpaces()).thenReturn(true);
            CompletableFuture<Scope> lookedUp = new CompletableFuture<>();
            when(networks.requireNativePrivateAddress(expected.account(), expected.region(), "vpc-protected",
                    "subnet-protected", "helper", "10.0.0.7", null)).thenAnswer(invocation -> {
                        lookedUp.complete(scope());
                        // Native address spaces are keyed by account: any other account has no such subnet.
                        if (!expected.account().equals(resolver.getAccountId())) {
                            throw new IllegalStateException(
                                    "The recorded private address is outside its declared native subnet");
                        }
                        return "10.0.0.7";
                    });
            Ec2ContainerManager manager = new Ec2ContainerManager(
                    containerBuilder, lifecycle, mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                    mock(DockerHostResolver.class), docker, mock(PortAllocator.class), config,
                    mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), resolver,
                    mock(ContainerNetworkReachability.class), networks, endpoint,
                    new Ec2InstanceTypeCatalog(), worker, retries, Duration.ofSeconds(1));
            CompletableFuture<Scope> replaced = new CompletableFuture<>();

            RequestScopes.runAs(caller.account(), caller.region(), () -> {
                manager.replaceRoot(instance, ResolvedAmiImage.minimal("ubuntu:26.04"), null, () -> { },
                        () -> replaced.complete(scope()),
                        reason -> replaced.completeExceptionally(new IllegalStateException(reason)));
                assertEquals(caller, scope(), "Scheduling must preserve the caller's context");
            });

            assertEquals(expected, replaced.get(5, TimeUnit.SECONDS),
                    "The replaced guest is published in its owner's account and region");
            assertEquals(expected, lookedUp.get(5, TimeUnit.SECONDS));
            verify(stop).exec();
            verify(containerBuilder).newContainer("ubuntu:26.04");
            assertEquals("container-new", instance.getDockerContainerId());
            assertEquals("running", instance.getState().getName());
            assertEquals("10.0.0.7", instance.getContainerBridgeIp());
            assertEquals("10.0.0.7", instance.getPrivateIpAddress());
        }
    }

    private Scope scope() {
        return new Scope(resolver.getAccountId(), resolver.getRegion(), resolver.getPartition());
    }

    private record Scope(String account, String region, String partition) {}
}

package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RestartContainerCmd;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.HostConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class Ec2RebootRequestScopeIntegrationTest {

    @Inject
    RegionResolver resolver;

    @Test
    void protectedRebootRevalidatesTheAddressInTheOwnersAddressSpace() throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor();
             ExecutorService retries = Executors.newSingleThreadExecutor()) {
            Scope caller = new Scope("999900001111", "ap-southeast-1", "aws");
            for (boolean hasOwner : List.of(true, false)) {
                Scope expected = new Scope(hasOwner ? "444455556666" : caller.account(),
                        "us-gov-east-1", "aws-us-gov");
                Instance instance = new Instance();
                instance.setInstanceId("i-protected-reboot-" + hasOwner);
                instance.setDockerContainerId("container-reboot-" + hasOwner);
                instance.setOwnerId(hasOwner ? expected.account() : null);
                instance.setRegion(expected.region());
                instance.setState(InstanceState.running());
                instance.setVpcId("vpc-protected");
                instance.setSubnetId("subnet-protected");
                instance.setContainerBridgeIp("10.0.0.7");
                DockerClient docker = mock(DockerClient.class);
                RestartContainerCmd restart = mock(RestartContainerCmd.class);
                when(docker.restartContainerCmd(instance.getDockerContainerId())).thenReturn(restart);
                InspectContainerCmd inspectWorkload = mock(InspectContainerCmd.class);
                InspectContainerCmd inspectHelper = mock(InspectContainerCmd.class);
                InspectContainerResponse workload = mock(InspectContainerResponse.class);
                InspectContainerResponse helper = mock(InspectContainerResponse.class);
                when(docker.inspectContainerCmd(instance.getDockerContainerId())).thenReturn(inspectWorkload);
                when(inspectWorkload.exec()).thenReturn(workload);
                when(workload.getHostConfig()).thenReturn(HostConfig.newHostConfig().withNetworkMode("container:helper"));
                when(docker.inspectContainerCmd("helper")).thenReturn(inspectHelper);
                when(inspectHelper.exec()).thenReturn(helper);
                when(helper.getConfig()).thenReturn(new ContainerConfig().withLabels(Map.of(
                        Ec2MetadataNamespace.HELPER_LABEL, "true", "io.floci.service", "ec2",
                        "io.floci.resource-id", instance.getInstanceId(), "io.floci.owner", "4566")));
                EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
                when(config.port()).thenReturn(4566);
                VpcNetworkManager networks = mock(VpcNetworkManager.class);
                when(networks.isolatesAddressSpaces()).thenReturn(true);
                CompletableFuture<Scope> lookedUp = new CompletableFuture<>();
                when(networks.requireNativePrivateAddress(expected.region(), "vpc-protected", "subnet-protected",
                        "helper", "10.0.0.7", null)).thenAnswer(invocation -> {
                            lookedUp.complete(scope());
                            // Native address spaces are keyed by account: any other account has no such subnet.
                            if (!expected.account().equals(resolver.getAccountId())) {
                                throw new IllegalStateException(
                                        "The recorded private address is outside its declared native subnet");
                            }
                            return "10.0.0.7";
                        });
                Ec2ContainerManager manager = new Ec2ContainerManager(
                        mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                        mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                        mock(DockerHostResolver.class), docker, mock(PortAllocator.class), config,
                        mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), resolver,
                        mock(ContainerNetworkReachability.class), networks, mock(ContainerReachableEndpoint.class),
                        new Ec2InstanceTypeCatalog(), worker, retries, Duration.ofSeconds(1));
                CompletableFuture<Scope> published = new CompletableFuture<>();

                RequestScopes.runAs(caller.account(), caller.region(), () -> {
                    manager.reboot(instance, () -> published.complete(scope()));
                    assertEquals(caller, scope(), "Scheduling must preserve the caller's context");
                });

                assertEquals(expected, lookedUp.get(5, TimeUnit.SECONDS));
                assertEquals(expected, published.get(5, TimeUnit.SECONDS),
                        "The rebooted guest's addresses are published once its own address space accepts them");
                assertFalse(worker.submit(() -> Arc.container().requestContext().isActive()).get(5, TimeUnit.SECONDS),
                        "A reboot must not leak its context to the next task");
                verify(restart).exec();
                assertEquals("running", instance.getState().getName());
                assertEquals("10.0.0.7", instance.getContainerBridgeIp());
            }
        }
    }

    private Scope scope() {
        return new Scope(resolver.getAccountId(), resolver.getRegion(), resolver.getPartition());
    }

    private record Scope(String account, String region, String partition) {}
}

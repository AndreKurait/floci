package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class Ec2StartRequestScopeIntegrationTest {

    @Inject
    RegionResolver resolver;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backgroundStartPrerequisiteUsesOwnerOrCallerAndClearsItsScopeAfterFailure(boolean ownerless) throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor();
             ExecutorService retries = Executors.newSingleThreadExecutor()) {
            Ec2ContainerManager manager = new Ec2ContainerManager(
                    mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                    mock(DockerHostResolver.class), mock(DockerClient.class), mock(PortAllocator.class),
                    mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(Ec2MetadataServer.class),
                    mock(Ec2PortForwardManager.class), resolver, mock(ContainerNetworkReachability.class),
                    mock(VpcNetworkManager.class), mock(ContainerReachableEndpoint.class),
                    new Ec2InstanceTypeCatalog(), worker, retries, Duration.ofSeconds(1));
            Scope caller = new Scope("999900001111", "ap-southeast-1", "aws");
            for (Scope owner : List.of(new Scope("111122223333", "eu-west-1", "aws"),
                    new Scope("444455556666", "us-gov-east-1", "aws-us-gov"))) {
                Instance instance = new Instance();
                instance.setInstanceId("i-" + owner.account());
                instance.setDockerContainerId("container-" + owner.account());
                instance.setOwnerId(ownerless ? null : owner.account());
                instance.setRegion(owner.region());
                CompletableFuture<Scope> observed = new CompletableFuture<>();

                RequestScopes.runAs(caller.account(), caller.region(), () -> {
                    manager.start(instance, () -> {
                        observed.complete(scope());
                        // Fail before Docker access, as a failed account-scoped VPC lookup does.
                        throw new IllegalStateException("start prerequisite refused");
                    }, null);
                    assertEquals(caller, scope(), "Scheduling must preserve the caller's context");
                });

                Scope expected = new Scope(ownerless ? caller.account() : owner.account(),
                        owner.region(), owner.partition());
                assertEquals(expected, observed.get(5, TimeUnit.SECONDS));
                assertFalse(worker.submit(() -> Arc.container().requestContext().isActive())
                        .get(5, TimeUnit.SECONDS), "A failed start must not leak its context to the next task");
                assertEquals("stopped", instance.getState().getName());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backgroundStartUsesOwnerOrCallerAndClearsItsScopeAfterFailure(boolean ownerless) throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor();
             ExecutorService retries = Executors.newSingleThreadExecutor()) {
            DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
            Ec2ContainerManager manager = manager(docker, mock(ContainerLifecycleManager.class), worker, retries);
            Scope caller = new Scope("999900001111", "ap-southeast-1", "aws");
            for (Scope owner : List.of(new Scope("111122223333", "eu-west-1", "aws"),
                    new Scope("444455556666", "us-gov-east-1", "aws-us-gov"))) {
                Instance instance = instance(ownerless ? null : owner.account(), owner.region());
                CompletableFuture<Scope> observed = new CompletableFuture<>();
                when(docker.startContainerCmd(instance.getDockerContainerId())).thenAnswer(invocation -> {
                    observed.complete(scope());
                    throw new IllegalStateException("container start refused");
                });

                RequestScopes.runAs(caller.account(), caller.region(), () -> {
                    manager.start(instance);
                    assertEquals(caller, scope(), "Scheduling must preserve the caller's context");
                });

                Scope expected = new Scope(ownerless ? caller.account() : owner.account(),
                        owner.region(), owner.partition());
                assertEquals(expected, observed.get(5, TimeUnit.SECONDS));
                assertFalse(worker.submit(() -> Arc.container().requestContext().isActive())
                        .get(5, TimeUnit.SECONDS), "A failed start must not leak its context to the next task");
                assertEquals("stopped", instance.getState().getName());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protectedNamespaceLookupUsesOwnerOrCapturedCallerAndClearsFailedScope(boolean ownerless) throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor();
             ExecutorService retries = Executors.newSingleThreadExecutor()) {
            DockerClient docker = mock(DockerClient.class);
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            Ec2ContainerManager manager = manager(docker, lifecycle, worker, retries);
            Scope caller = new Scope("999900001111", "ap-southeast-1", "aws");
            Scope expected = new Scope(ownerless ? caller.account() : "444455556666",
                    "us-gov-east-1", "aws-us-gov");
            Instance instance = instance(ownerless ? null : expected.account(), expected.region());
            when(docker.startContainerCmd(instance.getDockerContainerId())).thenReturn(mock(StartContainerCmd.class));
            when(lifecycle.isContainerRunning(instance.getDockerContainerId())).thenReturn(true);
            CompletableFuture<Scope> observed = new CompletableFuture<>();
            when(docker.inspectContainerCmd(instance.getDockerContainerId())).thenAnswer(invocation -> {
                observed.complete(scope());
                throw new IllegalStateException("protected namespace lookup refused");
            });

            RequestScopes.runAs(caller.account(), caller.region(), () -> {
                manager.start(instance);
                assertEquals(caller, scope());
            });

            assertEquals(expected, observed.get(5, TimeUnit.SECONDS));
            assertFalse(worker.submit(() -> Arc.container().requestContext().isActive()).get(5, TimeUnit.SECONDS));
            assertEquals("stopped", instance.getState().getName());
        }
    }

    private Ec2ContainerManager manager(DockerClient docker, ContainerLifecycleManager lifecycle,
                                        ExecutorService worker, ExecutorService retries) {
        return new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                mock(DockerHostResolver.class), docker, mock(PortAllocator.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(Ec2MetadataServer.class),
                mock(Ec2PortForwardManager.class), resolver, mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class), mock(ContainerReachableEndpoint.class),
                new Ec2InstanceTypeCatalog(), worker, retries, Duration.ofSeconds(1));
    }

    private Instance instance(String owner, String region) {
        Instance instance = new Instance();
        instance.setInstanceId("i-" + region);
        instance.setDockerContainerId("container-" + region);
        instance.setOwnerId(owner);
        instance.setRegion(region);
        return instance;
    }

    @Test
    void protectedVpcLookupUsesExplicitOwnerOrCapturedCallerAndClearsFailedScope() throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor();
             ExecutorService retries = Executors.newSingleThreadExecutor()) {
            Scope caller = new Scope("999900001111", "ap-southeast-1", "aws");
            for (boolean hasOwner : List.of(true, false)) {
                Scope expected = new Scope(hasOwner ? "444455556666" : caller.account(),
                        "us-gov-east-1", "aws-us-gov");
                Instance instance = new Instance();
                instance.setInstanceId("i-protected-" + hasOwner);
                instance.setDockerContainerId("container-" + hasOwner);
                instance.setOwnerId(hasOwner ? expected.account() : null);
                instance.setRegion(expected.region());
                instance.setVpcId("vpc-protected");
                instance.setSubnetId("subnet-protected");
                instance.setContainerBridgeIp("10.0.0.7");
                DockerClient docker = mock(DockerClient.class);
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
                CompletableFuture<Scope> observed = new CompletableFuture<>();
                when(networks.requireNativePrivateAddress(expected.account(), expected.region(), "vpc-protected", "subnet-protected",
                        "helper", "10.0.0.7", null)).thenAnswer(invocation -> {
                            observed.complete(scope());
                            throw new IllegalStateException("account-scoped VPC lookup refused");
                        });
                Ec2ContainerManager manager = new Ec2ContainerManager(
                        mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                        mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                        mock(DockerHostResolver.class), docker, mock(PortAllocator.class), config,
                        mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), resolver,
                        mock(ContainerNetworkReachability.class), networks, mock(ContainerReachableEndpoint.class),
                        new Ec2InstanceTypeCatalog(), worker, retries, Duration.ofSeconds(1));
                RequestScopes.runAs(caller.account(), caller.region(), () -> {
                    manager.start(instance);
                    assertEquals(caller, scope());
                });
                assertEquals(expected, observed.get(5, TimeUnit.SECONDS));
                assertFalse(worker.submit(() -> Arc.container().requestContext().isActive()).get(5, TimeUnit.SECONDS));
                assertEquals("stopped", instance.getState().getName());
                verify(docker, never()).startContainerCmd(instance.getDockerContainerId());
            }
        }
    }

    private Scope scope() {
        return new Scope(resolver.getAccountId(), resolver.getRegion(), resolver.getPartition());
    }

    private record Scope(String account, String region, String partition) {}
}

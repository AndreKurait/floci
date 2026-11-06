package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
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
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

@QuarkusTest
class Ec2StartRequestScopeIntegrationTest {

    @Inject
    RegionResolver resolver;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backgroundStartUsesOwnerOrCallerAndClearsItsScopeAfterFailure(boolean ownerless) throws Exception {
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

    private Scope scope() {
        return new Scope(resolver.getAccountId(), resolver.getRegion(), resolver.getPartition());
    }

    private record Scope(String account, String region, String partition) {}
}

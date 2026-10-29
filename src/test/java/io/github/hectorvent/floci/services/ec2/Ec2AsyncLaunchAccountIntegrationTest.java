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
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class Ec2AsyncLaunchAccountIntegrationTest {
    @Inject
    RegionResolver regionResolver;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void queuedLaunchUsesInstanceOwnerAndRestoresReusedWorkerScope(boolean legacyOwner, boolean failAllocation)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        PortAllocator ports = mock(PortAllocator.class);
        List<List<String>> observed = new ArrayList<>();
        when(ports.allocateAndStart(anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            observed.add(scope());
            if (failAllocation) {
                throw new IllegalStateException("Controlled allocation failure");
            }
            return null;
        });
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                mock(DockerHostResolver.class), mock(DockerClient.class, RETURNS_DEEP_STUBS),
                ports, config, mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class),
                regionResolver, mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class), executor, Duration.ofSeconds(1));
        try {
            List<String> baseline = executor.submit(this::scope).get(10, TimeUnit.SECONDS);
            List<List<String>> expected = List.of(
                    List.of("111122223333", "us-west-2", "aws"),
                    List.of("444455556666", "cn-north-1", "aws-cn"));
            for (List<String> selected : expected) {
                CountDownLatch release = new CountDownLatch(1);
                executor.submit(() -> {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return null;
                });
                Instance instance = new Instance();
                instance.setInstanceId("i-" + selected.getFirst());
                instance.setOwnerId(legacyOwner ? null : selected.getFirst());
                instance.setRegion(selected.get(1));
                instance.setPrivateIpAddress("10.0.0.10");
                try {
                    RequestScopes.runAs(legacyOwner ? selected.getFirst() : "999900001111", "us-east-1",
                            () -> manager.launch(instance, ResolvedAmiImage.minimal("example/guest:1"),
                                    null, selected.get(1)));
                } finally {
                    release.countDown();
                }
                assertEquals(baseline, executor.submit(this::scope).get(10, TimeUnit.SECONDS),
                        "A reused executor thread must not retain the instance's scope");
            }
            assertEquals(expected, observed);
        } finally {
            manager.stop();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private List<String> scope() {
        return List.of(regionResolver.getAccountId(), regionResolver.getRegion(), regionResolver.getPartition());
    }
}

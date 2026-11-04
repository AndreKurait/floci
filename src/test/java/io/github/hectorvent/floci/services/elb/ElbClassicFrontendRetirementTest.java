package io.github.hectorvent.floci.services.elb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class ElbClassicFrontendRetirementTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final ElbClassicFrontendManager manager;
    private final Map<String, ElbClassicFrontendManager.Frontend> frontends;

    @SuppressWarnings("unchecked")
    ElbClassicFrontendRetirementTest() throws Exception {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        ListContainersCmd listContainers = mock(ListContainersCmd.class);
        when(docker.listContainersCmd()).thenReturn(listContainers);
        when(listContainers.withShowAll(true)).thenReturn(listContainers);
        when(listContainers.exec()).thenReturn(List.of());
        manager = new ElbClassicFrontendManager(mock(EmulatorConfig.class), ec2,
                mock(SecurityGroupFirewallManager.class), mock(VpcNetworkManager.class),
                mock(ContainerBuilder.class), lifecycle, new ObjectMapper(), mock(IamService.class), mock(AcmService.class));
        Field field = ElbClassicFrontendManager.class.getDeclaredField("frontends");
        field.setAccessible(true);
        frontends = (Map<String, ElbClassicFrontendManager.Frontend>) field.get(manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "shutdown", "fallback"})
    void queuedHealthCannotTouchARetiringFrontend(String operation) throws Exception {
        ClassicLoadBalancer loadBalancer = loadBalancer("retiring");
        ElbClassicFrontendManager.Frontend frontend = install(loadBalancer);
        ElbClassicFrontendManager.Probe probe = new ElbClassicFrontendManager.Probe(frontend, "instance", "10.0.1.10");
        CountDownLatch stopping = new CountDownLatch(1);
        CountDownLatch finishStop = new CountDownLatch(1);
        AtomicBoolean controlAttempted = new AtomicBoolean();
        when(docker.execCreateCmd("helper")).thenAnswer(invocation -> {
            controlAttempted.set(true);
            throw new IllegalStateException("The frontend is already stopping");
        });
        doAnswer(invocation -> {
            stopping.countDown();
            assertTrue(finishStop.await(5, TimeUnit.SECONDS));
            return null;
        }).when(lifecycle).stopAndRemoveStrict("proxy", null, 1);

        Runnable retire = switch (operation) {
            case "delete" -> () -> manager.remove(loadBalancer);
            case "shutdown" -> manager::stopManagedContainers;
            default -> manager::close;
        };
        CompletableFuture<Void> deletion = CompletableFuture.runAsync(retire);
        CompletableFuture<Void> publication = new CompletableFuture<>();
        Thread health = Thread.ofPlatform().unstarted(() -> {
            try {
                manager.healthChanged(loadBalancer, probe, healthy());
                publication.complete(null);
            } catch (Throwable failure) {
                publication.completeExceptionally(failure);
            }
        });
        try {
            assertTrue(stopping.await(5, TimeUnit.SECONDS));
            health.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> controlAttempted.get()
                    || health.getState() == Thread.State.BLOCKED && Arrays.stream(health.getStackTrace())
                            .anyMatch(frame -> frame.getClassName().equals("java.util.concurrent.ConcurrentHashMap")));
            assertFalse(controlAttempted.get(), "Queued health must wait for frontend retirement");
        } finally {
            finishStop.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            if (health.getState() != Thread.State.NEW) {
                health.join(5000);
            }
        }
        publication.get(5, TimeUnit.SECONDS);
        assertFalse(manager.current(loadBalancer, probe));
        verify(lifecycle).stopAndRemoveStrict("proxy", null, 1);
        verify(docker, never()).execCreateCmd(anyString());
    }

    @Test
    void failedRetirementKeepsTheInterfaceAndCanBeRetried() {
        ClassicLoadBalancer loadBalancer = loadBalancer("retry");
        ElbClassicFrontendManager.Frontend frontend = install(loadBalancer);
        ElbClassicFrontendManager.Probe probe = new ElbClassicFrontendManager.Probe(frontend, "instance", "10.0.1.10");
        doThrow(new IllegalStateException("Docker unavailable")).doNothing()
                .when(lifecycle).stopAndRemoveStrict("proxy", null, 1);

        assertThrows(IllegalStateException.class, () -> manager.remove(loadBalancer));
        assertTrue(manager.current(loadBalancer, probe));
        verifyNoInteractions(ec2);
        manager.remove(loadBalancer);
        assertFalse(manager.current(loadBalancer, probe));
        verify(ec2).releaseNetworkInterfaceFromService(REGION, "eni");
        verify(ec2).deleteNetworkInterface(REGION, "eni");
    }

    @Test
    void staleProbeCannotUpdateAReplacementFrontend() {
        ClassicLoadBalancer loadBalancer = loadBalancer("replaced");
        ElbClassicFrontendManager.Frontend previous = install(loadBalancer);
        ElbClassicFrontendManager.Probe probe = new ElbClassicFrontendManager.Probe(previous, "instance", "10.0.1.10");
        install(loadBalancer);
        manager.healthChanged(loadBalancer, probe, healthy());
        verifyNoInteractions(docker);
        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), any(), anyInt());
    }

    private ElbClassicFrontendManager.Frontend install(ClassicLoadBalancer loadBalancer) {
        ElbClassicFrontendManager.Frontend frontend = new ElbClassicFrontendManager.Frontend(
                ACCOUNT, REGION, "", "eni", "10.0.1.11",
                new ElbClassicFrontendManager.NetworkNamespace("helper", "helper-name"), "proxy",
                Map.of("instance", List.of("backend/server")), Map.of("instance", "10.0.1.10"));
        frontends.put(ACCOUNT + "/" + REGION + "/" + loadBalancer.getLoadBalancerName(), frontend);
        return frontend;
    }

    private static ClassicLoadBalancer loadBalancer(String name) {
        ClassicLoadBalancer loadBalancer = new ClassicLoadBalancer();
        loadBalancer.setAccountId(ACCOUNT);
        loadBalancer.setRegion(REGION);
        loadBalancer.setLoadBalancerName(name);
        return loadBalancer;
    }

    private static ElbClassicHealthChecker.InstanceHealth healthy() {
        return new ElbClassicHealthChecker.InstanceHealth("InService", "", "");
    }
}

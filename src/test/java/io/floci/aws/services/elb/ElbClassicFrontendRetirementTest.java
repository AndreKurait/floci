package io.floci.aws.services.elb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.NetworkNamespaceLease;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.floci.aws.services.elb.model.ClassicLoadBalancerAttributes;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
    private final VpcRoutingManager routing = mock(VpcRoutingManager.class);
    private final ElbClassicFrontendManager manager;
    private final Map<String, ElbClassicFrontendManager.Frontends> frontends;

    @SuppressWarnings("unchecked")
    ElbClassicFrontendRetirementTest() throws Exception {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        ListContainersCmd listContainers = mock(ListContainersCmd.class);
        when(docker.listContainersCmd()).thenReturn(listContainers);
        when(listContainers.withShowAll(true)).thenReturn(listContainers);
        when(listContainers.exec()).thenReturn(List.of());
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(AccountAwareStorageBackend.inMemory(ACCOUNT)).when(factory)
                .create(eq("elb"), eq("elb-classic-frontends.json"), any());
        manager = new ElbClassicFrontendManager(mock(EmulatorConfig.class), ec2,
                firewall, mock(VpcNetworkManager.class),
                mock(ContainerBuilder.class), lifecycle, new ObjectMapper(), mock(IamService.class),
                mock(AcmService.class), routing, factory);
        Field field = ElbClassicFrontendManager.class.getDeclaredField("frontends");
        field.setAccessible(true);
        frontends = (Map<String, ElbClassicFrontendManager.Frontends>) field.get(manager);
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
            await().atMost(Duration.ofSeconds(5))
                    .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                    .until(() -> controlAttempted.get()
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

    @ParameterizedTest
    @ValueSource(strings = {"helper", "firewall", "routing", "interface"})
    void partialRetirementRetainsOwnershipUntilTheRetryCompletes(String phase) {
        ClassicLoadBalancer loadBalancer = loadBalancer("partial-" + phase);
        ElbClassicFrontendManager.Frontend frontend = install(loadBalancer);
        ElbClassicFrontendManager.Probe probe = new ElbClassicFrontendManager.Probe(frontend, "instance", "10.0.1.10");
        IllegalStateException failure = new IllegalStateException("Removal failed at " + phase);
        switch (phase) {
            case "helper" -> doThrow(failure).doNothing().when(lifecycle).removeIfExistsStrict("helper");
            case "firewall" -> doThrow(failure).doNothing().when(firewall).unregister("eni");
            case "routing" -> doThrow(failure).doNothing().when(routing).unregister(ACCOUNT, REGION, "eni");
            case "interface" -> doThrow(failure).doNothing().when(ec2).deleteNetworkInterface(REGION, "eni");
            default -> throw new IllegalArgumentException(phase);
        }

        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.remove(loadBalancer)));
        assertTrue(manager.current(loadBalancer, probe), "Failed cleanup must retain the owned frontend for retry");
        assertEquals("10.0.1.11", manager.address(loadBalancer).orElseThrow());
        verify(lifecycle).stopAndRemoveStrict("proxy", null, 1);
        if (!"interface".equals(phase)) {
            verify(ec2, never()).releaseNetworkInterfaceFromService(anyString(), anyString());
            verify(ec2, never()).deleteNetworkInterface(anyString(), anyString());
        }

        clearInvocations(lifecycle, firewall, routing, ec2);
        manager.remove(loadBalancer);
        assertFalse(manager.current(loadBalancer, probe));
        assertTrue(manager.address(loadBalancer).isEmpty());
        InOrder order = inOrder(lifecycle, firewall, routing, ec2);
        order.verify(lifecycle).stopAndRemoveStrict("proxy", null, 1);
        order.verify(firewall).unregister("eni");
        order.verify(routing).unregister(ACCOUNT, REGION, "eni");
        order.verify(lifecycle).removeIfExistsStrict("helper");
        order.verify(ec2).releaseNetworkInterfaceFromService(REGION, "eni");
        order.verify(ec2).deleteNetworkInterface(REGION, "eni");
        clearInvocations(lifecycle, firewall, routing, ec2);
        manager.remove(loadBalancer);
        verifyNoInteractions(lifecycle, firewall, routing, ec2);
    }

    @Test
    void policyRetirementCanStillWriteToTheHelperNamespace() {
        ClassicLoadBalancer loadBalancer = loadBalancer("policy-writer");
        install(loadBalancer);
        AtomicBoolean helperExists = new AtomicBoolean(true);
        doAnswer(invocation -> {
            helperExists.set(false);
            return null;
        }).when(lifecycle).removeIfExistsStrict("helper");
        doAnswer(invocation -> {
            assertTrue(helperExists.get(), "Policy retirement needs the helper until its writers finish");
            return null;
        }).when(firewall).unregister("eni");

        manager.remove(loadBalancer);

        assertFalse(helperExists.get());
        assertTrue(frontends.isEmpty());
        verify(ec2).releaseNetworkInterfaceFromService(REGION, "eni");
        verify(ec2).deleteNetworkInterface(REGION, "eni");
    }

    @Test
    void failedPolicyRetirementKeepsTheNamespaceAndInterfaceForRetry() {
        ClassicLoadBalancer loadBalancer = loadBalancer("policy-retry");
        install(loadBalancer);
        doThrow(new IllegalStateException("Policy writer not drained")).doNothing()
                .when(firewall).unregister("eni");

        assertThrows(IllegalStateException.class, () -> manager.remove(loadBalancer));
        assertFalse(frontends.isEmpty());
        verify(lifecycle, never()).removeIfExistsStrict("helper");
        verifyNoInteractions(ec2);

        manager.remove(loadBalancer);

        assertTrue(frontends.isEmpty());
        verify(lifecycle).removeIfExistsStrict("helper");
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

    @Test
    void anOldRegistrationCannotPublishHealthAfterReregistrationOnTheSameFrontend() {
        ClassicLoadBalancer lb = loadBalancer("registration");
        ElbClassicFrontendManager.Frontend installed = install(lb);
        AtomicInteger commands = new AtomicInteger();
        ElbClassicBackendRuntime runtime = new ElbClassicBackendRuntime(
                List.of(), installed.servers(), installed.backends(), command -> {
                    commands.incrementAndGet();
                    return command.startsWith("show stat")
                            ? "# pxname,svname,qcur,scur\nbackend,server,0,1\n" : "";
                }, () -> 0);
        ElbClassicFrontendManager.Frontend frontend = installed.withRuntime(runtime);
        frontends.put(ACCOUNT + "/" + REGION + "/" + lb.getLoadBalancerName(),
                new ElbClassicFrontendManager.Frontends("", List.of(frontend)));
        ElbClassicFrontendManager.Probe old = manager.probesFor(lb, "instance").getFirst();
        assertTrue(manager.current(lb, old));
        ClassicLoadBalancerAttributes attributes = new ClassicLoadBalancerAttributes();
        attributes.setConnectionDrainingEnabled(true);
        attributes.setConnectionDrainingTimeout(30);
        runtime.reconcile(Map.of(), attributes);
        runtime.reconcile(installed.backends(), attributes);
        assertFalse(manager.current(lb, old));
        assertTrue(manager.current(lb, manager.probesFor(lb, "instance").getFirst()));
        int before = commands.get();
        manager.healthChanged(lb, old, healthy());
        assertEquals(before, commands.get());
        verifyNoInteractions(docker);
    }

    @Test
    void aFailedNodeDoesNotPreventOtherNodesFromRetiringAndCleanupCanBeRetried() {
        ClassicLoadBalancer lb = loadBalancer("multi-node");
        ElbClassicFrontendManager.Frontend first = new ElbClassicFrontendManager.Frontend(
                ACCOUNT, REGION, "", "eni-a", "10.0.1.11",
                new NetworkNamespaceLease("helper-a", "helper-a"), "proxy-a", Map.of(), Map.of());
        ElbClassicFrontendManager.Frontend second = new ElbClassicFrontendManager.Frontend(
                ACCOUNT, REGION, "", "eni-b", "10.0.2.11",
                new NetworkNamespaceLease("helper-b", "helper-b"), "proxy-b", Map.of(), Map.of());
        String key = ACCOUNT + "/" + REGION + "/" + lb.getLoadBalancerName();
        frontends.put(key, new ElbClassicFrontendManager.Frontends("", List.of(first, second)));
        doThrow(new IllegalStateException("first node still owned")).doNothing()
                .when(lifecycle).stopAndRemoveStrict("proxy-a", null, 1);
        assertThrows(IllegalStateException.class, () -> manager.remove(lb));
        verify(ec2, never()).deleteNetworkInterface(REGION, "eni-a");
        verify(ec2).deleteNetworkInterface(REGION, "eni-b");
        assertTrue(frontends.containsKey(key));
        manager.remove(lb);
        assertFalse(frontends.containsKey(key));
        verify(ec2).deleteNetworkInterface(REGION, "eni-a");
    }

    private ElbClassicFrontendManager.Frontend install(ClassicLoadBalancer loadBalancer) {
        ElbClassicFrontendManager.Frontend frontend = new ElbClassicFrontendManager.Frontend(
                ACCOUNT, REGION, "", "eni", "10.0.1.11",
                new NetworkNamespaceLease("helper", "helper-name"), "proxy",
                Map.of("instance", List.of("backend/server")), Map.of("instance", "10.0.1.10"));
        frontends.put(ACCOUNT + "/" + REGION + "/" + loadBalancer.getLoadBalancerName(),
                new ElbClassicFrontendManager.Frontends(frontend.state(), List.of(frontend)));
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

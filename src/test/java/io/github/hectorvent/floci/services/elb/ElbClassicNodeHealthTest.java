package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.NetworkNamespaceLease;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(15)
class ElbClassicNodeHealthTest {
    private static final String INSTANCE = "i-target";
    private final ElbClassicFrontendManager frontends = mock(ElbClassicFrontendManager.class);
    private final ClassicLoadBalancer lb = new ClassicLoadBalancer();
    private final ElbClassicFrontendManager.Probe good = node("eni-a", "first");
    private final ElbClassicFrontendManager.Probe bad = node("eni-b", "second");
    private final AtomicReference<List<ElbClassicFrontendManager.Probe>> nodes =
            new AtomicReference<>(List.of(good, bad));
    private final AtomicInteger goodChecks = new AtomicInteger();
    private final AtomicInteger badChecks = new AtomicInteger();
    private final AtomicBoolean goodReachable = new AtomicBoolean(true);
    private Vertx vertx;
    private ElbClassicHealthChecker health;

    @BeforeEach
    void setup() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(services);
        when(services.elb()).thenReturn(mock(EmulatorConfig.ElbServiceConfig.class));
        when(frontends.enabled()).thenReturn(true);
        when(frontends.probesFor(lb, INSTANCE)).thenAnswer(invocation -> nodes.get());
        when(frontends.current(eq(lb), any())).thenAnswer(invocation -> {
            ElbClassicFrontendManager.Probe probe = invocation.getArgument(1);
            return nodes.get().stream().anyMatch(current -> current.frontend() == probe.frontend());
        });
        when(frontends.probe(eq(good), any(), anyInt())).thenAnswer(invocation -> {
            goodChecks.incrementAndGet();
            return goodReachable.get();
        });
        when(frontends.probe(eq(bad), any(), anyInt())).thenAnswer(invocation -> {
            badChecks.incrementAndGet();
            return false;
        });
        lb.setAccountId("111111111111");
        lb.setRegion("us-east-1");
        lb.setLoadBalancerName("node-health");
        ClassicHealthCheck check = ClassicHealthCheck.defaults();
        check.setHealthyThreshold(2);
        check.setUnhealthyThreshold(2);
        check.setInterval(300);
        check.setTimeout(2);
        lb.setHealthCheck(check);
        vertx = Vertx.vertx();
        health = new ElbClassicHealthChecker(vertx, config, mock(Ec2Service.class), frontends);
    }

    @AfterEach
    void close() throws Exception {
        health.clear();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void aHealthyNodeCannotAdmitTheInstanceThroughAnUnhealthyNode() {
        startAndAwaitFirstChecks();
        assertEquals("OutOfService", health.getHealth(lb, INSTANCE).state());
        health.runArmedChecks();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertEquals("InService", health.getHealth(lb, INSTANCE).state());
            verify(frontends).healthChanged(lb, bad, ElbClassicHealthChecker.InstanceHealth.failedChecks());
        });
        verify(frontends, never()).healthChanged(lb, bad, ElbClassicHealthChecker.InstanceHealth.inService());
        verify(frontends).healthChanged(lb, good, ElbClassicHealthChecker.InstanceHealth.inService());
    }

    @Test
    void theApiWaitsUntilAnEligibleNodeActuallyAppliesItsHealthyState() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(frontends).healthChanged(lb, good, ElbClassicHealthChecker.InstanceHealth.inService());
        try {
            startAndAwaitFirstChecks();
            health.runArmedChecks();
            assertTrue(publishing.await(3, TimeUnit.SECONDS));
            assertEquals("OutOfService", health.getHealth(lb, INSTANCE).state());
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(3)).until(() -> "InService".equals(health.getHealth(lb, INSTANCE).state()));
    }

    @Test
    void replacingANodeDoesNotReuseItsPreviousSuccessfulChecks() {
        startAndAwaitFirstChecks();
        health.runArmedChecks();
        await().atMost(Duration.ofSeconds(3)).until(() -> "InService".equals(health.getHealth(lb, INSTANCE).state()));
        ElbClassicFrontendManager.Probe replacement = node("eni-a", "replacement");
        AtomicInteger checks = new AtomicInteger();
        when(frontends.probe(eq(replacement), any(), anyInt())).thenAnswer(invocation -> {
            checks.incrementAndGet();
            return true;
        });
        nodes.set(List.of(replacement, bad));
        assertEquals("OutOfService", health.getHealth(lb, INSTANCE).state());
        health.runArmedChecks();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(frontends).healthChanged(lb, replacement, ElbClassicHealthChecker.InstanceHealth.registering()));
        assertEquals(1, checks.get());
        assertEquals("OutOfService", health.getHealth(lb, INSTANCE).state());
        health.runArmedChecks();
        await().atMost(Duration.ofSeconds(3)).until(() -> "InService".equals(health.getHealth(lb, INSTANCE).state()));
    }

    @Test
    void aDelayedHealthPublicationConvergesOnTheNewestProbeResult() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(frontends).healthChanged(lb, good, ElbClassicHealthChecker.InstanceHealth.inService());
        try {
            startAndAwaitFirstChecks();
            health.runArmedChecks();
            assertTrue(publishing.await(3, TimeUnit.SECONDS));
            goodReachable.set(false);
            health.runArmedChecks();
            health.runArmedChecks();
            await().atMost(Duration.ofSeconds(3)).until(() -> goodChecks.get() == 4 && badChecks.get() == 4);
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertEquals(ElbClassicHealthChecker.InstanceHealth.failedChecks(), health.getHealth(lb, INSTANCE)));
        InOrder changes = inOrder(frontends);
        changes.verify(frontends).healthChanged(lb, good, ElbClassicHealthChecker.InstanceHealth.registering());
        changes.verify(frontends).healthChanged(lb, good, ElbClassicHealthChecker.InstanceHealth.inService());
        changes.verify(frontends).healthChanged(lb, good, ElbClassicHealthChecker.InstanceHealth.failedChecks());
    }

    private void startAndAwaitFirstChecks() {
        health.startMonitoring(lb);
        health.registerInstances(lb, List.of(INSTANCE));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertEquals(1, goodChecks.get());
            assertEquals(1, badChecks.get());
            verify(frontends).healthChanged(lb, good, ElbClassicHealthChecker.InstanceHealth.registering());
            verify(frontends).healthChanged(lb, bad, ElbClassicHealthChecker.InstanceHealth.registering());
        });
    }

    private static ElbClassicFrontendManager.Probe node(String eni, String generation) {
        ElbClassicFrontendManager.Frontend node = new ElbClassicFrontendManager.Frontend(
                "111111111111", "us-east-1", generation, eni, "10.0.1.20",
                new NetworkNamespaceLease("helper-" + generation, "helper-" + generation),
                "proxy-" + generation, Map.of(INSTANCE, List.of("listener/target")), Map.of(INSTANCE, "10.0.1.10"));
        return new ElbClassicFrontendManager.Probe(node, INSTANCE, "10.0.1.10");
    }
}

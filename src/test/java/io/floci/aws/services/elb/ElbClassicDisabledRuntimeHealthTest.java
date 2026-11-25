package io.floci.aws.services.elb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.elb.model.ClassicHealthCheck;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElbClassicDisabledRuntimeHealthTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private final Vertx vertx = Vertx.vertx();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger changedRequests = new AtomicInteger();
    private int backendPort;
    private ElbClassicService service;
    private ElbClassicHealthChecker health;
    private ClassicLoadBalancer original;
    private ElbClassicFrontendManager frontends;

    @BeforeEach
    void setUp() throws Exception {
        HttpServer backend = vertx.createHttpServer()
                .requestHandler(request -> {
                    if ("/changed".equals(request.path())) {
                        changedRequests.incrementAndGet();
                        request.response().setStatusCode(503).end();
                    } else {
                        request.response().setStatusCode(status.get()).end();
                    }
                })
                .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        backendPort = backend.actualPort();
        Ec2Service ec2 = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setContainerBridgeIp("127.0.0.1");
        when(ec2.findInstanceById(eq(ACCOUNT), anyString())).thenReturn(instance);
        frontends = mock(ElbClassicFrontendManager.class);
        ObjectMapper mapper = new ObjectMapper();
        when(frontends.copy(any())).thenAnswer(call -> mapper.convertValue(call.getArgument(0), ClassicLoadBalancer.class));
        health = new ElbClassicHealthChecker(vertx, null, ec2, frontends);
        original = new ClassicLoadBalancer();
        original.setAccountId(ACCOUNT);
        original.setRegion(REGION);
        original.setLoadBalancerName("web");
        ClassicHealthCheck check = ClassicHealthCheck.defaults();
        check.setTarget("HTTP:" + backend.actualPort() + "/");
        check.setInterval(300);
        check.setTimeout(1);
        check.setHealthyThreshold(1);
        check.setUnhealthyThreshold(1);
        original.setHealthCheck(check);
        original.getInstanceIds().add("i-first");
        AccountAwareStorageBackend<Map<String, ClassicLoadBalancer>> storage =
                AccountAwareStorageBackend.inMemory(ACCOUNT);
        storage.put(REGION, new ConcurrentHashMap<>(Map.of("web", original)));
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(storage).when(factory).create(eq("elb"), eq("elb-load-balancers.json"), any());
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn(ACCOUNT);
        service = new ElbClassicService(ec2, health, factory, null, resolver, null, null, frontends);
        health.startMonitoring(original);
        health.registerInstances(original, original.getInstanceIds());
        awaitHealth("i-first", "InService");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (health != null) {
            health.clear();
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void copiedRegistrationKeepsExistingHealthAndArmsChecksForTheNewInstance() {
        service.registerInstances(REGION, "web", List.of("i-second"));
        ClassicLoadBalancer current = service.requireLoadBalancer(REGION, "web");
        assertNotSame(original, current);
        assertEquals(List.of("i-first"), original.getInstanceIds());
        assertEquals("InService", health.getHealth(current, "i-first").state());
        awaitHealth("i-second", "InService");

        status.set(503);
        health.runArmedChecks();
        awaitHealth("i-second", "OutOfService");
        assertEquals("Instance", health.getHealth(current, "i-second").reasonCode());
        awaitHealth("i-first", "OutOfService");
        verify(frontends, never()).probeFor(any(), anyString());
    }

    @Test
    void copiedDeregistrationDoesNotLetTheOldTimerRestoreRemovedHealth() {
        service.registerInstances(REGION, "web", List.of("i-second"));
        awaitHealth("i-second", "InService");
        service.deregisterInstances(REGION, "web", List.of("i-first"));
        ClassicLoadBalancer current = service.requireLoadBalancer(REGION, "web");
        assertEquals(List.of("i-second"), current.getInstanceIds());
        assertEquals("ELB", health.getHealth(current, "i-first").reasonCode());

        status.set(503);
        health.runArmedChecks();
        awaitHealth("i-second", "OutOfService");
        assertEquals("ELB", health.getHealth(current, "i-first").reasonCode());
        status.set(200);
        health.runArmedChecks();
        awaitHealth("i-second", "InService");
        assertEquals("ELB", health.getHealth(current, "i-first").reasonCode());
    }

    @Test
    void copiedConfigurationRearmsTheTimerAgainstTheNewHttpEndpoint() {
        ClassicHealthCheck requested = ClassicHealthCheck.defaults();
        requested.setTarget("HTTP:" + backendPort + "/changed");
        requested.setInterval(300);
        requested.setTimeout(2);
        requested.setHealthyThreshold(2);
        requested.setUnhealthyThreshold(2);
        service.configureHealthCheck(REGION, "web", requested);
        ClassicLoadBalancer current = service.requireLoadBalancer(REGION, "web");
        assertNotSame(original, current);
        assertEquals("HTTP:" + backendPort + "/", original.getHealthCheck().getTarget());
        assertEquals("InService", health.getHealth(current, "i-first").state());

        health.runArmedChecks();
        await().atMost(Duration.ofSeconds(5)).until(() -> changedRequests.get() == 1);
        health.runArmedChecks();
        awaitHealth("i-first", "OutOfService");
        assertEquals(2, changedRequests.get());
        assertEquals("Instance", health.getHealth(current, "i-first").reasonCode());
    }

    private void awaitHealth(String instance, String expected) {
        await().pollInSameThread().atMost(Duration.ofSeconds(5)).until(() -> expected.equals(
                health.getHealth(service.requireLoadBalancer(REGION, "web"), instance).state()));
    }
}

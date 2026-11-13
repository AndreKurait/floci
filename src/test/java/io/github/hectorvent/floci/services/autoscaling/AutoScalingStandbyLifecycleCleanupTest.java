package io.github.hectorvent.floci.services.autoscaling;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingStandbyLifecycleCleanupTest {
    @Test
    void actualCleanupContinuesAfterGroupDeletionFails() throws Exception {
        assertActualCleanup(true);
    }

    @Test
    void actualCleanupDeletesKnownNamesAfterCreateResponseIsLost() throws Exception {
        assertActualCleanup(false);
    }

    private static void assertActualCleanup(boolean failGroupDelete) throws Exception {
        List<String> actions = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("Action=DeleteLoadBalancer")) {
                actions.add("loadBalancer");
            } else if (body.contains("Action=DeleteTargetGroup")) {
                actions.add("targetGroup");
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        AssertionError failure = new AssertionError("Injected group deletion failure");
        AutoScalingClient client = mock(AutoScalingClient.class, invocation -> {
            String method = invocation.getMethod().getName();
            if ("deleteAutoScalingGroup".equals(method)) {
                actions.add("group");
                if (failGroupDelete) {
                    throw failure;
                }
            } else if ("deleteLaunchConfiguration".equals(method)) {
                actions.add("configuration");
            } else if ("close".equals(method)) {
                actions.add("client");
            }
            return null;
        });
        try {
            AutoScalingStandbyLifecycleIntegrationTest fixture = new AutoScalingStandbyLifecycleIntegrationTest();
            fixture.endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            setField(fixture, "client", client);
            setField(fixture, "name", "owned-cleanup");
            setField(fixture, "targetGroup", "owned-target");
            if (failGroupDelete) {
                assertSame(failure, assertThrows(AssertionError.class, fixture::cleanUp));
            } else {
                fixture.cleanUp();
            }
            assertEquals(List.of("group", "configuration", "loadBalancer", "targetGroup", "client"), actions);
        } finally {
            server.stop(0);
        }
    }

    private static void setField(Object fixture, String name, Object value) throws Exception {
        Field field = fixture.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(fixture, value);
    }

    @Test
    void failedGroupDeletionStillAttemptsEveryRemainingOwnedResource() {
        Runnable configuration = mock(Runnable.class);
        Runnable loadBalancer = mock(Runnable.class);
        Runnable targetGroup = mock(Runnable.class);
        Runnable client = mock(Runnable.class);
        AssertionError primary = new AssertionError("group deletion");
        IllegalStateException secondary = new IllegalStateException("configuration deletion");
        doThrow(secondary).when(configuration).run();

        AssertionError actual = assertThrows(AssertionError.class,
                () -> AutoScalingStandbyLifecycleIntegrationTest.cleanResources(() -> {
                    throw primary;
                }, configuration, loadBalancer, targetGroup, client));

        assertSame(primary, actual);
        assertArrayEquals(new Throwable[]{secondary}, actual.getSuppressed());
        verify(configuration).run();
        verify(loadBalancer).run();
        verify(targetGroup).run();
        verify(client).run();
    }

    @Test
    void failedLoadBalancerDeletionStillDeletesTargetGroupAndClosesClient() {
        Runnable targetGroup = mock(Runnable.class);
        Runnable client = mock(Runnable.class);
        RuntimeException primary = new IllegalStateException("load balancer");
        assertSame(primary, assertThrows(RuntimeException.class,
                () -> AutoScalingStandbyLifecycleIntegrationTest.cleanResources(() -> { }, () -> { }, () -> {
                    throw primary;
                }, targetGroup, client)));
        verify(targetGroup).run();
        verify(client).run();
    }

    @Test
    void successfulCleanupUsesReverseDependencyOrder() {
        List<String> order = new ArrayList<>();
        AutoScalingStandbyLifecycleIntegrationTest.cleanResources(() -> order.add("group"),
                () -> order.add("configuration"), () -> order.add("loadBalancer"),
                () -> order.add("targetGroup"), () -> order.add("client"));
        assertEquals(List.of("group", "configuration", "loadBalancer", "targetGroup", "client"), order);
    }
}

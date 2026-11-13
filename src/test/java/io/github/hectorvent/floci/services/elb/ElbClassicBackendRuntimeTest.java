package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancerAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElbClassicBackendRuntimeTest {
    private static final String INSTANCE = "i-backend";
    private static final Map<String, String> REGISTERED = Map.of(INSTANCE, "10.0.1.5");

    @Test
    void deregistrationStopsNewWorkAndCompletesAsSoonAsExistingWorkFinishes() {
        Fixture fixture = new Fixture(443);
        fixture.control.active.put("listener_443/backend", 1L);
        fixture.runtime.reconcile(Map.of(), attributes(true, 30));

        assertTrue(fixture.runtime.addresses().isEmpty());
        assertEquals(Set.of(INSTANCE), fixture.runtime.draining());
        assertEquals("maint", fixture.control.states.get("listener_443/backend"));
        assertEquals(0, fixture.control.shutdowns);
        fixture.clock.set(Duration.ofSeconds(2).toNanos());
        fixture.runtime.refresh();
        assertEquals(Set.of(INSTANCE), fixture.runtime.draining());

        fixture.control.active.replaceAll((server, count) -> 0L);
        fixture.runtime.refresh();
        assertFalse(fixture.runtime.hasPendingWork());
        assertTrue(fixture.runtime.draining().isEmpty());
        assertTrue(fixture.control.active.isEmpty());
        assertEquals(0, fixture.control.shutdowns);
    }

    @Test
    void repeatedDeregistrationDoesNotExtendTheDeadlineAndQueuedWorkCounts() {
        Fixture fixture = new Fixture(443);
        fixture.control.queued.put("listener_443/backend", 1L);
        fixture.runtime.reconcile(Map.of(), attributes(true, 10));
        fixture.clock.set(Duration.ofSeconds(9).toNanos());
        fixture.runtime.reconcile(Map.of(), attributes(true, 300));
        assertEquals(Set.of(INSTANCE), fixture.runtime.draining());
        assertEquals(0, fixture.control.shutdowns);

        fixture.clock.set(Duration.ofSeconds(10).toNanos());
        fixture.runtime.refresh();
        assertFalse(fixture.runtime.hasPendingWork());
        assertEquals(1, fixture.control.shutdowns);
    }

    @Test
    void disabledDrainingClosesExistingConnectionsImmediately() {
        Fixture fixture = new Fixture(443);
        fixture.control.active.put("listener_443/backend", 1L);
        fixture.runtime.reconcile(Map.of(), attributes(false, 300));
        assertFalse(fixture.runtime.hasPendingWork());
        assertEquals(1, fixture.control.shutdowns);
    }

    @Test
    void disablingDrainingFinishesAnAlreadyPendingDeregistration() {
        Fixture fixture = new Fixture(443);
        fixture.control.active.put("listener_443/backend", 1L);
        fixture.runtime.reconcile(Map.of(), attributes(true, 300));
        fixture.runtime.reconcile(Map.of(), attributes(false, 300));
        assertFalse(fixture.runtime.hasPendingWork());
        assertEquals(1, fixture.control.shutdowns);
    }

    @Test
    void reregisteringCancelsTheOldDeadlineAndInvalidatesOldHealthResults() {
        Fixture fixture = new Fixture(443);
        long original = fixture.runtime.generation(INSTANCE);
        fixture.control.active.put("listener_443/backend", 1L);
        fixture.runtime.reconcile(Map.of(), attributes(true, 10));
        assertEquals(0, fixture.runtime.generation(INSTANCE));
        fixture.runtime.reconcile(REGISTERED, attributes(true, 10));
        assertNotEquals(original, fixture.runtime.generation(INSTANCE));
        assertEquals(REGISTERED, fixture.runtime.addresses());

        fixture.clock.set(Duration.ofSeconds(20).toNanos());
        fixture.runtime.refresh();
        assertEquals(0, fixture.control.shutdowns);
        assertEquals(1L, fixture.control.active.get("listener_443/backend"));
        assertFalse(fixture.runtime.hasPendingWork());
        assertEquals("maint", fixture.control.states.get("listener_443/backend"),
                "Registration must wait for fresh health admission");
        fixture.runtime.healthChanged(INSTANCE, true);
        assertEquals("ready", fixture.control.states.get("listener_443/backend"));
    }

    @Test
    void unhealthyConnectionsHaveNoDeregistrationDeadlineButDisablingDrainingClosesThem() {
        Fixture fixture = new Fixture(443);
        fixture.control.active.put("listener_443/backend", 1L);
        fixture.runtime.healthChanged(INSTANCE, false);
        fixture.clock.set(Duration.ofHours(2).toNanos());
        fixture.runtime.refresh();
        assertEquals(0, fixture.control.shutdowns);
        assertEquals(REGISTERED, fixture.runtime.addresses());
        assertTrue(fixture.runtime.draining().isEmpty());

        fixture.runtime.reconcile(REGISTERED, attributes(false, 30));
        assertEquals(1, fixture.control.shutdowns);
        assertEquals("maint", fixture.control.states.get("listener_443/backend"));
    }

    @Test
    void transientDeletionRetriesDoNotReportAnActiveConnectionAfterCompletion() {
        Fixture fixture = new Fixture(443);
        fixture.control.busyDelete.add("listener_443/backend");
        fixture.runtime.reconcile(Map.of(), attributes(true, 30));
        assertTrue(fixture.runtime.draining().isEmpty());
        assertTrue(fixture.runtime.hasPendingWork());
        fixture.control.busyDelete.clear();
        fixture.runtime.refresh();
        assertFalse(fixture.runtime.hasPendingWork());
        assertEquals(0, fixture.control.shutdowns);
    }

    @Test
    void reregistrationRestoresOnlyListenersAlreadyDeletedDuringRetirement() {
        Fixture fixture = new Fixture(80, 443);
        long original = fixture.runtime.generation(INSTANCE);
        fixture.control.busyDelete.add("listener_443/backend");
        fixture.runtime.reconcile(Map.of(), attributes(true, 30));
        assertEquals(Set.of("listener_443/backend"), fixture.control.active.keySet());
        fixture.runtime.reconcile(REGISTERED, attributes(true, 30));
        List<String> servers = fixture.runtime.servers().get(INSTANCE);
        assertEquals(2, servers.size());
        assertTrue(servers.contains("listener_443/backend"));
        assertTrue(servers.stream().anyMatch(server -> server.startsWith("listener_80/target_")));
        assertNotEquals(original, fixture.runtime.generation(INSTANCE));
        assertFalse(fixture.runtime.hasPendingWork());
    }

    @Test
    void aNewRegistrationAfterCompletedDeletionCannotReuseAnOldHealthGeneration() {
        Fixture fixture = new Fixture(443);
        long original = fixture.runtime.generation(INSTANCE);
        fixture.runtime.reconcile(Map.of(), attributes(true, 30));
        fixture.runtime.reconcile(REGISTERED, attributes(true, 30));
        assertNotEquals(original, fixture.runtime.generation(INSTANCE));
        assertEquals(1, fixture.runtime.servers().get(INSTANCE).size());
        assertTrue(fixture.runtime.servers().get(INSTANCE).getFirst().startsWith("listener_443/target_"));
    }

    @Test
    void failedStatisticsOrControlCommandsNeverCompleteDraining() {
        Fixture fixture = new Fixture(443);
        fixture.control.active.put("listener_443/backend", 1L);
        fixture.runtime.reconcile(Map.of(), attributes(true, 30));
        fixture.control.statisticsOverride = "# pxname,svname,qcur,scur\n";
        assertThrows(IllegalStateException.class, fixture.runtime::refresh);
        assertEquals(Set.of(INSTANCE), fixture.runtime.draining());
        fixture.control.statisticsOverride = null;
        fixture.clock.set(Duration.ofSeconds(30).toNanos());
        fixture.control.refuseShutdown = true;
        assertThrows(IllegalStateException.class, fixture.runtime::refresh);
        assertEquals(Set.of(INSTANCE), fixture.runtime.draining());
        assertTrue(fixture.runtime.hasPendingWork());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "unavailable", "# pxname,svname,scur\nlistener_443,backend,1",
        "# pxname,svname,qcur,scur\nlistener_443,backend,-1,0",
        "# pxname,svname,qcur,scur\nlistener_443,backend,0",
        "# pxname,svname,qcur,scur\nlistener_443,backend,0,1\nlistener_443,backend,0,0"
    })
    void malformedStatisticsAreRejected(String response) {
        assertThrows(RuntimeException.class, () -> ElbClassicBackendRuntime.sessions(response));
    }

    private static ClassicLoadBalancerAttributes attributes(boolean draining, int seconds) {
        ClassicLoadBalancerAttributes attributes = new ClassicLoadBalancerAttributes();
        attributes.setConnectionDrainingEnabled(draining);
        attributes.setConnectionDrainingTimeout(seconds);
        return attributes;
    }

    private static final class Fixture {
        private final AtomicLong clock = new AtomicLong();
        private final Control control = new Control();
        private final ElbClassicBackendRuntime runtime;

        private Fixture(int... ports) {
            List<ClassicListener> listeners = new ArrayList<>();
            List<String> servers = new ArrayList<>();
            for (int port : ports) {
                ClassicListener listener = new ClassicListener();
                listener.setLoadBalancerPort(port);
                listener.setInstancePort(8080);
                listener.setProtocol("TCP");
                listener.setInstanceProtocol("TCP");
                listeners.add(listener);
                String server = "listener_" + port + "/backend";
                servers.add(server);
                control.active.put(server, 0L);
                control.queued.put(server, 0L);
            }
            runtime = new ElbClassicBackendRuntime(listeners, Map.of(INSTANCE, servers),
                    REGISTERED, control::apply, clock::get);
            runtime.reconcile(REGISTERED, attributes(true, 30));
            runtime.healthChanged(INSTANCE, true);
        }
    }

    /** Stateful HAProxy command responses; packet tests exercise the actual pinned proxy. */
    private static final class Control {
        private final Map<String, Long> active = new LinkedHashMap<>();
        private final Map<String, Long> queued = new LinkedHashMap<>();
        private final Map<String, String> states = new LinkedHashMap<>();
        private final List<String> busyDelete = new ArrayList<>();
        private String statisticsOverride;
        private boolean refuseShutdown;
        private int shutdowns;

        private String apply(String commands) {
            List<String> replies = new ArrayList<>();
            for (String command : commands.strip().split("\\R")) {
                String[] parts = command.split(" ");
                if (command.equals("show stat -1 4 -1")) {
                    if (statisticsOverride != null) {
                        return statisticsOverride;
                    }
                    StringBuilder csv = new StringBuilder("# pxname,svname,qcur,scur\n");
                    active.forEach((server, count) -> csv.append(server.replace('/', ',')).append(',')
                            .append(queued.getOrDefault(server, 0L)).append(',').append(count).append('\n'));
                    return csv.toString();
                } else if (command.startsWith("set server ")) {
                    states.put(parts[2], parts[4]);
                } else if (command.startsWith("shutdown sessions server ")) {
                    if (refuseShutdown) {
                        return "Permission denied";
                    }
                    active.put(parts[3], 0L);
                    queued.put(parts[3], 0L);
                    shutdowns++;
                } else if (command.startsWith("del server ")) {
                    if (busyDelete.contains(parts[2])) {
                        replies.add("Server still has connections attached to it, cannot remove it.");
                    } else {
                        active.remove(parts[2]);
                        queued.remove(parts[2]);
                        replies.add("Server deleted.");
                    }
                } else if (command.startsWith("add server ")) {
                    active.put(parts[2], 0L);
                    queued.put(parts[2], 0L);
                    states.put(parts[2], "maint");
                    replies.add("New server registered.");
                } else {
                    throw new AssertionError("Unexpected proxy command: " + command);
                }
            }
            return String.join("\n", replies);
        }
    }
}

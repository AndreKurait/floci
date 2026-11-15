package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.config.LoadBalancerProxySources.Backend;
import io.github.hectorvent.floci.config.LoadBalancerProxySources.Destination;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoadBalancerProxySourcesTest {
    private final LoadBalancerProxySources sources = new LoadBalancerProxySources();
    private final Object owner = new Object();
    private final Backend backend = new Backend(40001, true, () -> true);
    private final Destination destination = new Destination("172.16.0.10", 443);
    private static final String PREFACE = "PROXY TCP4 172.16.0.14 172.16.0.10 45678 443\r\n";

    @Test
    void destinationAndListenerAreSelectedIndependentlyOfClientNames() {
        sources.register(owner, "172.16.0.10", Map.of(destination, backend));
        LoadBalancerProxySources.Route route = sources.decode(sources.ticket("172.16.0.10"), PREFACE);
        assertEquals(backend, route.backend());
        assertEquals("172.16.0.14", route.addresses().source().hostAddress());
        assertEquals(45678, route.addresses().source().port());
        assertEquals("172.16.0.10", route.addresses().destination().hostAddress());
        assertEquals(443, route.addresses().destination().port());
        assertNull(sources.ticket("172.16.0.11"));
    }

    @Test
    void wrongOwnersAndForgedDestinationsCannotSelectAnotherListener() {
        sources.register(owner, "172.16.0.10", Map.of(destination, backend));
        LoadBalancerProxySources.Ticket ticket = sources.ticket("172.16.0.10");
        assertThrows(IllegalStateException.class,
                () -> sources.register(new Object(), "172.16.0.10", Map.of(destination, backend)));
        for (String preface : List.of(
                PREFACE.replace("172.16.0.10", "172.16.0.11"),
                PREFACE.replace("443\r", "444\r"),
                PREFACE.replace("TCP4", "TCP6"),
                PREFACE.replace("172.16.0.14", "caller.example.test"),
                PREFACE.replace("45678", "00080"),
                PREFACE.replace("45678", "65536"),
                PREFACE.replace("\r\n", "\n"),
                "PROXY UNKNOWN\r\n")) {
            assertThrows(IllegalArgumentException.class, () -> sources.decode(ticket, preface), preface);
        }
    }

    @Test
    void revocationAndReplacementInvalidatePendingProxyTickets() {
        sources.register(owner, "172.16.0.10", Map.of(destination, backend));
        LoadBalancerProxySources.Ticket original = sources.ticket("172.16.0.10");
        sources.register(owner, "172.16.0.10", Map.of(destination, backend));
        assertFalse(sources.current(original));
        assertThrows(IllegalArgumentException.class, () -> sources.decode(original, PREFACE));
        LoadBalancerProxySources.Ticket replaced = sources.ticket("172.16.0.10");
        sources.revoke(owner);
        assertFalse(sources.current(replaced));
        assertThrows(IllegalArgumentException.class, () -> sources.decode(replaced, PREFACE));
    }

    @Test
    void retiredNativeListenerCannotBeUsedThroughAnOtherwiseLiveHelper() {
        AtomicBoolean live = new AtomicBoolean(true);
        sources.register(owner, "172.16.0.10", Map.of(destination, new Backend(40001, true, live::get)));
        LoadBalancerProxySources.Ticket ticket = sources.ticket("172.16.0.10");
        live.set(false);
        assertThrows(IllegalArgumentException.class, () -> sources.decode(ticket, PREFACE));
    }
}

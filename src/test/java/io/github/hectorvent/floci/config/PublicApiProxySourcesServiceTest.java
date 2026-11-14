package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PublicApiProxySourcesServiceTest {
    private final PublicApiProxySources sources = new PublicApiProxySources();
    private final Object owner = new Object();
    private static final String PEER = "172.18.0.2";
    private static final String CALLER = "172.18.1.10";
    private static final String PREFACE = "PROXY TCP4 " + CALLER + " 192.0.2.1 49152 443\r\n";

    @Test
    void onlyCurrentOwnerAndObservedCallerAreAdmitted() {
        assertNull(sources.ticket(PEER));
        sources.register(owner, PEER, Set.of(CALLER));
        PublicApiProxySources.Ticket ticket = sources.ticket(PEER);
        PublicApiProxySources.Addresses addresses = sources.decode(ticket, PREFACE);
        assertEquals(CALLER, addresses.source().hostAddress());
        assertEquals(49152, addresses.source().port());
        assertEquals("192.0.2.1", addresses.destination().hostAddress());
        assertThrows(IllegalArgumentException.class, () -> sources.decode(null, PREFACE));
        assertThrows(IllegalArgumentException.class,
                () -> sources.decode(ticket, PREFACE.replace(CALLER, "172.18.1.11")));
        assertThrows(IllegalStateException.class, () -> sources.register(new Object(), PEER, Set.of(CALLER)));
    }

    @Test
    void registrationCopiesCallersAndForeignRetirementCannotRevokeIt() {
        Set<String> callers = new HashSet<>(Set.of(CALLER));
        sources.register(owner, PEER, callers);
        callers.clear();
        sources.revoke(new Object());
        assertEquals(CALLER, sources.decode(sources.ticket(PEER), PREFACE).source().hostAddress());
    }

    @Test
    void aFragmentedConnectionCannotInheritAReplacementBridgeOrRoute() {
        sources.register(owner, PEER, Set.of(CALLER));
        PublicApiProxySources.Ticket old = sources.ticket(PEER);
        sources.register(owner, PEER, Set.of(CALLER));
        assertThrows(IllegalArgumentException.class, () -> sources.decode(old, PREFACE));
        PublicApiProxySources.Ticket current = sources.ticket(PEER);
        sources.revoke(owner);
        assertNull(sources.ticket(PEER));
        sources.register(new Object(), PEER, Set.of(CALLER));
        assertThrows(IllegalArgumentException.class, () -> sources.decode(current, PREFACE));
        assertDoesNotThrow(() -> sources.decode(sources.ticket(PEER), PREFACE));
    }

    @Test
    void nativeAlbAndApiKeepIndependentCallerAdmissionAndWithdrawal() {
        String albPreface = PREFACE.replace("192.0.2.1", "192.0.2.2");
        sources.register(owner, PEER, Map.of("192.0.2.1", Set.of("172.18.1.11"), "192.0.2.2", Set.of(CALLER)));
        PublicApiProxySources.Ticket ticket = sources.ticket(PEER);
        assertEquals("192.0.2.2", sources.decode(ticket, albPreface).destination().hostAddress());
        assertThrows(IllegalArgumentException.class, () -> sources.decode(ticket, PREFACE));
        assertThrows(IllegalArgumentException.class,
                () -> sources.decode(ticket, albPreface.replace(CALLER, "172.18.1.11")));

        sources.register(owner, PEER, Map.of("192.0.2.1", Set.of(CALLER), "192.0.2.2", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> sources.decode(ticket, albPreface));
        assertThrows(IllegalArgumentException.class, () -> sources.decode(sources.ticket(PEER), albPreface));
        assertEquals(CALLER, sources.decode(sources.ticket(PEER), PREFACE).source().hostAddress());
        assertThrows(IllegalArgumentException.class,
                () -> sources.register(owner, PEER, Map.of("192.0.2.3", Set.of(CALLER))));
    }

    @Test
    void nativeAlbHttpRetainsItsDestinationPortWithoutAdmittingApiHttpOrCustomPorts() {
        sources.register(owner, PEER, Map.of("192.0.2.1", Set.of(CALLER), "192.0.2.2", Set.of(CALLER)));
        PublicApiProxySources.Ticket ticket = sources.ticket(PEER);
        String alb = PREFACE.replace("192.0.2.1", "192.0.2.2");
        PublicApiProxySources.Addresses http = sources.decode(ticket, alb.replace("443\r\n", "80\r\n"));
        assertEquals("192.0.2.2", http.destination().hostAddress());
        assertEquals(80, http.destination().port());
        assertEquals(443, sources.decode(ticket, alb).destination().port());
        for (String port : Set.of("0", "080", "81", "8080", "8443", "65536")) {
            assertThrows(IllegalArgumentException.class,
                    () -> sources.decode(ticket, alb.replace("443\r\n", port + "\r\n")));
        }
        assertThrows(IllegalArgumentException.class,
                () -> sources.decode(ticket, PREFACE.replace("443\r\n", "80\r\n")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "PROXY UNKNOWN\r\n", "PROXY TCP6 ::1 ::1 49152 443\r\n",
        "PROXY TCP4 172.18.1.10 192.0.2.1 0 443\r\n",
        "PROXY TCP4 172.18.1.10 192.0.2.1 65536 443\r\n",
        "PROXY TCP4 172.18.1.10 192.0.2.1 049152 443\r\n",
        "PROXY TCP4 172.18.1.10 192.0.2.2 49152 443\r\n",
        "PROXY TCP4 172.18.1.10 192.0.2.1 49152 80\r\n",
        "PROXY TCP4 172.18.1.10 192.0.2.1 49152 443\n",
        "PROXY  TCP4 172.18.1.10 192.0.2.1 49152 443\r\n"
    })
    void rejectsMalformedAndWrongDestinationClaims(String preface) {
        sources.register(owner, PEER, Set.of(CALLER));
        assertThrows(IllegalArgumentException.class, () -> sources.decode(sources.ticket(PEER), preface));
    }
}

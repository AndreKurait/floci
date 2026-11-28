package io.floci.aws.core.common.dns;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsAnswerTest {

    @Test
    void negativeAnswersCarryNoAddresses() {
        assertTrue(DnsAnswer.nxDomain().isEmpty());
        assertFalse(DnsAnswer.nxDomain().nameExists());
        assertTrue(DnsAnswer.noData().isEmpty());
        assertTrue(DnsAnswer.noData().nameExists());
    }

    @Test
    void recordsMarkTheNameAsExisting() {
        DnsAnswer answer = DnsAnswer.records(List.of("10.0.0.1"), 15);

        assertFalse(answer.isEmpty());
        assertTrue(answer.nameExists());
        assertEquals(15, answer.ttlSeconds());
    }

    @Test
    void keepsATtlInsideTheRangeAnARecordCanCarry() {
        assertEquals(0, DnsAnswer.records(List.of("10.0.0.1"), 0).ttlSeconds());
        assertEquals(Integer.MAX_VALUE,
                DnsAnswer.records(List.of("10.0.0.1"), Integer.MAX_VALUE).ttlSeconds());
    }

    @Test
    void rejectsATtlNoResolverCouldActOn() {
        assertThrows(IllegalArgumentException.class, () -> DnsAnswer.records(List.of("10.0.0.1"), -1));
    }

    @Test
    void doesNotShareTheCallersList() {
        List<String> addresses = new ArrayList<>(List.of("10.0.0.1"));
        DnsAnswer answer = DnsAnswer.records(addresses, 15);
        addresses.add("10.0.0.2");

        assertEquals(List.of("10.0.0.1"), answer.addresses());
        assertThrows(UnsupportedOperationException.class, () -> answer.addresses().add("10.0.0.3"));
    }

    @Test
    void upstreamTransportRequiresResolvedAddressesAndSurvivesAuthority() {
        DnsAnswer answer = DnsAnswer.records(List.of("192.0.2.7"), 30).viaUpstream();
        assertTrue(answer.upstream());
        assertTrue(answer.withAuthority("example",
                new DnsRecord.Soa("ns.example hostmaster.example 1 2 3 4 5"), 30).upstream());
        assertThrows(IllegalArgumentException.class,
                () -> DnsAnswer.noData().viaUpstream());
        assertThrows(IllegalArgumentException.class,
                () -> DnsAnswer.nxDomain().viaUpstream());
    }
}

package io.github.hectorvent.floci.core.common.dns;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DnsAliasTargetTest {
    @Test
    void unownedAliasesCannotUseSuffixSpoofingOrOrdinaryDnsResolution() {
        DnsRecordSource source = mock(DnsRecordSource.class);
        when(source.resolveAliasTarget("api.example", "ZTARGET", 1, null)).thenReturn(Optional.empty());
        EmbeddedDnsServer server = new EmbeddedDnsServer(List.of("example"), List.of(source));

        assertTrue(server.resolveAliasTarget("api.example", "ZTARGET", 1).isEmpty());
        verify(source).resolveAliasTarget("api.example", "ZTARGET", 1, null);
        verifyNoMoreInteractions(source);
    }

    @Test
    void competingAliasOwnersAreRefused() {
        DnsRecordSource first = mock(DnsRecordSource.class);
        DnsRecordSource second = mock(DnsRecordSource.class);
        when(first.resolveAliasTarget("lb.example", "ZTARGET", 1, null))
                .thenReturn(Optional.of(DnsAnswer.records(List.of("198.51.100.4"), 60)));
        when(second.resolveAliasTarget("lb.example", "ZTARGET", 1, null))
                .thenReturn(Optional.of(DnsAnswer.records(List.of("198.51.100.5"), 60)));
        EmbeddedDnsServer server = new EmbeddedDnsServer(List.of(), List.of(first, second));

        assertEquals(5, server.resolveAliasTarget("lb.example", "ZTARGET", 1).orElseThrow().responseCode());
    }

    @Test
    void failedResourceLookupCannotBecomeAnUpstreamQuery() {
        DnsRecordSource source = mock(DnsRecordSource.class);
        when(source.resolveAliasTarget("lb.example", "ZTARGET", 1, null)).thenThrow(new IllegalStateException("storage"));
        EmbeddedDnsServer server = new EmbeddedDnsServer(List.of("example"), List.of(source));

        assertEquals(5, server.resolveAliasTarget("lb.example", "ZTARGET", 1).orElseThrow().responseCode());
    }

    @Test
    void resourceLookupReceivesTheQueryingVpc() {
        DnsRecordSource source = mock(DnsRecordSource.class);
        DnsClientVpcSource.ClientVpc origin =
                new DnsClientVpcSource.ClientVpc("730000000001", "eu-west-1", "vpc-origin");
        DnsAnswer answer = DnsAnswer.records(List.of("198.51.100.4"), 60);
        when(source.resolveAliasTarget("lb.example", "ZTARGET", 1, origin))
                .thenReturn(Optional.of(answer));
        EmbeddedDnsServer server = new EmbeddedDnsServer(List.of(), List.of(source));

        assertEquals(answer, server.resolveAliasTarget("lb.example", "ZTARGET", 1, origin).orElseThrow());
        verify(source).resolveAliasTarget("lb.example", "ZTARGET", 1, origin);
        verifyNoMoreInteractions(source);
    }
}

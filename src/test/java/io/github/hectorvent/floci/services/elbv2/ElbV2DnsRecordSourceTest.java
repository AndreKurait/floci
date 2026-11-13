package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ElbV2DnsRecordSourceTest {
    private final ElbV2Service service = mock(ElbV2Service.class);
    private final EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
    private final ElbV2DnsRecordSource source = new ElbV2DnsRecordSource(service, dns);
    private final LoadBalancer target = new LoadBalancer();

    @BeforeEach
    void setUp() {
        target.setType("application");
        target.setCanonicalHostedZoneId("OWNED");
        when(service.findByDnsName("owned.elb.example")).thenReturn(List.of(target));
    }

    @Test
    void unknownAndDeletedTargetsNeverUseTheEmulatorAddress() {
        when(service.findByDnsName("owned.elb.example")).thenReturn(List.of());
        assertTrue(source.resolveAliasTarget("owned.elb.example", "OWNED", 1).isEmpty());
        verifyNoInteractions(dns);
    }

    @Test
    void duplicateDnsOwnersAreRefused() {
        when(service.findByDnsName("owned.elb.example")).thenReturn(List.of(target, target));
        assertEquals(5, source.resolveAliasTarget("owned.elb.example", "OWNED", 1).orElseThrow().responseCode());
        verifyNoInteractions(dns);
    }

    @Test
    void wrongHostedZoneAndUnsupportedTargetsAreRefused() {
        assertEquals(5, source.resolveAliasTarget("owned.elb.example", "OTHER", 1).orElseThrow().responseCode());
        assertEquals(5, source.resolveAliasTarget("owned.elb.example", null, 1).orElseThrow().responseCode());
        target.setType("network");
        assertEquals(5, source.resolveAliasTarget("owned.elb.example", "OWNED", 1).orElseThrow().responseCode());
        target.setType("gateway");
        assertEquals(5, source.resolveAliasTarget("owned.elb.example", "OWNED", 1).orElseThrow().responseCode());
        verifyNoInteractions(dns);
    }

    @Test
    void validAliasUsesTheBoundDnsAddressAndTargetTtl() {
        when(dns.getServerIp()).thenReturn(Optional.of("192.0.2.8"));
        DnsAnswer answer = source.resolveAliasTarget("owned.elb.example", "OWNED", 1).orElseThrow();
        assertEquals(List.of("192.0.2.8"), answer.addresses());
        assertEquals(60, answer.ttlSeconds());
    }

    @Test
    void absentRuntimeAddressAndIpv6ProduceNoInventedAddress() {
        when(dns.getServerIp()).thenReturn(Optional.empty());
        assertTrue(source.resolveAliasTarget("owned.elb.example", "OWNED", 1).orElseThrow().isEmpty());
        DnsAnswer ipv6 = source.resolveAliasTarget("owned.elb.example", "OWNED", 28).orElseThrow();
        assertEquals(0, ipv6.responseCode());
        assertTrue(ipv6.isEmpty());
    }

    @Test
    void nonAddressAliasQueriesAreRefused() {
        assertEquals(5, source.resolveAliasTarget("owned.elb.example", "OWNED", 16).orElseThrow().responseCode());
        verifyNoInteractions(dns);
    }
}

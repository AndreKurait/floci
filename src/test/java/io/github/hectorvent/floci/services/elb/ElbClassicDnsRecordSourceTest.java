package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElbClassicDnsRecordSourceTest {
    private final ElbClassicService service = mock(ElbClassicService.class);
    private final ElbClassicFrontendManager frontends = mock(ElbClassicFrontendManager.class);
    private final ElbClassicDnsRecordSource source = new ElbClassicDnsRecordSource(service, frontends);

    @Test
    void dualstackAliasUsesTheManagedTargetsCanonicalZoneAndAddresses() {
        ClassicLoadBalancer target = target();
        when(service.findByDnsName("frontend.elb.example.")).thenReturn(List.of(target));
        when(frontends.addresses(target)).thenReturn(List.of("198.51.100.4", "198.51.100.5"));

        DnsAnswer answer = source.resolveAliasTarget("DUALSTACK.FRONTEND.ELB.EXAMPLE.", "ZTARGET", 1).orElseThrow();
        assertEquals(List.of("198.51.100.4", "198.51.100.5"), answer.addresses());
        assertEquals(60, answer.ttlSeconds());
        assertEquals(5, source.resolveAliasTarget("frontend.elb.example.", "ZOTHER", 1).orElseThrow().responseCode());
        assertEquals(5, source.resolveAliasTarget("frontend.elb.example.", "ZTARGET", 28).orElseThrow().responseCode());
    }

    @Test
    void aliasesDoNotInventAddressesForMissingOrDisabledFrontends() {
        ClassicLoadBalancer target = target();
        when(service.findByDnsName("frontend.elb.example")).thenReturn(List.of(target));
        when(frontends.addresses(target)).thenReturn(List.of());
        assertTrue(source.resolveAliasTarget("frontend.elb.example", "ZTARGET", 1).orElseThrow().isEmpty());
        assertTrue(source.resolveAliasTarget("missing.elb.example", "ZTARGET", 1).isEmpty());

        when(frontends.enabled()).thenReturn(false);
        assertTrue(source.resolveAliasTarget("frontend.elb.example", "ZTARGET", 1).isEmpty());
    }

    private ClassicLoadBalancer target() {
        when(frontends.enabled()).thenReturn(true);
        ClassicLoadBalancer target = new ClassicLoadBalancer();
        target.setCanonicalHostedZoneNameId("ZTARGET");
        return target;
    }
}

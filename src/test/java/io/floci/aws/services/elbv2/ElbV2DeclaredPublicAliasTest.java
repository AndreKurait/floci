package io.floci.aws.services.elbv2;

import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElbV2DeclaredPublicAliasTest {
    private static final String NAME = "internal-owned.elb.example.test";
    private static final String CONTROL = "172.18.0.2";
    private static final ClientVpc OWNER = new ClientVpc("111111111111", "us-east-1", "vpc-owner");
    private static final ClientVpc CALLER = new ClientVpc("111111111111", "us-east-1", "vpc-caller");
    private static final List<String> FRONTENDS = List.of("10.174.1.8", "10.174.2.8");

    @Test
    void declaredPeerValidatesTheInternalFrontendWithoutChangingOrdinaryVpcDns() {
        Fixture fixture = new Fixture();
        assertEquals(DnsAnswer.noData(), fixture.source.resolveAliasTarget(NAME, "ZOWNED", 1, CALLER)
                .orElseThrow());
        assertEquals(FRONTENDS, fixture.source.resolveAliasTarget(NAME, "ZOWNED", 1, OWNER)
                .orElseThrow().addresses());
        for (ClientVpc origin : new ClientVpc[]{CALLER, OWNER, null}) {
            DnsAnswer answer = fixture.source.resolveDeclaredPublicAliasTarget(NAME, "ZOWNED", 1, origin)
                    .orElseThrow();
            assertEquals(List.of(CONTROL), answer.addresses());
            assertEquals(60, answer.ttlSeconds());
        }
    }

    @Test
    void missingFrontendOrSharedListenerCannotInventAnAddress() {
        Fixture fixture = new Fixture();
        when(fixture.networks.frontendAddresses(fixture.balancer, OWNER)).thenReturn(List.of());
        assertEquals(DnsAnswer.noData(), fixture.source.resolveDeclaredPublicAliasTarget(NAME, "ZOWNED", 1, CALLER)
                .orElseThrow());
        when(fixture.networks.frontendAddresses(fixture.balancer, OWNER)).thenReturn(FRONTENDS);
        when(fixture.dns.getServerIp()).thenReturn(Optional.empty());
        assertEquals(DnsAnswer.noData(), fixture.source.resolveDeclaredPublicAliasTarget(NAME, "ZOWNED", 1, CALLER)
                .orElseThrow());
    }

    @Test
    void declarationStillRequiresOneExistingApplicationBalancerAndItsCanonicalZone() {
        Fixture fixture = new Fixture();
        assertEquals(DnsAnswer.refused(), fixture.source.resolveDeclaredPublicAliasTarget(NAME, "OTHER", 1, CALLER)
                .orElseThrow());
        assertEquals(DnsAnswer.noData(), fixture.source.resolveDeclaredPublicAliasTarget(NAME, "ZOWNED", 28, CALLER)
                .orElseThrow());
        assertEquals(DnsAnswer.refused(), fixture.source.resolveDeclaredPublicAliasTarget(NAME, "ZOWNED", 16, CALLER)
                .orElseThrow());
        fixture.balancer.setType("network");
        assertEquals(DnsAnswer.refused(), fixture.source.resolveDeclaredPublicAliasTarget(NAME, "ZOWNED", 1, CALLER)
                .orElseThrow());
        fixture.balancer.setType("application");
        when(fixture.service.findByDnsName(NAME)).thenReturn(List.of(fixture.balancer, fixture.balancer));
        assertEquals(DnsAnswer.refused(), fixture.source.resolveDeclaredPublicAliasTarget(NAME, "ZOWNED", 1, CALLER)
                .orElseThrow());
        when(fixture.service.findByDnsName(NAME)).thenReturn(List.of());
        assertTrue(fixture.source.resolveDeclaredPublicAliasTarget(NAME, "ZOWNED", 1, CALLER).isEmpty());
    }

    private static final class Fixture {
        private final ElbV2Service service = mock(ElbV2Service.class);
        private final EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        private final ElbV2VpcNetworkManager networks = mock(ElbV2VpcNetworkManager.class);
        private final LoadBalancer balancer = new LoadBalancer();
        private final ElbV2DnsRecordSource source = new ElbV2DnsRecordSource(service, dns, null, networks);

        private Fixture() {
            balancer.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-east-1:111111111111:loadbalancer/app/owned/id");
            balancer.setType("application");
            balancer.setScheme("internal");
            balancer.setCanonicalHostedZoneId("ZOWNED");
            balancer.setRegion(OWNER.region());
            balancer.setVpcId(OWNER.vpcId());
            when(service.findByDnsName(NAME)).thenReturn(List.of(balancer));
            when(dns.getServerIp()).thenReturn(Optional.of(CONTROL));
            when(networks.frontendsEnabled()).thenReturn(true);
            when(networks.frontendAddresses(balancer, OWNER)).thenReturn(FRONTENDS);
        }
    }
}

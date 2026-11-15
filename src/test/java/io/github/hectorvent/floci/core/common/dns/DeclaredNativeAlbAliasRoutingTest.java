package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.LoadBalancerProxySources;
import io.github.hectorvent.floci.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.net.VpcRoutingManager;
import io.github.hectorvent.floci.services.elbv2.ElbV2DnsOwnerFixture;
import io.github.hectorvent.floci.services.elbv2.ElbV2DnsRecordSource;
import io.github.hectorvent.floci.services.elbv2.ElbV2FrontendManager;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.ElbV2VpcNetworkManager;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeclaredNativeAlbAliasRoutingTest {
    private static final String HOST = "api.peer.example";
    private static final String TARGET = "owned.elb.example";
    private static final String ZONE = "ZOWNED";
    private static final String CONTROL = "172.18.0.2";
    private static final ClientVpc ORIGIN = new ClientVpc("111111111111", "us-east-1", "vpc-caller");
    private final List<DnsRecordSource> records = new ArrayList<>();
    private final ElbV2Service service = mock(ElbV2Service.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final LoadBalancer target = new LoadBalancer();
    private EmbeddedDnsServer dns;
    private ElbV2DnsRecordSource source;

    @BeforeEach
    void prepareNativeAlias() {
        dns = spy(new EmbeddedDnsServer(List.of(), records, List.of(), List.of(), true, List.of(HOST)));
        doReturn(Optional.of(CONTROL)).when(dns).getServerIp();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().awsHttpsPort()).thenReturn(443);
        when(config.port()).thenReturn(4566);
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        when(firewall.enabled()).thenReturn(true);
        VpcNetworkManager networks = mock(VpcNetworkManager.class);
        when(networks.enabled()).thenReturn(true);
        VpcRoutingManager routing = mock(VpcRoutingManager.class);
        when(routing.enabled()).thenReturn(true);
        ElbV2FrontendManager frontends = new ElbV2FrontendManager(
                config, lifecycle, routing, mock(LoadBalancerProxySources.class));
        ElbV2VpcNetworkManager nativeNetworks = new ElbV2VpcNetworkManager(
                config, mock(Ec2Service.class), firewall, lifecycle, networks, frontends);
        source = new ElbV2DnsRecordSource(service, dns, config, nativeNetworks);
        records.add(source);
        target.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-east-1:111111111111:loadbalancer/app/owned/123");
        target.setRegion("us-east-1");
        target.setVpcId("vpc-load-balancer");
        target.setType("application");
        target.setScheme("internal");
        target.setCanonicalHostedZoneId(ZONE);
        ElbV2DnsOwnerFixture.targets(service, TARGET, target);
    }

    @Test
    void declaredAliasRetainsItsPublicRouteWhenTheNativeFrontendIsOutsideTheCallerVpc() {
        assertEquals(DnsAnswer.noData(), source.resolveAliasTarget(TARGET, ZONE, 1, ORIGIN).orElseThrow());
        DnsAnswer answer = dns.resolvePublicAliasTarget("API.PEER.EXAMPLE.", TARGET, ZONE, 1, ORIGIN).orElseThrow();
        assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS), answer.addresses());
        assertEquals(60, answer.ttlSeconds());
        assertEquals(0, answer.responseCode());
        assertEquals(DnsAnswer.noData(), source.resolveAliasTarget(TARGET, ZONE, 1, ORIGIN).orElseThrow());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void declaredAliasOutsideAVpcKeepsItsExplicitUpstreamRoute() {
        assertEquals(DnsAnswer.noData(), source.resolveAliasTarget(TARGET, ZONE, 1, null).orElseThrow());
        DnsAnswer answer = dns.resolvePublicAliasTarget(HOST, TARGET, ZONE, 1, null).orElseThrow();
        assertTrue(answer.upstream());
        assertEquals(List.of(CONTROL), answer.addresses());
        assertEquals(60, answer.ttlSeconds());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void undeclaredNamesInvalidOriginsAndOtherQueryTypesKeepNativeScope() {
        for (String host : List.of("child." + HOST, "not" + HOST, "unconfigured.example")) {
            assertEquals(DnsAnswer.noData(),
                    dns.resolvePublicAliasTarget(host, TARGET, ZONE, 1, ORIGIN).orElseThrow());
        }
        for (ClientVpc origin : List.of(new ClientVpc("", "us-east-1", "vpc-caller"),
                new ClientVpc("111111111111", "", "vpc-caller"),
                new ClientVpc("111111111111", "us-east-1", ""))) {
            assertEquals(DnsAnswer.noData(),
                    dns.resolvePublicAliasTarget(HOST, TARGET, ZONE, 1, origin).orElseThrow());
        }
        assertEquals(DnsAnswer.noData(), dns.resolvePublicAliasTarget(HOST, TARGET, ZONE, 28, ORIGIN).orElseThrow());
        assertEquals(DnsAnswer.refused(), dns.resolvePublicAliasTarget(HOST, TARGET, ZONE, 16, ORIGIN).orElseThrow());
    }

    @Test
    void declarationStillRequiresTheCurrentUniqueAliasOwnerAndCanonicalZone() {
        assertEquals(DnsAnswer.refused(),
                dns.resolvePublicAliasTarget(HOST, TARGET, "OTHER", 1, ORIGIN).orElseThrow());
        for (String type : List.of("network", "gateway")) {
            target.setType(type);
            assertEquals(DnsAnswer.refused(),
                    dns.resolvePublicAliasTarget(HOST, TARGET, ZONE, 1, ORIGIN).orElseThrow());
        }
        target.setType("application");
        ElbV2DnsOwnerFixture.targets(service, TARGET, target, target);
        assertEquals(DnsAnswer.refused(), dns.resolvePublicAliasTarget(HOST, TARGET, ZONE, 1, ORIGIN).orElseThrow());
        ElbV2DnsOwnerFixture.targets(service, TARGET);
        assertTrue(dns.resolvePublicAliasTarget(HOST, TARGET, ZONE, 1, ORIGIN).isEmpty());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void unavailableRuntimeListenerDoesNotInventADeclaredRoute() {
        doReturn(Optional.empty()).when(dns).getServerIp();
        assertEquals(DnsAnswer.noData(), dns.resolvePublicAliasTarget(HOST, TARGET, ZONE, 1, ORIGIN).orElseThrow());
    }

    @Test
    void anotherSourceClaimingTheAliasRemainsAmbiguous() {
        records.add(new DnsRecordSource() {
            @Override
            public Optional<DnsAnswer> resolveIpv4(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type) {
                return Optional.of(DnsAnswer.records(List.of(CONTROL), 60));
            }
        });
        assertEquals(DnsAnswer.refused(), dns.resolvePublicAliasTarget(HOST, TARGET, ZONE, 1, ORIGIN).orElseThrow());
    }
}

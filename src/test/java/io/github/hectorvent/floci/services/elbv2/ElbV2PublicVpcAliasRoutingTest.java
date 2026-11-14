package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.DnsClientVpcSource;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElbV2PublicVpcAliasRoutingTest {
    private static final String CONTROL = "172.18.0.2";
    private static final String NAME = "owned.elb.example.test";
    private static final DnsClientVpcSource.ClientVpc VPC =
            new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-caller");

    @ParameterizedTest
    @CsvSource({"false,true,true,true,443", "true,false,true,true,443", "true,true,false,true,443",
            "true,true,true,false,443", "true,true,true,true,8443", "true,true,true,true,0"})
    void unavailableNativeHttpsTransportRetainsTheExistingAddress(boolean routing, boolean publicApi,
                                                                  boolean nativeHttps, boolean tls, int port) {
        assertEquals(List.of(CONTROL), source(routing, publicApi, nativeHttps, tls, port)
                .resolveAliasTarget(NAME, "ZOWNED", 1, VPC).orElseThrow().addresses());
    }

    @Test
    void validVpcCallerUsesThePublicDestinationWithTheOriginalTtl() {
        DnsAnswer answer = source(true, true, true, true, 443)
                .resolveAliasTarget(NAME, "ZOWNED", 1, VPC).orElseThrow();
        assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS), answer.addresses());
        assertEquals(60, answer.ttlSeconds());
    }

    @Test
    void missingListenerAddressRemainsNoData() {
        for (boolean nativeHttps : List.of(false, true)) {
            assertEquals(DnsAnswer.noData(), source(true, true, nativeHttps, true, 443, false)
                    .resolveAliasTarget(NAME, "ZOWNED", 1, VPC).orElseThrow());
        }
    }

    @Test
    void externalApiFrontDoorUsesTheDistinctNativeAlbDestination() {
        ElbV2DnsRecordSource source = source(true, true, false, true, 443, true, true);
        DnsAnswer answer = source.resolveAliasTarget(NAME, "ZOWNED", 1, VPC).orElseThrow();
        assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS), answer.addresses());
        assertEquals(60, answer.ttlSeconds());
        assertEquals(List.of(CONTROL), source.resolveAliasTarget(NAME, "ZOWNED", 1, null)
                .orElseThrow().addresses());
        assertEquals(DnsAnswer.refused(), source.resolveAliasTarget(NAME, "OTHER", 1, VPC).orElseThrow());
        assertEquals(DnsAnswer.noData(), source.resolveAliasTarget(NAME, "ZOWNED", 28, VPC).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"HTTP,80", "HTTP,443", "HTTPS,8443"})
    void otherListenerProtocolsAndPortsRetainTheExistingAddress(String protocol, int port) {
        ElbV2DnsRecordSource source = source(true, true, false, true, 443, true, true,
                List.of(listener(protocol, port)));
        assertEquals(List.of(CONTROL), source.resolveAliasTarget(NAME, "ZOWNED", 1, VPC).orElseThrow().addresses());
    }

    @Test
    void emptyAndMixedListenerSetsRetainTheExistingAddress() {
        for (List<Listener> listeners : List.of(List.<Listener>of(),
                List.of(listener("HTTP", 80), listener("HTTPS", 443)),
                List.of(listener("HTTPS", 443), listener("HTTPS", 8443)))) {
            ElbV2DnsRecordSource source = source(true, true, false, true, 443, true, true, listeners);
            assertEquals(List.of(CONTROL), source.resolveAliasTarget(NAME, "ZOWNED", 1, VPC).orElseThrow().addresses());
        }
    }

    private static ElbV2DnsRecordSource source(boolean routing, boolean publicApi, boolean nativeHttps,
                                               boolean tls, int port) {
        return source(routing, publicApi, nativeHttps, tls, port, true);
    }

    private static ElbV2DnsRecordSource source(boolean routing, boolean publicApi, boolean nativeHttps,
                                               boolean tls, int port, boolean addressAvailable) {
        return source(routing, publicApi, nativeHttps, tls, port, addressAvailable, false);
    }

    private static ElbV2DnsRecordSource source(boolean routing, boolean publicApi, boolean nativeHttps,
                                               boolean tls, int port, boolean addressAvailable, boolean externalApi) {
        return source(routing, publicApi, nativeHttps, tls, port, addressAvailable, externalApi,
                List.of(listener("HTTPS", 443)));
    }

    private static ElbV2DnsRecordSource source(boolean routing, boolean publicApi, boolean nativeHttps,
                                                boolean tls, int port, boolean addressAvailable, boolean externalApi,
                                                List<Listener> listeners) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().vpcRouting().enabled()).thenReturn(routing);
        when(config.network().vpcRouting().publicAwsApi()).thenReturn(publicApi);
        when(config.network().vpcRouting().publicAwsApiNativeHttps()).thenReturn(nativeHttps);
        when(config.network().vpcRouting().publicAwsApiUpstreamHost())
                .thenReturn(externalApi ? Optional.of("172.18.0.20") : Optional.empty());
        when(config.tls().enabled()).thenReturn(tls);
        when(config.tls().awsHttpsPort()).thenReturn(port);
        EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        when(dns.getServerIp()).thenReturn(addressAvailable ? Optional.of(CONTROL) : Optional.empty());
        when(dns.publicApiAddress(VPC)).thenReturn(addressAvailable
                ? Optional.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS) : Optional.empty());
        when(dns.publicAlbHttpsAddress(VPC)).thenReturn(addressAvailable
                ? Optional.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS) : Optional.empty());
        when(dns.publicAlbHttpsAddress(null)).thenReturn(addressAvailable ? Optional.of(CONTROL) : Optional.empty());
        LoadBalancer balancer = new LoadBalancer();
        balancer.setLoadBalancerArn("owned-balancer");
        balancer.setType("application");
        balancer.setScheme("internet-facing");
        balancer.setCanonicalHostedZoneId("ZOWNED");
        ElbV2Service service = mock(ElbV2Service.class);
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer));
        when(service.findListenersForLoadBalancer("owned-balancer")).thenReturn(listeners);
        return new ElbV2DnsRecordSource(service, dns, config);
    }

    private static Listener listener(String protocol, int port) {
        Listener listener = new Listener();
        listener.setProtocol(protocol);
        listener.setPort(port);
        return listener;
    }
}

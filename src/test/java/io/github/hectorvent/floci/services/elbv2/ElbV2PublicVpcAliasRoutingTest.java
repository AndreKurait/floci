package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.DnsClientVpcSource;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
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

    private static ElbV2DnsRecordSource source(boolean routing, boolean publicApi, boolean nativeHttps,
                                               boolean tls, int port) {
        return source(routing, publicApi, nativeHttps, tls, port, true);
    }

    private static ElbV2DnsRecordSource source(boolean routing, boolean publicApi, boolean nativeHttps,
                                               boolean tls, int port, boolean addressAvailable) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().vpcRouting().enabled()).thenReturn(routing);
        when(config.network().vpcRouting().publicAwsApi()).thenReturn(publicApi);
        when(config.network().vpcRouting().publicAwsApiNativeHttps()).thenReturn(nativeHttps);
        when(config.tls().enabled()).thenReturn(tls);
        when(config.tls().awsHttpsPort()).thenReturn(port);
        EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        when(dns.getServerIp()).thenReturn(addressAvailable ? Optional.of(CONTROL) : Optional.empty());
        when(dns.publicApiAddress(VPC)).thenReturn(addressAvailable
                ? Optional.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS) : Optional.empty());
        LoadBalancer balancer = new LoadBalancer();
        balancer.setType("application");
        balancer.setScheme("internet-facing");
        balancer.setCanonicalHostedZoneId("ZOWNED");
        ElbV2Service service = mock(ElbV2Service.class);
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer));
        return new ElbV2DnsRecordSource(service, dns, config);
    }
}

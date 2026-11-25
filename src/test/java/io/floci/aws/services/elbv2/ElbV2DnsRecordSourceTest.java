package io.floci.aws.services.elbv2;

import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ElbV2DnsRecordSourceTest {
    private static final String NAME = "owned.elb.example.test";
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
    void sharedFallbackAppliesToEveryListenerAndStillRequiresTheExactAliasOwner() {
        target.setLoadBalancerArn("owned-alb");
        ElbV2VpcNetworkManager networks = mock(ElbV2VpcNetworkManager.class);
        when(networks.frontendsEnabled()).thenReturn(true);
        when(networks.usesSharedFrontend("owned-alb")).thenReturn(true);
        when(networks.frontendAddresses(target, null)).thenReturn(List.of("192.0.2.99"));
        when(dns.getServerIp()).thenReturn(Optional.of("192.0.2.8"));
        ElbV2DnsRecordSource nativeSource = new ElbV2DnsRecordSource(service, dns, null, networks);
        assertEquals(List.of("192.0.2.8"), nativeSource.resolveAliasTarget(
                "owned.elb.example", "OWNED", 1).orElseThrow().addresses());
        assertEquals(List.of("192.0.2.8"), nativeSource.resolve(
                "owned.elb.example", 1, null).orElseThrow().addresses());
        assertEquals(5, nativeSource.resolveAliasTarget(
                "owned.elb.example", "OTHER", 1).orElseThrow().responseCode());
        when(networks.usesSharedFrontend("owned-alb")).thenReturn(false);
        when(networks.frontendAddresses(target, null)).thenReturn(List.of());
        assertTrue(nativeSource.resolveAliasTarget("owned.elb.example", "OWNED", 1)
                .orElseThrow().isEmpty(), "Restoring owned frontends must wait for complete readiness");
        when(networks.frontendAddresses(target, null)).thenReturn(List.of("192.0.2.99"));
        assertEquals(List.of("192.0.2.99"), nativeSource.resolveAliasTarget(
                "owned.elb.example", "OWNED", 1).orElseThrow().addresses());
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
    void anUnknownNameInTheLoadBalancerZonesIsNxdomainWithTheAwsForm() {
        when(service.awsDnsNames()).thenReturn(true);
        for (String name : List.of("nobody-1234567890.eu-west-1.elb.amazonaws.com",
                "internal-nobody-1234567890.eu-west-1.elb.amazonaws.com.",
                "dualstack.nobody-0123456789abcdef.elb.eu-west-1.amazonaws.com",
                "nobody-1234567890.cn-north-1.elb.amazonaws.com.cn")) {
            when(service.findByDnsName(name)).thenReturn(List.of());
            DnsAnswer answer = source.resolveAuthoritative(name, 1, (ClientVpc) null).orElseThrow();
            assertTrue(answer.isEmpty(), name);
            assertEquals(false, answer.nameExists(), name);
        }
        for (String name : List.of("elasticloadbalancing.eu-west-1.amazonaws.com", "elb.eu-west-1.amazonaws.com",
                "nobody.elb.nowhere-1.amazonaws.com", "nobody.elb.example")) {
            when(service.findByDnsName(name)).thenReturn(List.of());
            assertTrue(source.resolveAuthoritative(name, 1, (ClientVpc) null).isEmpty(), name);
        }
        verifyNoInteractions(dns);
    }

    @Test
    void theDefaultFormIsLeftToTheEmulatorSuffix() {
        assertTrue(source.resolveAuthoritative("owned.elb.example", 1, (ClientVpc) null).isEmpty());
        verifyNoInteractions(dns);
    }

    @Test
    void nonAddressAliasQueriesAreRefused() {
        assertEquals(5, source.resolveAliasTarget("owned.elb.example", "OWNED", 16).orElseThrow().responseCode());
        verifyNoInteractions(dns);
    }

    @Test
    void nativeFrontendAnswersRetainOwnershipAndTheOriginalCallerVpc() {
        ElbV2VpcNetworkManager networks = mock(ElbV2VpcNetworkManager.class);
        when(networks.frontendsEnabled()).thenReturn(true);
        ElbV2DnsRecordSource nativeSource = new ElbV2DnsRecordSource(service, dns, null, networks);
        ClientVpc origin = new ClientVpc("111111111111", "us-west-2", "vpc-caller");
        when(networks.frontendAddresses(target, null)).thenReturn(List.of("172.16.0.10"));
        when(networks.frontendAddresses(target, origin)).thenReturn(List.of("198.51.100.1"));

        assertEquals(List.of("172.16.0.10"), nativeSource.resolveAliasTarget(
                "owned.elb.example", "OWNED", 1).orElseThrow().addresses());
        assertEquals(List.of("198.51.100.1"), nativeSource.resolveAliasTarget(
                "owned.elb.example", "OWNED", 1, origin).orElseThrow().addresses());
        assertEquals(List.of("198.51.100.1"), nativeSource.resolve(
                "owned.elb.example", 1, origin).orElseThrow().addresses());
        assertEquals(5, nativeSource.resolveAliasTarget(
                "owned.elb.example", "FOREIGN", 1, origin).orElseThrow().responseCode());
        when(networks.frontendAddresses(target, origin)).thenReturn(List.of());
        assertTrue(nativeSource.resolveAliasTarget(
                "owned.elb.example", "OWNED", 1, origin).orElseThrow().isEmpty());
        verifyNoInteractions(dns);
    }
    @ParameterizedTest
    @ValueSource(strings = {"internal", "internet-facing"})
    void aValidatedApplicationBalancerUsesItsListenerAddress(String scheme) {
        LoadBalancer target = balancer("application");
        target.setScheme(scheme);
        when(service.findByDnsName(NAME)).thenReturn(List.of(target));
        assertEquals(Optional.of(DnsAnswer.records(List.of("192.0.2.10"), 60)),
                source.resolveAliasTarget(NAME, "ZOWNED", 1, "192.0.2.10"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"network", "gateway"})
    void unsupportedBalancerTypesRemainRefused(String type) {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer(type)));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "ZOWNED", 1, "192.0.2.10"));
    }

    @Test
    void mismatchedOwnershipAndUnsupportedQueryTypesCannotReachTheListener() {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer("application")));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "WRONG", 1, "192.0.2.10"));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, null, 1, "192.0.2.10"));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "ZOWNED", 16, "192.0.2.10"));
    }

    @Test
    void ambiguousResourcesAreRefused() {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer("application"), balancer("application")));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "ZOWNED", 1, "192.0.2.10"));
    }

    @Test
    void missingResourcesAreUnclaimedAndRawNamesAreNotClaimed() {
        when(service.findByDnsName(NAME)).thenReturn(List.of());
        assertEquals(Optional.empty(), source.resolveAliasTarget(NAME, "ZOWNED", 1, "192.0.2.10"));
        assertEquals(Optional.empty(), source.resolveIpv4(NAME));
    }

    @Test
    void validIpv6TargetsAndUnavailableListenerAddressesReturnNoData() {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer("application")));
        assertEquals(Optional.of(DnsAnswer.noData()), source.resolveAliasTarget(NAME, "ZOWNED", 28, "192.0.2.10"));
        assertEquals(Optional.of(DnsAnswer.noData()), source.resolveAliasTarget(NAME, "ZOWNED", 1, (String) null));
        assertEquals(Optional.of(DnsAnswer.noData()), source.resolveAliasTarget(NAME, "ZOWNED", 1, " "));
    }

    private static LoadBalancer balancer(String type) {
        LoadBalancer target = new LoadBalancer();
        target.setType(type);
        target.setCanonicalHostedZoneId("ZOWNED");
        return target;
    }
}

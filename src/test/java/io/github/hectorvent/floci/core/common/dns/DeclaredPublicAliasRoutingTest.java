package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class DeclaredPublicAliasRoutingTest {
    private static final String HOST = "api.peer.example";
    private static final String CONTROL = "172.18.0.2";
    private static final DnsClientVpcSource.ClientVpc VPC =
            new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-caller");

    @Test
    void ownedAliasRetainsItsTtlAndAuthorityOnTheDeclaredPublicRoute() {
        DnsAnswer original = DnsAnswer.records(List.of(CONTROL), 45).withAuthority(
                "peer.example", new DnsRecord.Soa("ns.peer.example hostmaster.peer.example 1 2 3 4 5"), 60);
        DnsAnswer routed = server(true, original)
                .resolvePublicAliasTarget("API.PEER.EXAMPLE.", "lb.example", "ZOWNED", 1, VPC).orElseThrow();
        assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS), routed.addresses());
        assertEquals(original.ttlSeconds(), routed.ttlSeconds());
        assertEquals(original.authority(), routed.authority());
        assertEquals(original.responseCode(), routed.responseCode());
    }

    @Test
    void declarationDoesNotInventOrRepairAnInvalidAliasTarget() {
        assertTrue(server(true, null)
                .resolvePublicAliasTarget(HOST, "missing.example", "ZOWNED", 1, VPC).isEmpty());
        for (DnsAnswer answer : List.of(DnsAnswer.refused(), DnsAnswer.noData(), DnsAnswer.nxDomain())) {
            assertEquals(answer, server(true, answer)
                    .resolvePublicAliasTarget(HOST, "lb.example", "ZOWNED", 1, VPC).orElseThrow());
        }
    }

    @Test
    void resourceAddressesThatAreNotTheOwnedListenerRetainTheirValues() {
        for (DnsAnswer answer : List.of(
                DnsAnswer.records(List.of("198.51.100.7"), 30),
                DnsAnswer.records(List.of(CONTROL, "198.51.100.7"), 30),
                DnsAnswer.typedRecords(List.of(new DnsRecord.Address(28, "::1")), 30))) {
            assertEquals(answer, server(true, answer)
                    .resolvePublicAliasTarget(HOST, "lb.example", "ZOWNED", 1, VPC).orElseThrow());
        }
    }

    @Test
    void disabledRoutingUndeclaredNamesAndNonIpv4QueriesDoNotSelectTheHandoff() {
        DnsAnswer answer = DnsAnswer.records(List.of(CONTROL), 60);
        assertEquals(answer, server(false, answer)
                .resolvePublicAliasTarget(HOST, "lb.example", "ZOWNED", 1, VPC).orElseThrow());
        EmbeddedDnsServer dns = server(true, answer);
        for (String name : List.of("child." + HOST, "not" + HOST, "other.example")) {
            assertEquals(answer, dns.resolvePublicAliasTarget(name, "lb.example", "ZOWNED", 1, VPC)
                    .orElseThrow());
        }
        assertEquals(answer, dns.resolvePublicAliasTarget(HOST, "lb.example", "ZOWNED", 28, VPC).orElseThrow());
    }

    @Test
    void declaredOwnedAliasOutsideVpcSelectsUpstreamAfterOwnershipValidation() {
        DnsAnswer answer = DnsAnswer.records(List.of(CONTROL), 45);
        EmbeddedDnsServer owner = server(true, answer);
        DnsAnswer validated = owner.resolvePublicAliasTarget(HOST, "lb.example", "ZOWNED", 1, null)
                .orElseThrow();
        assertTrue(validated.upstream());
        assertEquals(answer.records(), validated.records());
        assertEquals(45, validated.ttlSeconds());
        DnsRecordSource zone = new DnsRecordSource() {
            @Override
            public Optional<DnsAnswer> resolveIpv4(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolveAuthoritative(String name, int type) {
                return Optional.of(validated.withAuthority("peer.example",
                        new DnsRecord.Soa("ns.peer.example hostmaster.peer.example 1 2 3 4 5"), 60));
            }
        };
        EmbeddedDnsServer dns = new EmbeddedDnsServer(
                List.of(), List.of(zone), List.of(), List.of(), true, List.of(HOST));
        assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM, dns.planQuery(HOST, "172.18.0.3", CONTROL, 1));
        for (DnsAnswer invalid : List.of(DnsAnswer.refused(), DnsAnswer.noData(), DnsAnswer.nxDomain())) {
            assertEquals(invalid, server(true, invalid)
                    .resolvePublicAliasTarget(HOST, "lb.example", "ZOWNED", 1, null).orElseThrow());
            assertThrows(IllegalArgumentException.class, invalid::viaUpstream);
        }
    }

    @Test
    void nonVpcLiteralAndUnsupportedAnswersDoNotSelectUpstream() {
        for (DnsAnswer answer : List.of(DnsAnswer.records(List.of(CONTROL), 60),
                DnsAnswer.refused(), DnsAnswer.noData(), DnsAnswer.nxDomain())) {
            DnsRecordSource records = name -> Optional.of(answer);
            EmbeddedDnsServer dns = new EmbeddedDnsServer(
                    List.of(), List.of(records), List.of(), List.of(), true, List.of(HOST));
            assertEquals(answer, dns.planQuery(HOST, "172.18.0.3", CONTROL, 1).answer().orElseThrow());
            assertFalse(answer.upstream());
        }
    }

    @Test
    void incompleteVpcOriginRetainsTheExistingTransport() {
        DnsAnswer answer = DnsAnswer.records(List.of(CONTROL), 60);
        EmbeddedDnsServer dns = server(true, answer);
        for (DnsClientVpcSource.ClientVpc origin : List.of(
                new DnsClientVpcSource.ClientVpc("", "us-east-1", "vpc-caller"),
                new DnsClientVpcSource.ClientVpc("111111111111", "", "vpc-caller"),
                new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", ""))) {
            assertEquals(answer, dns.resolvePublicAliasTarget(HOST, "lb.example", "ZOWNED", 1, origin)
                    .orElseThrow());
        }
    }

    private static EmbeddedDnsServer server(boolean enabled, DnsAnswer answer) {
        DnsRecordSource owner = new DnsRecordSource() {
            @Override
            public Optional<DnsAnswer> resolveIpv4(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type) {
                return Optional.ofNullable(answer);
            }
        };
        EmbeddedDnsServer dns = spy(new EmbeddedDnsServer(
                List.of(), List.of(owner), List.of(), List.of(), enabled, List.of(HOST)));
        doReturn(Optional.of(CONTROL)).when(dns).getServerIp();
        return dns;
    }
}

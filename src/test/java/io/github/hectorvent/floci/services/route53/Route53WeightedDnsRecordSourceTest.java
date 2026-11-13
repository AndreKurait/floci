package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.DnsLookupHelper;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Route53WeightedDnsRecordSourceTest {
    private static final String NAME = "api.example.test.";

    @Test
    void weightsSelectOneCompleteRecordSetAndExcludeZeroWeight() {
        ResourceRecordSet first = weighted("first", 1, "192.0.2.1");
        first.setRecords(List.of(new ResourceRecord("192.0.2.1"), new ResourceRecord("192.0.2.2")));
        first.setTtl(31L);
        ResourceRecordSet second = weighted("second", 3, "192.0.2.3");
        second.setTtl(47L);
        List<ResourceRecordSet> records = List.of(first, weighted("disabled", 0, "192.0.2.4"), second);
        for (int ticket = 0; ticket < 4; ticket++) {
            DnsAnswer answer = source(records, draw(4, ticket), null).resolveAuthoritative(NAME, 1).orElseThrow();
            assertEquals(0, answer.responseCode());
            assertEquals(ticket == 0 ? List.of("192.0.2.1", "192.0.2.2") : List.of("192.0.2.3"),
                    answer.addresses());
            assertEquals(ticket == 0 ? 31 : 47, answer.ttlSeconds());
            assertNotNull(answer.authority());
        }
    }

    @Test
    void allZeroWeightsGiveEachRecordOneEqualSlot() {
        List<ResourceRecordSet> records = List.of(weighted("first", 0, "192.0.2.1"),
                weighted("second", 0, "192.0.2.2"), weighted("third", 0, "192.0.2.3"));
        for (int ticket = 0; ticket < records.size(); ticket++) {
            DnsAnswer answer = source(records, draw(3, ticket), null).resolveAuthoritative(NAME, 1).orElseThrow();
            assertEquals(0, answer.responseCode());
            assertEquals(List.of("192.0.2." + (ticket + 1)), answer.addresses());
        }
    }

    @ParameterizedTest
    @CsvSource({"A,1,192.0.2.10", "AAAA,28,2001:db8::10", "TXT,16,weighted-answer"})
    void onlyTheRequestedTypeParticipatesInTheWeightGroup(String type, int queryType, String value) {
        String recordValue = "TXT".equals(type) ? "\"" + value + "\"" : value;
        ResourceRecordSet selected = weighted("selected", 1, recordValue);
        selected.setType(type);
        ResourceRecordSet unrelated = weighted("unrelated", 255, "192.0.2.99");
        unrelated.setType("MX");
        DnsAnswer answer = source(List.of(selected, unrelated), draw(1, 0), null)
                .resolveAuthoritative(NAME, queryType).orElseThrow();
        assertEquals(0, answer.responseCode());
        assertEquals(1, answer.records().size());
        assertEquals(queryType, answer.records().getFirst().type());
    }

    @ParameterizedTest
    @ValueSource(strings = {"region", "failover", "healthCheck", "targetHealth"})
    void unsupportedPolicyOnAnUnselectedRecordRemainsRefused(String policy) {
        ResourceRecordSet unsupported = weighted("disabled", 0, "192.0.2.2");
        switch (policy) {
            case "region" -> unsupported.setRegion("eu-west-1");
            case "failover" -> unsupported.setFailover("SECONDARY");
            case "healthCheck" -> unsupported.setHealthCheckId("health-check");
            case "targetHealth" -> {
                AliasTarget alias = new AliasTarget();
                alias.setDnsName("target.example.test.");
                alias.setHostedZoneId("ZONE");
                alias.setEvaluateTargetHealth(true);
                unsupported.setAliasTarget(alias);
            }
            default -> throw new AssertionError(policy);
        }
        DnsAnswer answer = source(List.of(weighted("enabled", 1, "192.0.2.1"), unsupported),
                noDraw(), null).resolveAuthoritative(NAME, 1).orElseThrow();
        assertEquals(5, answer.responseCode());
        assertTrue(answer.records().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingIdentifier", "blankIdentifier", "duplicateIdentifier",
            "negativeWeight", "excessiveWeight", "mixedSimple"})
    void invalidWeightedGroupsAreRefusedBeforeSelection(String invalid) {
        ResourceRecordSet second = weighted("second", 1, "192.0.2.2");
        switch (invalid) {
            case "missingIdentifier" -> second.setSetIdentifier(null);
            case "blankIdentifier" -> second.setSetIdentifier(" ");
            case "duplicateIdentifier" -> second.setSetIdentifier("first");
            case "negativeWeight" -> second.setWeight(-1L);
            case "excessiveWeight" -> second.setWeight(256L);
            case "mixedSimple" -> {
                second.setWeight(null);
                second.setSetIdentifier(null);
            }
            default -> throw new AssertionError(invalid);
        }
        DnsAnswer answer = source(List.of(weighted("first", 1, "192.0.2.1"), second),
                noDraw(), null).resolveAuthoritative(NAME, 1).orElseThrow();
        assertEquals(5, answer.responseCode());
        assertTrue(answer.records().isEmpty());
    }

    @Test
    void weightedAliasRetainsTheValidatedTargetsTtlAndTransport() {
        ResourceRecordSet record = weighted("target", 1, "192.0.2.1");
        record.setRecords(null);
        record.setTtl(null);
        AliasTarget alias = new AliasTarget();
        alias.setDnsName("target.example.test.");
        alias.setHostedZoneId("ZONE");
        record.setAliasTarget(alias);
        EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        when(dns.resolvePublicAliasTarget(NAME, "target.example.test.", "ZONE", 1, null))
                .thenReturn(Optional.of(DnsAnswer.records(List.of("192.0.2.20"), 73).viaUpstream()));
        DnsAnswer answer = source(List.of(record), draw(1, 0), dns)
                .resolveAuthoritative(NAME, 1).orElseThrow();
        assertEquals(0, answer.responseCode());
        assertEquals(List.of("192.0.2.20"), answer.addresses());
        assertEquals(73, answer.ttlSeconds());
        assertTrue(answer.upstream());
        assertNotNull(answer.authority());
    }

    @Test
    void missingQueryTypeRetainsAuthoritativeNoData() {
        DnsAnswer answer = source(List.of(weighted("target", 1, "192.0.2.1")), noDraw(), null)
                .resolveAuthoritative(NAME, 28).orElseThrow();
        assertEquals(0, answer.responseCode());
        assertTrue(answer.records().isEmpty());
        assertTrue(answer.nameExists());
        assertNotNull(answer.authority());
    }

    private static LongUnaryOperator draw(long expectedBound, long ticket) {
        return bound -> {
            assertEquals(expectedBound, bound);
            return ticket;
        };
    }

    private static LongUnaryOperator noDraw() {
        return bound -> {
            throw new AssertionError("This query must not select a weighted record");
        };
    }

    private static Route53DnsRecordSource source(List<ResourceRecordSet> records, LongUnaryOperator draw,
                                                EmbeddedDnsServer dns) {
        ResourceRecordSet soa = new ResourceRecordSet();
        soa.setName("example.test.");
        soa.setType("SOA");
        soa.setTtl(60L);
        soa.setRecords(List.of(new ResourceRecord("ns.example.test. hostmaster.example.test. 1 60 60 60 60")));
        List<ResourceRecordSet> all = new ArrayList<>(records);
        all.add(soa);
        Route53Service zones = mock(Route53Service.class);
        when(zones.publicDnsZones(NAME)).thenReturn(List.of(
                new Route53Service.PublicDnsZone("example.test.", records, all)));
        return new Route53DnsRecordSource(zones, null, new DnsLookupHelper(), dns, draw);
    }

    private static ResourceRecordSet weighted(String identifier, long weight, String value) {
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName(NAME);
        record.setType("A");
        record.setTtl(60L);
        record.setRecords(List.of(new ResourceRecord(value)));
        record.setSetIdentifier(identifier);
        record.setWeight(weight);
        return record;
    }
}

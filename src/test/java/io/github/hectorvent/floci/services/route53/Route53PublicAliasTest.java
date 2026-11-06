package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Route53PublicAliasTest {
    private static final String ACCOUNT = "000000000000";
    private static final String TARGET = "one.elb.example.net.";
    private static final String TARGET_ZONE = "ZALB";
    private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
    private final StorageFactory factory = mock(StorageFactory.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final RegionResolver regions = new RegionResolver("us-east-1", ACCOUNT);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private Route53Service service;
    private String zone;

    @BeforeEach
    void createZone() {
        when(factory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                stores.computeIfAbsent(invocation.getArgument(1),
                        ignored -> spy(AccountAwareStorageBackend.inMemory(ACCOUNT))));
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Route53ServiceConfig route53 = mock(EmulatorConfig.Route53ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(services);
        when(services.route53()).thenReturn(route53);
        when(route53.defaultNameserver1()).thenReturn(Optional.empty());
        when(route53.defaultNameserver2()).thenReturn(Optional.empty());
        when(route53.defaultNameserver3()).thenReturn(Optional.empty());
        when(route53.defaultNameserver4()).thenReturn(Optional.empty());
        service = new Route53Service(factory, config, regions, ec2);
        zone = service.createHostedZone("example.test.", "aliases", null, null).zone().getId();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "AAAA"})
    void addressAliasesNormalizeNamesAndHostedZonePrefixes(String type) {
        put(alias("api.example.test.", type, "ONE.ELB.EXAMPLE.NET", "/hostedzone/" + TARGET_ZONE));
        assertEquals(new Route53Service.PublicAlias(TARGET, TARGET_ZONE),
                service.lookupPublicAlias("API.EXAMPLE.TEST"));
    }

    @Test
    void addressFamiliesCanAgreeOnTheSameAlias() {
        put(alias("api.example.test.", "A", TARGET, TARGET_ZONE),
                alias("api.example.test.", "AAAA", TARGET, "/hostedzone/" + TARGET_ZONE));
        assertEquals(new Route53Service.PublicAlias(TARGET, TARGET_ZONE), service.lookupPublicAlias("api.example.test."));
    }

    @Test
    void conflictingAddressFamiliesCannotSelectAnArbitraryLoadBalancer() {
        put(alias("api.example.test.", "A", TARGET, TARGET_ZONE),
                alias("api.example.test.", "AAAA", "two.elb.example.net.", TARGET_ZONE));
        assertUnresolved("api.example.test.");
    }

    @Test
    void aliasesWithinTheSameZoneAcceptAPrefixedZoneId() {
        put(alias("api.example.test.", "A", "next.example.test.", "/hostedzone/" + zone),
                alias("next.example.test.", "AAAA", TARGET, TARGET_ZONE));
        assertEquals(new Route53Service.PublicAlias(TARGET, TARGET_ZONE), service.lookupPublicAlias("api.example.test."));
    }

    @Test
    void missingCnameTargetIsClaimedButAnUnconfiguredNameIsNot() {
        put(record("api.example.test.", "CNAME", "missing.example.test."));
        assertUnresolved("api.example.test.");
        assertNull(service.lookupPublicAlias("missing.example.test."));
        assertNull(service.lookupPublicAlias("outside.example.net."));
    }

    @Test
    void cyclesCannotBecomeListenerFallbacks() {
        put(record("api.example.test.", "CNAME", "other.example.test."),
                record("other.example.test.", "CNAME", "api.example.test."));
        assertUnresolved("api.example.test.");
    }

    @Test
    void wildcardStopsAtACloserEmptyNonterminal() {
        put(alias("*.example.test.", "A", TARGET, TARGET_ZONE));
        assertEquals(TARGET, service.lookupPublicAlias("api.deep.example.test.").dnsName());
        put(record("leaf.deep.example.test.", "TXT", "\"present\""));
        assertNull(service.lookupPublicAlias("api.deep.example.test."));
        assertUnresolved("deep.example.test.");
        put(alias("*.deep.example.test.", "AAAA", "two.elb.example.net.", TARGET_ZONE));
        assertEquals("two.elb.example.net.", service.lookupPublicAlias("api.deep.example.test.").dnsName());
    }

    @Test
    void delegationChangesTakeEffectBeforeTheNextLookup() {
        put(alias("api.child.example.test.", "A", TARGET, TARGET_ZONE));
        ResourceRecordSet delegation = record("child.example.test.", "NS", "ns.outside.example.net.");
        put(delegation);
        assertUnresolved("api.child.example.test.");
        delete(delegation);
        assertEquals(TARGET, service.lookupPublicAlias("api.child.example.test.").dnsName());
    }

    @Test
    void recordUpdatesDeletesAndZoneDeletionRetireTheIndexedAnswer() {
        ResourceRecordSet first = alias("api.example.test.", "A", TARGET, TARGET_ZONE);
        put(first);
        ResourceRecordSet replacement = alias("api.example.test.", "A", "two.elb.example.net.", TARGET_ZONE);
        put(replacement);
        assertEquals("two.elb.example.net.", service.lookupPublicAlias("api.example.test.").dnsName());
        delete(replacement);
        assertNull(service.lookupPublicAlias("api.example.test."));
        service.deleteHostedZone(zone);
        assertNull(service.lookupPublicAlias("api.example.test."));
    }

    @Test
    void persistedRecordsRebuildTheIndexAndResetClearsIt() {
        put(alias("api.example.test.", "A", TARGET, TARGET_ZONE));
        Route53Service restored = new Route53Service(factory, config, regions, ec2);
        assertEquals(TARGET, restored.lookupPublicAlias("api.example.test.").dnsName());
        restored.clear();
        assertNull(restored.lookupPublicAlias("api.example.test."));
    }

    @Test
    void equallySpecificPublicZonesAreAmbiguousAndTheLongerZoneWins() {
        put(alias("api.example.test.", "A", TARGET, TARGET_ZONE));
        String duplicate = service.createHostedZone("example.test.", "duplicate", null, null).zone().getId();
        assertUnresolved("api.example.test.");
        service.deleteHostedZone(duplicate);
        assertEquals(TARGET, service.lookupPublicAlias("api.example.test.").dnsName());
        String child = service.createHostedZone("api.example.test.", "child", null, null).zone().getId();
        service.changeResourceRecordSets(child, List.of(change("UPSERT",
                alias("api.example.test.", "AAAA", "child.elb.example.net.", TARGET_ZONE))), null);
        assertEquals("child.elb.example.net.", service.lookupPublicAlias("api.example.test.").dnsName());
    }

    @Test
    void repeatedLookupsNeverScanRecordsOrLoadStorageOnTheRequestThread() {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            changes.add(change("UPSERT", record("unrelated-" + i + ".example.test.", "TXT", "\"present\"")));
        }
        changes.add(change("UPSERT", alias("api.example.test.", "A", TARGET, TARGET_ZONE)));
        service.changeResourceRecordSets(zone, changes, null);
        stores.values().forEach(store -> clearInvocations(store));
        for (int i = 0; i < 100; i++) {
            assertEquals(TARGET, service.lookupPublicAlias("api.example.test.").dnsName());
        }
        stores.values().forEach(store -> verifyNoInteractions(store));
    }

    private void assertUnresolved(String name) {
        Route53Service.PublicAlias answer = service.lookupPublicAlias(name);
        assertNotNull(answer, "A configured but broken DNS answer must prevent listener fallback");
        assertNull(answer.dnsName());
    }

    private void put(ResourceRecordSet... records) {
        service.changeResourceRecordSets(zone,
                List.of(records).stream().map(record -> change("UPSERT", record)).toList(), null);
    }

    private void delete(ResourceRecordSet record) {
        service.changeResourceRecordSets(zone, List.of(change("DELETE", record)), null);
    }

    private static Map<String, Object> change(String action, ResourceRecordSet record) {
        return Map.of("action", action, "rrs", record);
    }

    private static ResourceRecordSet alias(String name, String type, String target, String zoneId) {
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName(name);
        record.setType(type);
        AliasTarget alias = new AliasTarget();
        alias.setDnsName(target);
        alias.setHostedZoneId(zoneId);
        record.setAliasTarget(alias);
        return record;
    }

    private static ResourceRecordSet record(String name, String type, String value) {
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName(name);
        record.setType(type);
        record.setTtl(60L);
        record.setRecords(List.of(new ResourceRecord(value)));
        return record;
    }
}

package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.github.hectorvent.floci.core.common.dns.DnsLookupHelper;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53DnsResolutionTest {

    @Inject
    Route53Service route53Service;

    @Inject
    EmulatorConfig config;

    @InjectMock
    DnsLookupHelper dnsLookupHelper;

    @InjectMock
    EmbeddedDnsServer dnsServer;

    @Inject
    Route53DnsRecordSource dnsRecordSource;

    private final List<String> createdZoneIds = new ArrayList<>();

    @AfterEach
    void cleanupZones() {
        for (String zoneId : createdZoneIds) {
            try {
                List<Map<String, Object>> deletes = route53Service.listResourceRecordSets(zoneId, null, null, 100).stream()
                        .filter(rrs -> !"NS".equals(rrs.getType()) && !"SOA".equals(rrs.getType()))
                        .map(rrs -> Map.<String, Object>of("action", "DELETE", "rrs", rrs))
                        .toList();
                if (!deletes.isEmpty()) {
                    route53Service.changeResourceRecordSets(zoneId, deletes, null);
                }
                route53Service.deleteHostedZone(zoneId);
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        createdZoneIds.clear();
    }

    @Test
    void resolvesDirectARecordInPrivateHostedZone() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "api." + zone, "A", "10.0.1.100");

        Optional<DnsAnswer> result = resolveFromVpc("api." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.1.100"), result.get().addresses());
    }

    @Test
    void answersWithTheRecordSetTtl() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "api." + zone, "A", "10.0.1.100", 42L);

        Optional<DnsAnswer> result = resolveFromVpc("api." + zone);
        assertTrue(result.isPresent());
        assertEquals(42, result.get().ttlSeconds());
    }

    @Test
    void cnameChainAnswersWithTheShortestTtlInTheChain() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "api." + zone, "A", "10.0.3.50", 600L);
        addRecord(zoneId, "short." + zone, "CNAME", "api." + zone + ".", 30L);
        addRecord(zoneId, "long." + zone, "CNAME", "short." + zone + ".", 900L);

        Optional<DnsAnswer> result = resolveFromVpc("long." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.3.50"), result.get().addresses());
        assertEquals(30, result.get().ttlSeconds());
    }

    @Test
    void aliasAnswersWithTheTargetTtl() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "target." + zone, "A", "10.0.4.99", 120L);
        addAlias(zoneId, "alias." + zone, "A", "target." + zone + ".");

        Optional<DnsAnswer> result = resolveFromVpc("alias." + zone);
        assertTrue(result.isPresent());
        assertEquals(120, result.get().ttlSeconds());
    }

    @Test
    void publicAliasUsesTheResourceAddressesAndTtlWithThePublicZoneAuthority() {
        String zone = uniqueZone();
        String id = createPublicZone(zone);
        addAlias(id, zone, "A", "owned.elb.example.");
        Mockito.when(dnsServer.resolveAliasTarget("owned.elb.example.", id, 1, null))
                .thenReturn(Optional.of(DnsAnswer.records(List.of("198.51.100.4", "198.51.100.5"), 60)));

        DnsAnswer answer = dnsRecordSource.resolveAuthoritative(zone, 1).orElseThrow();
        assertEquals(List.of("198.51.100.4", "198.51.100.5"), answer.addresses());
        assertEquals(60, answer.ttlSeconds());
        assertEquals(zone + ".", answer.authority().name());
        assertEquals(0, answer.responseCode());
        DnsAnswer ipv6 = dnsRecordSource.resolveAuthoritative(zone, 28).orElseThrow();
        assertTrue(ipv6.isEmpty());
        assertTrue(ipv6.nameExists());
        Mockito.verifyNoInteractions(dnsLookupHelper);
    }

    @Test
    void publicAliasCannotResolveUnownedTargetsThroughUpstreamDns() {
        String zone = uniqueZone();
        String id = createPublicZone(zone);
        addAlias(id, zone, "A", "unowned.localhost.floci.io.");
        Mockito.when(dnsServer.resolveAliasTarget("unowned.localhost.floci.io.", id, 1, null))
                .thenReturn(Optional.empty());

        assertEquals(5, dnsRecordSource.resolveAuthoritative(zone, 1).orElseThrow().responseCode());
        Mockito.verifyNoInteractions(dnsLookupHelper);
    }

    @Test
    void publicAliasWithoutActiveAddressesPreservesTheNameAndNegativeAuthority() {
        String zone = uniqueZone();
        String id = createPublicZone(zone);
        addAlias(id, zone, "A", "owned.elb.example.");
        Mockito.when(dnsServer.resolveAliasTarget("owned.elb.example.", id, 1, null))
                .thenReturn(Optional.of(DnsAnswer.noData()));

        DnsAnswer answer = dnsRecordSource.resolveAuthoritative(zone, 1).orElseThrow();
        assertTrue(answer.isEmpty());
        assertTrue(answer.nameExists());
        assertEquals(zone + ".", answer.authority().name());
        assertEquals(0, answer.responseCode());
    }

    @Test
    void publicAliasHealthEvaluationRemainsRefused() {
        String zone = uniqueZone();
        String id = createPublicZone(zone);
        addAlias(id, zone, "A", "owned.elb.example.");
        ResourceRecordSet alias = route53Service.listResourceRecordSets(id, null, null, 100).stream()
                .filter(record -> record.getAliasTarget() != null).findFirst().orElseThrow();
        alias.getAliasTarget().setEvaluateTargetHealth(true);
        route53Service.changeResourceRecordSets(id, List.of(Map.of("action", "UPSERT", "rrs", alias)), null);

        assertEquals(5, dnsRecordSource.resolveAuthoritative(zone, 1).orElseThrow().responseCode());
        Mockito.verifyNoInteractions(dnsServer, dnsLookupHelper);
    }

    @Test
    void resolvesMultipleARecords() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecords(zoneId, "api." + zone, "A", List.of("10.0.1.101", "10.0.1.102"));

        Optional<DnsAnswer> result = resolveFromVpc("api." + zone);
        assertTrue(result.isPresent());
        assertEquals(2, result.get().addresses().size());
        assertTrue(result.get().addresses().contains("10.0.1.101"));
        assertTrue(result.get().addresses().contains("10.0.1.102"));
    }

    @Test
    void answersWithAtMostEightRecords() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        List<String> ips = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            ips.add("10.0.1." + i);
        }
        addRecords(zoneId, "fleet." + zone, "A", ips);

        Optional<DnsAnswer> result = resolveFromVpc("fleet." + zone);
        assertTrue(result.isPresent());
        assertEquals(8, result.get().addresses().size());
    }

    @Test
    void resolutionIsCaseInsensitiveAndToleratesATrailingDot() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "api." + zone, "A", "10.0.1.200");

        Optional<DnsAnswer> result = resolveFromVpc("API." + zone.toUpperCase() + ".");
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.1.200"), result.get().addresses());
    }

    @Test
    void doesNotResolvePublicHostedZone() {
        String zone = uniqueZone();
        String zoneId = route53Service.createHostedZone(
                zone + ".", UUID.randomUUID().toString(), "public zone", null).zone().getId();
        createdZoneIds.add(zoneId);
        addRecord(zoneId, "api." + zone, "A", "1.2.3.4");

        Optional<DnsAnswer> result = resolveFromVpc("api." + zone);
        assertTrue(result.isEmpty());
    }

    @Test
    void doesNotResolveNameOutsideAnyPrivateZone() {
        assertTrue(resolveFromVpc("example.com").isEmpty());
        assertTrue(resolveFromVpc("").isEmpty());
        assertTrue(resolveFromVpc(null).isEmpty());
    }

    @Test
    void resolvesWildcardRecordInPrivateZone() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "*." + zone, "A", "10.0.2.1");

        Optional<DnsAnswer> result = resolveFromVpc("app." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.2.1"), result.get().addresses());
    }

    @Test
    void wildcardDoesNotMatchBeneathCloserExistingName() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "*." + zone, "A", "10.0.2.1");
        addRecord(zoneId, "b." + zone, "TXT", "\"text-record\"");

        // Wildcard should not match a.b.zone because b.zone exists
        Optional<DnsAnswer> result = resolveFromVpc("a.b." + zone);
        assertTrue(result.isPresent());
        assertEquals(DnsAnswer.nxDomain(), result.get());
    }

    @Test
    void wildcardDoesNotMatchAnEmptyNonTerminal() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "*." + zone, "A", "10.0.2.1");
        addRecord(zoneId, "api.team." + zone, "A", "10.0.2.2");

        Optional<DnsAnswer> team = resolveFromVpc("team." + zone);
        assertTrue(team.isPresent());
        assertTrue(team.get().isEmpty());
        assertTrue(team.get().nameExists());

        Optional<DnsAnswer> web = resolveFromVpc("web.team." + zone);
        assertTrue(web.isPresent());
        assertEquals(DnsAnswer.nxDomain(), web.get());

        Optional<DnsAnswer> other = resolveFromVpc("other." + zone);
        assertTrue(other.isPresent());
        assertEquals(List.of("10.0.2.1"), other.get().addresses());
    }

    @Test
    void closerWildcardTakesPrecedenceOverParentWildcard() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "*." + zone, "A", "10.0.2.1");
        addRecord(zoneId, "*.team." + zone, "A", "10.0.2.3");

        Optional<DnsAnswer> result = resolveFromVpc("web.team." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.2.3"), result.get().addresses());
    }

    @Test
    void resolvesPrivateCnameChainToARecord() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "api." + zone, "A", "10.0.3.50");
        addRecord(zoneId, "service." + zone, "CNAME", "api." + zone + ".");

        Optional<DnsAnswer> result = resolveFromVpc("service." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.3.50"), result.get().addresses());
    }

    @Test
    void handlesCnameCycleGracefullyWithoutInfiniteLoop() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "cname1." + zone, "CNAME", "cname2." + zone);
        addRecord(zoneId, "cname2." + zone, "CNAME", "cname1." + zone);

        Optional<DnsAnswer> result = resolveFromVpc("cname1." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of(), result.get().addresses());
    }

    @Test
    void resolvesPrivateCnameToEc2PrivateDnsName() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "ec2host." + zone, "CNAME", "ip-10-0-5-88.ec2.internal.");

        Optional<DnsAnswer> result = resolveFromVpc("ec2host." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.5.88"), result.get().addresses());
    }

    @Test
    void resolvesPrivateCnameToExternalHostname() {
        Mockito.when(dnsLookupHelper.resolveIpv4("external.example.com"))
                .thenReturn(List.of("93.184.216.34"));

        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "ext." + zone, "CNAME", "external.example.com.");

        Optional<DnsAnswer> result = resolveFromVpc("ext." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("93.184.216.34"), result.get().addresses());
    }

    @Test
    void resolvesAliasRecordInPrivateZone() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "target." + zone, "A", "10.0.4.99");
        addAlias(zoneId, "alias." + zone, "A", "target." + zone + ".");

        Optional<DnsAnswer> result = resolveFromVpc("alias." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.4.99"), result.get().addresses());
    }

    @Test
    void answersNxDomainForNonExistentNameInPrivateZone() {
        String zone = uniqueZone();
        createPrivateZone(zone);

        Optional<DnsAnswer> result = resolveFromVpc("nonexistent." + zone);
        assertTrue(result.isPresent());
        assertEquals(DnsAnswer.nxDomain(), result.get());
    }

    @Test
    void answersNoDataForEmptyNonTerminalName() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "api.team." + zone, "A", "10.0.6.1");

        Optional<DnsAnswer> result = resolveFromVpc("team." + zone);
        assertTrue(result.isPresent());
        assertTrue(result.get().isEmpty());
        assertTrue(result.get().nameExists());
    }

    @Test
    void cnameToExternalHostnameCapsTheTtlForTheUnseenTarget() {
        Mockito.when(dnsLookupHelper.resolveIpv4("external-ttl.example.com"))
                .thenReturn(List.of("93.184.216.35"));

        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "ext." + zone, "CNAME", "external-ttl.example.com.", 3600L);

        Optional<DnsAnswer> result = resolveFromVpc("ext." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("93.184.216.35"), result.get().addresses());
        assertEquals(DnsAnswer.DEFAULT_TTL_SECONDS, result.get().ttlSeconds());
    }

    @Test
    void answersNoDataForExistingNameWithoutARecord() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "txtonly." + zone, "TXT", "\"only text here\"");

        Optional<DnsAnswer> result = resolveFromVpc("txtonly." + zone);
        assertTrue(result.isPresent());
        assertTrue(result.get().isEmpty());
        assertTrue(result.get().nameExists());
    }

    @Test
    void parentZoneDoesNotAnswerChildNamesIfChildZoneExists() {
        String parentZone = uniqueZone();
        String childZone = "sub." + parentZone;
        String parentId = createPrivateZone(parentZone);
        String childId = createPrivateZone(childZone);

        addRecord(parentId, "api.sub." + parentZone, "A", "10.0.10.1");
        addRecord(childId, "api." + childZone, "A", "10.0.20.1");

        Optional<DnsAnswer> result = resolveFromVpc("api.sub." + parentZone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.20.1"), result.get().addresses());
    }

    @Test
    void cnameTargetToFlociBuiltinSuffixResolvesLocalAddress() throws Exception {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "floci-cname." + zone, "CNAME", "s3.localhost.localstack.cloud");

        Optional<DnsAnswer> result = resolveFromVpc("floci-cname." + zone);
        assertTrue(result.isPresent());
        String expectedIp = InetAddress.getLocalHost().getHostAddress();
        assertEquals(List.of(expectedIp), result.get().addresses());
    }

    @Test
    void privateCnameToEmulatorSuffixUsesTheAdvertisedDnsAddress() {
        Mockito.when(dnsServer.getServerIp()).thenReturn(Optional.of("172.26.0.2"));
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "service." + zone, "CNAME", "s3.localhost.floci.io");

        Optional<DnsAnswer> result = resolveFromVpc("service." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("172.26.0.2"), result.get().addresses());
    }

    @Test
    void cnameTargetToDefaultFlociSuffixResolvesLocalAddress() throws Exception {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "floci-cname2." + zone, "CNAME", "sqs.localhost.floci.io");

        Optional<DnsAnswer> result = resolveFromVpc("floci-cname2." + zone);
        assertTrue(result.isPresent());
        String expectedIp = InetAddress.getLocalHost().getHostAddress();
        assertEquals(List.of(expectedIp), result.get().addresses());
    }

    @Test
    void aliasTargetToFlociBuiltinSuffixResolvesLocalAddress() throws Exception {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addAlias(zoneId, "floci-alias." + zone, "A", "s3.localhost.localstack.cloud");

        Optional<DnsAnswer> result = resolveFromVpc("floci-alias." + zone);
        assertTrue(result.isPresent());
        String expectedIp = InetAddress.getLocalHost().getHostAddress();
        assertEquals(List.of(expectedIp), result.get().addresses());
    }

    @Test
    void aliasTargetToBareLocalstackCloudDoesNotResolveLocalAddress() throws Exception {
        Mockito.when(dnsLookupHelper.resolveIpv4("s3.localstack.cloud"))
                .thenReturn(List.of("192.0.2.1"));

        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addAlias(zoneId, "bare-alias." + zone, "A", "s3.localstack.cloud");

        Optional<DnsAnswer> result = resolveFromVpc("bare-alias." + zone);
        assertTrue(result.isPresent());
        String localIp = InetAddress.getLocalHost().getHostAddress();
        assertFalse(result.get().addresses().contains(localIp));
        assertEquals(List.of("192.0.2.1"), result.get().addresses());
    }

    @Test
    void aliasTargetToEc2PrivateDnsResolves() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addAlias(zoneId, "ec2-alias." + zone, "A", "ip-10-0-0-5.ec2.internal");

        Optional<DnsAnswer> result = resolveFromVpc("ec2-alias." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.0.5"), result.get().addresses());
    }

    @Test
    void unknownCallerDoesNotReadPrivateRecords() {
        String zone = uniqueZone();
        addRecord(createPrivateZone(zone), "api." + zone, "A", "10.0.1.100");

        assertTrue(dnsRecordSource.resolveIpv4("api." + zone).isEmpty());
        assertTrue(dnsRecordSource.resolve("api." + zone, 1, null).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CNAME", "ALIAS"})
    void chasingTargetsKeepsTheOriginalVpcVisibility(String type) {
        String sourceName = uniqueZone();
        String targetName = "api." + uniqueZone();
        String sourceZone = createPrivateZone(sourceName);
        addRecord(createPrivateZone(targetName, "vpc-54321"), targetName, "A", "10.99.1.7");
        Mockito.when(dnsLookupHelper.resolveIpv4(targetName)).thenReturn(List.of("203.0.113.7"));
        if ("CNAME".equals(type)) {
            addRecord(sourceZone, "alias." + sourceName, "CNAME", targetName);
        } else {
            addAlias(sourceZone, "alias." + sourceName, "A", targetName);
        }

        assertEquals(List.of("203.0.113.7"), resolveFromVpc("alias." + sourceName).orElseThrow().addresses());
        Mockito.verify(dnsLookupHelper).resolveIpv4(targetName);
    }

    private Optional<DnsAnswer> resolveFromVpc(String name) {
        return dnsRecordSource.resolve(name, 1, new ClientVpc(config.defaultAccountId(), "us-east-1", "vpc-12345"));
    }

    private String createPrivateZone(String name) {
        return createPrivateZone(name, "vpc-12345");
    }

    private String createPublicZone(String name) {
        String id = route53Service.createHostedZone(name, UUID.randomUUID().toString(), null, null).zone().getId();
        createdZoneIds.add(id);
        return id;
    }

    private String createPrivateZone(String name, String vpcId) {
        String zoneName = name.endsWith(".") ? name : name + ".";
        String id = route53Service.createHostedZone(
                zoneName,
                UUID.randomUUID().toString(),
                "test private zone",
                new VpcAssociation(vpcId, "us-east-1")).zone().getId();
        createdZoneIds.add(id);
        return id;
    }

    private void addRecord(String zoneId, String name, String type, String value) {
        addRecord(zoneId, name, type, value, 300L);
    }

    private void addRecord(String zoneId, String name, String type, String value, long ttl) {
        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType(type);
        rrs.setTtl(ttl);
        rrs.setRecords(List.of(new ResourceRecord(value)));
        route53Service.changeResourceRecordSets(zoneId, List.of(Map.of("action", "CREATE", "rrs", rrs)), null);
    }

    private void addRecords(String zoneId, String name, String type, List<String> values) {
        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType(type);
        rrs.setTtl(300L);
        rrs.setRecords(values.stream().map(ResourceRecord::new).toList());
        route53Service.changeResourceRecordSets(zoneId, List.of(Map.of("action", "CREATE", "rrs", rrs)), null);
    }

    private void addAlias(String zoneId, String name, String type, String targetDnsName) {
        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType(type);
        AliasTarget alias = new AliasTarget();
        alias.setDnsName(targetDnsName);
        alias.setHostedZoneId(zoneId);
        alias.setEvaluateTargetHealth(false);
        rrs.setAliasTarget(alias);
        route53Service.changeResourceRecordSets(zoneId, List.of(Map.of("action", "CREATE", "rrs", rrs)), null);
    }

    private static String uniqueZone() {
        return "r53dns" + UUID.randomUUID().toString().substring(0, 8) + ".internal";
    }
}

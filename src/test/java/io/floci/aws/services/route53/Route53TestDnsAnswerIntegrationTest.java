package io.floci.aws.services.route53;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.AliasTarget;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.CreateHostedZoneResponse;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.route53.model.Route53Exception;
import software.amazon.awssdk.services.route53.model.TestDnsAnswerResponse;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53TestDnsAnswerIntegrationTest {
    private static final String ACCOUNT = "741686000051";

    @Test
    void recordUpdatesAndDeletionAreVisibleThroughTheAwsSdk() {
        try (Zone zone = new Zone()) {
            String name = "WWW." + zone.name;
            zone.put(record(name, "A", "192.0.2.10", "192.0.2.11"));
            zone.put(record(name, "AAAA", "2001:db8::10"));
            zone.put(record(name, "TXT", "\"hello <&>\" \"second chunk\""));
            TestDnsAnswerResponse first = zone.query(name, "A");
            assertEquals(200, first.sdkHttpResponse().statusCode());
            assertEquals("UDP", first.protocol());
            assertEquals(name, first.recordName());
            assertEquals("A", first.recordTypeAsString());
            assertEquals("NOERROR", first.responseCode());
            assertEquals(Set.of("192.0.2.10", "192.0.2.11"), Set.copyOf(first.recordData()));
            assertTrue(zone.nameServers.contains(first.nameserver()));
            assertEquals(List.of("2001:db8::10"), zone.query(name, "AAAA").recordData());
            assertEquals(List.of("\"hello <&>\" \"second chunk\""), zone.query(name, "TXT").recordData());
            ResourceRecordSet updated = record(name, "A", "192.0.2.20");
            zone.put(updated);
            assertEquals(List.of("192.0.2.20"), zone.query(name, "A").recordData());
            zone.change(ChangeAction.DELETE, updated);
            assertAnswer(zone.query(name, "A"), "NOERROR", List.of());
            assertAnswer(zone.query("absent." + zone.name, "A"), "NXDOMAIN", List.of());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "CNAME,target.example.", "MX,10 mail.example.", "PTR,target.example.",
            "SRV,1 2 443 target.example.", "CAA,0 issue \"ca.example\"",
            "NAPTR,100 10 \"U\" \"E2U+sip\" \"!^.*$!sip:info@example.com!\" .",
            "SPF,\"v=spf1 -all\"",
            "DS,12345 13 2 0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
            "TLSA,3 1 1 0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
            "SSHFP,1 1 0123456789ABCDEF0123456789ABCDEF01234567", "SVCB,1 svc.example.", "HTTPS,1 svc.example."
    })
    void nonAliasRecordsReturnTheirStoredPresentationValues(String type, String value) {
        try (Zone zone = new Zone()) {
            zone.put(record("value." + zone.name, type, value));
            assertAnswer(zone.query("value." + zone.name, type), "NOERROR", List.of(value));
        }
    }

    @Test
    void apexAnswersAndDelegationUseTheSelectedZone() {
        try (Zone zone = new Zone()) {
            List<String> apexNs = zone.query(zone.name, "NS").recordData();
            assertEquals(Set.copyOf(zone.nameServers.stream().map(ns -> ns + ".").toList()), Set.copyOf(apexNs));
            assertEquals(1, zone.query(zone.name, "SOA").recordData().size());
            zone.put(record("child." + zone.name, "NS", apexNs.toArray(String[]::new)));
            assertAnswer(zone.query("child." + zone.name, "NS"), "NOERROR", List.of());
            assertAnswer(zone.query("below.child." + zone.name, "A"), "NOERROR", List.of());
        }
    }

    @Test
    void delegationSignerRecordsRemainAuthoritativeInTheParent() {
        try (Zone parent = new Zone(); Zone child = new Zone(ACCOUNT, "child." + parent.name)) {
            String digest = "12345 13 2 " + "0123456789ABCDEF".repeat(4);
            parent.put(record(child.name, "NS", child.nameServers.toArray(String[]::new)));
            parent.put(record(child.name, "DS", digest));
            assertAnswer(parent.query(child.name, "DS"), "NOERROR", List.of(digest));
            assertAnswer(parent.query(child.name, "NS"), "NOERROR", List.of());
            assertAnswer(parent.query("below." + child.name, "DS"), "NOERROR", List.of());
        }
    }

    @Test
    void wildcardSelectionRespectsExplicitNamesAndEmptyNonterminals() {
        try (Zone zone = new Zone()) {
            zone.put(record("*." + zone.name, "A", "192.0.2.30"));
            zone.put(record("explicit." + zone.name, "TXT", "\"present\""));
            zone.put(record("leaf.branch." + zone.name, "A", "192.0.2.31"));
            assertAnswer(zone.query("wild." + zone.name, "A"), "NOERROR", List.of("192.0.2.30"));
            assertAnswer(zone.query("explicit." + zone.name, "A"), "NOERROR", List.of());
            assertAnswer(zone.query("branch." + zone.name, "A"), "NOERROR", List.of());
            assertAnswer(zone.query("missing.branch." + zone.name, "A"), "NXDOMAIN", List.of());
        }
    }

    @Test
    void weightedRecordsAndSameZoneAliasesSelectTheTargetValues() {
        try (Zone zone = new Zone()) {
            String target = "target." + zone.name;
            zone.put(record(target, "A", "192.0.2.40").toBuilder().setIdentifier("zero").weight(0L).build());
            zone.put(record(target, "A", "192.0.2.41").toBuilder().setIdentifier("selected").weight(100L).build());
            String alias = "alias." + zone.name;
            zone.put(ResourceRecordSet.builder().name(alias).type("A").aliasTarget(AliasTarget.builder()
                    .hostedZoneId(zone.id).dnsName(target).evaluateTargetHealth(false).build()).build());
            for (int i = 0; i < 5; i++) {
                assertAnswer(zone.query(target, "A"), "NOERROR", List.of("192.0.2.41"));
                assertAnswer(zone.query(alias, "A"), "NOERROR", List.of("192.0.2.41"));
            }
            zone.put(record(target, "A", "192.0.2.41").toBuilder().setIdentifier("selected").weight(0L).build());
            TestDnsAnswerResponse allZero = zone.query(target, "A");
            assertEquals(1, allZero.recordData().size());
            assertTrue(Set.of("192.0.2.40", "192.0.2.41").contains(allZero.recordData().getFirst()));
        }
    }

    @Test
    void explicitlySelectedZonesAreAccountScopedAndGlobalAcrossRegions() {
        try (Zone first = new Zone();
             Zone overlapping = new Zone(ACCOUNT, first.name);
             Zone otherAccount = new Zone("741686000052", first.name);
             Route53Client otherRegion = AwsTestClients.create(Route53Client.builder(), ACCOUNT, Region.EU_WEST_1)) {
            first.put(record(first.name, "A", "192.0.2.50"));
            overlapping.put(record(first.name, "A", "192.0.2.51"));
            otherAccount.put(record(first.name, "A", "192.0.2.52"));
            assertEquals(List.of("192.0.2.50"), first.query(first.name, "A").recordData());
            assertEquals(List.of("192.0.2.51"), overlapping.query(first.name, "A").recordData());
            assertEquals(List.of("192.0.2.52"), otherAccount.query(first.name, "A").recordData());
            assertEquals(List.of("192.0.2.50"), otherRegion.testDNSAnswer(r -> r.hostedZoneId(first.id)
                    .recordName(first.name).recordType("A")).recordData());
            assertError("NoSuchHostedZone", 404, () -> otherAccount.client.testDNSAnswer(r ->
                    r.hostedZoneId(first.id).recordName(first.name).recordType("A")));
        }
    }

    @ParameterizedTest
    @CsvSource({"192.0.2.1,198.51.100.1,0", "192.0.2.1,198.51.100.1,32",
            "2001:db8::1,2001:db8:1::1,0", "2001:db8::1,2001:db8:1::1,128"})
    void validResolverAndClientSubnetOptionsPreserveSimpleAnswers(String resolver, String subnet, String mask) {
        try (Zone zone = new Zone()) {
            zone.put(record(zone.name, "A", "192.0.2.60"));
            TestDnsAnswerResponse answer = zone.client.testDNSAnswer(r -> r.hostedZoneId(zone.id)
                    .recordName(zone.name).recordType("A").resolverIP(resolver)
                    .edns0ClientSubnetIP(subnet).edns0ClientSubnetMask(mask));
            assertAnswer(answer, "NOERROR", List.of("192.0.2.60"));
        }
    }

    @ParameterizedTest
    @CsvSource({"resolverip,no-such-host.invalid", "edns0clientsubnetip,256.0.0.1",
            "edns0clientsubnetmask,-1", "edns0clientsubnetmask,129", "recordtype,INVALID"})
    void invalidOptionsHaveAwsXmlErrors(String member, String value) {
        given().queryParam("hostedzoneid", "ZMissing").queryParam("recordname", "example.com")
                .queryParam(member.equals("recordtype") ? "unused" : "recordtype", "A")
                .queryParam(member, value).get("/2013-04-01/testdnsanswer")
                .then().statusCode(400).body(containsString("<Code>InvalidInput</Code>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hostedzoneid", "recordname", "recordtype"})
    void requiredMembersAreCheckedBeforeZoneLookup(String missing) {
        given().queryParam(missing.equals("hostedzoneid") ? "unused" : "hostedzoneid", "ZMissing")
                .queryParam(missing.equals("recordname") ? "unused" : "recordname", "example.com")
                .queryParam(missing.equals("recordtype") ? "unused" : "recordtype", "A")
                .get("/2013-04-01/testdnsanswer").then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"));
    }

    @Test
    void privateZonesHaveAnAwsSdkInvalidInputResponse() {
        try (Route53Client client = AwsTestClients.create(Route53Client.builder(), ACCOUNT, Region.US_EAST_1);
             Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1)) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.231.0.0/16")).vpc().vpcId();
            try {
                String name = "private-answer-" + UUID.randomUUID() + ".example.";
                String id = client.createHostedZone(r -> r.name(name).callerReference(UUID.randomUUID().toString())
                        .vpc(v -> v.vpcId(vpcId).vpcRegion("us-east-1"))).hostedZone().id();
                try {
                    assertError("InvalidInput", 400, () -> client.testDNSAnswer(r -> r.hostedZoneId(id)
                            .recordName(name).recordType("A")));
                } finally {
                    client.deleteHostedZone(r -> r.id(id));
                }
            } finally {
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"hostedzoneid,33", "recordname,1025", "resolverip,46"})
    void overlongMembersAreRejected(String member, int length) {
        given().queryParam(member.equals("hostedzoneid") ? "unused" : "hostedzoneid", "ZMissing")
                .queryParam(member.equals("recordname") ? "unused" : "recordname", "example.com")
                .queryParam("recordtype", "A").queryParam(member, "x".repeat(length))
                .get("/2013-04-01/testdnsanswer").then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"));
    }

    @Test
    void ipv4SubnetMasksCannotExceedThirtyTwoBits() {
        try (Zone zone = new Zone()) {
            assertError("InvalidInput", 400, () -> zone.client.testDNSAnswer(r -> r.hostedZoneId(zone.id)
                    .recordName(zone.name).recordType("A").edns0ClientSubnetIP("192.0.2.1")
                    .edns0ClientSubnetMask("33")));
        }
    }

    @Test
    void unsupportedRoutingAndRecursiveTargetsAreExplicitlyRefused() {
        try (Zone zone = new Zone()) {
            zone.put(record("latency." + zone.name, "A", "192.0.2.70").toBuilder()
                    .setIdentifier("region").region("us-east-1").build());
            zone.put(record("cname." + zone.name, "CNAME", "outside.example."));
            zone.put(ResourceRecordSet.builder().name("external." + zone.name).type("A")
                    .aliasTarget(a -> a.hostedZoneId("ZOTHER").dnsName("outside.example.")
                            .evaluateTargetHealth(false)).build());
            zone.put(ResourceRecordSet.builder().name("loop." + zone.name).type("A")
                    .aliasTarget(a -> a.hostedZoneId(zone.id).dnsName("loop." + zone.name)
                            .evaluateTargetHealth(false)).build());
            for (String name : List.of("latency.", "cname.", "external.", "loop.")) {
                assertAnswer(zone.query(name + zone.name, "A"), "REFUSED", List.of());
            }
            assertAnswer(zone.query("outside.example.", "A"), "REFUSED", List.of());
        }
    }

    private static ResourceRecordSet record(String name, String type, String... values) {
        return ResourceRecordSet.builder().name(name).type(type).ttl(60L)
                .resourceRecords(List.of(values).stream().map(v -> ResourceRecord.builder().value(v).build()).toList())
                .build();
    }

    private static void assertAnswer(TestDnsAnswerResponse answer, String code, List<String> values) {
        assertEquals(code, answer.responseCode());
        assertEquals(values, answer.recordData());
    }

    private static void assertError(String code, int status, Runnable action) {
        Route53Exception error = assertThrows(Route53Exception.class, action::run);
        assertEquals(status, error.statusCode());
        assertEquals(code, error.awsErrorDetails().errorCode());
    }

    private static final class Zone implements AutoCloseable {
        private final Route53Client client;
        private final String id;
        private final String name;
        private final List<String> nameServers;

        private Zone() {
            this(ACCOUNT, "answer-" + UUID.randomUUID() + ".example.");
        }

        private Zone(String account, String name) {
            client = AwsTestClients.create(Route53Client.builder(), account, Region.US_EAST_1);
            this.name = name;
            CreateHostedZoneResponse created = client.createHostedZone(r -> r.name(name)
                    .callerReference(UUID.randomUUID().toString()));
            id = created.hostedZone().id();
            nameServers = created.delegationSet().nameServers();
        }

        private void put(ResourceRecordSet record) {
            change(ChangeAction.UPSERT, record);
        }

        private void change(ChangeAction action, ResourceRecordSet record) {
            client.changeResourceRecordSets(r -> r.hostedZoneId(id).changeBatch(b -> b.changes(
                    Change.builder().action(action).resourceRecordSet(record).build())));
        }

        private TestDnsAnswerResponse query(String recordName, String type) {
            return client.testDNSAnswer(r -> r.hostedZoneId(id).recordName(recordName).recordType(type));
        }

        @Override
        public void close() {
            try {
                List<ResourceRecordSet> records = client.listResourceRecordSets(r -> r.hostedZoneId(id))
                        .resourceRecordSets();
                for (ResourceRecordSet record : records) {
                    if (!record.name().equalsIgnoreCase(name)
                            || !Set.of("NS", "SOA").contains(record.typeAsString())) {
                        change(ChangeAction.DELETE, record);
                    }
                }
                client.deleteHostedZone(r -> r.id(id));
            } finally {
                client.close();
            }
        }
    }
}

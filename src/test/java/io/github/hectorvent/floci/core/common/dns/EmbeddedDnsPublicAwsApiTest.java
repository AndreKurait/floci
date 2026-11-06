package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddedDnsPublicAwsApiTest {
    private static final String CLIENT = "10.87.1.10";
    private static final String CONTROL = "172.17.0.1";
    private static final DnsClientVpcSource.ClientVpc VPC =
            new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-caller");

    @Test
    void publicApisUseNativeRoutingOnlyForKnownVpcClients() {
        EmbeddedDnsServer dns = server(true, List.of(), List.of(), VPC);
        for (String suffix : TlsConfigSource.awsEndpointSuffixes()) {
            String name = "ec2.us-east-1." + suffix;
            assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS),
                    addresses(dns, name, CLIENT));
            assertEquals(List.of(CONTROL), addresses(dns, name, "172.17.0.20"));
        }
        assertEquals(List.of(CONTROL), addresses(dns, "bucket.localhost.floci.io", CLIENT));
        assertEquals(List.of("10.87.1.10"), addresses(dns, "ip-10-87-1-10.ec2.internal", CLIENT));
        assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                dns.planQuery("notamazonaws.com", CLIENT, CONTROL, 1));
        assertTrue(dns.planQuery("ec2.us-east-1.amazonaws.com", CLIENT, CONTROL, 28)
                .answer().orElseThrow().addresses().isEmpty());
    }

    @Test
    void disabledRoutingRetainsTheExistingSpoofedAddress() {
        assertEquals(List.of(CONTROL), addresses(server(false, List.of(), List.of(), VPC),
                "ec2.us-east-1.amazonaws.com", CLIENT));
    }

    @Test
    void incompleteVpcIdentityCannotSelectThePublicRoute() {
        for (DnsClientVpcSource.ClientVpc incomplete : List.of(
                new DnsClientVpcSource.ClientVpc("", "us-east-1", "vpc-caller"),
                new DnsClientVpcSource.ClientVpc("111111111111", "", "vpc-caller"),
                new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", ""))) {
            assertEquals(List.of(CONTROL), addresses(server(true, List.of(), List.of(), incomplete),
                    "ec2.us-east-1.amazonaws.com", CLIENT));
        }
    }

    @Test
    void storedPublicResourceAnswersRetainPrecedence() {
        String hostname = "app-123.us-east-1.elb.amazonaws.com";
        DnsRecordSource frontend = new DnsRecordSource() {
            @Override
            public Optional<DnsAnswer> resolveIpv4(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolveAuthoritative(String name, int type) {
                return hostname.equals(name)
                        ? Optional.of(DnsAnswer.records(List.of("198.51.100.7"), 30)) : Optional.empty();
            }
        };
        EmbeddedDnsServer dns = server(true, List.of(frontend), List.of(), VPC);
        assertEquals(List.of("198.51.100.7"), addresses(dns, hostname, CLIENT));
        assertEquals(List.of("198.51.100.7"), addresses(dns, hostname, "172.17.0.20"));
    }

    @Test
    void ordinaryForwardingRulesStillResolveOutsideAwsSpoofing() {
        DnsForwardingRule rule = DnsForwardingRule.forwardTo("corp.example",
                List.of(new DnsForwardingRule.Target("10.87.0.53")));
        DnsForwardingRuleSource source = (account, region, vpc) -> List.of(rule);
        EmbeddedDnsServer dns = server(true, List.of(), List.of(source), VPC);
        assertEquals(rule.targets(), dns.planQuery("api.corp.example", CLIENT, CONTROL, 1).ruleTargets());
        assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS),
                addresses(dns, "EC2.US-EAST-1.AMAZONAWS.COM", CLIENT));
    }

    @Test
    void declaredPeerNamesUseThePublicDestinationOnlyForKnownVpcClients() {
        EmbeddedDnsServer dns = server(true, List.of(), List.of(), VPC, List.of("API.PEER.EXAMPLE."));
        assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS),
                addresses(dns, "api.peer.example", CLIENT));
        assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS),
                addresses(dns, "API.PEER.EXAMPLE", CLIENT));
        for (String other : List.of("child.api.peer.example", "notapi.peer.example", "other.example")) {
            assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM, dns.planQuery(other, CLIENT, CONTROL, 1));
        }
        assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                dns.planQuery("api.peer.example", "172.17.0.20", CONTROL, 1));
        assertTrue(dns.planQuery("api.peer.example", CLIENT, CONTROL, 28)
                .answer().orElseThrow().addresses().isEmpty());
        assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                server(false, List.of(), List.of(), VPC, List.of("api.peer.example"))
                        .planQuery("api.peer.example", CLIENT, CONTROL, 1));
    }

    @Test
    void nativeRecordsAndForwardingRulesPrecedeDeclaredPeerDefaults() {
        DnsRecordSource records = name -> "api.peer.example".equals(name)
                ? Optional.of(DnsAnswer.records(List.of("10.87.2.7"), 30)) : Optional.empty();
        EmbeddedDnsServer dns = server(true, List.of(records), List.of(), VPC, List.of("api.peer.example"));
        assertEquals(List.of("10.87.2.7"), addresses(dns, "api.peer.example", CLIENT));
        DnsForwardingRule rule = DnsForwardingRule.forwardTo("peer.example",
                List.of(new DnsForwardingRule.Target("10.87.0.53")));
        DnsForwardingRuleSource source = (account, region, vpc) -> List.of(rule);
        assertEquals(rule.targets(), server(true, List.of(records), List.of(source), VPC,
                List.of("api.peer.example")).planQuery("api.peer.example", CLIENT, CONTROL, 1).ruleTargets());
    }

    @Test
    void publicPeerNamesRejectWildcardsAddressesAndMalformedConfiguration() {
        for (String invalid : List.of("*", "*.peer.example", "https://peer.example", "127.0.0.1",
                "peer.example:443", "-peer.example", "peer-.example", "peer..example", "peer.example\n",
                "x".repeat(64) + ".example")) {
            assertThrows(IllegalArgumentException.class,
                    () -> server(true, List.of(), List.of(), VPC, List.of(invalid)), invalid);
        }
    }

    private static EmbeddedDnsServer server(boolean enabled, List<DnsRecordSource> records,
                                            List<DnsForwardingRuleSource> rules,
                                            DnsClientVpcSource.ClientVpc identity) {
        return server(enabled, records, rules, identity, List.of());
    }

    private static EmbeddedDnsServer server(boolean enabled, List<DnsRecordSource> records,
                                            List<DnsForwardingRuleSource> rules,
                                            DnsClientVpcSource.ClientVpc identity, List<String> publicHosts) {
        DnsClientVpcSource clients = address -> CLIENT.equals(address)
                ? Optional.of(identity) : Optional.empty();
        return new EmbeddedDnsServer(List.copyOf(TlsConfigSource.awsEndpointSuffixes()),
                records, rules, List.of(clients), enabled, publicHosts);
    }

    private static List<String> addresses(EmbeddedDnsServer server, String name, String client) {
        return server.planQuery(name, client, CONTROL, 1).answer().orElseThrow().addresses();
    }
}

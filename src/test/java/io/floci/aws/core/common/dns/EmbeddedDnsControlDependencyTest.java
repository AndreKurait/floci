package io.floci.aws.core.common.dns;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedDnsControlDependencyTest {
    private static final String HOST = "api.peer.example";
    private static final String GUEST = "172.18.0.3";
    private static final String PEER = "172.18.0.2";
    private static final DnsClientVpcSource.ClientVpc VPC =
            new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-native");

    @Test
    void admittedPeerAnswerPrecedesStoredRecords() {
        EmbeddedDnsServer server = server(List.of(source(PEER)));
        assertEquals(List.of(PEER), answer(server, HOST, GUEST, 1).addresses());
        assertEquals(List.of(PEER), answer(server, HOST.toUpperCase(), GUEST, 1).addresses());
        assertTrue(answer(server, HOST, GUEST, 28).addresses().isEmpty());
        assertEquals(5, answer(server, HOST, GUEST, 1).ttlSeconds());
    }

    @Test
    void sameVpcCallerWithoutItsOwnAdmissionKeepsThePublicPath() {
        EmbeddedDnsServer server = server(List.of(source(PEER)));
        assertEquals(List.of("10.0.1.99"), answer(server, HOST, "172.18.0.4", 1).addresses());
        assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                server.planQuery("ec2.us-east-1.amazonaws.com", GUEST, "172.18.0.1", 1));
        assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                server.planQuery("sibling." + HOST, GUEST, "172.18.0.1", 1));
    }

    @Test
    void unknownClientAndDefaultProviderReceiveNoNativeDependencyAnswer() {
        EmbeddedDnsServer server = server(List.of(source(PEER)));
        assertEquals(List.of("10.0.1.99"), answer(server, HOST, "203.0.113.2", 1).addresses());
        DnsClientVpcSource ordinary = client -> Optional.of(VPC);
        assertEquals(List.of("10.0.1.99"), answer(server(List.of(ordinary)), HOST, GUEST, 1).addresses());
    }

    @Test
    void conflictingNamespaceClaimsFailClosed() {
        DnsAnswer answer = answer(server(List.of(source(PEER), source("172.18.0.99"))), HOST, GUEST, 1);
        assertEquals(5, answer.responseCode());
        assertTrue(answer.addresses().isEmpty());
    }

    @Test
    void matchingClaimsFromTwoSourcesKeepTheAdmittedAnswer() {
        assertEquals(List.of(PEER), answer(server(List.of(source(PEER), source(PEER))), HOST, GUEST, 1).addresses());
    }

    @Test
    void explicitDeploymentDependencyIsAuthoritativeOverStoredPrivateRecords() {
        DnsRecordSource privateZone = new DnsRecordSource() {
            @Override
            public Optional<DnsAnswer> resolveIpv4(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolvePrivate(String name, int type, DnsClientVpcSource.ClientVpc origin) {
                return HOST.equals(name) && VPC.equals(origin)
                        ? Optional.of(DnsAnswer.records(List.of("10.0.1.99"), 60)) : Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolve(String name, int type, DnsClientVpcSource.ClientVpc origin) {
                return resolvePrivate(name, type, origin);
            }
        };
        EmbeddedDnsServer server = new EmbeddedDnsServer(List.of(), List.of(privateZone), List.of(),
                List.of(source(PEER)), true, List.of(HOST), PEER);
        assertEquals(List.of(PEER), answer(server, HOST, GUEST, 1).addresses());
        assertEquals(List.of("10.0.1.99"), answer(server, HOST, "172.18.0.4", 1).addresses());
    }

    private static DnsAnswer answer(EmbeddedDnsServer server, String host, String client, int type) {
        return server.planQuery(host, client, "172.18.0.1", type).answer().orElseThrow();
    }

    private static EmbeddedDnsServer server(List<DnsClientVpcSource> sources) {
        DnsRecordSource stored = name -> HOST.equalsIgnoreCase(name)
                ? Optional.of(DnsAnswer.records(List.of("10.0.1.99"), 60)) : Optional.empty();
        return new EmbeddedDnsServer(List.of(), List.of(stored), List.of(), sources);
    }

    private static DnsClientVpcSource source(String address) {
        return new DnsClientVpcSource() {
            @Override
            public Optional<ClientVpc> vpcForClient(String client) {
                return client != null && client.startsWith("172.18.") ? Optional.of(VPC) : Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> controlDependency(String name, int type, String client) {
                return GUEST.equals(client) && HOST.equalsIgnoreCase(name)
                        ? Optional.of(type == 1 ? DnsAnswer.records(List.of(address), 5) : DnsAnswer.noData())
                        : Optional.empty();
            }
        };
    }
    @Test
    void admittedPeerAnswerPrecedesSyntheticPublicRouting() {
        EmbeddedDnsServer server = publicServer(List.of(source(PEER)));
        assertEquals(List.of(PEER), answer(server, HOST, GUEST, 1).addresses());
        assertEquals(List.of(PEER), answer(server, HOST.toUpperCase(), GUEST, 1).addresses());
        assertTrue(answer(server, HOST, GUEST, 28).addresses().isEmpty());
        assertEquals(5, answer(server, HOST, GUEST, 1).ttlSeconds());
    }

    @Test
    void sameVpcCallerWithoutAdmissionKeepsSyntheticPublicPath() {
        EmbeddedDnsServer server = publicServer(List.of(source(PEER)));
        assertEquals(List.of("192.0.2.1"), answer(server, HOST, "172.18.0.4", 1).addresses());
        assertEquals(List.of("192.0.2.1"), answer(server, "ec2.us-east-1.amazonaws.com", GUEST, 1).addresses());
        assertEquals(EmbeddedDnsServer.QueryPlan.ROUTED_UPSTREAM,
                server.planQuery("sibling." + HOST, GUEST, "172.18.0.1", 1));
    }

    @Test
    void unknownClientAndDefaultProviderKeepSyntheticPublicPath() {
        EmbeddedDnsServer server = publicServer(List.of(source(PEER)));
        assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                server.planQuery(HOST, "203.0.113.2", "172.18.0.1", 1));
        DnsClientVpcSource ordinary = client -> Optional.of(VPC);
        assertEquals(List.of("192.0.2.1"), answer(publicServer(List.of(ordinary)), HOST, GUEST, 1).addresses());
    }

    private static EmbeddedDnsServer publicServer(List<DnsClientVpcSource> sources) {
        return new EmbeddedDnsServer(List.of(), List.of(), List.of(), sources, true, List.of(HOST), PEER);
    }

}

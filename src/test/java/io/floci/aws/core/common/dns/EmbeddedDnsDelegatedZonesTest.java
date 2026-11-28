package io.floci.aws.core.common.dns;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.CurrentContainerNetworkResolver;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A resolver that forwards whole zones to Floci (EC2 public DNS names, load balancer names, a Route 53 zone) and is
 * itself Floci's upstream gets back every name Floci does not answer, in a loop. Declaring those zones in
 * {@code floci.dns.authoritative-suffixes} makes Floci answer them itself, as AWS's own name servers do: an EC2 public
 * DNS name with the address it carries, a resource-backed name with its records, and anything else with NXDOMAIN.
 * Before, an EC2 public DNS name inside such a scope got NXDOMAIN too.
 */
class EmbeddedDnsDelegatedZonesTest {
    private static final String CONTROL = "127.0.0.1";
    private static final String CLIENT = "192.0.2.9";
    private static final List<String> ZONES = List.of("compute.amazonaws.com", "compute-1.amazonaws.com",
            "elb.amazonaws.com", "elb.eu-west-1.amazonaws.com", "zone-1.eu-west-1.example.test");
    private static final String ALB = "frontend-7-1234567890.eu-west-1.elb.amazonaws.com";
    private static final String NLB = "web-0123456789abcdef.elb.eu-west-1.amazonaws.com";

    @Test
    void anEc2PublicDnsNameAnswersWithItsAddressInsideTheScope() throws Exception {
        try (Fixture fixture = start(ZONES, Map.of())) {
            assertEquals(List.of("10.0.0.5"), answer(fixture, "ec2-10-0-0-5.compute-1.amazonaws.com", 1).addresses());
            assertEquals(List.of("172.16.4.9"),
                    answer(fixture, "ec2-172-16-4-9.eu-west-1.compute.amazonaws.com", 1).addresses());
            DnsAnswer ipv6 = answer(fixture, "ec2-10-0-0-5.compute-1.amazonaws.com", 28);
            assertTrue(ipv6.isEmpty());
            assertTrue(ipv6.nameExists());
        }
    }

    @Test
    void anUnownedNameInADelegatedZoneIsNxdomainNotForwarded() throws Exception {
        try (Fixture fixture = start(ZONES, Map.of(ALB, DnsAnswer.records(List.of("172.16.4.0"), 60)))) {
            for (String name : List.of("nobody.compute-1.amazonaws.com", "ec2-999-0-0-1.compute-1.amazonaws.com",
                    "ec2-10-0-0-5.us-east-1.compute.amazonaws.com", "nobody-1234567890.eu-west-1.elb.amazonaws.com",
                    "all.nobody-1234567890.eu-west-1.elb.amazonaws.com", "nobody-0123456789abcdef.elb.eu-west-1.amazonaws.com",
                    "dualstack.nobody-0123456789abcdef.elb.eu-west-1.amazonaws.com",
                    "missing.zone-1.eu-west-1.example.test")) {
                assertEquals(DnsAnswer.nxDomain(), answer(fixture, name, 1), name);
            }
            assertEquals(List.of("172.16.4.0"), answer(fixture, ALB, 1).addresses());
        }
    }

    @Test
    void outsideTheScopesAnUnownedNameStillGoesUpstreamAndTheEmulatorSuffixStillAnswers() throws Exception {
        try (Fixture fixture = start(List.of(), Map.of())) {
            EmbeddedDnsServer.QueryPlan plan = fixture.server().planQuery(NLB, CLIENT, CONTROL, 1);
            assertTrue(plan.answer().isEmpty(), "an undeclared zone is left to upstream DNS, which is what loops");
            assertEquals(List.of(CONTROL), answer(fixture, "ns-1.route53.localhost.floci.io", 1).addresses(),
                    "the Route 53 name servers are under Floci's own suffix, answered by Floci and never forwarded");
        }
    }

    private static DnsAnswer answer(Fixture fixture, String name, int type) {
        return fixture.server().planQuery(name, CLIENT, CONTROL, type).answer().orElseThrow();
    }

    private static Fixture start(List<String> scopes, Map<String, DnsAnswer> records) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.dns().listenAddress()).thenReturn(Optional.of(CONTROL));
        when(config.dns().authoritativeSuffixes()).thenReturn(Optional.of(scopes));
        when(config.dns().containerFallbackServers()).thenReturn(List.of());
        DnsRecordSource source = name -> Optional.ofNullable(records.get(name));
        Vertx vertx = Vertx.vertx();
        try {
            EmbeddedDnsServer server = new EmbeddedDnsServer(config, mock(ContainerDetector.class), vertx,
                    List.of(source), List.of(), List.of(), mock(CurrentContainerNetworkResolver.class), 0);
            return new Fixture(server, vertx);
        } catch (Exception failure) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            throw failure;
        }
    }

    private record Fixture(EmbeddedDnsServer server, Vertx vertx) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            server.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}

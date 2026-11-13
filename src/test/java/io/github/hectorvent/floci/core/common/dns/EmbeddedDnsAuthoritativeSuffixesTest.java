package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddedDnsAuthoritativeSuffixesTest {
    private static final String CONTROL = "127.0.0.1";
    private static final String CLIENT = "192.0.2.9";
    private static final String SCOPE = "resource.localhost.floci.io";
    private static final DnsClientVpcSource.ClientVpc VPC =
            new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-client");

    @Test
    void environmentConfigurationMapsTheOptionalResourceScopes() {
        String prefix = "floci.dns";
        EmulatorConfig.DnsConfig config = new SmallRyeConfigBuilder()
                .withMapping(EmulatorConfig.DnsConfig.class, prefix)
                .withSources(new EnvConfigSource(Map.of(
                        "FLOCI_DNS_AUTHORITATIVE_SUFFIXES", SCOPE + ",another.example"), 300))
                .build().getConfigMapping(EmulatorConfig.DnsConfig.class, prefix);
        assertEquals(List.of(SCOPE, "another.example"), config.authoritativeSuffixes().orElseThrow());
    }

    @Test
    void unprovisionedScopeAndChildrenDoNotUseWildcardOrUpstream() throws Exception {
        try (Fixture fixture = start(List.of(SCOPE), List.of(), List.of(), List.of())) {
            for (String name : List.of(SCOPE, "missing." + SCOPE)) {
                for (int type : List.of(1, 28, 16)) {
                    assertEquals(DnsAnswer.nxDomain(), answer(fixture.server(), name, type));
                }
            }
            assertEquals(DnsAnswer.nxDomain(),
                    fixture.server().resolveARecordWithOwnership(SCOPE, CONTROL).orElseThrow());
        }
    }

    @Test
    void resourceChangesAreVisibleWithoutRewritingAddresses() throws Exception {
        Map<String, DnsAnswer> records = new HashMap<>();
        DnsRecordSource source = name -> Optional.ofNullable(records.get(name));
        try (Fixture fixture = start(List.of(SCOPE), List.of(source), List.of(), List.of())) {
            EmbeddedDnsServer server = fixture.server();
            assertEquals(DnsAnswer.nxDomain(), answer(server, SCOPE, 1));
            DnsAnswer first = DnsAnswer.records(List.of("198.51.100.7"), 15);
            records.put(SCOPE, first);
            assertEquals(first, answer(server, SCOPE, 1));
            DnsAnswer replacement = DnsAnswer.records(List.of("198.51.100.8", "198.51.100.9"), 25);
            records.put(SCOPE, replacement);
            assertEquals(replacement, answer(server, SCOPE, 1));
            records.remove(SCOPE);
            assertEquals(DnsAnswer.nxDomain(), answer(server, SCOPE, 1));
        }
    }

    @Test
    void resourceNegativeAndIpv6AnswersRemainAuthoritative() throws Exception {
        Map<String, DnsAnswer> records = new HashMap<>();
        DnsRecordSource source = new DnsRecordSource() {
            @Override
            public Optional<DnsAnswer> resolveIpv4(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolve(String name, int type) {
                return Optional.ofNullable(records.get(name));
            }
        };
        try (Fixture fixture = start(List.of(SCOPE), List.of(source), List.of(), List.of())) {
            for (DnsAnswer response : List.of(DnsAnswer.noData(), DnsAnswer.refused(),
                    DnsAnswer.nxDomain().withAuthority(SCOPE,
                            new DnsRecord.Soa("ns.example hostmaster.example 1 2 3 4 5"), 30))) {
                records.put(SCOPE, response);
                assertEquals(response, answer(fixture.server(), SCOPE, 28));
            }
        }
    }

    @Test
    void suffixMatchingNormalizesCaseAndUsesLabelBoundaries() throws Exception {
        try (Fixture fixture = start(List.of("RESOURCE.LOCALHOST.FLOCI.IO."), List.of(), List.of(), List.of())) {
            assertEquals(DnsAnswer.nxDomain(), answer(fixture.server(), SCOPE, 1));
            assertEquals(DnsAnswer.nxDomain(),
                    answer(fixture.server(), "CHILD." + SCOPE.toUpperCase(Locale.ROOT) + ".", 1));
            assertEquals(List.of(CONTROL), answer(fixture.server(), "not" + SCOPE, 1).addresses());
            assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                    fixture.server().planQuery("unrelated.example", CLIENT, CONTROL, 1));
        }
    }

    @Test
    void unsetConfigurationPreservesExistingWildcardAnswers() throws Exception {
        try (Fixture fixture = start(List.of(), List.of(), List.of(), List.of())) {
            assertEquals(List.of(CONTROL), answer(fixture.server(), SCOPE, 1).addresses());
            assertEquals(DnsAnswer.noData(), answer(fixture.server(), SCOPE, 28));
        }
    }

    @Test
    void scopedForwardingRuleKeepsItsPrecedence() throws Exception {
        DnsForwardingRule rule = new DnsForwardingRule(SCOPE,
                List.of(new DnsForwardingRule.Target("192.0.2.53", 53)));
        DnsForwardingRuleSource rules = (account, region, vpc) -> List.of(rule);
        DnsClientVpcSource clients = address -> CLIENT.equals(address) ? Optional.of(VPC) : Optional.empty();
        try (Fixture fixture = start(List.of(SCOPE), List.of(), List.of(rules), List.of(clients))) {
            assertEquals(rule.targets(), fixture.server().planQuery(SCOPE, CLIENT, CONTROL, 1).ruleTargets());
            assertEquals(DnsAnswer.nxDomain(),
                    fixture.server().planQuery(SCOPE, "192.0.2.10", CONTROL, 1).answer().orElseThrow());
        }
    }

    @Test
    void reservedScopeDoesNotFollowAResourceRequestForUpstreamTransport() throws Exception {
        DnsRecordSource source = name -> Optional.of(DnsAnswer.records(List.of(CONTROL), 30).viaUpstream());
        try (Fixture fixture = start(List.of(SCOPE), List.of(source), List.of(), List.of())) {
            assertEquals(DnsAnswer.refused(), answer(fixture.server(), SCOPE, 1));
        }
    }

    @Test
    void malformedScopesFailBeforeListenersOpen() {
        for (String invalid : List.of(".", "*.example", "https://example", "192.0.2.1", "bad..example")) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (Fixture fixture = start(List.of(invalid), List.of(), List.of(), List.of())) {
                    assertTrue(fixture.server().listenerPort() > 0);
                }
            }, invalid);
        }
    }

    @Test
    void udpAndTcpReturnTheSameNegativeAnswer() throws Exception {
        try (Fixture fixture = start(List.of(SCOPE), List.of(), List.of(), List.of())) {
            ByteBuffer packet = ByteBuffer.allocate(512);
            packet.putShort((short) 0x5131).putShort((short) 0x0100).putShort((short) 1)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0);
            for (String label : SCOPE.split("\\.")) {
                byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
                packet.put((byte) bytes.length).put(bytes);
            }
            packet.put((byte) 0).putShort((short) 1).putShort((short) 1);
            byte[] query = Arrays.copyOf(packet.array(), packet.position());
            int port = fixture.server().listenerPort();
            byte[] udp;
            try (DatagramSocket client = new DatagramSocket()) {
                client.connect(InetAddress.ofLiteral(CONTROL), port);
                client.setSoTimeout(5000);
                client.send(new DatagramPacket(query, query.length));
                DatagramPacket reply = new DatagramPacket(new byte[512], 512);
                client.receive(reply);
                udp = Arrays.copyOf(reply.getData(), reply.getLength());
            }
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(CONTROL, port), 5000);
                client.setSoTimeout(5000);
                DataOutputStream output = new DataOutputStream(client.getOutputStream());
                output.writeShort(query.length);
                output.write(query);
                output.flush();
                DataInputStream input = new DataInputStream(client.getInputStream());
                assertArrayEquals(udp, input.readNBytes(input.readUnsignedShort()));
            }
            assertEquals(3, udp[3] & 15);
            assertEquals(0, ByteBuffer.wrap(udp).getShort(6));
            assertTrue((ByteBuffer.wrap(udp).getShort(2) & 0x0400) != 0);
        }
    }

    private static DnsAnswer answer(EmbeddedDnsServer server, String name, int type) {
        return server.planQuery(name, CLIENT, CONTROL, type).answer().orElseThrow();
    }

    private static Fixture start(List<String> scopes, List<DnsRecordSource> records,
                                 List<DnsForwardingRuleSource> rules, List<DnsClientVpcSource> clients) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.dns().listenAddress()).thenReturn(Optional.of(CONTROL));
        when(config.dns().authoritativeSuffixes()).thenReturn(Optional.of(scopes));
        when(config.dns().containerFallbackServers()).thenReturn(List.of());
        Vertx vertx = Vertx.vertx();
        try {
            EmbeddedDnsServer server = new EmbeddedDnsServer(config, mock(ContainerDetector.class), vertx,
                    records, rules, clients, mock(CurrentContainerNetworkResolver.class), 0);
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

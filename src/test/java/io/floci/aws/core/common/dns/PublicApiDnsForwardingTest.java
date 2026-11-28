package io.floci.aws.core.common.dns;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.testing.ParallelLocks;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class PublicApiDnsForwardingTest {
    private static final String HOST = "peer.example";
    private static final String UPSTREAM = "172.18.4.1";
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @ParameterizedTest
    @CsvSource({"false,true,false", "true,true,false", "false,false,false", "true,false,false",
            "false,true,true", "true,true,true"})
    void routedRecursionPreservesWireAnswersAndOnlyChangesKnownVpcAddresses(
            boolean tcp, boolean managedVpc, boolean negative) throws Exception {
        Vertx vertx = Vertx.vertx();
        EmbeddedDnsServer.Listeners listeners = null;
        try (ServerSocket upstreamTcp = new ServerSocket(0, 1, LOOPBACK);
             DatagramSocket upstreamUdp = new DatagramSocket(0, LOOPBACK)) {
            CompletableFuture<byte[]> sent = new CompletableFuture<>();
            Thread responder = Thread.ofVirtual().start(() -> {
                try {
                    if (tcp) {
                        try (Socket socket = upstreamTcp.accept()) {
                            socket.setSoTimeout(5000);
                            DataInputStream input = new DataInputStream(socket.getInputStream());
                            byte[] query = input.readNBytes(input.readUnsignedShort());
                            byte[] reply = answer(query, negative);
                            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                            output.writeShort(reply.length);
                            output.write(reply);
                            output.flush();
                            sent.complete(reply);
                        }
                    } else {
                        upstreamUdp.setSoTimeout(5000);
                        DatagramPacket request = new DatagramPacket(new byte[4096], 4096);
                        upstreamUdp.receive(request);
                        byte[] reply = answer(Arrays.copyOf(request.getData(), request.getLength()), negative);
                        upstreamUdp.send(new DatagramPacket(reply, reply.length, request.getSocketAddress()));
                        sent.complete(reply);
                    }
                } catch (Exception failure) {
                    sent.completeExceptionally(failure);
                }
            });
            RelayDns dns = new RelayDns(managedVpc, List.of(), tcp ? upstreamTcp.getLocalPort()
                    : upstreamUdp.getLocalPort());
            listeners = dns.bindAll(vertx, "127.0.0.1", 0, true)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            byte[] actual = query(listeners.tcp().actualPort(), tcp);
            byte[] original = sent.get(5, TimeUnit.SECONDS);
            byte[] expected = original.clone();
            if (managedVpc && !negative) {
                byte[] address = InetAddress.getByName(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS)
                        .getAddress();
                System.arraycopy(address, 0, expected, expected.length - address.length, address.length);
            }
            assertArrayEquals(expected, actual);
            assertEquals(1, dns.forwards.get());
            if (!negative) {
                String address = managedVpc ? EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS : UPSTREAM;
                assertArrayEquals(InetAddress.getByName(address).getAddress(),
                        Arrays.copyOfRange(actual, actual.length - 4, actual.length));
            }
            responder.join(5000);
        } finally {
            close(vertx, listeners);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeHostRefusalAndReservedAuthorityCannotTriggerRecursion(boolean tcp) throws Exception {
        for (boolean reserved : List.of(false, true)) {
            Vertx vertx = Vertx.vertx();
            EmbeddedDnsServer.Listeners listeners = null;
            RelayDns dns = new RelayDns(true, reserved ? List.of("example") : List.of(), 9);
            try {
                listeners = dns.bindAll(vertx, "127.0.0.1", 0, reserved)
                        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                byte[] reply = query(listeners.tcp().actualPort(), tcp);
                assertEquals(reserved ? 3 : 5, ByteBuffer.wrap(reply).getShort(2) & 15);
                assertEquals(0, dns.forwards.get());
            } finally {
                close(vertx, listeners);
            }
        }
    }

    private static byte[] answer(byte[] query, boolean negative) {
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of());
        return negative ? dns.buildEmptyResponse(query, ByteBuffer.wrap(query).getShort(), 12, query.length, 3)
                : dns.buildAResponse(query, ByteBuffer.wrap(query).getShort(), 12, query.length,
                        DnsAnswer.records(List.of(UPSTREAM), 37));
    }

    private static byte[] query(int port, boolean tcp) throws Exception {
        byte[] query = DnsTestQuery.query(HOST, 1, 0x4321);
        if (tcp) {
            try (Socket socket = new Socket(LOOPBACK, port)) {
                socket.setSoTimeout(5000);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeShort(query.length);
                output.write(query);
                output.flush();
                DataInputStream input = new DataInputStream(socket.getInputStream());
                return input.readNBytes(input.readUnsignedShort());
            }
        }
        try (DatagramSocket socket = new DatagramSocket(0, LOOPBACK)) {
            socket.setSoTimeout(5000);
            socket.send(new DatagramPacket(query, query.length, LOOPBACK, port));
            DatagramPacket reply = new DatagramPacket(new byte[4096], 4096);
            socket.receive(reply);
            return Arrays.copyOf(reply.getData(), reply.getLength());
        }
    }

    private static void close(Vertx vertx, EmbeddedDnsServer.Listeners listeners) throws Exception {
        try {
            if (listeners != null) {
                listeners.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static class RelayDns extends EmbeddedDnsServer {
        private final int upstreamPort;
        private final AtomicInteger forwards = new AtomicInteger();

        RelayDns(boolean managedVpc, List<String> authorities, int upstreamPort) {
            super(List.of(), List.of(), List.of(), List.of(address -> managedVpc
                    ? Optional.of(new DnsClientVpcSource.ClientVpc("111122223333", "us-east-1", "vpc-peer"))
                    : Optional.empty()), true, List.of(), authorities, UPSTREAM);
            this.upstreamPort = upstreamPort;
        }

        @Override
        Future<byte[]> forwardAsync(Vertx vertx, byte[] query, boolean tcp) {
            forwards.incrementAndGet();
            List<DnsForwardingRule.Target> targets = List.of(new DnsForwardingRule.Target("127.0.0.1", upstreamPort));
            return vertx.executeBlocking(() -> tcp ? forwardToTargetsTcp(query, targets)
                    : forwardToTargets(query, targets), false);
        }
    }
}

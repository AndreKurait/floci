package io.floci.aws.core.common.dns;

import io.floci.aws.testing.ParallelLocks;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(ParallelLocks.PORTS)
class EmbeddedDnsTcpTest {

    private static final String ADDRESS = "127.0.0.1";
    private Vertx vertx;

    @BeforeEach
    void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void fragmentedAndPipelinedQueriesReuseTheConnection() throws Exception {
        int port = start(new EmbeddedDnsServer(List.of()));
        try (Socket client = connect(port)) {
            byte[] first = framed(query("bucket.localhost.floci.io", 1));
            byte[] second = framed(query("other.localhost.floci.io", 2));
            client.getOutputStream().write(first, 0, 1);
            client.getOutputStream().flush();
            client.getOutputStream().write(first, 1, first.length - 1);
            client.getOutputStream().write(second);
            client.getOutputStream().flush();

            byte[] one = readResponse(client);
            byte[] two = readResponse(client);
            assertEquals(Set.of((short) 1, (short) 2),
                    Set.of(ByteBuffer.wrap(one).getShort(), ByteBuffer.wrap(two).getShort()));
            assertAnswer(one, 1, ADDRESS);
            assertAnswer(two, 1, ADDRESS);

            byte[] request = query("again.localhost.floci.io", 3);
            client.getOutputStream().write(framed(request));
            byte[] third = readResponse(client);
            assertEquals((short) 3, ByteBuffer.wrap(third).getShort());
            assertAnswer(third, 1, ADDRESS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tcpReturnsTheCompleteRecordSetWhenUdpTruncates(boolean authoritative) throws Exception {
        List<String> addresses = IntStream.rangeClosed(1, 80).mapToObj(i -> "192.0.2." + i).toList();
        DnsAnswer records = DnsAnswer.records(addresses, 60);
        DnsAnswer answer = authoritative
                ? records.withAuthority("example.test",
                        new DnsRecord.Soa("ns.example.test hostmaster.example.test 1 3600 600 86400 60"), 60)
                : records;
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of(), List.of(name -> Optional.of(answer)));
        int port = start(dns);
        byte[] request = query("service.example.test", 11);
        try (DatagramSocket udp = new DatagramSocket(0, InetAddress.getByName(ADDRESS));
             Socket tcp = connect(port)) {
            udp.setSoTimeout(5000);
            udp.send(new DatagramPacket(request, request.length, InetAddress.getByName(ADDRESS), port));
            DatagramPacket response = new DatagramPacket(new byte[4096], 4096);
            udp.receive(response);
            assertEquals(0x0200, ByteBuffer.wrap(response.getData()).getShort(2) & 0x0200);
            assertTrue(response.getLength() <= 512);

            tcp.getOutputStream().write(framed(request));
            byte[] complete = readResponse(tcp);
            assertTrue(complete.length > 512);
            assertEquals(0, ByteBuffer.wrap(complete).getShort(2) & 0x0200);
            assertEquals(authoritative ? 0x0400 : 0, ByteBuffer.wrap(complete).getShort(2) & 0x0400);
            assertAnswer(complete, 80, "192.0.2.80");
        }
    }

    @Test
    void forwardingRuleUsesTcpAndValidatesTheUpstreamAnswer() throws Exception {
        byte[] request = query("db.corp.internal", 21);
        EmbeddedDnsServer formatter = new EmbeddedDnsServer(List.of());
        byte[] expected = formatter.buildAResponse(request, (short) 21, 12, request.length,
                DnsAnswer.records(List.of("192.0.2.42"), 60));
        try (ServerSocket target = new ServerSocket(0, 1, InetAddress.getByName(ADDRESS))) {
            target.setSoTimeout(5000);
            CompletableFuture<byte[]> observed = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try (Socket socket = target.accept()) {
                    socket.setSoTimeout(5000);
                    byte[] received = readResponse(socket);
                    byte[] wrongId = expected.clone();
                    wrongId[1]++;
                    socket.getOutputStream().write(framed(wrongId));
                    socket.getOutputStream().write(framed(expected));
                    observed.complete(received);
                } catch (Exception error) {
                    observed.completeExceptionally(error);
                }
            });
            int port = start(forwarding(target.getLocalPort()));
            try (Socket client = connect(port)) {
                client.getOutputStream().write(framed(request));
                assertArrayEquals(expected, readResponse(client));
                assertArrayEquals(request, observed.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void failedForwardingDoesNotBreakOtherQueriesOnTheConnection() throws Exception {
        int unused;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getByName(ADDRESS))) {
            unused = reserved.getLocalPort();
        }
        int port = start(forwarding(unused));
        try (Socket client = connect(port)) {
            client.getOutputStream().write(framed(query("db.corp.internal", 31)));
            assertEquals(2, ByteBuffer.wrap(readResponse(client)).getShort(2) & 0x000f);
            client.getOutputStream().write(framed(query("bucket.localhost.floci.io", 32)));
            assertAnswer(readResponse(client), 1, ADDRESS);
        }
    }

    @Test
    void invalidMessageLengthClosesOnlyThatConnection() throws Exception {
        int port = start(new EmbeddedDnsServer(List.of()));
        try (Socket invalid = connect(port)) {
            invalid.getOutputStream().write(new byte[]{0, 0});
            assertEquals(-1, invalid.getInputStream().read());
        }
        try (Socket valid = connect(port)) {
            valid.getOutputStream().write(framed(query("bucket.localhost.floci.io", 41)));
            assertAnswer(readResponse(valid), 1, ADDRESS);
        }
    }

    @ParameterizedTest
    @CsvSource({"positive,0,1,true", "empty,0,0,true", "missing,3,0,true", "refused,5,0,false"})
    void reservedAuthorityKeepsItsFlagsOverTcp(String kind, int rcode, int answers,
                                               boolean authoritative) throws Exception {
        DnsAnswer answer = switch (kind) {
            case "positive" -> DnsAnswer.records(List.of("192.0.2.19"), 19);
            case "empty" -> DnsAnswer.noData();
            case "refused" -> DnsAnswer.refused();
            default -> DnsAnswer.nxDomain();
        };
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of("example.test"),
                List.of(name -> Optional.of(answer)), List.of(), List.of(), List.of("owned.example.test"));
        try (Socket client = connect(start(dns))) {
            client.getOutputStream().write(framed(query("name.owned.example.test", 61)));
            ByteBuffer response = ByteBuffer.wrap(readResponse(client));
            assertEquals(rcode, response.getShort(2) & 15);
            assertEquals(authoritative, (response.getShort(2) & 0x0400) != 0);
            assertEquals(0, response.getShort(2) & 0x0080);
            assertEquals(answers, response.getShort(6));
        }
    }

    @Test
    void reservedAuthorityRefusesAnUnsupportedClassOverTcp() throws Exception {
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of("example.test"),
                List.of(), List.of(), List.of(), List.of("owned.example.test"));
        try (Socket client = connect(start(dns))) {
            byte[] request = query("missing.owned.example.test", 62);
            request[request.length - 1] = 3;
            client.getOutputStream().write(framed(request));
            ByteBuffer response = ByteBuffer.wrap(readResponse(client));
            assertEquals(5, response.getShort(2) & 15);
            assertFalse((response.getShort(2) & 0x0400) != 0);
            assertEquals(0, response.getShort(2) & 0x0080);
        }
    }

    @Test
    void pendingQueriesAreBoundedAndAnotherConnectionRemainsUsable() throws Exception {
        AtomicBoolean stalled = new AtomicBoolean(true);
        AtomicInteger admitted = new AtomicInteger();
        List<Promise<byte[]>> pending = new ArrayList<>();
        byte[] request = query("bucket.localhost.floci.io", 51);
        byte[] answer = new EmbeddedDnsServer(List.of()).buildAResponse(request, (short) 51, 12, request.length,
                DnsAnswer.records(List.of(ADDRESS), 60));
        NetServer server = DnsTcpListener.listen(vertx, ADDRESS, 0, (query, sender) -> {
            if (!stalled.get()) {
                return Future.succeededFuture(answer);
            }
            admitted.incrementAndGet();
            Promise<byte[]> promise = Promise.promise();
            pending.add(promise);
            return promise.future();
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try (Socket client = connect(server.actualPort())) {
            DataOutputStream output = new DataOutputStream(client.getOutputStream());
            for (int i = 0; i <= DnsTcpListener.MAX_PENDING_QUERIES; i++) {
                output.write(framed(request));
            }
            output.flush();
            assertEquals(-1, client.getInputStream().read());
            assertEquals(DnsTcpListener.MAX_PENDING_QUERIES, admitted.get());
        } finally {
            stalled.set(false);
            vertx.runOnContext(ignored -> pending.forEach(promise -> promise.complete(answer)));
        }
        try (Socket healthy = connect(server.actualPort())) {
            healthy.getOutputStream().write(framed(request));
            assertArrayEquals(answer, readResponse(healthy));
        }
    }

    @Test
    void excessConnectionsAreClosedWhileAdmittedConnectionsRemainUsable() throws Exception {
        int port = start(new EmbeddedDnsServer(List.of()));
        List<Socket> admitted = new ArrayList<>();
        byte[] request = framed(query("bucket.localhost.floci.io", 71));
        try {
            for (int i = 0; i < DnsTcpListener.MAX_CONNECTIONS; i++) {
                Socket client = connect(port);
                admitted.add(client);
                client.getOutputStream().write(request);
                assertAnswer(readResponse(client), 1, ADDRESS);
            }
            try (Socket excess = connect(port)) {
                assertEquals(-1, excess.getInputStream().read());
            }
            Socket first = admitted.getFirst();
            first.getOutputStream().write(request);
            assertAnswer(readResponse(first), 1, ADDRESS);
        } finally {
            for (Socket client : admitted) {
                client.close();
            }
        }
    }

    @Test
    void failureToBindUdpRetiresTheTcpListener() throws Exception {
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of());
        try (DatagramSocket occupied = new DatagramSocket(0, InetAddress.getByName(ADDRESS))) {
            int port = occupied.getLocalPort();
            assertThrows(ExecutionException.class, () -> dns.listenAll(vertx, ADDRESS, port)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(dns.getServerIp().isEmpty());
            try (ServerSocket reclaimed = new ServerSocket(port, 1, InetAddress.getByName(ADDRESS))) {
                assertEquals(port, reclaimed.getLocalPort());
            }
        }
    }

    private int start(EmbeddedDnsServer dns) throws Exception {
        return dns.listenAll(vertx, ADDRESS, 0).toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).tcp().actualPort();
    }

    private static EmbeddedDnsServer forwarding(int targetPort) {
        DnsForwardingRule rule = DnsForwardingRule.forwardTo("corp.internal",
                List.of(new DnsForwardingRule.Target(ADDRESS, targetPort)));
        return new EmbeddedDnsServer(List.of(), List.of(), List.of((account, region, vpc) -> List.of(rule)),
                List.of(client -> Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-dns"))));
    }

    private static Socket connect(int port) throws Exception {
        Socket socket = new Socket(ADDRESS, port);
        socket.setSoTimeout(5000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static byte[] query(String name, int id) {
        ByteBuffer query = ByteBuffer.allocate(512);
        query.putShort((short) id).putShort((short) 0x0100).putShort((short) 1)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            query.put((byte) bytes.length).put(bytes);
        }
        query.put((byte) 0).putShort((short) 1).putShort((short) 1);
        return Arrays.copyOf(query.array(), query.position());
    }

    private static byte[] framed(byte[] bytes) {
        return ByteBuffer.allocate(bytes.length + 2).putShort((short) bytes.length).put(bytes).array();
    }

    private static byte[] readResponse(Socket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int length = input.readUnsignedShort();
        byte[] response = new byte[length];
        input.readFully(response);
        return response;
    }

    private static void assertAnswer(byte[] response, int count, String lastAddress) throws Exception {
        assertEquals(0, ByteBuffer.wrap(response).getShort(2) & 0x000f);
        assertEquals(count, ByteBuffer.wrap(response).getShort(6));
        assertArrayEquals(InetAddress.getByName(lastAddress).getAddress(),
                Arrays.copyOfRange(response, response.length - 4, response.length));
    }
}

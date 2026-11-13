package io.github.hectorvent.floci.core.common.dns;

// The Vert.x DatagramSocket is the server; the JDK socket is qualified at the client boundary.
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.LINUX)
class EmbeddedDnsListenerTest {

    @Test
    void connectedClientsReceiveRepliesFromEachAdvertisedAddress() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            for (String address : List.of("127.0.0.2", "127.0.0.3")) {
                EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of());
                DatagramSocket server = dns.listen(vertx, address, 0)
                        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                try (java.net.DatagramSocket client =
                             new java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
                    InetAddress queriedAddress = InetAddress.getByName(address);
                    client.connect(queriedAddress, server.localAddress().port());
                    client.setSoTimeout(5000);
                    byte[] request = query();
                    client.send(new DatagramPacket(request, request.length));
                    DatagramPacket response = new DatagramPacket(new byte[512], 512);
                    client.receive(response);

                    assertEquals(queriedAddress, response.getAddress());
                    assertEquals(server.localAddress().port(), response.getPort());
                    ByteBuffer packet = ByteBuffer.wrap(response.getData(), 0, response.getLength());
                    assertEquals((short) 0x5130, packet.getShort(0));
                    assertEquals(0x8000, packet.getShort(2) & 0x8000);
                    assertEquals(0, packet.getShort(2) & 0x000f);
                    assertEquals(1, packet.getShort(6));
                    assertArrayEquals(queriedAddress.getAddress(), Arrays.copyOfRange(response.getData(),
                            response.getLength() - 4, response.getLength()));
                    assertEquals(address, dns.getServerIp().orElseThrow());
                } finally {
                    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedBindDoesNotAdvertiseAnUnavailableResolver() throws Exception {
        Vertx vertx = Vertx.vertx();
        try (java.net.DatagramSocket occupied =
                     new java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.2"))) {
            EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of());

            assertThrows(ExecutionException.class, () -> dns.listen(vertx, "127.0.0.2", occupied.getLocalPort())
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(dns.getServerIp().isEmpty());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static byte[] query() {
        ByteBuffer query = ByteBuffer.allocate(512);
        query.putShort((short) 0x5130).putShort((short) 0x0100).putShort((short) 1)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String label : "bucket.localhost.floci.io".split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            query.put((byte) bytes.length).put(bytes);
        }
        query.put((byte) 0).putShort((short) 1).putShort((short) 1);
        return Arrays.copyOf(query.array(), query.position());
    }
}

package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddedDnsHostStartupTest {
    private static final String ADDRESS = "127.0.0.1";

    @Test
    void hostOptInStartsBothListenersAndReleasesTheirSockets() throws Exception {
        Vertx vertx = Vertx.vertx();
        EmbeddedDnsServer server = null;
        try {
            server = start(vertx, Optional.of(ADDRESS), 0);
            int port = server.listenerPort();
            assertEquals(ADDRESS, server.getServerIp().orElseThrow());
            byte[] query = query();
            byte[] udp;
            try (DatagramSocket client = new DatagramSocket()) {
                client.connect(InetAddress.ofLiteral(ADDRESS), port);
                client.setSoTimeout(5000);
                client.send(new DatagramPacket(query, query.length));
                DatagramPacket reply = new DatagramPacket(new byte[512], 512);
                client.receive(reply);
                assertEquals(InetAddress.ofLiteral(ADDRESS), reply.getAddress());
                udp = Arrays.copyOf(reply.getData(), reply.getLength());
            }
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(ADDRESS, port), 5000);
                client.setSoTimeout(5000);
                DataOutputStream output = new DataOutputStream(client.getOutputStream());
                output.writeShort(query.length);
                output.write(query);
                output.flush();
                DataInputStream input = new DataInputStream(client.getInputStream());
                byte[] tcp = input.readNBytes(input.readUnsignedShort());
                assertArrayEquals(udp, tcp);
            }
            assertEquals(0, udp[3] & 15);
            assertEquals(1, ByteBuffer.wrap(udp).getShort(6));
            assertArrayEquals(InetAddress.ofLiteral(ADDRESS).getAddress(),
                    Arrays.copyOfRange(udp, udp.length - 4, udp.length));
            server.stop();
            assertTrue(server.getServerIp().isEmpty());
            await().atMost(Duration.ofSeconds(5)).ignoreException(BindException.class).untilAsserted(() -> {
                try (ServerSocket tcp = bindTcp(port);
                     DatagramSocket rebound = new DatagramSocket(port, InetAddress.ofLiteral(ADDRESS))) {
                    assertEquals(port, tcp.getLocalPort());
                    assertEquals(port, rebound.getLocalPort());
                }
            });
        } finally {
            if (server != null) {
                server.stop();
            }
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void hostWithoutAnExplicitListenerDoesNotStartDns() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            EmbeddedDnsServer server = start(vertx, Optional.empty(), 0);
            assertTrue(server.getServerIp().isEmpty());
            assertEquals(-1, server.listenerPort());
            server.stop();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void occupiedUdpPortFailsExplicitStartupAndReleasesTheTcpListener() throws Exception {
        Vertx vertx = Vertx.vertx();
        try (DatagramSocket occupied = new DatagramSocket(0, InetAddress.ofLiteral(ADDRESS))) {
            int port = occupied.getLocalPort();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> start(vertx, Optional.of(ADDRESS), port));
            assertTrue(failure.getMessage().contains("floci.dns.listen-address=" + ADDRESS));
            try (ServerSocket tcp = bindTcp(port)) {
                assertEquals(port, tcp.getLocalPort());
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void occupiedTcpPortFailsExplicitStartup() throws Exception {
        Vertx vertx = Vertx.vertx();
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.ofLiteral(ADDRESS))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> start(vertx, Optional.of(ADDRESS), occupied.getLocalPort()));
            assertTrue(failure.getMessage().contains("floci.dns.listen-address=" + ADDRESS));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private EmbeddedDnsServer start(Vertx vertx, Optional<String> address, int port) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.dns().listenAddress()).thenReturn(address);
        when(config.dns().containerFallbackServers()).thenReturn(List.of());
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(false);
        return new EmbeddedDnsServer(config, detector, vertx, List.of(), List.of(), List.of(),
                mock(CurrentContainerNetworkResolver.class), port);
    }

    private ServerSocket bindTcp(int port) throws Exception {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(ADDRESS, port));
            return socket;
        } catch (Exception failure) {
            socket.close();
            throw failure;
        }
    }

    private byte[] query() {
        ByteBuffer packet = ByteBuffer.allocate(512);
        packet.putShort((short) 0x5130).putShort((short) 0x0100).putShort((short) 1)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String label : "bucket.localhost.floci.io".split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            packet.put((byte) bytes.length).put(bytes);
        }
        packet.put((byte) 0).putShort((short) 1).putShort((short) 1);
        return Arrays.copyOf(packet.array(), packet.position());
    }
}

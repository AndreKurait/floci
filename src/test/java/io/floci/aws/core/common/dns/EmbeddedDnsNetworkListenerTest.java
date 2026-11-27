package io.floci.aws.core.common.dns;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.aws.core.common.docker.DockerBridgeAddress;
import io.floci.aws.testing.ParallelLocks;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ResourceLock(ParallelLocks.PORTS)
class EmbeddedDnsNetworkListenerTest {
    @Test
    void additionalAddressServesBothTransportsAndRetainsThePrimary() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.dns().listenAddress()).thenReturn(Optional.of("127.0.0.2"));
        Vertx vertx = Vertx.vertx();
        EmbeddedDnsServer server = new EmbeddedDnsServer(config, mock(ContainerDetector.class), vertx,
                List.of(), List.of(), List.of(), mock(CurrentContainerNetworkResolver.class),
                mock(DockerBridgeAddress.class), 0);
        try {
            String primary = server.getServerIp().orElseThrow();
            assertEquals("127.0.0.1", server.resolverOnLocalAddress("127.0.0.1"));
            assertEquals("127.0.0.1", server.resolverOnLocalAddress("127.0.0.1"));
            assertEquals(primary, server.getServerIp().orElseThrow());
            byte[] query = query();
            byte[] response;
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(2000);
                socket.send(new DatagramPacket(query, query.length,
                        InetAddress.ofLiteral("127.0.0.1"), server.listenerPort()));
                DatagramPacket received = new DatagramPacket(new byte[4096], 4096);
                socket.receive(received);
                response = Arrays.copyOf(received.getData(), received.getLength());
            }
            assertAnswer(response);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.listenerPort()), 2000);
                socket.setSoTimeout(2000);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeShort(query.length);
                output.write(query);
                output.flush();
                DataInputStream input = new DataInputStream(socket.getInputStream());
                assertAnswer(input.readNBytes(input.readUnsignedShort()));
            }
            assertThrows(IllegalStateException.class, () -> server.resolverOnLocalAddress("192.0.2.123"));
            assertEquals(primary, server.getServerIp().orElseThrow());
        } finally {
            server.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        assertEquals(Optional.empty(), server.getServerIp());
    }

    private static void assertAnswer(byte[] response) {
        assertEquals(0, response[3] & 15);
        assertEquals(1, response[7]);
        assertArrayEquals(new byte[]{127, 0, 0, 1}, Arrays.copyOfRange(response,
                response.length - 4, response.length));
    }

    private static byte[] query() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeShort(42);
        output.writeShort(256);
        output.writeShort(1);
        output.writeShort(0);
        output.writeShort(0);
        output.writeShort(0);
        for (String label : "probe.localhost.floci.io".split("\\.")) {
            output.writeByte(label.length());
            output.writeBytes(label);
        }
        output.writeByte(0);
        output.writeShort(1);
        output.writeShort(1);
        return bytes.toByteArray();
    }
}

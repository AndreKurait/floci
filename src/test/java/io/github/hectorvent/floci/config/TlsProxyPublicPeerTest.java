package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.testutil.FreePorts;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(20)
class TlsProxyPublicPeerTest {
    private Vertx vertx;
    private TlsProxyServer proxy;
    private int port;
    private volatile int expectedLength = PAYLOAD.length;
    private final PublicApiProxySources sources = new PublicApiProxySources();
    private final Object owner = new Object();
    private final BlockingQueue<Observed> received = new LinkedBlockingQueue<>();
    private static final byte[] PAYLOAD = {0x16, 3, 3, 0, 6, 0, 1, (byte) 255, 4, 5, 6};

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        NetServer backend = vertx.createNetServer(new NetServerOptions().setUseProxyProtocol(true))
                .connectHandler(socket -> {
                    Buffer payload = Buffer.buffer();
                    socket.handler(bytes -> {
                        payload.appendBuffer(bytes);
                        if (payload.length() >= expectedLength) {
                            received.add(new Observed(socket.remoteAddress().hostAddress(),
                                    socket.remoteAddress().port(), payload.getBytes()));
                            socket.write(payload).onSuccess(ignored -> socket.close());
                        }
                    });
                }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        port = FreePorts.anyInterfacePort();
        when(config.port()).thenReturn(port);
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().awsHttpsPort()).thenReturn(port);
        proxy = new TlsProxyServer(vertx, config, "127.0.0.1", backend.actualPort(), backend.actualPort(), sources);
        sources.register(owner, "127.0.0.1", Set.of("172.18.1.10", "172.18.1.11"));
    }

    @AfterEach
    void stop() throws Exception {
        if (proxy != null) {
            proxy.stop();
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void fragmentedPrefacesKeepDistinctCallersAndExactBinaryTraffic() throws Exception {
        for (String caller : Set.of("172.18.1.10", "172.18.1.11")) {
            try (Socket socket = connect()) {
                byte[] preface = preface(caller).getBytes(StandardCharsets.US_ASCII);
                for (byte value : preface) {
                    socket.getOutputStream().write(value);
                    socket.getOutputStream().flush();
                }
                socket.getOutputStream().write(PAYLOAD);
                socket.getOutputStream().flush();
                Observed observation = received.poll(5, TimeUnit.SECONDS);
                assertNotNull(observation);
                assertEquals(caller, observation.address());
                assertEquals(49152, observation.port());
                assertArrayEquals(PAYLOAD, observation.payload());
                assertArrayEquals(PAYLOAD, socket.getInputStream().readNBytes(PAYLOAD.length));
            }
        }
    }

    @Test
    void unknownCallerCannotReachTheBackend() throws Exception {
        try (Socket socket = connect()) {
            socket.getOutputStream().write((preface("172.18.1.99")).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    void excessivePrefaceIsClosedWithoutBackendTraffic() throws Exception {
        try (Socket socket = connect()) {
            socket.getOutputStream().write(("PROXY " + "x".repeat(110)).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    void directUntrustedPeerCannotReplaceItsSocketIdentity() throws Exception {
        sources.revoke(owner);
        byte[] forged = preface("172.18.1.10").getBytes(StandardCharsets.US_ASCII);
        expectedLength = forged.length;
        try (Socket socket = connect()) {
            socket.getOutputStream().write(forged);
            socket.getOutputStream().flush();
            Observed observation = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(observation);
            assertEquals("127.0.0.1", observation.address());
            assertEquals(socket.getLocalPort(), observation.port());
            assertTrue(Arrays.equals(forged, observation.payload()),
                    "an untrusted claim remains payload and cannot become transport identity");
        }
    }

    @Test
    void anIncompleteTrustedPrefaceTimesOutWithoutBackendTraffic() throws Exception {
        try (Socket socket = connect()) {
            socket.setSoTimeout(8000);
            long start = System.nanoTime();
            socket.getOutputStream().write("PROXY TCP4 ".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 4500);
            assertTrue(received.isEmpty());
        }
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static String preface(String address) {
        return "PROXY TCP4 " + address + " 192.0.2.1 49152 443\r\n";
    }

    private record Observed(String address, int port, byte[] payload) {}
}

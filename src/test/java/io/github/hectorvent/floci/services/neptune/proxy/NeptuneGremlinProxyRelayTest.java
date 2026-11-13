package io.github.hectorvent.floci.services.neptune.proxy;

import io.github.hectorvent.floci.services.elasticache.proxy.TimelineRelayJoin;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NeptuneGremlinProxyRelayTest {

    private static final Duration ONE_NANO = Duration.ofNanos(1);
    private static final Duration RELAY_JOIN_TIMEOUT =
            Duration.ofMillis(NeptuneGremlinProxy.RELAY_JOIN_TIMEOUT_MILLIS);

    @Test
    void closingBackendReleasesClientConnection() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket freePort = new ServerSocket(0)) {
            int proxyPort = freePort.getLocalPort();
            NeptuneGremlinProxy proxy = new NeptuneGremlinProxy(
                    "cluster", "127.0.0.1", backendServer.getLocalPort());
            freePort.close();
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                try (Socket backend = backendServer.accept()) {
                    backend.close();
                }

                client.setSoTimeout(2_000);
                assertEquals(-1, client.getInputStream().read());
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void clientHalfCloseStillAllowsBackendResponse() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket freePort = new ServerSocket(0)) {
            int proxyPort = freePort.getLocalPort();
            NeptuneGremlinProxy proxy = new NeptuneGremlinProxy(
                    "cluster", "127.0.0.1", backendServer.getLocalPort());
            freePort.close();
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort);
                 Socket backend = backendServer.accept()) {
                client.setSoTimeout(2_000);
                backend.setSoTimeout(2_000);
                client.getOutputStream().write("request".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                client.shutdownOutput();

                while (backend.getInputStream().read() != -1) {
                    // Drain the request before asserting that the half-close reached the backend.
                }
                backend.getOutputStream().write("response".getBytes(StandardCharsets.US_ASCII));
                backend.getOutputStream().flush();

                byte[] response = client.getInputStream().readNBytes(8);
                assertEquals("response", new String(response, StandardCharsets.US_ASCII));
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void clientHalfCloseClosesRelayWhenBackendStaysOpenAfterResponse() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket freePort = new ServerSocket(0)) {
            int proxyPort = freePort.getLocalPort();
            NeptuneGremlinProxy proxy = new NeptuneGremlinProxy(
                    "cluster", "127.0.0.1", backendServer.getLocalPort());
            freePort.close();
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort);
                 Socket backend = backendServer.accept()) {
                client.setSoTimeout(3_000);
                client.shutdownOutput();
                while (backend.getInputStream().read() != -1) {
                    // Drain until the client half-close reaches the backend.
                }
                backend.getOutputStream().write("response".getBytes(StandardCharsets.US_ASCII));
                backend.getOutputStream().flush();

                assertEquals("response", new String(client.getInputStream().readNBytes(8),
                        StandardCharsets.US_ASCII));
                assertEquals(-1, client.getInputStream().read());
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void aRelayWhoseBackendStaysOpenIsClosedWhenTheJoinTimeoutEnds() throws Exception {
        TestTime time = TestTime.create();
        TimelineRelayJoin joins = new TimelineRelayJoin(time);
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket freePort = new ServerSocket(0)) {
            int proxyPort = freePort.getLocalPort();
            NeptuneGremlinProxy proxy = proxyJoiningOn(joins, backendServer.getLocalPort());
            freePort.close();
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort);
                 Socket backend = backendServer.accept()) {
                client.setSoTimeout(30_000);
                client.shutdownOutput();
                while (backend.getInputStream().read() != -1) {
                    // Drain until the client half-close reaches the backend.
                }

                assertEquals(RELAY_JOIN_TIMEOUT, joins.awaitJoin(), "the client direction, which has ended");
                assertEquals(RELAY_JOIN_TIMEOUT, joins.awaitJoin(), "the backend direction, which is still open");

                time.advance(RELAY_JOIN_TIMEOUT.minus(ONE_NANO));
                assertEquals(1, joins.waiting(), "the proxy is still waiting for the backend direction");
                backend.getOutputStream().write("response".getBytes(StandardCharsets.US_ASCII));
                backend.getOutputStream().flush();
                assertEquals("response", new String(client.getInputStream().readNBytes(8),
                        StandardCharsets.US_ASCII));

                time.advance(ONE_NANO);
                assertEquals(-1, client.getInputStream().read());
                assertEquals(2, joins.started());
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void keepsHealthyIdleConnectionOpenBeyondRelayShutdownDeadline() throws Exception {
        TestTime time = TestTime.create();
        TimelineRelayJoin joins = new TimelineRelayJoin(time);
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket freePort = new ServerSocket(0)) {
            int proxyPort = freePort.getLocalPort();
            NeptuneGremlinProxy proxy = proxyJoiningOn(joins, backendServer.getLocalPort());
            freePort.close();
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.setSoTimeout(5_000);
                byte[] request = "PING".getBytes(StandardCharsets.US_ASCII);
                client.getOutputStream().write(request);
                client.getOutputStream().flush();

                try (Socket backend = backendServer.accept()) {
                    backend.setSoTimeout(5_000);
                    assertEquals("PING", new String(backend.getInputStream().readNBytes(request.length),
                            StandardCharsets.US_ASCII));
                    time.advance(RELAY_JOIN_TIMEOUT.multipliedBy(2).plusMillis(200));

                    backend.getOutputStream().write("PONG".getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().flush();
                    assertEquals("PONG", new String(client.getInputStream().readNBytes(4),
                            StandardCharsets.US_ASCII));
                    assertEquals(0, joins.started(),
                            "neither direction ended, so the proxy never started its shutdown wait");
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void bindsWithABurstTolerantAcceptBacklog() throws Exception {
        AtomicInteger acceptBacklog = new AtomicInteger();
        NeptuneGremlinProxy proxy = new NeptuneGremlinProxy("cluster", "127.0.0.1", 1) {
            @Override
            ServerSocket listenSocket(int proxyPort, int backlog) throws IOException {
                acceptBacklog.set(backlog);
                return super.listenSocket(proxyPort, backlog);
            }
        };
        proxy.start(0);
        try {
            assertEquals(4096, acceptBacklog.get(),
                    "a connection burst must not overflow the proxy's accept queue");
        } finally {
            proxy.stop();
        }
    }

    private static NeptuneGremlinProxy proxyJoiningOn(TimelineRelayJoin joins, int backendPort) {
        return new NeptuneGremlinProxy("cluster", "127.0.0.1", backendPort) {
            @Override
            void joinRelay(Thread relay, long timeoutMillis) throws InterruptedException {
                joins.join(relay, timeoutMillis);
            }
        };
    }
}

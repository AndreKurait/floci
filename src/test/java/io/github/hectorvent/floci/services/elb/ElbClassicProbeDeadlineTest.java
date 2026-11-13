package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.testing.TestTime;
import io.github.hectorvent.floci.testing.TimelineDeadlineWaits;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ElbClassicProbeDeadlineTest {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);

    @Test
    void complete200ResponseSucceeds() throws Exception {
        completedResponse(200, true);
    }

    @Test
    void complete500ResponseFails() throws Exception {
        completedResponse(500, false);
    }

    @Test
    void slowHeaderExpiresAndClosesTheProbeSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch responseStarted = new CountDownLatch(1);
            CountDownLatch finishResponse = new CountDownLatch(1);
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader request = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    while (!request.readLine().isEmpty()) {
                        // Consume the complete request before observing cancellation EOF.
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nX-Progress: ".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    responseStarted.countDown();
                    // A paced peer is the behavior under test, not an arbitrary readiness delay.
                    while (!finishResponse.await(100, TimeUnit.MILLISECONDS)) {
                        try {
                            out.write('a');
                            out.flush();
                        } catch (SocketException expected) {
                            // The deadline closes the client while the peer is still sending.
                            break;
                        }
                    }
                    assertEquals(-1, socket.getInputStream().read(),
                            "Expiry must close the underlying socket, not just abandon a future");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, executor);
            TestTime time = TestTime.create();
            TimelineDeadlineWaits waits = new TimelineDeadlineWaits(time);
            ElbClassicHealthChecker checker = new ElbClassicHealthChecker(null, null, null, null, waits);
            CompletableFuture<Boolean> probe = CompletableFuture.supplyAsync(() -> {
                try {
                    return probe(checker, server.getLocalPort());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, executor);
            try {
                assertTrue(responseStarted.await(5, TimeUnit.SECONDS));
                waits.awaitWaiting(1);
                time.advance(PROBE_TIMEOUT.minusNanos(1));
                assertFalse(probe.isDone(), "The probe has its whole one-second timeout");
                time.advance(Duration.ofNanos(1));
                assertFalse(assertDoesNotThrow(() -> probe.get(5, TimeUnit.SECONDS)),
                        "Continuous response progress must not extend the one-second deadline");
                finishResponse.countDown();
                peer.get(5, TimeUnit.SECONDS);
            } finally {
                finishResponse.countDown();
            }
        }
    }

    private void completedResponse(int status, boolean expected) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    socket.getInputStream().read();
                    socket.getOutputStream().write(("HTTP/1.1 " + status
                            + " Result\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, executor);
            assertEquals(expected, probe(new ElbClassicHealthChecker(null, null, null), server.getLocalPort()));
            peer.get(5, TimeUnit.SECONDS);
        }
    }

    private static boolean probe(ElbClassicHealthChecker checker, int port) throws Exception {
        Method method = ElbClassicHealthChecker.class.getDeclaredMethod("probe", String.class,
                ElbClassicHealthChecker.Target.class, int.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(checker, "127.0.0.1",
                new ElbClassicHealthChecker.Target("http", port, "/health"), (int) PROBE_TIMEOUT.toSeconds());
    }
}

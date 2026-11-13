package io.github.hectorvent.floci.services.ecr.registry;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryHttpClientReadinessTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stalledProbeHonorsItsDeadlineAndCancellation(boolean interrupt) throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> caller = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/v2/", exchange -> {
                received.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(200, -1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException expected) {
                    // The timed-out or cancelled client may have closed its connection.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                RegistryHttpClient client = new RegistryHttpClient("http://127.0.0.1:" + server.getAddress().getPort());
                Future<Probe> result = executor.submit(() -> {
                    caller.set(Thread.currentThread());
                    boolean ready = client.ping(Duration.ofSeconds(interrupt ? 5 : 1));
                    return new Probe(ready, Thread.currentThread().isInterrupted());
                });
                assertTrue(received.await(5, TimeUnit.SECONDS));
                if (interrupt) {
                    caller.get().interrupt();
                }
                Probe probe = result.get(3, TimeUnit.SECONDS);
                assertFalse(probe.ready());
                assertEquals(interrupt, probe.interrupted());
            } finally {
                release.countDown();
                server.stop(0);
            }
        } finally {
            server.stop(0);
        }
    }

    private record Probe(boolean ready, boolean interrupted) {}
}

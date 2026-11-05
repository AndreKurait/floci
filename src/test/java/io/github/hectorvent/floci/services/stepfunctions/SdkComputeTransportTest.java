package io.github.hectorvent.floci.services.stepfunctions;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkComputeTransportTest {
    @Test
    void completeResponseRetainsBodyAndStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] expected = "{\"message\":\"denied\"}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(403, expected.length);
                exchange.getResponseBody().write(expected);
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> response = SdkComputeTaskInvoker.send(client, request(server), Duration.ofSeconds(3));
            assertEquals(403, response.statusCode());
            assertArrayEquals(expected, response.body());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void headersDoNotEndTheCompleteBodyDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                headers.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient();
             ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> request = reader.submit(() -> assertThrows(TimeoutException.class,
                    () -> SdkComputeTaskInvoker.send(client, request(server), Duration.ofSeconds(1))));
            try {
                assertTrue(headers.await(3, TimeUnit.SECONDS), "Server never sent response headers");
                request.get(3, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseSizeIsBoundedBeforeAccumulatingTheWholeBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 9 * 1024 * 1024);
                exchange.getResponseBody().write(new byte[9 * 1024 * 1024]);
            } catch (IOException expected) {
                // The bounded subscriber can close the connection while the server is still sending.
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> SdkComputeTaskInvoker.send(client, request(server), Duration.ofSeconds(3)));
            assertTrue(failure.getCause() instanceof IOException, failure.toString());
            assertTrue(failure.getCause().getMessage().contains("8388608"), failure.toString());
        } finally {
            server.stop(0);
        }
    }

    private static HttpRequest request(HttpServer server) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")).GET().build();
    }
}

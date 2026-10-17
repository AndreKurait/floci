package io.github.hectorvent.floci.services.bedrockruntime;

import io.github.hectorvent.floci.testing.BedrockProxyBackendProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.BedrockProxyBackendProfile.PORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a caller who disconnects closes the upstream request, whether the upstream has
 * sent nothing yet, part of a response, or one streamed token.
 *
 * <p>The backend URL and passthrough come from {@link BedrockProxyBackendProfile}, which this class
 * shares with the other proxy classes that talk to a stand-in. The {@code fixture} model id is not
 * in that profile's mapping, so it reaches the stand-in through passthrough. The profile keeps the
 * default 60-second request timeout, far longer than these tests wait, so only the disconnect can
 * close an upstream that stalls before its headers.
 */
@QuarkusTest
@TestProfile(BedrockProxyBackendProfile.class)
class BedrockProxyCancellationIntegrationTest {
    @TestHTTPResource
    URI base;

    @Test
    void disconnectClosesUpstreamWithoutAnotherToken() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(PORT)) {
            server.setSoTimeout(10000);
            Thread upstream = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    InputStream input = socket.getInputStream();
                    StringBuilder header = new StringBuilder();
                    while (!header.toString().endsWith("\r\n\r\n")) {
                        int next = input.read();
                        if (next < 0 || header.length() > 65536) {
                            return;
                        }
                        header.append((char) next);
                    }
                    int bytes = 0;
                    for (String line : header.toString().split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            bytes = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    assertEquals(bytes, input.readNBytes(bytes).length);
                    OutputStream output = socket.getOutputStream();
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    byte[] chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n".getBytes(StandardCharsets.UTF_8);
                    output.write((Integer.toHexString(chunk.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(chunk);
                    output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    // No more output: disconnect must close the transport independently of token delivery.
                    try {
                        if (input.read() == -1) {
                            closed.countDown();
                        }
                    } catch (SocketException disconnected) {
                        closed.countDown();
                    }
                } catch (Exception expected) {
                    // The latch remains unset so a backend setup failure cannot pass the assertion.
                }
            });
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpRequest request = HttpRequest.newBuilder(base.resolve("/model/fixture/converse-stream"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"synthetic\"}]}]}"))
                    .build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, response.statusCode());
                try (InputStream body = response.body()) {
                    assertTrue(body.read() >= 0);
                }
                assertTrue(closed.await(5, TimeUnit.SECONDS), "Client disconnect must close the stalled upstream");
            } finally {
                upstream.interrupt();
                upstream.join(1000);
            }
        }
    }

    @Test
    void nonStreamingDisconnectBeforeHeadersClosesUpstream() throws Exception {
        disconnectWhileUpstreamStalls("converse", false);
    }

    @Test
    void streamingDisconnectBeforeHeadersClosesUpstream() throws Exception {
        disconnectWhileUpstreamStalls("converse-stream", false);
    }

    @Test
    void nonStreamingDisconnectDuringBodyClosesUpstream() throws Exception {
        disconnectWhileUpstreamStalls("converse", true);
    }

    private void disconnectWhileUpstreamStalls(String route, boolean partialResponse) throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(PORT)) {
            server.setSoTimeout(10000);
            Thread upstream = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    InputStream input = socket.getInputStream();
                    StringBuilder header = new StringBuilder();
                    while (!header.toString().endsWith("\r\n\r\n")) {
                        int next = input.read();
                        if (next < 0 || header.length() > 65536) {
                            return;
                        }
                        header.append((char) next);
                    }
                    int bytes = 0;
                    for (String line : header.toString().split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            bytes = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    if (input.readNBytes(bytes).length != bytes) {
                        return;
                    }
                    if (partialResponse) {
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + "Content-Length: 1000\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    accepted.countDown();
                    // The upstream stalls before completing its response, so cancellation must close it.
                    try {
                        if (input.read() == -1) {
                            closed.countDown();
                        }
                    } catch (SocketException disconnected) {
                        closed.countDown();
                    }
                } catch (Exception expected) {
                    // An unset latch exposes setup and transport failures to the test thread.
                }
            });
            try (Socket frontend = new Socket(base.getHost(), base.getPort())) {
                String body = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"synthetic\"}]}]}";
                String request = "POST /model/fixture/" + route + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Authorization: AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
                frontend.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                frontend.getOutputStream().flush();
                assertTrue(accepted.await(5, TimeUnit.SECONDS), "Upstream must receive the request before disconnect");
                frontend.close();
                assertTrue(closed.await(5, TimeUnit.SECONDS), "Disconnect must close the stalled upstream request");
            } finally {
                upstream.interrupt();
                upstream.join(1000);
            }
        }
    }
}

package io.github.hectorvent.floci.services.bedrockruntime;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.bedrockruntime.backend.ProxyBackend;
import io.github.hectorvent.floci.testing.BedrockProxyBackendProfile;
import io.github.hectorvent.floci.testutil.LogCapture;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import static io.github.hectorvent.floci.testing.BedrockProxyBackendProfile.PORT;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that an upstream error reaches the caller as ModelErrorException with a bounded
 * description, while the operational log keeps the status and leaves out the upstream body.
 *
 * <p>The backend URL and passthrough come from {@link BedrockProxyBackendProfile}, which this class
 * shares with the other proxy classes that talk to a stand-in. The {@code fixture} model id is not
 * in that profile's mapping, so it reaches the stand-in through passthrough.
 */
@QuarkusTest
@TestProfile(BedrockProxyBackendProfile.class)
class BedrockProxyLoggingIntegrationTest {

    @TestHTTPResource
    URI base;

    @ParameterizedTest
    @ValueSource(strings = {"converse", "converse-stream"})
    void upstreamErrorIsLoggedWithoutResponseBody(String operation) throws IOException {
        String marker = "synthetic-private-upstream-content";
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        backend.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"error\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        backend.start();
        try {
            List<LogRecord> records = LogCapture.capture(ProxyBackend.class, () ->
                    given()
                            .contentType("application/json")
                            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request")
                            .body("{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"synthetic\"}]}]}")
                    .when()
                            .post("/model/fixture/" + operation)
                    .then()
                            .statusCode(424)
                            .body("__type", equalTo("ModelErrorException"))
                            .body("message", containsString("HTTP 503"))
                            .body("message", containsString(marker)));
            SimpleFormatter formatter = new SimpleFormatter();
            assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.WARNING)
                    && formatter.format(record).contains("HTTP 503")), "The upstream status must remain diagnosable");
            assertFalse(records.stream().anyMatch(record -> formatter.format(record).contains(marker)),
                    "Operational logs must not include upstream response bodies");
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void streamingErrorBodyReadIsBoundedWithoutWaitingForEof() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        backend.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, 32768);
            try (exchange) {
                exchange.getResponseBody().write("x".repeat(8192).getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        backend.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/model/fixture/converse-stream"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"synthetic\"}]}]}"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(424, response.statusCode());
            assertTrue(response.body().contains("ModelErrorException"));
            assertTrue(response.body().contains("HTTP 503"));
            assertTrue(response.body().length() < 1024, "Caller error descriptions must remain bounded");
        } finally {
            release.countDown();
            backend.stop(0);
        }
    }
}

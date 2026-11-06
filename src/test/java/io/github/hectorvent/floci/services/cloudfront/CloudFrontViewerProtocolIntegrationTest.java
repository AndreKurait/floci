package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.config.SelfSignedTlsProfile;
import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.PemTrustOptions;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.hectorvent.floci.testing.AlbFixtures.exchange;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudFrontViewerProtocolIntegrationTest.ViewerTlsProfile.class)
class CloudFrontViewerProtocolIntegrationTest {
    public static class ViewerTlsProfile extends SelfSignedTlsProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());
            overrides.put("floci.services.cloudfront.allowed-private-origin-hosts", "127.0.0.1");
            return overrides;
        }
    }

    @Inject CloudFrontService cloudFront;
    @Inject Vertx vertx;
    @ConfigProperty(name = "quarkus.http.test-ssl-port") int sslPort;
    @RegisterExtension final PartitionCleanup cleanup = new PartitionCleanup();

    private final AtomicInteger originRequests = new AtomicInteger();
    private HttpServer origin;

    @BeforeEach
    void startOrigin() throws Exception {
        origin = vertx.createHttpServer().requestHandler(request -> {
            originRequests.incrementAndGet();
            if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
                request.toWebSocket().onSuccess(socket -> socket.textMessageHandler(socket::writeTextMessage));
            } else {
                request.body().onSuccess(body -> request.response().end("origin-content"));
            }
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @AfterEach
    void stopOrigin() throws Exception {
        origin.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void httpsOnlyRefusesHttpBeforeContactingOrigin(String method) {
        Distribution distribution = distribution("https-only");
        viewer(distribution).request(method, "/object").then().statusCode(403);
        assertEquals(0, originRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"allow-all", "https-only", "redirect-to-https"})
    void eachPolicyServesActualHttps(String policy) {
        Distribution distribution = distribution(policy);
        viewer(distribution).relaxedHTTPSValidation().baseUri("https://localhost").port(sslPort)
                .get("/object").then().statusCode(200).body(equalTo("origin-content"));
        assertEquals(1, originRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void redirectUsesMethodPreservingStatusAndRawTarget(String method) {
        Distribution distribution = distribution("redirect-to-https");
        String target = "/encoded%2Fpath?key=a%2Fb&space=one+two";
        int expectedStatus = List.of("GET", "HEAD").contains(method) ? 301 : 307;
        viewer(distribution).urlEncodingEnabled(false).request(method, target).then().statusCode(expectedStatus)
                .header("Location", equalTo("https://" + distribution.getDomainName() + target));
        assertEquals(0, originRequests.get());
    }

    @Test
    void redirectRefusesHttp10PostWithoutContactingOrigin() throws Exception {
        Distribution distribution = distribution("redirect-to-https");
        try (Socket socket = new Socket("127.0.0.1", RestAssured.port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("POST /object HTTP/1.0\r\nHost: " + distribution.getDomainName()
                    + "\r\nContent-Length: 4\r\nConnection: close\r\n\r\ndata").getBytes(StandardCharsets.US_ASCII));
            String status = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertTrue(status.contains(" 403 "), status);
        }
        assertEquals(0, originRequests.get());
    }

    @Test
    void matchingCacheBehaviorOverridesDefaultProtocolPolicy() {
        Distribution distribution = distribution("https-only");
        CacheBehavior publicObjects = new CacheBehavior();
        publicObjects.setPathPattern("/public/*");
        publicObjects.setTargetOriginId("origin");
        publicObjects.setViewerProtocolPolicy("allow-all");
        distribution.getConfig().setCacheBehaviors(List.of(publicObjects));
        viewer(distribution).get("/public/object").then().statusCode(200);
        viewer(distribution).get("/private/object").then().statusCode(403);
        assertEquals(1, originRequests.get());
    }

    @Test
    void forwardedProtoHeaderDoesNotTurnPlainHttpIntoHttps() {
        viewer(distribution("https-only")).header("X-Forwarded-Proto", "https")
                .get("/object").then().statusCode(403);
        assertEquals(0, originRequests.get());
    }

    @ParameterizedTest
    @CsvSource({"https-only,403", "redirect-to-https,301"})
    void protocolPolicyAppliesToAnUnencryptedWebSocketHandshake(String policy, int expectedStatus) {
        viewer(distribution(policy)).header("Connection", "Upgrade").header("Upgrade", "websocket")
                .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==").header("Sec-WebSocket-Version", "13")
                .get("/object").then().statusCode(expectedStatus);
        assertEquals(0, originRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https-only", "redirect-to-https"})
    void restrictedPoliciesAllowWebSocketsOverVerifiedTls(String policy) throws Exception {
        Distribution distribution = distribution(policy);
        HttpClient client = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setForceSni(true)
                .setTrustOptions(new PemTrustOptions().addCertPath(
                        FlociCertificateAuthority.loadOrCreate(Path.of(SelfSignedTlsProfile.TLS_DIR))
                                .certificatePath().toString())).setVerifyHost(true));
        try {
            WebSocket socket = client.webSocket(new WebSocketConnectOptions().setSsl(true)
                    .setHost("localhost").setPort(sslPort).setURI("/object")
                    .putHeader("Host", distribution.getDomainName()))
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("hello", exchange(socket, "hello"));
            socket.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
        assertEquals(1, originRequests.get());
    }

    private RequestSpecification viewer(Distribution distribution) {
        return given().redirects().follow(false).header("Host", distribution.getDomainName());
    }

    private Distribution distribution(String policy) {
        Origin target = new Origin();
        target.setId("origin");
        target.setDomainName("127.0.0.1");
        target.setCustomOriginConfig(Map.of("HTTPPort", String.valueOf(origin.actualPort()),
                "HTTPSPort", "443", "OriginProtocolPolicy", "http-only"));
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("origin");
        behavior.setViewerProtocolPolicy(policy);
        behavior.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(target));
        config.setDefaultCacheBehavior(behavior);
        Distribution requested = new Distribution();
        requested.setConfig(config);
        Distribution created = cloudFront.createDistribution(requested, Map.of());
        cleanup.register(() -> cloudFront.removeDistribution(created.getId()));
        return created;
    }
}

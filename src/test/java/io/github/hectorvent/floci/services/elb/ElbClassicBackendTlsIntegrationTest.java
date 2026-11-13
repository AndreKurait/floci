package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.testing.RealClassicElbDataPlaneProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeInstanceHealthResponse;
import software.amazon.awssdk.services.elasticloadbalancing.model.HealthCheck;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(RealClassicElbDataPlaneProfile.class)
class ElbClassicBackendTlsIntegrationTest {

    private static final String ACCOUNT = "321321321322";
    private static final Region REGION = Region.EU_WEST_1;
    private final String name = "classic-tls-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<Executable> cleanup = new ArrayList<>();
    private final AtomicInteger healthStatus = new AtomicInteger(200);
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicBoolean encrypted = new AtomicBoolean();
    private Ec2Client ec2Client;
    private ElasticLoadBalancingClient client;
    private String instanceId;
    private String subnetId;

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    Vertx vertx;

    @Inject
    Ec2Service ec2;

    @Inject
    ElbClassicHealthChecker health;

    @BeforeEach
    void createSdkResources() {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCOUNT, "secret"));
        ec2Client = Ec2Client.builder().endpointOverride(endpoint).region(REGION)
                .credentialsProvider(credentials).build();
        cleanup.add(ec2Client::close);
        client = ElasticLoadBalancingClient.builder().endpointOverride(endpoint).region(REGION)
                .credentialsProvider(credentials).build();
        cleanup.add(client::close);
        String vpcId = ec2Client.createVpc(r -> r.cidrBlock("10.82.0.0/16")).vpc().vpcId();
        cleanup.add(() -> ec2Client.deleteVpc(r -> r.vpcId(vpcId)));
        subnetId = ec2Client.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.82.1.0/24")).subnet().subnetId();
        cleanup.add(() -> ec2Client.deleteSubnet(r -> r.subnetId(subnetId)));
        instanceId = ec2Client.runInstances(r -> r.imageId("ami-classic-tls").instanceType("t3.micro")
                .minCount(1).maxCount(1).subnetId(subnetId)).instances().getFirst().instanceId();
        cleanup.add(() -> ec2Client.terminateInstances(r -> r.instanceIds(instanceId)));
        // Bind mock EC2 to a real backend socket, as the Docker launcher would.
        Instance instance = ec2.findInstanceById(ACCOUNT, instanceId);
        assertNotNull(instance);
        instance.setContainerBridgeIp("127.0.0.1");
        instance.setState(InstanceState.running());
    }

    @AfterEach
    void removeOnlyThisTestsResources() {
        assertAll(cleanup.reversed());
    }

    @Test
    void httpsBackendReceivesEncryptedRequestsWithOriginalPayloadAndHeaders() throws Exception {
        HttpServer backend = backend(true);
        String dns = createLoadBalancer(backend.actualPort(), "HTTPS", "TCP:" + backend.actualPort());
        awaitHealth("InService");

        given().urlEncodingEnabled(false).header("Host", dns).header("Authorization", "native-client-signature")
                .contentType("application/octet-stream").body("native request".getBytes(StandardCharsets.UTF_8))
                .post("/packages/prepare?version=3%2E0").then().statusCode(200).body(equalTo("native request"));

        assertTrue(encrypted.get(), "The backend must receive TLS, including with its self-signed certificate");
        assertEquals("POST /packages/prepare?version=3%2E0 " + dns + " native-client-signature", received.get());
    }

    @Test
    void httpsBackendDoesNotFallBackToPlaintextWhenTheHandshakeFails() throws Exception {
        HttpServer backend = backend(false);
        String dns = createLoadBalancer(backend.actualPort(), "HTTPS", "TCP:" + backend.actualPort());
        awaitHealth("InService");
        given().header("Host", dns).get("/packages/prepare").then().statusCode(502);
        assertNull(received.get(), "A failed TLS handshake must not become a plaintext application request");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "HTTPS"})
    void healthChecksRequire200AndRecoverAfterFailures(String protocol) throws Exception {
        HttpServer backend = backend("HTTPS".equals(protocol));
        createLoadBalancer(backend.actualPort(), protocol, protocol + ":" + backend.actualPort() + "/health");
        awaitHealth("InService");
        for (int status : List.of(204, 302, 500)) {
            healthStatus.set(status);
            awaitHealth("OutOfService");
            healthStatus.set(200);
            awaitHealth("InService");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTPS", "SSL"})
    void tlsHealthChecksRejectAReachablePlaintextServer(String protocol) throws Exception {
        HttpServer backend = backend(false);
        String path = "HTTPS".equals(protocol) ? "/health" : "";
        createLoadBalancer(backend.actualPort(), "HTTP", protocol + ":" + backend.actualPort() + path);
        awaitHealth("OutOfService");
    }

    @Test
    void sslHealthChecksCompleteATlsHandshakeWithoutSendingAnHttpRequest() throws Exception {
        HttpServer backend = backend(true);
        createLoadBalancer(backend.actualPort(), "HTTPS", "SSL:" + backend.actualPort());
        awaitHealth("InService");
        assertNull(received.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTPS", "SSL"})
    void tlsHealthCheckTimeoutIncludesAStalledHandshake(String protocol) throws Exception {
        AtomicBoolean receivedClientHello = new AtomicBoolean();
        NetServer stalled = vertx.createNetServer().connectHandler(socket -> {
            socket.handler(bytes -> {
                if (bytes.length() > 0 && bytes.getByte(0) == 22) {
                    receivedClientHello.set(true);
                }
            });
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> stalled.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        String path = "HTTPS".equals(protocol) ? "/health" : "";
        createLoadBalancer(stalled.actualPort(), "HTTPS", protocol + ":" + stalled.actualPort() + path);
        long start = System.nanoTime();
        awaitHealth("OutOfService");
        assertTrue(receivedClientHello.get(), "A TCP connection alone must not satisfy a TLS health check");
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 1800,
                "The unchanged two-second health timeout should expire while awaiting the handshake");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "HTTPS"})
    void continuousHeaderProgressExpiresAndRemovesPreviouslyHealthyInstance(String protocol) throws Exception {
        AtomicBoolean drip = new AtomicBoolean();
        AtomicInteger closedDrips = new AtomicInteger();
        NetServerOptions options = new NetServerOptions();
        if ("HTTPS".equals(protocol)) {
            CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator()
                    .generateSelfSignedCertificate("backend.example.test", List.of(), KeyAlgorithm.RSA_2048);
            options.setSsl(true).setPemKeyCertOptions(new PemKeyCertOptions()
                    .setCertValue(Buffer.buffer(certificate.certificatePem()))
                    .setKeyValue(Buffer.buffer(certificate.privateKeyPem())));
        }
        NetServer backend = vertx.createNetServer(options).connectHandler(socket -> {
            AtomicBoolean started = new AtomicBoolean();
            socket.handler(bytes -> {
                if (!started.compareAndSet(false, true)) {
                    return;
                }
                if (!drip.get()) {
                    socket.end(Buffer.buffer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
                    return;
                }
                socket.write("HTTP/1.1 200 OK\r\nX-Progress: ");
                long timer = vertx.setPeriodic(100, ignored -> socket.write("a"));
                socket.closeHandler(ignored -> {
                    vertx.cancelTimer(timer);
                    closedDrips.incrementAndGet();
                });
            });
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> backend.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        createLoadBalancer(backend.actualPort(), protocol, protocol + ":" + backend.actualPort() + "/health");
        awaitHealth("InService");
        drip.set(true);
        awaitHealth("OutOfService");
        await().atMost(Duration.ofSeconds(5))
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> assertTrue(closedDrips.get() >= 2,
                        "Both failed probes must close their sockets rather than retaining the backend connection"));
    }

    private HttpServer backend(boolean tls) throws Exception {
        HttpServerOptions options = new HttpServerOptions();
        if (tls) {
            CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator()
                    .generateSelfSignedCertificate("backend.example.test", List.of(), KeyAlgorithm.RSA_2048);
            options.setSsl(true).setPemKeyCertOptions(new PemKeyCertOptions()
                    .setCertValue(Buffer.buffer(certificate.certificatePem()))
                    .setKeyValue(Buffer.buffer(certificate.privateKeyPem())));
        }
        HttpServer backend = vertx.createHttpServer(options).requestHandler(request -> {
            if ("/health".equals(request.path())) {
                request.response().setStatusCode(healthStatus.get())
                        .putHeader("Location", "/healthy").end();
                return;
            }
            encrypted.set(request.isSSL());
            received.set(request.method() + " " + request.uri() + " " + request.getHeader("Host")
                    + " " + request.getHeader("Authorization"));
            request.body().onSuccess(body -> request.response().end(body));
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> backend.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        return backend;
    }

    private String createLoadBalancer(int port, String backendProtocol, String target) {
        String dns = client.createLoadBalancer(r -> r.loadBalancerName(name).subnets(subnetId)
                .listeners(Listener.builder().protocol("HTTP").loadBalancerPort(80)
                        .instanceProtocol(backendProtocol).instancePort(port).build())).dnsName();
        cleanup.add(() -> client.deleteLoadBalancer(r -> r.loadBalancerName(name)));
        client.configureHealthCheck(r -> r.loadBalancerName(name).healthCheck(HealthCheck.builder()
                .target(target).timeout(2).interval(300).healthyThreshold(2).unhealthyThreshold(2).build()));
        client.registerInstancesWithLoadBalancer(r -> r.loadBalancerName(name)
                .instances(instance -> instance.instanceId(instanceId)));
        return dns;
    }

    private void awaitHealth(String state) {
        health.runArmedChecks();
        health.runArmedChecks();
        await().atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> {
                    DescribeInstanceHealthResponse response = client.describeInstanceHealth(r -> r.loadBalancerName(name));
                    assertEquals(1, response.instanceStates().size());
                    assertEquals(state, response.instanceStates().getFirst().state());
                    if ("OutOfService".equals(state)) {
                        assertEquals("Instance", response.instanceStates().getFirst().reasonCode(),
                                "An unprobed instance is not evidence");
                    }
                });
    }
}

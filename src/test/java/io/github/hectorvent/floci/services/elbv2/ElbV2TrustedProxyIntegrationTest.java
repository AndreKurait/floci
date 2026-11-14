package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.SelfSignedTlsProfile;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateListenerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ElbV2TrustedProxyIntegrationTest.TrustedProxyProfile.class)
class ElbV2TrustedProxyIntegrationTest {

    private static final String ACCOUNT = "321321321324";
    private static final String TRUSTED_PROXY = "127.0.0.2";
    private static final String UNTRUSTED_PEER = "127.0.0.3";
    private static final String GUEST = "192.0.2.10";
    private static final String SPOOFED = "198.51.100.7";
    private static final String REQUEST_URI = "/packages/prepare?version=3%2E0";
    private static final String AUTHORIZATION = "AWS4-HMAC-SHA256 native-client-signature";
    private static final String BODY = "{\"native\":\"request\"}";

    private final List<Executable> cleanup = new ArrayList<>();
    private final AtomicReference<ReceivedRequest> received = new AtomicReference<>();
    private ElasticLoadBalancingV2Client elb;
    private List<String> subnets;
    private String targetGroupArn;
    private String certificateArn;
    private String dns;
    private int listenerPort;
    private SSLContext tlsContext;

    @Inject
    Vertx vertx;

    @Inject
    EmulatorConfig config;

    @TestHTTPResource("/")
    URI endpoint;

    @AfterEach
    void removeOnlyThisTestsResources() {
        assertAll(cleanup.reversed());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trustedProxyAppendsTheVerifiedGuestIdentity(boolean tls) throws Exception {
        startListener(tls);

        assertEquals(200, request(tls, TRUSTED_PROXY, dns,
                "Forwarded: for=" + GUEST + ";proto=https\r\nX-Forwarded-For: " + GUEST + "\r\n"));

        assertEquals(new ReceivedRequest(GUEST + ", " + GUEST, "POST", REQUEST_URI, AUTHORIZATION, BODY),
                received.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictingForwardedForDoesNotReplaceTrustedForwardedIdentity(boolean tls) throws Exception {
        startListener(tls);

        assertEquals(200, request(tls, TRUSTED_PROXY, dns,
                "Forwarded: for=" + GUEST + ";proto=https\r\n"
                        + "X-Forwarded-For: " + SPOOFED + "\r\n"));

        assertEquals(SPOOFED + ", " + GUEST, received.get().forwardedFor());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void untrustedPeerCannotReplaceItsIdentityWithForwardingHeaders(boolean tls) throws Exception {
        startListener(tls);

        assertEquals(200, request(tls, UNTRUSTED_PEER, dns,
                "Forwarded: for=" + GUEST + ";proto=https;host=unknown.invalid\r\n"
                        + "X-Forwarded-For: " + SPOOFED + "\r\n"));

        assertEquals(SPOOFED + ", " + UNTRUSTED_PEER, received.get().forwardedFor());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trustedPeerWithoutForwardedKeepsItsSocketIdentity(boolean tls) throws Exception {
        startListener(tls);

        assertEquals(200, request(tls, TRUSTED_PROXY, dns, ""));

        assertEquals(TRUSTED_PROXY, received.get().forwardedFor());
        assertEquals(200, request(tls, TRUSTED_PROXY, dns, "X-Forwarded-For: " + SPOOFED + "\r\n"));
        assertEquals(SPOOFED + ", " + TRUSTED_PROXY, received.get().forwardedFor());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trustedForwardedCannotRouteAnUnknownHost(boolean tls) throws Exception {
        startListener(tls);
        addSecondLoadBalancer(tls);

        assertEquals(502, request(tls, TRUSTED_PROXY, "unknown.invalid",
                "Forwarded: for=" + GUEST + ";proto=https\r\n"));

        assertNull(received.get(), "An unknown Host must not reach the ALB target");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedTrustedForwardedIsRejectedBeforeTheTarget(boolean tls) throws Exception {
        startListener(tls);

        assertEquals(400, request(tls, TRUSTED_PROXY, dns,
                "Forwarded: for=" + GUEST + ";proto=1https\r\n"));

        assertNull(received.get(), "A rejected Forwarded header must not reach the target");
    }

    private void startListener(boolean tls) throws Exception {
        HttpServer backend = vertx.createHttpServer().requestHandler(request -> {
            if ("/health".equals(request.path())) {
                request.response().end("healthy");
                return;
            }
            request.body().onSuccess(body -> {
                received.set(new ReceivedRequest(request.getHeader("X-Forwarded-For"),
                        request.method().name(), request.uri(), request.getHeader("Authorization"), body.toString()));
                request.response().end("target");
            });
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> backend.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.EU_WEST_1, endpoint);
        cleanup.add(ec2::close);
        elb = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), ACCOUNT, Region.EU_WEST_1, endpoint);
        cleanup.add(elb::close);
        String vpc = ec2.createVpc(r -> r.cidrBlock("10.84.0.0/16")).vpc().vpcId();
        cleanup.add(() -> ec2.deleteVpc(r -> r.vpcId(vpc)));
        subnets = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            String cidr = "10.84." + index + ".0/24";
            String zone = "eu-west-1" + (char) ('a' + index);
            String subnet = ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock(cidr)
                    .availabilityZone(zone)).subnet().subnetId();
            subnets.add(subnet);
            cleanup.add(() -> ec2.deleteSubnet(r -> r.subnetId(subnet)));
        }
        String name = "alb-proxy-" + UUID.randomUUID().toString().substring(0, 8);
        LoadBalancer loadBalancer = elb.createLoadBalancer(r -> r.name(name).type("application")
                .subnets(subnets)).loadBalancers().getFirst();
        String arn = loadBalancer.loadBalancerArn();
        dns = loadBalancer.dnsName();
        cleanup.add(() -> elb.deleteLoadBalancer(r -> r.loadBalancerArn(arn)));
        targetGroupArn = elb.createTargetGroup(r -> r.name(name).protocol("HTTP").port(backend.actualPort())
                        .vpcId(vpc).targetType("ip").healthCheckPath("/health").healthCheckIntervalSeconds(5))
                .targetGroups().getFirst().targetGroupArn();
        cleanup.add(() -> elb.deleteTargetGroup(r -> r.targetGroupArn(targetGroupArn)));
        listenerPort = tls ? config.tls().awsHttpsPort() : FreePorts.anyInterfacePort();
        CreateListenerRequest.Builder listener = CreateListenerRequest.builder()
                .loadBalancerArn(arn).protocol(tls ? "HTTPS" : "HTTP").port(listenerPort)
                .defaultActions(Action.builder().type("forward").targetGroupArn(targetGroupArn).build());
        if (tls) {
            certificateArn = importTrustedCertificate();
            listener.certificates(r -> r.certificateArn(certificateArn));
        }
        String listenerArn = elb.createListener(listener.build()).listeners().getFirst().listenerArn();
        cleanup.add(() -> elb.deleteListener(r -> r.listenerArn(listenerArn)));
        elb.registerTargets(r -> r.targetGroupArn(targetGroupArn)
                .targets(TargetDescription.builder().id("127.0.0.1").port(backend.actualPort()).build()));
        await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .ignoreExceptionsMatching(IOException.class::isInstance)
                .untilAsserted(() -> {
                    try (Socket socket = openConnection(tls, "127.0.0.1")) {
                        assertTrue(socket.isConnected());
                    }
                });
        await().atMost(Duration.ofSeconds(20)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertEquals("healthy", elb.describeTargetHealth(r -> r.targetGroupArn(targetGroupArn))
                        .targetHealthDescriptions().getFirst().targetHealth().stateAsString()));
    }

    private void addSecondLoadBalancer(boolean tls) {
        String name = "alb-other-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = elb.createLoadBalancer(r -> r.name(name).type("application")
                .subnets(subnets)).loadBalancers().getFirst().loadBalancerArn();
        cleanup.add(() -> elb.deleteLoadBalancer(r -> r.loadBalancerArn(arn)));
        CreateListenerRequest.Builder listener = CreateListenerRequest.builder()
                .loadBalancerArn(arn).protocol(tls ? "HTTPS" : "HTTP").port(listenerPort)
                .defaultActions(Action.builder().type("fixed-response")
                        .fixedResponseConfig(r -> r.statusCode("403").contentType("text/plain").messageBody("other"))
                        .build());
        if (tls) {
            listener.certificates(r -> r.certificateArn(certificateArn));
        }
        String listenerArn = elb.createListener(listener.build()).listeners().getFirst().listenerArn();
        cleanup.add(() -> elb.deleteListener(r -> r.listenerArn(listenerArn)));
    }

    private String importTrustedCertificate() throws Exception {
        CertificateGenerator.GeneratedCertificate pair = new CertificateGenerator()
                .generateSelfSignedCertificate(dns, List.of(), KeyAlgorithm.RSA_2048);
        AcmClient acm = AwsTestClients.create(AcmClient.builder(), ACCOUNT, Region.EU_WEST_1, endpoint);
        cleanup.add(acm::close);
        String arn = acm.importCertificate(r -> r.certificate(SdkBytes.fromUtf8String(pair.certificatePem()))
                .privateKey(SdkBytes.fromUtf8String(pair.privateKeyPem()))).certificateArn();
        cleanup.add(() -> acm.deleteCertificate(r -> r.certificateArn(arn)));
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pair.certificatePem().getBytes(StandardCharsets.US_ASCII)));
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("listener", certificate);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        tlsContext = SSLContext.getInstance("TLS");
        tlsContext.init(null, factory.getTrustManagers(), null);
        return arn;
    }

    private int request(boolean tls, String peer, String host, String headers) throws Exception {
        try (Socket socket = openConnection(tls, peer)) {
            return exchange(socket, host, headers);
        }
    }

    private Socket openConnection(boolean tls, String peer) throws Exception {
        Socket transport = new Socket();
        try {
            transport.bind(new InetSocketAddress(peer, 0));
            transport.connect(new InetSocketAddress("127.0.0.1", listenerPort), 2000);
            transport.setSoTimeout(5000);
            if (!tls) {
                return transport;
            }
            SSLSocket socket = (SSLSocket) tlsContext.getSocketFactory()
                    .createSocket(transport, dns, listenerPort, true);
            try {
                socket.setSoTimeout(5000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                return socket;
            } catch (Exception failure) {
                try (socket) {
                    throw failure;
                }
            }
        } catch (Exception failure) {
            try (transport) {
                throw failure;
            }
        }
    }

    private static int exchange(Socket socket, String host, String headers) throws Exception {
        socket.getOutputStream().write(("POST " + REQUEST_URI + " HTTP/1.1\r\nHost: " + host + "\r\n"
                + headers + "Authorization: " + AUTHORIZATION + "\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + BODY.length() + "\r\nConnection: close\r\n\r\n" + BODY)
                .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(response.contains("\r\n\r\n"), "Expected a complete HTTP response: " + response);
        return Integer.parseInt(response.substring(0, response.indexOf("\r\n")).split(" ", 3)[1]);
    }

    private record ReceivedRequest(String forwardedFor, String method, String uri, String authorization, String body) {}

    public static class TrustedProxyProfile extends SelfSignedTlsProfile {

        private static final int AWS_HTTPS_PORT = freePort();

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.putAll(Map.of(
                    "floci.services.elbv2.mock", "false",
                    "quarkus.http.host", "127.0.0.1",
                    "quarkus.http.proxy.proxy-address-forwarding", "true",
                    "quarkus.http.proxy.allow-forwarded", "true",
                    "quarkus.http.proxy.allow-x-forwarded", "false",
                    "quarkus.http.proxy.enable-forwarded-host", "false",
                    "quarkus.http.proxy.enable-forwarded-prefix", "false",
                    "quarkus.http.proxy.trusted-proxies", TRUSTED_PROXY));
            overrides.put("floci.port", "0");
            overrides.put("floci.tls.aws-https-port", String.valueOf(AWS_HTTPS_PORT));
            overrides.put("quarkus.http.test-ssl-port", "0");
            return overrides;
        }

        private static int freePort() {
            try {
                return FreePorts.anyInterfacePort();
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot allocate the shared TLS proxy test port", failure);
            }
        }
    }
}

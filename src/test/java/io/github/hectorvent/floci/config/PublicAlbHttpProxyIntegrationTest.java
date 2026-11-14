package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.LoadBalancerDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.AliasTarget;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SDK-owned aliases reached through the real PROXY decoder and Quarkus HTTP router. */
@QuarkusTest
@TestProfile(PublicAlbHttpProxyIntegrationTest.PublicHttpProfile.class)
class PublicAlbHttpProxyIntegrationTest {
    private static final String PUBLIC_ALB = EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS;
    private static final String CALLER = "172.18.1.10";

    public static final class PublicHttpProfile extends RealElbV2DataPlaneProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            // Reserve modeled HTTP80 without starting a standalone public ALB listener.
            overrides.put("floci.port", "80");
            overrides.put("floci.tls.enabled", "false");
            overrides.put("floci.services.elb.mock", "false");
            overrides.put("floci.services.elb.vpc-frontends", "false");
            overrides.put("quarkus.http.host", "127.0.0.1");
            overrides.put("quarkus.http.proxy.use-proxy-protocol", "true");
            return overrides;
        }
    }

    @Inject
    Router router;
    @TestHTTPResource("/")
    URI backend;

    private final BlockingQueue<Observed> received = new LinkedBlockingQueue<>();
    private final AtomicInteger classicFallback = new AtomicInteger();
    private final AtomicInteger apiFallback = new AtomicInteger();
    private final List<Route> observationRoutes = new ArrayList<>();
    private final List<String> subnets = new ArrayList<>();
    private final List<String> balancers = new ArrayList<>();
    private final List<ResourceRecordSet> records = new ArrayList<>();
    private Vertx vertx;
    private TlsProxyServer proxy;
    private int port;
    private Ec2Client ec2;
    private AcmClient acm;
    private ElasticLoadBalancingV2Client elb;
    private ElasticLoadBalancingClient classic;
    private Route53Client route53;
    private String vpc;
    private String zone;
    private String domain;
    private String classicName;
    private String certificate;

    @BeforeEach
    void startLoopbackProxyAndCreateSdkVpc() throws IOException {
        observationRoutes.add(router.route().order(-1000).handler(context -> {
            if (PUBLIC_ALB.equals(context.request().localAddress().hostAddress())) {
                received.add(new Observed(context.request().remoteAddress().hostAddress(),
                        context.request().remoteAddress().port(), context.request().localAddress().hostAddress(),
                        context.request().localAddress().port()));
            }
            context.next();
        }));
        observationRoutes.add(router.route().order(-100).handler(context -> {
            if (PUBLIC_ALB.equals(context.request().localAddress().hostAddress())) {
                classicFallback.incrementAndGet();
            }
            context.next();
        }));
        observationRoutes.add(router.route().order(-1).handler(context -> {
            if (PUBLIC_ALB.equals(context.request().localAddress().hostAddress())) {
                apiFallback.incrementAndGet();
            }
            context.next();
        }));
        vertx = Vertx.vertx();
        port = FreePorts.loopbackIpv4Port();
        EmulatorConfig transport = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(transport.port()).thenReturn(port);
        when(transport.tls().enabled()).thenReturn(true);
        when(transport.tls().awsHttpsPort()).thenReturn(port);
        PublicApiProxySources sources = new PublicApiProxySources();
        sources.register(this, "127.0.0.2", Map.of(PUBLIC_ALB, Set.of(CALLER)));
        proxy = new TlsProxyServer(vertx, transport, "127.0.0.1", backend.getPort(), backend.getPort(), sources);

        URI endpoint = URI.create("http://127.0.0.1:" + port);
        StaticCredentialsProvider owner =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("731000000001", "test"));
        StaticCredentialsProvider zoneOwner =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("731000000002", "test"));
        ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                .credentialsProvider(owner).httpClientBuilder(Apache5HttpClient.builder()).build();
        acm = AcmClient.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                .credentialsProvider(owner).httpClientBuilder(Apache5HttpClient.builder()).build();
        elb = ElasticLoadBalancingV2Client.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                .credentialsProvider(owner).httpClientBuilder(Apache5HttpClient.builder()).build();
        classic = ElasticLoadBalancingClient.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                .credentialsProvider(owner).httpClientBuilder(Apache5HttpClient.builder()).build();
        route53 = Route53Client.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                .credentialsProvider(zoneOwner).httpClientBuilder(Apache5HttpClient.builder()).build();
        vpc = ec2.createVpc(r -> r.cidrBlock("10.73.0.0/16")).vpc().vpcId();
        subnets.add(ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.73.1.0/24")
                .availabilityZone("eu-west-1a")).subnet().subnetId());
        subnets.add(ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.73.2.0/24")
                .availabilityZone("eu-west-1b")).subnet().subnetId());
        domain = UUID.randomUUID() + ".example.test";
        zone = route53.createHostedZone(r -> r.name(domain).callerReference(UUID.randomUUID().toString()))
                .hostedZone().id();
    }

    @AfterEach
    void removeSdkResourcesAndCloseOwnedSockets() throws Exception {
        try {
            for (ResourceRecordSet record : records.reversed()) {
                change(ChangeAction.DELETE, record);
            }
            if (zone != null) {
                route53.deleteHostedZone(r -> r.id(zone));
            }
            if (classicName != null) {
                classic.deleteLoadBalancer(r -> r.loadBalancerName(classicName));
            }
            for (String arn : balancers.reversed()) {
                elb.deleteLoadBalancer(r -> r.loadBalancerArn(arn));
            }
            if (certificate != null) {
                acm.deleteCertificate(r -> r.certificateArn(certificate));
            }
            for (String subnet : subnets.reversed()) {
                ec2.deleteSubnet(r -> r.subnetId(subnet));
            }
            if (vpc != null) {
                ec2.deleteVpc(r -> r.vpcId(vpc));
            }
        } finally {
            for (AutoCloseable client : new AutoCloseable[]{route53, classic, elb, acm, ec2}) {
                if (client != null) {
                    client.close();
                }
            }
            observationRoutes.forEach(Route::remove);
            if (proxy != null) {
                proxy.stop();
            }
            if (vertx != null) {
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void realHttpDestinationServesOwnedAliasAndRechecksEachKeepAliveRequest() throws Exception {
        LoadBalancer target = createAlb("internet-facing");
        String listener = httpListener(target);
        String host = "owned." + domain;
        ResourceRecordSet record = installAlias(host, target.dnsName(), target.canonicalHostedZoneId());
        try (Socket socket = bridge()) {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            Response fixed = request(socket, input, host);
            assertEquals(200, fixed.status());
            assertEquals("owned-http", fixed.body());
            assertOriginalDestination();

            elb.modifyListener(r -> r.listenerArn(listener).defaultActions(a -> a.type("redirect")
                    .redirectConfig(c -> c.protocol("HTTPS").port("443").statusCode("HTTP_301"))));
            Response redirect = request(socket, input, host);
            assertEquals(301, redirect.status());
            assertEquals("https://" + host + ":443/check", redirect.headers().get("location"));
            assertOriginalDestination();

            change(ChangeAction.DELETE, record);
            records.remove(record);
            assertEquals(502, request(socket, input, host).status(), "withdrawal applies to the existing connection");
            assertOriginalDestination();
        }
        assertNoFallback();
    }

    @Test
    void unownedInternalWrongZoneDeletedAndMalformedAliasesNeverReachFallback() throws Exception {
        LoadBalancer target = createAlb("internet-facing");
        httpListener(target);
        String owned = "owned." + domain;
        installAlias(owned, target.dnsName(), target.canonicalHostedZoneId());
        assertDenied("unknown." + domain);
        assertDenied("localhost");
        assertDenied(target.dnsName());
        assertMalformedAuthority(owned + "@localhost");
        assertMalformedAuthority(owned + ":65536");

        LoadBalancer internal = createAlb("internal");
        httpListener(internal);
        String internalHost = "internal." + domain;
        installAlias(internalHost, internal.dnsName(), internal.canonicalHostedZoneId());
        assertDenied(internalHost);
        String wrongZone = "wrong-zone." + domain;
        installAlias(wrongZone, target.dnsName(), "WRONG");
        assertDenied(wrongZone);

        classicName = "classic-" + UUID.randomUUID().toString().substring(0, 12);
        classic.createLoadBalancer(r -> r.loadBalancerName(classicName).subnets(subnets)
                .listeners(l -> l.protocol("HTTP").loadBalancerPort(80).instanceProtocol("HTTP").instancePort(8080)));
        LoadBalancerDescription legacy = classic.describeLoadBalancers(r -> r.loadBalancerNames(classicName))
                .loadBalancerDescriptions().getFirst();
        String classicHost = "classic." + domain;
        installAlias(classicHost, legacy.dnsName(), legacy.canonicalHostedZoneNameID());
        try (Socket ordinary = new Socket()) {
            ordinary.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            ordinary.setSoTimeout(5000);
            assertEquals(503, request(ordinary, new BufferedInputStream(ordinary.getInputStream()), classicHost).status(),
                    "the real Classic handler is active and reports its missing targets on ordinary traffic");
        }
        assertDenied(classicHost);

        elb.deleteLoadBalancer(r -> r.loadBalancerArn(target.loadBalancerArn()));
        balancers.remove(target.loadBalancerArn());
        assertDenied(owned);
        assertNoFallback();
    }

    @Test
    void secureListenerIsNotServedAsPlainHttpEvenOnTheReservedPort() throws Exception {
        LoadBalancer target = createAlb("internet-facing");
        String host = "secure." + domain;
        certificate = acm.requestCertificate(r -> r.domainName(host).validationMethod("DNS")).certificateArn();
        elb.createListener(r -> r.loadBalancerArn(target.loadBalancerArn()).protocol("HTTPS").port(80)
                .certificates(c -> c.certificateArn(certificate)).defaultActions(a -> a.type("fixed-response")
                        .fixedResponseConfig(f -> f.statusCode("200").contentType("text/plain").messageBody("secure"))));
        installAlias(host, target.dnsName(), target.canonicalHostedZoneId());
        assertDenied(host);
        assertNoFallback();
    }

    private LoadBalancer createAlb(String scheme) {
        LoadBalancer target = elb.createLoadBalancer(r -> r.name("http-" + UUID.randomUUID().toString().substring(0, 20))
                .type("application").scheme(scheme).subnets(subnets)).loadBalancers().getFirst();
        balancers.add(target.loadBalancerArn());
        return target;
    }

    private String httpListener(LoadBalancer target) {
        return elb.createListener(r -> r.loadBalancerArn(target.loadBalancerArn()).protocol("HTTP").port(80)
                .defaultActions(a -> a.type("fixed-response").fixedResponseConfig(f -> f.statusCode("200")
                        .contentType("text/plain").messageBody("owned-http")))).listeners().getFirst().listenerArn();
    }

    private ResourceRecordSet installAlias(String host, String dnsName, String hostedZone) {
        ResourceRecordSet record = ResourceRecordSet.builder().name(host).type("A").aliasTarget(AliasTarget.builder()
                .dnsName(dnsName).hostedZoneId(hostedZone).evaluateTargetHealth(false).build()).build();
        change(ChangeAction.CREATE, record);
        records.add(record);
        return record;
    }

    private void change(ChangeAction action, ResourceRecordSet record) {
        route53.changeResourceRecordSets(r -> r.hostedZoneId(zone)
                .changeBatch(b -> b.changes(c -> c.action(action).resourceRecordSet(record))));
    }

    private Socket bridge() throws IOException {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress("127.0.0.2", 0));
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(5000);
        socket.getOutputStream().write(("PROXY TCP4 " + CALLER + " " + PUBLIC_ALB + " 49152 80\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        return socket;
    }

    private void assertDenied(String host) throws Exception {
        try (Socket socket = bridge()) {
            Response response = request(socket, new BufferedInputStream(socket.getInputStream()), host);
            assertEquals(502, response.status(), host);
            assertEquals("No listener for host", response.body(), "the native ALB guard owns this denial");
        }
        assertOriginalDestination();
        assertNoFallback();
    }

    private void assertMalformedAuthority(String host) throws Exception {
        try (Socket socket = bridge()) {
            Response response = request(socket, new BufferedInputStream(socket.getInputStream()), host);
            assertEquals(400, response.status(), host);
        }
        assertTrue(received.isEmpty(), "the HTTP parser rejects malformed authority before the router");
        assertNoFallback();
    }

    private void assertOriginalDestination() throws InterruptedException {
        assertEquals(new Observed(CALLER, 49152, PUBLIC_ALB, 80), received.poll(5, TimeUnit.SECONDS),
                "Quarkus must see both original endpoint tuples after real PROXY propagation");
    }

    private void assertNoFallback() {
        assertEquals(0, classicFallback.get(), "the native ALB guard runs before the Classic routing stage");
        assertEquals(0, apiFallback.get(), "native ALB traffic cannot enter the API routing stage");
    }

    private static Response request(Socket socket, InputStream input, String host) throws IOException {
        socket.getOutputStream().write(("GET /check HTTP/1.1\r\nHost: " + host
                + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        String status = line(input);
        assertNotNull(status, "the real HTTP backend must answer an admitted .2:80 transport");
        Map<String, String> headers = new HashMap<>();
        for (String header = line(input); header != null && !header.isEmpty(); header = line(input)) {
            int separator = header.indexOf(':');
            headers.put(header.substring(0, separator).toLowerCase(Locale.ROOT), header.substring(separator + 1).trim());
        }
        assertTrue(headers.containsKey("content-length"), "fixture responses must have a bounded HTTP body");
        int length = Integer.parseInt(headers.get("content-length"));
        assertTrue(length >= 0 && length <= 8192);
        byte[] body = input.readNBytes(length);
        assertEquals(length, body.length);
        return new Response(Integer.parseInt(status.split(" ")[1]), headers, new String(body, StandardCharsets.UTF_8));
    }

    private static String line(InputStream input) throws IOException {
        StringBuilder result = new StringBuilder();
        while (result.length() < 8192) {
            int next = input.read();
            if (next == -1) {
                return result.isEmpty() ? null : result.toString();
            }
            if (next == '\n') {
                return result.toString().stripTrailing();
            }
            result.append((char) next);
        }
        throw new IOException("HTTP fixture response line exceeds 8192 bytes");
    }

    private record Observed(String source, int sourcePort, String destination, int destinationPort) {}
    private record Response(int status, Map<String, String> headers, String body) {}
}

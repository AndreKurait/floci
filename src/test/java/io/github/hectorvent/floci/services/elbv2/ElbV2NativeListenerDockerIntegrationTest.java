package io.github.hectorvent.floci.services.elbv2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.CertificateGenerator.GeneratedCertificate;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Docker destinations preserve listener identity before a client's TLS handshake. */
@QuarkusTest
@TestProfile(ElbV2NativeListenerDockerIntegrationTest.NativeVpc.class)
class ElbV2NativeListenerDockerIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String BALANCER_LABEL = "io.floci.elbv2.frontend";
    private static final String LISTENER_LABEL = "io.floci.elbv2.frontend-listener";
    private static final String FIRST_HOST = "first.native.example";
    private static final String SECOND_HOST = "second.native.example";
    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test"));

    public static class NativeVpc implements QuarkusTestProfile {
        private static final String NAMESPACE = System.getProperty("test.native-alb.namespace",
                "alb-native-listener-test-" + UUID.randomUUID());

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.network.security-group-enforcement.enabled", "true",
                    "floci.services.ec2.mock", "false", "floci.services.elbv2.mock", "false",
                    "floci.storage.mode", "memory", "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.services.ec2.aws-faithful-private-ip", "true",
                    "floci.services.ec2.vpc-networks.isolate-address-spaces", "true",
                    "floci.docker.resource-namespace", NAMESPACE);
        }
    }

    @Inject ElbV2FrontendManager frontends;
    @Inject EmbeddedDnsServer dns;
    @Inject DockerClient docker;
    @Inject ContainerBuilder builder;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject EmulatorConfig config;
    @TestHTTPResource("/") URI endpoint;
    private final Deque<Executable> cleanup = new ArrayDeque<>();

    @AfterEach
    void removeOwnedResources() {
        assertAll("Native listener, certificate and VPC cleanup", cleanup);
    }

    @Test
    void sdkListenersServeTheirOwnDefaultCertificatesWithoutSniAndRetireTheirDestinations() throws Exception {
        requireDocker();
        Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(CREDENTIALS).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.addFirst(ec2::close);
        AcmClient acm = AcmClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(CREDENTIALS).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.addFirst(acm::close);
        ElasticLoadBalancingV2Client elb = ElasticLoadBalancingV2Client.builder().endpointOverride(endpoint)
                .region(Region.of(REGION)).credentialsProvider(CREDENTIALS)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(90))).build();
        cleanup.addFirst(elb::close);
        String vpc = ec2.createVpc(r -> r.cidrBlock("10.84.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(r -> r.vpcId(vpc)));
        String subnetA = subnet(ec2, vpc, "10.84.1.0/24", REGION + "a");
        String subnetB = subnet(ec2, vpc, "10.84.2.0/24", REGION + "b");
        GeneratedCertificate first = certificate(FIRST_HOST);
        GeneratedCertificate second = certificate(SECOND_HOST);
        GeneratedCertificate replacement = certificate(FIRST_HOST);
        GeneratedCertificate reimported = certificate(FIRST_HOST);
        String firstCertificate = importCertificate(acm, first);
        String secondCertificate = importCertificate(acm, second);
        String replacementCertificate = importCertificate(acm, replacement);
        LoadBalancer firstLb = balancer(elb, "first", subnetA, subnetB);
        LoadBalancer secondLb = balancer(elb, "second", subnetA, subnetB);
        int port = unusedPort();
        String firstListener = listener(elb, firstLb, port, firstCertificate, "first-default");
        listener(elb, secondLb, port, secondCertificate, "second-default");
        String firstAddress = address(firstLb);
        String secondAddress = address(secondLb);
        assertNotEquals(firstAddress, secondAddress);
        for (boolean sni : List.of(false, true)) {
            assertReply(firstAddress, port, FIRST_HOST, FIRST_HOST, sni, first, "first-default");
            assertReply(secondAddress, port, SECOND_HOST, SECOND_HOST, sni, second, "second-default");
        }
        assertReply(firstAddress, port, FIRST_HOST, SECOND_HOST, false, first, "first-default");
        assertThrows(SSLHandshakeException.class,
                () -> request(firstAddress, port, SECOND_HOST, SECOND_HOST, false, first, second));
        assertThrows(SSLHandshakeException.class,
                () -> request(firstAddress, port, SECOND_HOST, SECOND_HOST, true, first, second));
        elb.modifyListener(r -> r.listenerArn(firstListener)
                .certificates(c -> c.certificateArn(replacementCertificate)));
        assertEquals(firstAddress, address(firstLb), "Certificate replacement must retain the destination");
        assertReply(firstAddress, port, FIRST_HOST, FIRST_HOST, false, replacement, "first-default");
        acm.importCertificate(r -> r.certificateArn(replacementCertificate)
                .certificate(SdkBytes.fromUtf8String(reimported.certificatePem()))
                .privateKey(SdkBytes.fromUtf8String(reimported.privateKeyPem())));
        assertReply(firstAddress, port, FIRST_HOST, FIRST_HOST, false, reimported, "first-default");
        Container proxy = ownedContainers(firstLb.loadBalancerArn()).stream()
                .filter(container -> firstListener.equals(container.getLabels().get(LISTENER_LABEL)))
                .findFirst().orElseThrow();
        docker.stopContainerCmd(proxy.getId()).withTimeout(1).exec();
        elb.modifyListener(r -> r.listenerArn(firstListener).port(port));
        assertEquals(firstAddress, address(firstLb));
        assertReply(firstAddress, port, FIRST_HOST, FIRST_HOST, false, reimported, "first-default");
        assertReply(secondAddress, port, SECOND_HOST, SECOND_HOST, false, second, "second-default");
        elb.deleteListener(r -> r.listenerArn(firstListener));
        assertTrue(frontends.addresses(firstLb.loadBalancerArn()).isEmpty());
        assertTrue(dns.resolveAliasTarget(firstLb.dnsName(), firstLb.canonicalHostedZoneId(), 1)
                .orElseThrow().isEmpty());
        assertTrue(ownedContainers(firstLb.loadBalancerArn()).isEmpty());
        assertThrows(IOException.class, () -> request(firstAddress, port, FIRST_HOST, FIRST_HOST, false, reimported));
        assertReply(secondAddress, port, SECOND_HOST, SECOND_HOST, false, second, "second-default");
    }

    @Test
    void ownedProxyPreservesSocketIdentityAndCleanupDoesNotRemoveAForeignNamespaceConsumer() throws Exception {
        requireDocker();
        String balancer = "arn:aws:elasticloadbalancing:us-east-1:111111111111:loadbalancer/app/native-fixture/123";
        String listener = "arn:aws:elasticloadbalancing:us-east-1:111111111111:listener/app/native-fixture/123/456";
        int port = unusedPort();
        AtomicReference<String> clientAddress = new AtomicReference<>();
        AtomicReference<String> destinationAddress = new AtomicReference<>();
        frontends.putListener(balancer, listener, port, null, request -> {
            clientAddress.set(request.remoteAddress().hostAddress());
            destinationAddress.set(request.localAddress().hostAddress());
            request.response().end("native-peer");
        });
        cleanup.addFirst(() -> frontends.removeListener(balancer, listener));
        String address = frontends.addresses(balancer).getFirst();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 3000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: fixture.example\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            assertTrue(new String(socket.getInputStream().readNBytes(4096), StandardCharsets.US_ASCII)
                    .endsWith("native-peer"));
            assertEquals(socket.getLocalAddress().getHostAddress(), clientAddress.get());
            assertEquals(address, destinationAddress.get());
        }
        Container namespace = ownedContainers(balancer).stream()
                .filter(container -> !container.getLabels().containsKey(LISTENER_LABEL)).findFirst().orElseThrow();
        String foreign = lifecycle.createAndStart(builder
                .newContainer(config.network().securityGroupEnforcement().helperImage())
                .withName("floci-native-foreign-" + UUID.randomUUID())
                .withNetworkMode("container:" + namespace.getId())
                .withEntrypoint(List.of("tail")).withCmd(List.of("-f", "/dev/null")).build()).containerId();
        cleanup.addFirst(() -> lifecycle.removeIfExistsStrict(foreign));
        assertThrows(IllegalStateException.class, () -> frontends.removeListener(balancer, listener));
        assertTrue(docker.inspectContainerCmd(foreign).exec().getState().getRunning());
        assertTrue(docker.inspectContainerCmd(namespace.getId()).exec().getState().getRunning());
        assertFalse(frontends.ownsAddress(address));
        lifecycle.removeIfExistsStrict(foreign);
        frontends.removeListener(balancer, listener);
        assertTrue(ownedContainers(balancer).isEmpty());
    }

    private void requireDocker() {
        try {
            Assumptions.assumeTrue("linux".equals(docker.infoCmd().exec().getOsType()), "Linux Docker is required");
        } catch (Exception unavailable) {
            Assumptions.abort("Linux Docker is unavailable");
        }
        assertTrue(frontends.enabled());
    }

    private List<Container> ownedContainers(String balancer) {
        return docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
                BALANCER_LABEL, balancer, ContainerStorageHelper.OWNER_LABEL,
                ContainerStorageHelper.ownerIdentity(config))).exec();
    }

    private String subnet(Ec2Client ec2, String vpc, String cidr, String zone) {
        String id = ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock(cidr).availabilityZone(zone)).subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(r -> r.subnetId(id)));
        return id;
    }

    private String importCertificate(AcmClient acm, GeneratedCertificate certificate) {
        String arn = acm.importCertificate(r -> r.certificate(SdkBytes.fromUtf8String(certificate.certificatePem()))
                .privateKey(SdkBytes.fromUtf8String(certificate.privateKeyPem()))).certificateArn();
        cleanup.addFirst(() -> acm.deleteCertificate(r -> r.certificateArn(arn)));
        return arn;
    }

    private LoadBalancer balancer(ElasticLoadBalancingV2Client elb, String name, String subnetA, String subnetB) {
        LoadBalancer balancer = elb.createLoadBalancer(r -> r.name("native-" + name + "-"
                + UUID.randomUUID().toString().substring(0, 8)).type("application")
                .subnets(subnetA, subnetB).scheme("internet-facing")).loadBalancers().getFirst();
        cleanup.addFirst(() -> elb.deleteLoadBalancer(r -> r.loadBalancerArn(balancer.loadBalancerArn())));
        return balancer;
    }

    private String listener(ElasticLoadBalancingV2Client elb, LoadBalancer balancer, int port,
                            String certificate, String body) {
        Action action = Action.builder().type("fixed-response")
                .fixedResponseConfig(r -> r.statusCode("200").contentType("text/plain").messageBody(body)).build();
        return elb.createListener(r -> r.loadBalancerArn(balancer.loadBalancerArn()).protocol("HTTPS").port(port)
                .certificates(c -> c.certificateArn(certificate)).defaultActions(action))
                .listeners().getFirst().listenerArn();
    }

    private String address(LoadBalancer balancer) {
        List<String> addresses = dns.resolveAliasTarget(balancer.dnsName(), balancer.canonicalHostedZoneId(), 1)
                .orElseThrow().addresses();
        assertEquals(frontends.addresses(balancer.loadBalancerArn()), addresses);
        assertEquals(1, addresses.size());
        return addresses.getFirst();
    }

    private static GeneratedCertificate certificate(String host) {
        return new CertificateGenerator().generateSelfSignedCertificate(host, List.of(), KeyAlgorithm.RSA_2048);
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertReply(String address, int port, String host, String authority, boolean sni,
                                    GeneratedCertificate expected, String body) throws Exception {
        Reply reply = request(address, port, host, authority, sni, expected);
        assertEquals(Pem.parseCertificate(expected.certificatePem()), reply.certificate());
        assertTrue(reply.http().startsWith("HTTP/1.1 200"), reply.http());
        assertTrue(reply.http().endsWith(body), reply.http());
    }

    private static Reply request(String address, int port, String host, String authority, boolean sni,
                                  GeneratedCertificate... trusted) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        for (int index = 0; index < trusted.length; index++) {
            store.setCertificateEntry(Integer.toString(index), Pem.parseCertificate(trusted[index].certificatePem()));
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(address, port), 3000);
            raw.setSoTimeout(5000);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(raw, host, port, true)) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(sni ? List.of(new SNIHostName(host)) : List.of());
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                X509Certificate certificate = (X509Certificate) socket.getSession().getPeerCertificates()[0];
                socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + authority
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                return new Reply(certificate,
                        new String(socket.getInputStream().readNBytes(8192), StandardCharsets.US_ASCII));
            }
        }
    }

    private record Reply(X509Certificate certificate, String http) {}
}

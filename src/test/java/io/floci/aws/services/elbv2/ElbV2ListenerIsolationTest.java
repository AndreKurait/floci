package io.floci.aws.services.elbv2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsProxyFixture;
import io.floci.aws.core.common.CertificateReferenceResolver;
import io.floci.aws.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.floci.aws.core.common.Pem;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.CertificateGenerator.GeneratedCertificate;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.elbv2.model.Action;
import io.floci.aws.services.elbv2.model.Listener;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.elbv2.model.Rule;
import io.floci.aws.testutil.FreePorts;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.elbv2.ElbV2PublicationTestSupport.publishRuntime;
import static io.floci.aws.services.elbv2.ElbV2PublicationTestSupport.removeRuntime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Listeners are isolated per load balancer. On AWS every load balancer has its own addresses, so an internal
 * NLB's TCP 443 listener and another load balancer's HTTPS 443 listener serve side by side. Floci gives them one
 * address, so the HTTPS listener must still be selected by its own load balancer's name: by SNI on the TLS
 * proxy's port. Before, a TCP listener created first parked every later listener on its port, so the HTTPS load
 * balancer's SNI route was never registered and its clients reached the emulator's own certificate.
 */
class ElbV2ListenerIsolationTest {

    private static final String REGION = "us-west-2";

    @ParameterizedTest(name = "tcp listener created first: {0}")
    @ValueSource(booleans = {true, false})
    @Timeout(60)
    void anNlbTcpListenerOnTheTlsPortDoesNotParkAnotherLoadBalancersHttpsListener(boolean tcpFirst)
            throws Exception {
        CertificateGenerator generator = new CertificateGenerator();
        GeneratedCertificate apiCertificate = generator.generateSelfSignedCertificate(
                "callbacks.example.test", List.of(), KeyAlgorithm.RSA_2048);
        GeneratedCertificate albCertificate = generator.generateSelfSignedCertificate(
                "service.example.test", List.of(), KeyAlgorithm.RSA_2048);
        SSLContext trust = trust(apiCertificate, albCertificate);
        Vertx vertx = Vertx.vertx();
        ElbV2DataPlane plane = new ElbV2DataPlane();
        HttpServer api = null;
        HttpServer edge = null;
        try {
            api = vertx.createHttpServer(new HttpServerOptions()
                            .setHost("127.0.0.1").setPort(0).setUseProxyProtocol(true)
                            .setSsl(true).setKeyCertOptions(new PemKeyCertOptions()
                                    .setCertValue(Buffer.buffer(apiCertificate.certificatePem()))
                                    .setKeyValue(Buffer.buffer(apiCertificate.privateKeyPem()))))
                    .requestHandler(request -> request.response().end("api-control"))
                    .listen().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            // The emulator's endpoint: the data plane's shared edge first, then the emulator's own API.
            Router router = Router.router(vertx);
            plane.register(router);
            router.route().handler(context -> context.response().end("emulator-api"));
            edge = vertx.createHttpServer().requestHandler(router)
                    .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            int edgePort = edge.actualPort();
            int sharedPort = FreePorts.anyInterfacePort();
            EmulatorConfig config = configuration(0, sharedPort);
            try (TlsProxyFixture proxy = new TlsProxyFixture(vertx, config, api.actualPort(), api.actualPort())) {
                ElbV2TlsReadiness.await(Duration.ofSeconds(5), () ->
                        assertTrue(request(trust, sharedPort, "callbacks.example.test", apiCertificate)
                                .contains("api-control")));
                plane.vertx = vertx;
                plane.config = config;
                plane.tlsProxyServer = proxy.server();
                plane.elbV2Service = mock(ElbV2Service.class);
                plane.certificateResolver = mock(CertificateReferenceResolver.class);

                Listener https = httpsListener(sharedPort);
                when(plane.elbV2Service.getLoadBalancerForListener(
                        REGION, https.getListenerArn(), https.getLoadBalancerArn()))
                        .thenReturn(loadBalancer("alb.example.test"));
                when(plane.certificateResolver.publishedMaterial(
                        https.getListenerArn(), REGION, https.getDefaultCertificate()))
                        .thenReturn(new CertificateMaterial(https.getDefaultCertificate(),
                                albCertificate.certificatePem(), albCertificate.privateKeyPem(), null));
                Listener tcp = tcpListener(sharedPort);
                when(plane.elbV2Service.getLoadBalancerForListener(
                        REGION, tcp.getListenerArn(), tcp.getLoadBalancerArn()))
                        .thenReturn(loadBalancer("nlb.example.test"));

                if (tcpFirst) {
                    publishRuntime(plane, tcp, REGION, sharedPort, List.of(forward()));
                    publishRuntime(plane, https, REGION, sharedPort, List.of(fixedResponse()));
                } else {
                    publishRuntime(plane, https, REGION, sharedPort, List.of(fixedResponse()));
                    publishRuntime(plane, tcp, REGION, sharedPort, List.of(forward()));
                }

                ElbV2TlsReadiness.await(Duration.ofSeconds(5), () ->
                        assertTrue(request(trust, sharedPort, "service.example.test", albCertificate)
                                .contains("load-balancer")));
                assertTrue(request(trust, sharedPort, "callbacks.example.test", apiCertificate).contains("api-control"));
                assertEquals("emulator-api", byHost(edgePort, "nlb.example.test:" + sharedPort),
                        "a TCP listener is never selected by Host");

                removeRuntime(plane, tcp.getListenerArn(), sharedPort);
                assertTrue(request(trust, sharedPort, "service.example.test", albCertificate).contains("load-balancer"));
                removeRuntime(plane, https.getListenerArn(), sharedPort);
            }
        } finally {
            plane.shutdown();
            for (HttpServer server : new HttpServer[]{api, edge}) {
                if (server != null) {
                    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                }
            }
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static LoadBalancer loadBalancer(String dnsName) {
        LoadBalancer loadBalancer = new LoadBalancer();
        loadBalancer.setDnsName(dnsName);
        return loadBalancer;
    }

    private static Listener httpsListener(int port) {
        Listener listener = new Listener();
        listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/alb/1/2");
        listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/alb/1");
        listener.setPort(port);
        listener.setProtocol("HTTPS");
        listener.setDefaultCertificate("arn:aws:acm:us-west-2:000000000000:certificate/alb");
        return listener;
    }

    private static Listener tcpListener(int port) {
        Listener listener = new Listener();
        listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/net/nlb/3/4");
        listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/net/nlb/3");
        listener.setPort(port);
        listener.setProtocol("TCP");
        return listener;
    }

    private static Rule fixedResponse() {
        Action response = new Action();
        response.setType("fixed-response");
        response.setFixedResponseStatusCode("200");
        response.setFixedResponseContentType("text/plain");
        response.setFixedResponseMessageBody("load-balancer");
        return rule(response);
    }

    private static Rule forward() {
        Action forward = new Action();
        forward.setType("forward");
        forward.setTargetGroupArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:targetgroup/nlb-tg/5");
        return rule(forward);
    }

    private static Rule rule(Action action) {
        Rule rule = new Rule();
        rule.setPriority("default");
        rule.setActions(List.of(action));
        return rule;
    }

    private static EmulatorConfig configuration(int publicPort, int sharedPort) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElbV2ServiceConfig elbv2 = mock(EmulatorConfig.ElbV2ServiceConfig.class);
        EmulatorConfig.DnsConfig dns = mock(EmulatorConfig.DnsConfig.class);
        when(config.port()).thenReturn(publicPort);
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(config.dns()).thenReturn(dns);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(tls.awsHttpsPort()).thenReturn(sharedPort);
        when(config.services()).thenReturn(services);
        when(services.elbv2()).thenReturn(elbv2);
        when(elbv2.enabled()).thenReturn(true);
        when(elbv2.mock()).thenReturn(false);
        when(config.security()).thenReturn(mock(EmulatorConfig.SecurityConfig.class));
        return config;
    }

    /** A plain HTTP request to the emulator's endpoint naming {@code host}: the response body. */
    private static String byHost(int port, String host) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return response.substring(response.indexOf("\r\n\r\n") + 4);
        }
    }

    private static SSLContext trust(GeneratedCertificate... certificates) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        for (int index = 0; index < certificates.length; index++) {
            store.setCertificateEntry("certificate-" + index, Pem.parseCertificate(certificates[index].certificatePem()));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static String request(SSLContext trust, int port, String host, GeneratedCertificate expected) throws Exception {
        try (Socket tcp = new Socket()) {
            tcp.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            tcp.setSoTimeout(2000);
            try (SSLSocket socket = (SSLSocket) trust.getSocketFactory().createSocket(tcp, host, port, true)) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(host)));
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                assertEquals(Pem.parseCertificate(expected.certificatePem()), socket.getSession().getPeerCertificates()[0]);
                socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}

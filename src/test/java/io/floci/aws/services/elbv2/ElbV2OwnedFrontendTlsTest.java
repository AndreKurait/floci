package io.floci.aws.services.elbv2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.LoadBalancerProxySources;
import io.floci.aws.config.LoadBalancerProxySources.Backend;
import io.floci.aws.config.LoadBalancerProxySources.Destination;
import io.floci.aws.config.TlsProxyFixture;
import io.floci.aws.core.common.CertificateReferenceResolver;
import io.floci.aws.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.floci.aws.core.common.Pem;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.CertificateGenerator.GeneratedCertificate;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.elbv2.model.Action;
import io.floci.aws.services.elbv2.model.Listener;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.elbv2.model.Rule;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.testutil.FreePorts;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.elbv2.ElbV2PublicationTestSupport.publishRuntime;
import static io.floci.aws.services.elbv2.ElbV2PublicationTestSupport.removeRuntime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElbV2OwnedFrontendTlsTest {
    @Test
    @Timeout(30)
    void acceptedControlPortStillServesHttpAndTheWholeAlbSelectsItsSharedRoute() throws Exception {
        Vertx vertx = Vertx.vertx();
        Vertx localBindings = mock(Vertx.class, delegatesTo(vertx));
        // The real shared server is exercised on its native port, confined to test loopback.
        doAnswer(call -> {
            HttpServerOptions options = call.getArgument(0);
            return vertx.createHttpServer(options.setHost("127.0.0.1"));
        }).when(localBindings).createHttpServer(any(HttpServerOptions.class));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        int sharedPort = FreePorts.anyInterfacePort();
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().awsHttpsPort()).thenReturn(sharedPort);
        LoadBalancerProxySources sources = new LoadBalancerProxySources();
        ElbV2FrontendManager manager = new ElbV2FrontendManager(config, mock(ContainerLifecycleManager.class),
                mock(VpcRoutingManager.class), sources);
        ElbV2VpcNetworkManager networks = mock(ElbV2VpcNetworkManager.class);
        when(networks.frontendsEnabled()).thenReturn(true);
        when(networks.selectFrontend(anyString(), anyString(), anyInt())).thenAnswer(call ->
                manager.select(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(networks.forgetFrontend(anyString())).thenAnswer(call -> manager.forget(call.getArgument(0)));
        ElbV2DataPlane plane = new ElbV2DataPlane((Route53Service) null, networks);
        HttpServer api = vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                .requestHandler(request -> request.response().end("api"))
                .listen().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        GeneratedCertificate certificate = new CertificateGenerator().generateSelfSignedCertificate(
                "first.example.test", List.of(), KeyAlgorithm.RSA_2048);
        try (TlsProxyFixture proxy = new TlsProxyFixture(vertx, config, api.actualPort(), api.actualPort(), sources)) {
            plane.vertx = localBindings;
            plane.config = config;
            plane.tlsProxyServer = proxy.server();
            plane.elbV2Service = mock(ElbV2Service.class);
            plane.certificateResolver = mock(CertificateReferenceResolver.class);
            Listener https = publish(plane, sharedPort, "first", certificate);
            Listener control = plainListener(plane, https.getLoadBalancerArn());
            assertTrue(manager.usesSharedListener(https.getLoadBalancerArn()));
            ElbV2TlsReadiness.await(Duration.ofSeconds(10), () -> {
                try (Socket client = new Socket()) {
                    client.connect(new InetSocketAddress("127.0.0.1", 1080), 1000);
                    client.setSoTimeout(2000);
                    client.getOutputStream().write(("GET / HTTP/1.1\r\nHost: first.elb.example.test"
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(response.startsWith("HTTP/1.1 200") && response.contains("control-port"));
                }
            });
            assertTrue(request(trust(certificate), sharedPort, "127.0.0.4", null, "first.example.test",
                    "first.example.test", true, certificate).contains("first"));
            verify(networks, never()).publishFrontend(anyString(), anyString(), eq(1080), any(), any());
            removeRuntime(plane, control.getListenerArn(), 1080);
            assertFalse(manager.usesSharedListener(https.getLoadBalancerArn()));
            removeRuntime(plane, https.getListenerArn(), sharedPort);
        } finally {
            plane.shutdown();
            api.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(40)
    void noSniUsesOnlyTheDestinationListenersCertificateAndKeepsApiTlsSeparate() throws Exception {
        Vertx vertx = Vertx.vertx();
        ElbV2VpcNetworkManager networks = mock(ElbV2VpcNetworkManager.class);
        when(networks.frontendsEnabled()).thenReturn(true);
        ElbV2DataPlane plane = new ElbV2DataPlane((Route53Service) null, networks);
        CertificateGenerator generator = new CertificateGenerator();
        GeneratedCertificate apiCertificate = generator.generateSelfSignedCertificate(
                "api.example.test", List.of(), KeyAlgorithm.RSA_2048);
        GeneratedCertificate first = generator.generateSelfSignedCertificate(
                "first.example.test", List.of(), KeyAlgorithm.RSA_2048);
        GeneratedCertificate second = generator.generateSelfSignedCertificate(
                "second.example.test", List.of(), KeyAlgorithm.RSA_2048);
        SSLContext trust = trust(apiCertificate, first, second);
        LoadBalancerProxySources sources = new LoadBalancerProxySources();
        Map<String, Object> current = new ConcurrentHashMap<>();
        Object firstNode = new Object();
        Object secondNode = new Object();
        doAnswer(call -> {
            String arn = call.getArgument(0);
            Object owner = call.getArgument(3);
            Backend backend = call.getArgument(4);
            boolean isFirst = arn.contains("/first/");
            current.put(arn, owner);
            sources.register(isFirst ? firstNode : secondNode, isFirst ? "127.0.0.2" : "127.0.0.3",
                    Map.of(new Destination(isFirst ? "172.16.0.10" : "172.16.0.11", 443), backend));
            return null;
        }).when(networks).publishFrontend(anyString(), anyString(), anyInt(), any(), any());
        doAnswer(call -> {
            String arn = call.getArgument(0);
            if (current.remove(arn, call.getArgument(1))) {
                sources.revoke(arn.contains("/first/") ? firstNode : secondNode);
            }
            return null;
        }).when(networks).withdrawFrontend(anyString(), any());
        HttpServer api = vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0)
                        .setUseProxyProtocol(true).setSsl(true).setKeyCertOptions(new PemKeyCertOptions()
                                .setCertValue(Buffer.buffer(apiCertificate.certificatePem()))
                                .setKeyValue(Buffer.buffer(apiCertificate.privateKeyPem()))))
                .requestHandler(request -> request.response().end("native-api"))
                .listen().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int sharedPort = FreePorts.anyInterfacePort();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().awsHttpsPort()).thenReturn(sharedPort);
        when(config.port()).thenReturn(0);
        try (TlsProxyFixture proxy = new TlsProxyFixture(vertx, config, api.actualPort(), api.actualPort(), sources)) {
            plane.vertx = vertx;
            plane.config = config;
            plane.tlsProxyServer = proxy.server();
            plane.elbV2Service = mock(ElbV2Service.class);
            plane.certificateResolver = mock(CertificateReferenceResolver.class);
            Listener firstListener = publish(plane, sharedPort, "first", first);
            Listener secondListener = publish(plane, sharedPort, "second", second);
            ElbV2TlsReadiness.await(Duration.ofSeconds(10), () -> assertTrue(
                    request(trust, sharedPort, "127.0.0.2", "172.16.0.10", "first.example.test",
                            "first.example.test", false, first).contains("first")));
            assertTrue(request(trust, sharedPort, "127.0.0.3", "172.16.0.11", "second.example.test",
                    "second.example.test", false, second).contains("second"));
            assertTrue(request(trust, sharedPort, "127.0.0.2", "172.16.0.10", "first.example.test",
                    "second.example.test", true, first).contains("first"), "Host cannot select another ALB");
            assertThrows(SSLHandshakeException.class, () -> request(trust, sharedPort, "127.0.0.2",
                    "172.16.0.10", "second.example.test", "second.example.test", false, second));
            assertTrue(request(trust, sharedPort, "127.0.0.4", null, "api.example.test",
                    "api.example.test", false, apiCertificate).contains("native-api"));

            GeneratedCertificate rotated = generator.generateSelfSignedCertificate(
                    "first.example.test", List.of(), KeyAlgorithm.RSA_2048);
            publish(plane, sharedPort, "first", rotated);
            SSLContext updated = trust(apiCertificate, rotated, second);
            assertTrue(request(updated, sharedPort, "127.0.0.2", "172.16.0.10", "first.example.test",
                    "first.example.test", false, rotated).contains("first"));
            removeRuntime(plane, firstListener.getListenerArn(), sharedPort);
            assertThrows(Exception.class, () -> request(updated, sharedPort, "127.0.0.2", "172.16.0.10",
                    "first.example.test", "first.example.test", false, rotated));
            assertTrue(request(updated, sharedPort, "127.0.0.3", "172.16.0.11", "second.example.test",
                    "second.example.test", false, second).contains("second"));
            removeRuntime(plane, secondListener.getListenerArn(), sharedPort);
        } finally {
            plane.shutdown();
            api.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static Listener publish(ElbV2DataPlane plane, int port, String name, GeneratedCertificate certificate) {
        Listener listener = new Listener();
        listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:111111111111:listener/app/" + name + "/1/2");
        listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:111111111111:loadbalancer/app/" + name + "/1");
        listener.setPort(port);
        listener.setProtocol("HTTPS");
        listener.setDefaultCertificate("arn:aws:acm:us-west-2:111111111111:certificate/" + name);
        LoadBalancer balancer = new LoadBalancer();
        balancer.setLoadBalancerArn(listener.getLoadBalancerArn());
        balancer.setType("application");
        balancer.setDnsName(name + ".elb.example.test");
        when(plane.elbV2Service.getLoadBalancerForListener("us-west-2", listener.getListenerArn(),
                listener.getLoadBalancerArn())).thenReturn(balancer);
        when(plane.certificateResolver.publishedMaterial(listener.getListenerArn(), "us-west-2",
                listener.getDefaultCertificate())).thenReturn(new CertificateMaterial(
                        listener.getDefaultCertificate(), certificate.certificatePem(), certificate.privateKeyPem(), null));
        Action action = new Action();
        action.setType("fixed-response");
        action.setFixedResponseStatusCode("200");
        action.setFixedResponseMessageBody(name);
        action.setFixedResponseContentType("text/plain");
        Rule rule = new Rule();
        rule.setPriority("default");
        rule.setActions(List.of(action));
        publishRuntime(plane, listener, "us-west-2", port, List.of(rule));
        return listener;
    }

    private static Listener plainListener(ElbV2DataPlane plane, String balancerArn) {
        Listener listener = new Listener();
        listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:111111111111:listener/app/first/1/control");
        listener.setLoadBalancerArn(balancerArn);
        listener.setPort(1080);
        listener.setProtocol("HTTP");
        LoadBalancer balancer = new LoadBalancer();
        balancer.setLoadBalancerArn(balancerArn);
        balancer.setType("application");
        balancer.setDnsName("first.elb.example.test");
        when(plane.elbV2Service.getLoadBalancerForListener("us-west-2", listener.getListenerArn(), balancerArn))
                .thenReturn(balancer);
        Action action = new Action();
        action.setType("fixed-response");
        action.setFixedResponseStatusCode("200");
        action.setFixedResponseMessageBody("control-port");
        action.setFixedResponseContentType("text/plain");
        Rule rule = new Rule();
        rule.setPriority("default");
        rule.setActions(List.of(action));
        publishRuntime(plane, listener, "us-west-2", 1080, List.of(rule));
        return listener;
    }

    private static SSLContext trust(GeneratedCertificate... certificates) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        for (int index = 0; index < certificates.length; index++) {
            store.setCertificateEntry("cert-" + index, Pem.parseCertificate(certificates[index].certificatePem()));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static String request(SSLContext context, int port, String peer, String destination, String name,
                                   String host, boolean sni, GeneratedCertificate expected) throws Exception {
        try (Socket tcp = new Socket()) {
            tcp.bind(new InetSocketAddress(peer, 0));
            tcp.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            tcp.setSoTimeout(2000);
            if (destination != null) {
                tcp.getOutputStream().write(("PROXY TCP4 172.16.0.14 " + destination + " 40000 443\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(tcp, name, port, true)) {
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(sni ? List.of(new SNIHostName(name)) : List.of());
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                assertEquals(Pem.parseCertificate(expected.certificatePem()), tls.getSession().getPeerCertificates()[0]);
                tls.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host
                        + "\r\nForwarded: for=198.51.100.8;host=second.example.test\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                return new String(tls.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}

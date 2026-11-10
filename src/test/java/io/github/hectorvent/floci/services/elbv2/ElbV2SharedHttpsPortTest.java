package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsProxyFixture;
import io.github.hectorvent.floci.config.TlsProxyServer;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.CertificateGenerator.GeneratedCertificate;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

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
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.publishRuntime;
import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.removeRuntime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElbV2SharedHttpsPortTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void latePrivateBindClosesWithoutReplacingTheCurrentRoute(boolean shutdown) {
        ElbV2DataPlane plane = new ElbV2DataPlane();
        plane.config = configuration(4566, 443);
        plane.vertx = mock(Vertx.class);
        plane.tlsProxyServer = mock(TlsProxyServer.class);
        when(plane.tlsProxyServer.reservesPort(443)).thenReturn(true);
        when(plane.tlsProxyServer.registerTlsRoute(eq(443), any(), any())).thenReturn(true);
        plane.elbV2Service = mock(ElbV2Service.class);
        plane.certificateResolver = mock(CertificateReferenceResolver.class);
        Listener listener = listener(443);
        LoadBalancer loadBalancer = new LoadBalancer();
        loadBalancer.setDnsName("alb.example.test");
        when(plane.elbV2Service.getLoadBalancerForListener(
                        "us-west-2", listener.getListenerArn(), listener.getLoadBalancerArn())).thenReturn(loadBalancer);
        GeneratedCertificate certificate = new CertificateGenerator().generateSelfSignedCertificate(
                "service.example.test", List.of(), KeyAlgorithm.RSA_2048);
        when(plane.certificateResolver.publishedMaterial(
                listener.getListenerArn(), "us-west-2", listener.getCertificates().getFirst()))
                .thenReturn(new CertificateMaterial(listener.getCertificates().getFirst(),
                        certificate.certificatePem(), certificate.privateKeyPem(), null));
        HttpServer pendingServer = mock(HttpServer.class);
        HttpServer replacement = mock(HttpServer.class);
        Promise<HttpServer> pendingBind = Promise.promise();
        for (HttpServer server : List.of(pendingServer, replacement)) {
            when(server.requestHandler(any())).thenReturn(server);
            when(server.close()).thenReturn(Future.succeededFuture());
        }
        when(pendingServer.listen()).thenReturn(pendingBind.future());
        when(replacement.listen()).thenReturn(Future.succeededFuture(replacement));
        when(replacement.actualPort()).thenReturn(44444);
        when(plane.vertx.createHttpServer(any(HttpServerOptions.class))).thenReturn(pendingServer, replacement);
        try {
            publishRuntime(plane, listener, "us-west-2", 443, List.of());
            if (shutdown) {
                plane.shutdown();
            } else {
                removeRuntime(plane, listener.getListenerArn(), 443);
                publishRuntime(plane, listener, "us-west-2", 443, List.of());
            }
            pendingBind.complete(pendingServer);
            verify(pendingServer, times(2)).close();
            verify(plane.tlsProxyServer, never()).registerTlsRoute(eq(443), eq(pendingServer), any());
            if (!shutdown) {
                ArgumentCaptor<ToIntFunction<String>> route = ArgumentCaptor.captor();
                verify(plane.tlsProxyServer).registerTlsRoute(eq(443), eq(replacement), route.capture());
                assertEquals(44444, route.getValue().applyAsInt("service.example.test"));
                assertEquals(0, route.getValue().applyAsInt("callbacks.example.test"));
                verify(plane.tlsProxyServer, never()).unregisterTlsRoute(443, replacement);
            }
        } finally {
            plane.shutdown();
        }
    }

    @Test
    @Timeout(30)
    void albCertificateAndTrafficShareTheCallbackPortWithoutReplacingTheApi() throws Exception {
        CertificateGenerator generator = new CertificateGenerator();
        GeneratedCertificate apiCertificate = generator.generateSelfSignedCertificate(
                "callbacks.example.test", List.of(), KeyAlgorithm.RSA_2048);
        GeneratedCertificate albCertificate = generator.generateSelfSignedCertificate(
                "service.example.test", List.of(), KeyAlgorithm.RSA_2048);
        SSLContext trust = trust(apiCertificate, albCertificate);
        int publicPort = FreePorts.anyInterfacePort();
        int sharedPort = FreePorts.anyInterfacePort();
        EmulatorConfig config = configuration(publicPort, sharedPort);
        Vertx vertx = Vertx.vertx();
        ElbV2DataPlane plane = new ElbV2DataPlane();
        HttpServer api = null;
        try {
            api = vertx.createHttpServer(new HttpServerOptions()
                            .setHost("127.0.0.1").setPort(0).setUseProxyProtocol(true)
                            .setSsl(true).setKeyCertOptions(new PemKeyCertOptions()
                                    .setCertValue(Buffer.buffer(apiCertificate.certificatePem()))
                                    .setKeyValue(Buffer.buffer(apiCertificate.privateKeyPem()))))
                    .requestHandler(request -> request.response().end("api-control"))
                    .listen().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            try (TlsProxyFixture proxy = new TlsProxyFixture(vertx, config, api.actualPort(), api.actualPort())) {
                ElbV2TlsReadiness.await(Duration.ofSeconds(5), () ->
                        assertTrue(request(trust, sharedPort, "callbacks.example.test", apiCertificate)
                                .contains("api-control")));
                plane.vertx = vertx;
                plane.config = config;
                plane.tlsProxyServer = proxy.server();
                plane.elbV2Service = mock(ElbV2Service.class);
                plane.certificateResolver = mock(CertificateReferenceResolver.class);
                Listener listener = listener(sharedPort);
                LoadBalancer loadBalancer = new LoadBalancer();
                loadBalancer.setDnsName("alb.example.test");
                when(plane.elbV2Service.getLoadBalancerForListener(
                        "us-west-2", listener.getListenerArn(), listener.getLoadBalancerArn()))
                        .thenReturn(loadBalancer);
                when(plane.certificateResolver.publishedMaterial(
                        listener.getListenerArn(), "us-west-2", listener.getCertificates().getFirst()))
                        .thenReturn(new CertificateMaterial(listener.getCertificates().getFirst(),
                                albCertificate.certificatePem(), albCertificate.privateKeyPem(), null));
                Action response = new Action();
                response.setType("fixed-response");
                response.setFixedResponseStatusCode("200");
                response.setFixedResponseContentType("text/plain");
                response.setFixedResponseMessageBody("load-balancer");
                Rule rule = new Rule();
                rule.setPriority("default");
                rule.setActions(List.of(response));
                publishRuntime(plane, listener, "us-west-2", sharedPort, List.of(rule));

                ElbV2TlsReadiness.await(Duration.ofSeconds(5), () ->
                        assertTrue(request(trust, sharedPort, "service.example.test", albCertificate)
                                .contains("load-balancer")));
                assertTrue(request(trust, sharedPort, "callbacks.example.test", apiCertificate).contains("api-control"));

                GeneratedCertificate replacement = generator.generateSelfSignedCertificate(
                        "service.example.test", List.of(), KeyAlgorithm.RSA_2048);
                when(plane.certificateResolver.publishedMaterial(
                        listener.getListenerArn(), "us-west-2", listener.getCertificates().getFirst()))
                        .thenReturn(new CertificateMaterial(listener.getCertificates().getFirst(),
                                replacement.certificatePem(), replacement.privateKeyPem(), null));
                publishRuntime(plane, listener, "us-west-2", sharedPort, List.of(rule));
                SSLContext rotatedTrust = trust(apiCertificate, replacement);
                assertTrue(request(rotatedTrust, sharedPort, "service.example.test", replacement).contains("load-balancer"));
                assertTrue(request(trust, sharedPort, "callbacks.example.test", apiCertificate).contains("api-control"));

                int movedPort = FreePorts.anyInterfacePort();
                listener.setPort(movedPort);
                publishRuntime(plane, listener, "us-west-2", sharedPort, List.of(rule));
                ElbV2TlsReadiness.await(Duration.ofSeconds(5), () ->
                        assertTrue(request(rotatedTrust, movedPort, "service.example.test", replacement)
                                .contains("load-balancer")));
                assertTrue(request(trust, sharedPort, "callbacks.example.test", apiCertificate).contains("api-control"));
                assertThrows(SSLHandshakeException.class,
                        () -> request(rotatedTrust, sharedPort, "service.example.test", replacement));
                removeRuntime(plane, listener.getListenerArn(), movedPort);
                assertTrue(request(trust, sharedPort, "callbacks.example.test", apiCertificate).contains("api-control"));
            }
        } finally {
            plane.shutdown();
            if (api != null) {
                api.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static Listener listener(int port) {
        Listener listener = new Listener();
        listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/lb/1/2");
        listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/lb/1");
        listener.setPort(port);
        listener.setProtocol("HTTPS");
        listener.setCertificates(List.of("arn:aws:acm:us-west-2:000000000000:certificate/alb"));
        return listener;
    }

    private static EmulatorConfig configuration(int publicPort, int sharedPort) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElbV2ServiceConfig elbv2 = mock(EmulatorConfig.ElbV2ServiceConfig.class);
        when(config.port()).thenReturn(publicPort);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(tls.awsHttpsPort()).thenReturn(sharedPort);
        when(config.services()).thenReturn(services);
        when(services.elbv2()).thenReturn(elbv2);
        when(elbv2.mock()).thenReturn(false);
        when(config.security()).thenReturn(mock(EmulatorConfig.SecurityConfig.class));
        return config;
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

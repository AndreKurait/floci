package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.CertificateGenerator.GeneratedCertificate;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PemKeyCertOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElbV2ListenerTransportTest {
    private static final String FIRST_HOST = "first.example.test";
    private static final String SECOND_HOST = "second.example.test";
    private final Vertx vertx = Vertx.vertx();
    private final List<ElbV2ListenerTransport> transports = new ArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    @AfterEach
    void close() throws Exception {
        try {
            for (ElbV2ListenerTransport transport : transports) {
                transport.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoListenersKeepTheirCertificatesAndClientIdentity(boolean sni) throws Exception {
        GeneratedCertificate first = certificate(FIRST_HOST);
        GeneratedCertificate second = certificate(SECOND_HOST);
        ElbV2ListenerTransport one = start("127.0.0.2", pem(first), "one");
        ElbV2ListenerTransport two = start("127.0.0.3", pem(second), "two");

        Response firstResponse = request(one, "127.0.0.2", FIRST_HOST, sni, first, second);
        Response secondResponse = request(two, "127.0.0.3", SECOND_HOST, sni, first, second);

        assertEquals(Pem.parseCertificate(first.certificatePem()), firstResponse.certificate());
        assertEquals(Pem.parseCertificate(second.certificatePem()), secondResponse.certificate());
        assertTrue(firstResponse.http().endsWith("one|203.0.113.17:32456|127.0.0.2:443|true"));
        assertTrue(secondResponse.http().endsWith("two|203.0.113.17:32456|127.0.0.3:443|true"));
        assertEquals(2, requests.get());
    }

    @Test
    void defaultCertificateCanBeReplacedWithoutChangingTheOwnedEndpoint() throws Exception {
        GeneratedCertificate first = certificate(FIRST_HOST);
        GeneratedCertificate replacement = certificate(FIRST_HOST);
        ElbV2ListenerTransport transport = start("127.0.0.2", pem(first), "one");
        int port = transport.controlPort();
        assertEquals(Pem.parseCertificate(first.certificatePem()),
                request(transport, "127.0.0.2", FIRST_HOST, false, first, replacement).certificate());

        transport.reload(pem(replacement));
        assertEquals(port, transport.controlPort());
        assertEquals(Pem.parseCertificate(replacement.certificatePem()),
                request(transport, "127.0.0.2", FIRST_HOST, false, first, replacement).certificate());

        PemKeyCertOptions invalid = new PemKeyCertOptions().addKeyValue(Buffer.buffer("invalid"))
                .addCertValue(Buffer.buffer("invalid"));
        assertThrows(IllegalStateException.class, () -> transport.reload(invalid));
        assertEquals(Pem.parseCertificate(replacement.certificatePem()),
                request(transport, "127.0.0.2", FIRST_HOST, false, first, replacement).certificate());
    }

    @Test
    void anotherFrontendCannotClaimTheOwnedDestination() throws Exception {
        ElbV2ListenerTransport transport = start("127.0.0.2", null, "one");
        try (Socket socket = socket(transport, "127.0.0.3")) {
            refused(socket, preface("127.0.0.2") + "GET / HTTP/1.0\r\n\r\n");
        }
        assertEquals(0, requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "PROXY TCP4 203.0.113.17 127.0.0.3 32456 443\r\n",
        "PROXY TCP4 203.0.113.17 127.0.0.2 32456 80\r\n",
        "PROXY TCP4 203.0.113.17 127.0.0.2 0 443\r\n",
        "PROXY TCP4 203.0.113.17 127.0.0.2 65536 443\r\n",
        "PROXY TCP4 invalid 127.0.0.2 32456 443\r\n",
        "PROXY TCP6 ::1 ::1 32456 443\r\n",
        "PROXY UNKNOWN\r\n",
        "PROXY TCP4 203.0.113.17 127.0.0.2 32456 443\n",
        "PROXY TCP4 203.0.113.17 127.0.0.2 32456 443 extra\r\n"
    })
    void ownedFrontendCannotChangeOrForgeTheBoundListener(String header) throws Exception {
        ElbV2ListenerTransport transport = start("127.0.0.2", null, "one");
        try (Socket socket = socket(transport, "127.0.0.2")) {
            refused(socket, header + "GET / HTTP/1.0\r\n\r\n");
        }
        assertEquals(0, requests.get());
    }

    @Test
    void closingTheTransportRemovesItsIngressSocket() throws Exception {
        ElbV2ListenerTransport transport = start("127.0.0.2", null, "one");
        int port = transport.controlPort();
        transport.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThrows(ConnectException.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            }
        });
    }

    @Test
    void plainHttpPreservesTheOriginalSocketAddresses() throws Exception {
        ElbV2ListenerTransport transport = start("127.0.0.2", null, "one");
        try (Socket socket = socket(transport, "127.0.0.2")) {
            socket.getOutputStream().write((preface("127.0.0.2")
                    + "GET / HTTP/1.1\r\nHost: unrelated.example.test\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readNBytes(8192), StandardCharsets.US_ASCII);
            assertTrue(response.endsWith("one|203.0.113.17:32456|127.0.0.2:443|false"));
        }
        assertEquals(1, requests.get());
    }

    private ElbV2ListenerTransport start(String address, PemKeyCertOptions certificates, String name)
            throws Exception {
        ElbV2ListenerTransport transport = ElbV2ListenerTransport.start(
                vertx, "127.0.0.1", address, 443, certificates, request -> {
                    requests.incrementAndGet();
                    request.response().end(name + "|" + request.remoteAddress().hostAddress() + ":"
                            + request.remoteAddress().port() + "|" + request.localAddress().hostAddress() + ":"
                            + request.localAddress().port() + "|" + request.isSSL());
                }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        transports.add(transport);
        return transport;
    }

    private static void refused(Socket socket, String data) throws Exception {
        try {
            socket.getOutputStream().write(data.getBytes(StandardCharsets.US_ASCII));
            assertEquals(-1, socket.getInputStream().read());
        } catch (SocketException expected) {
            // A reset also proves this connection was refused before HTTP dispatch.
        }
    }

    private static Socket socket(ElbV2ListenerTransport transport, String source) throws Exception {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(source, 0));
        socket.connect(new InetSocketAddress("127.0.0.1", transport.controlPort()), 2000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private record Response(X509Certificate certificate, String http) {}

    private static Response request(ElbV2ListenerTransport transport, String source, String host, boolean sni,
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
        try (Socket raw = socket(transport, source)) {
            raw.getOutputStream().write(preface(source).getBytes(StandardCharsets.US_ASCII));
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(raw, host, 443, true)) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(sni ? List.of(new SNIHostName(host)) : List.of());
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                X509Certificate certificate = (X509Certificate) socket.getSession().getPeerCertificates()[0];
                socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readNBytes(8192), StandardCharsets.US_ASCII);
                return new Response(certificate, response);
            }
        }
    }

    private static String preface(String destination) {
        return "PROXY TCP4 203.0.113.17 " + destination + " 32456 443\r\n";
    }

    private static GeneratedCertificate certificate(String host) {
        return new CertificateGenerator().generateSelfSignedCertificate(host, List.of(), KeyAlgorithm.RSA_2048);
    }

    private static PemKeyCertOptions pem(GeneratedCertificate certificate) {
        return new PemKeyCertOptions().addKeyValue(Buffer.buffer(certificate.privateKeyPem()))
                .addCertValue(Buffer.buffer(certificate.certificatePem()));
    }
}

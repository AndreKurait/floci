package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.testutil.FreePorts;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TlsProxyPeerTest {
    private Vertx vertx;
    private NetServer backend;
    private NetClient client;
    private TlsProxyServer proxy;
    private SelfSignedCertificate certificate;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (proxy != null) {
            proxy.stop();
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (certificate != null) {
            certificate.delete();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, 127.0.0.1", "true, 127.0.0.1", "false, ::1", "true, ::1"})
    @Timeout(20)
    void preservesOriginalPeerAndPayloadThroughPlainAndTlsConnections(boolean tls, String host) throws Exception {
        NetServerOptions serverOptions = new NetServerOptions().setUseProxyProtocol(true);
        NetClientOptions clientOptions = new NetClientOptions();
        if (tls) {
            certificate = SelfSignedCertificate.create("localhost");
            serverOptions.setSsl(true).setKeyCertOptions(certificate.keyCertOptions());
            clientOptions.setSsl(true).setHostnameVerificationAlgorithm("HTTPS")
                    .setTrustOptions(new PemTrustOptions().addCertPath(certificate.certificatePath()));
        }
        String payload = "GET / HTTP/1.1\r\nHost: localhost\r\n"
                + "Forwarded: for=192.0.2.77\r\nX-Forwarded-For: 192.0.2.88\r\n\r\n";
        CompletableFuture<Observed> observed = new CompletableFuture<>();
        backend = vertx.createNetServer(serverOptions).connectHandler(socket -> {
            Buffer received = Buffer.buffer();
            socket.handler(buffer -> {
                received.appendBuffer(buffer);
                if (received.length() >= payload.length()) {
                    observed.complete(new Observed(socket.remoteAddress(), socket.localAddress(), received.toString()));
                }
            });
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int publicPort = FreePorts.anyInterfacePort();
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tlsConfig = mock(EmulatorConfig.TlsConfig.class);
        when(config.tls()).thenReturn(tlsConfig);
        when(tlsConfig.enabled()).thenReturn(true);
        when(tlsConfig.awsHttpsPort()).thenReturn(0);
        when(config.port()).thenReturn(publicPort);
        when(config.security()).thenReturn(mock(EmulatorConfig.SecurityConfig.class));
        proxy = new TlsProxyServer(vertx, config, host, backend.actualPort(), backend.actualPort());
        client = vertx.createNetClient(clientOptions);
        NetSocket connection = client.connect(publicPort, host, "localhost").toCompletionStage()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        connection.write(payload).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Observed result = observed.get(5, TimeUnit.SECONDS);
        assertEquals(connection.localAddress(), result.source());
        assertEquals(connection.remoteAddress().port(), result.destination().port());
        assertEquals(InetAddress.getByName(connection.remoteAddress().hostAddress()),
                InetAddress.getByName(result.destination().hostAddress()));
        assertEquals(payload, result.payload(), "client headers must remain payload, not replace the socket peer");
    }

    private record Observed(SocketAddress source, SocketAddress destination, String payload) {
    }
}

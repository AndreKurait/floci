package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.testutil.FreePorts;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TlsProxyExternalPeerTest {
    @Test
    @Timeout(20)
    void externalTlsOwnerReceivesTheAcceptedCallerWhenExplicitlyEnabled() throws Exception {
        Vertx vertx = Vertx.vertx();
        SelfSignedCertificate certificate = SelfSignedCertificate.create("localhost");
        TlsProxyServer proxy = null;
        try {
            CompletableFuture<SocketAddress> observed = new CompletableFuture<>();
            NetServer backend = vertx.createNetServer(new NetServerOptions().setUseProxyProtocol(true)
                    .setSsl(true).setKeyCertOptions(certificate.keyCertOptions()))
                    .connectHandler(socket -> socket.handler(bytes -> {
                        observed.complete(socket.remoteAddress());
                        socket.write(bytes);
                    })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            int apiPort = FreePorts.anyInterfacePort();
            int tlsPort = FreePorts.anyInterfacePort();
            when(config.port()).thenReturn(apiPort);
            when(config.tls().enabled()).thenReturn(true);
            when(config.tls().awsHttpsPort()).thenReturn(tlsPort);
            when(config.tls().awsHttpsFallbackPort()).thenReturn(backend.actualPort());
            when(config.tls().awsHttpsFallbackHost()).thenReturn(Optional.of("127.0.0.1"));
            when(config.tls().awsHttpsFallbackProxyProtocol()).thenReturn(true);
            proxy = new TlsProxyServer(vertx, config, "127.0.0.1", 4510, 4511);
            NetClient client = vertx.createNetClient(new NetClientOptions().setSsl(true)
                    .setHostnameVerificationAlgorithm("HTTPS")
                    .setTrustOptions(new PemTrustOptions().addCertPath(certificate.certificatePath())));
            NetSocket connection = client.connect(tlsPort, "127.0.0.1", "localhost")
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            connection.write("native-request").toCompletionStage().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals(connection.localAddress(), observed.get(5, TimeUnit.SECONDS));
        } finally {
            if (proxy != null) {
                proxy.stop();
            }
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            certificate.delete();
        }
    }
}

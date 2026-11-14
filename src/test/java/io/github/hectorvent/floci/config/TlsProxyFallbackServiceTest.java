package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.testing.TlsTrust;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(20)
class TlsProxyFallbackServiceTest {
    private static final String PAYLOAD = "GET /ping HTTP/1.1\r\nHost: retained.example\r\n"
            + "X-Fixture: unchanged\r\n\r\n";
    private final List<SelfSignedCertificate> certificates = new ArrayList<>();
    private Vertx vertx;
    private TlsProxyServer proxy;

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
        certificates.forEach(SelfSignedCertificate::delete);
    }

    @ParameterizedTest
    @CsvSource({"native, true, false", "shared, true, false", "shared, false, false", "api, true, true"})
    void retainsEachTlsOwnerCertificateAndPayload(String owner, boolean registerNative, boolean primaryPort)
            throws Exception {
        Backend api = backend("api.fixture.local", true);
        Backend nativeOwner = backend("native.fixture.local", true);
        Backend shared = backend("shared.fixture.local", false);
        int[] ports = FreePorts.anyInterfacePorts(2);
        EmulatorConfig config = config(ports[0], ports[1], shared.server().actualPort());
        when(config.tls().awsHttpsFallbackHosts()).thenReturn(
                Optional.of(List.of(shared.name(), api.name())));
        proxy = new TlsProxyServer(vertx, config, "127.0.0.1",
                api.server().actualPort(), api.server().actualPort());
        if (registerNative) {
            assertTrue(proxy.registerTlsRoute(ports[1], nativeOwner,
                    name -> name.equals("native.fixture.local") ? nativeOwner.server().actualPort() : 0));
        }
        Backend selected = switch (owner) {
            case "native" -> nativeOwner;
            case "shared" -> shared;
            default -> api;
        };
        NetSocket socket = connect(primaryPort ? ports[0] : ports[1], selected);
        socket.write(PAYLOAD).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Received received = selected.received().get(5, TimeUnit.SECONDS);
        assertEquals(PAYLOAD, received.payload());
        if (selected != shared) {
            assertEquals(socket.localAddress().port(), received.sourcePort(),
                    "Native and API listeners keep the trusted PROXY peer");
        }
        assertFalse(selected != api && api.received().isDone());
        assertFalse(selected != nativeOwner && nativeOwner.received().isDone());
        assertFalse(selected != shared && shared.received().isDone());
    }

    @ParameterizedTest
    @ValueSource(strings = {"shared.fixture.local", "SHARED.FIXTURE.LOCAL."})
    void explicitSharedOwnerWinsOverAnOwnedNativeAlias(String hostname) throws Exception {
        Backend nativeOwner = backend("shared.fixture.local", true);
        Backend shared = backend("shared.fixture.local", false);
        int[] ports = FreePorts.anyInterfacePorts(2);
        EmulatorConfig selected = config(ports[0], ports[1], shared.server().actualPort());
        when(selected.tls().awsHttpsFallbackHosts()).thenReturn(Optional.of(List.of(hostname)));
        proxy = new TlsProxyServer(vertx, selected, "127.0.0.1", 4510, 4511);
        assertTrue(proxy.registerTlsRoute(ports[1], nativeOwner, name -> nativeOwner.server().actualPort()));
        NetSocket socket = connect(ports[1], shared);
        socket.write(PAYLOAD).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(PAYLOAD, shared.received().get(5, TimeUnit.SECONDS).payload());
        assertFalse(nativeOwner.received().isDone());
    }

    @Test
    void absentSharedSelectionRetainsTheOwnedNativeAlias() throws Exception {
        Backend nativeOwner = backend("shared.fixture.local", true);
        Backend shared = backend("shared.fixture.local", false);
        int[] ports = FreePorts.anyInterfacePorts(2);
        proxy = new TlsProxyServer(vertx, config(ports[0], ports[1], shared.server().actualPort()),
                "127.0.0.1", 4510, 4511);
        assertTrue(proxy.registerTlsRoute(ports[1], nativeOwner, name -> nativeOwner.server().actualPort()));
        NetSocket socket = connect(ports[1], nativeOwner);
        socket.write(PAYLOAD).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Received received = nativeOwner.received().get(5, TimeUnit.SECONDS);
        assertEquals(PAYLOAD, received.payload());
        assertEquals(socket.localAddress().port(), received.sourcePort());
        assertFalse(shared.received().isDone());
    }

    @Test
    void unavailableExplicitSharedOwnerDoesNotFallThroughToAnOwnedNativeAlias() throws Exception {
        Backend nativeOwner = backend("shared.fixture.local", true);
        Backend shared = backend("shared.fixture.local", false);
        int fallbackPort = shared.server().actualPort();
        shared.server().close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int[] ports = FreePorts.anyInterfacePorts(2);
        EmulatorConfig selected = config(ports[0], ports[1], fallbackPort);
        when(selected.tls().awsHttpsFallbackHosts()).thenReturn(Optional.of(List.of(shared.name())));
        proxy = new TlsProxyServer(vertx, selected, "127.0.0.1", 4510, 4511);
        assertTrue(proxy.registerTlsRoute(ports[1], nativeOwner, name -> nativeOwner.server().actualPort()));
        assertThrows(ExecutionException.class, () -> connect(ports[1], nativeOwner));
        assertFalse(nativeOwner.received().isDone());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "*.example", "https://peer.example", "peer.example:443", "peer..example",
        "peer.example..", "-peer.example", "peer-.example", "peer_name.example", "127.0.0.1", "::1",
        "peer.example,other.example", " peer.example", "peer.example "})
    void rejectsInvalidSharedHostSelectionBeforeIo(String hostname) {
        Vertx untouched = mock(Vertx.class);
        EmulatorConfig selected = config(4566, 443, 9443);
        when(selected.tls().awsHttpsFallbackHosts()).thenReturn(Optional.of(List.of(hostname)));
        assertThrows(IllegalArgumentException.class, () ->
                new TlsProxyServer(untouched, selected, "127.0.0.1", 4510, 4511));
        verifyNoInteractions(untouched);
    }

    @Test
    void rejectsEquivalentDuplicateSharedNamesBeforeIo() {
        Vertx untouched = mock(Vertx.class);
        EmulatorConfig selected = config(4566, 443, 9443);
        when(selected.tls().awsHttpsFallbackHosts()).thenReturn(
                Optional.of(List.of("peer.example", "PEER.EXAMPLE.")));
        assertThrows(IllegalArgumentException.class, () ->
                new TlsProxyServer(untouched, selected, "127.0.0.1", 4510, 4511));
        verifyNoInteractions(untouched);
    }

    @Test
    void sharedNamesRequireAnEnabledFallbackBeforeIo() {
        Vertx untouched = mock(Vertx.class);
        EmulatorConfig selected = config(4566, 443, 0);
        when(selected.tls().awsHttpsFallbackHosts()).thenReturn(Optional.of(List.of("peer.example")));
        assertThrows(IllegalArgumentException.class, () ->
                new TlsProxyServer(untouched, selected, "127.0.0.1", 4510, 4511));
        verifyNoInteractions(untouched);
    }

    @Test
    void unavailableFallbackDoesNotUseApiCertificate() throws Exception {
        Backend api = backend("api.fixture.local", true);
        Backend shared = backend("shared.fixture.local", false);
        int fallbackPort = shared.server().actualPort();
        shared.server().close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int[] ports = FreePorts.anyInterfacePorts(2);
        proxy = new TlsProxyServer(vertx, config(ports[0], ports[1], fallbackPort), "127.0.0.1",
                api.server().actualPort(), api.server().actualPort());
        assertThrows(ExecutionException.class, () -> connect(ports[1], api));
        assertFalse(api.received().isDone());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 65536, 4566, 443, 4510, 4511})
    void refusesInvalidOrRecursiveFallbackBeforeOpeningSockets(int fallback) {
        Vertx untouched = mock(Vertx.class);
        assertThrows(IllegalArgumentException.class, () ->
                new TlsProxyServer(untouched, config(4566, 443, fallback), "127.0.0.1", 4510, 4511));
        verifyNoInteractions(untouched);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4566})
    void fallbackRequiresDistinctEnabledAwsHttpsPort(int awsPort) {
        Vertx untouched = mock(Vertx.class);
        assertThrows(IllegalArgumentException.class, () ->
                new TlsProxyServer(untouched, config(4566, awsPort, 9443), "127.0.0.1", 4510, 4511));
        verifyNoInteractions(untouched);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "peer.example", "https://127.0.0.1", "127.1", "127.000.0.1",
        "0.0.0.0", "::1", "169.254.169.254", "192.0.2.1", "8.8.8.8", "224.0.0.1", "255.255.255.255",
        "10.0.0.1;false"})
    void rejectsNoncanonicalOrNonprivateFallbackHostsBeforeIo(String host) {
        Vertx untouched = mock(Vertx.class);
        EmulatorConfig selected = config(4566, 443, 9443);
        when(selected.tls().awsHttpsFallbackHost()).thenReturn(Optional.of(host));
        assertThrows(IllegalArgumentException.class, () ->
                new TlsProxyServer(untouched, selected, "127.0.0.1", 4510, 4511));
        verifyNoInteractions(untouched);
    }

    @ParameterizedTest
    @ValueSource(strings = {"172.31.255.254", "240.31.0.2"})
    void aSeparateLocalHostMayUseTheSameHttpsPort(String host) {
        Vertx untouched = mock(Vertx.class);
        EmulatorConfig selected = config(4566, 443, 443);
        when(selected.tls().awsHttpsFallbackHost()).thenReturn(Optional.of(host));
        IllegalStateException allocation = new IllegalStateException("Validated configuration reached allocation");
        when(untouched.createNetClient()).thenThrow(allocation);
        assertSame(allocation, assertThrows(IllegalStateException.class, () ->
                new TlsProxyServer(untouched, selected, "127.0.0.1", 4510, 4511)));
        verify(untouched).createNetClient();
        verifyNoMoreInteractions(untouched);
    }

    @Test
    void unavailableNativeRouteDoesNotUseTheFallbackCertificate() throws Exception {
        Backend shared = backend("shared.fixture.local", false);
        int[] ports = FreePorts.anyInterfacePorts(2);
        proxy = new TlsProxyServer(vertx, config(ports[0], ports[1], shared.server().actualPort()),
                "127.0.0.1", 4510, 4511);
        assertTrue(proxy.registerTlsRoute(ports[1], shared, name -> {
            throw new IllegalStateException("Native owner unavailable");
        }));
        assertThrows(ExecutionException.class, () -> connect(ports[1], shared));
        assertFalse(shared.received().isDone());
    }

    @Test
    void publicAlbTransportKeepsItsCertificateAndAdmittedCallerIdentity() throws Exception {
        Backend nativeOwner = backend("native.fixture.local", true);
        Backend shared = backend("shared.fixture.local", false);
        int[] ports = FreePorts.anyInterfacePorts(2);
        PublicApiProxySources sources = new PublicApiProxySources();
        sources.register(this, "127.0.0.1", Map.of("192.0.2.2", Set.of("172.18.1.10")));
        proxy = new TlsProxyServer(vertx, config(ports[0], ports[1], shared.server().actualPort()),
                "127.0.0.1", 4510, 4511, sources);
        assertTrue(proxy.registerTlsRoute(ports[1], nativeOwner, name -> 0,
                name -> name.equals(nativeOwner.name()) ? nativeOwner.server().actualPort() : 0));
        try (Socket tcp = publicAlbSocket(ports[1]); SSLSocket socket = publicAlbTls(tcp, ports[1], nativeOwner)) {
            socket.getOutputStream().write(PAYLOAD.getBytes(StandardCharsets.US_ASCII));
            Received observed = nativeOwner.received().get(5, TimeUnit.SECONDS);
            assertEquals(PAYLOAD, observed.payload());
            assertEquals(49152, observed.sourcePort());
            assertEquals("172.18.1.10", observed.sourceAddress());
            assertEquals("192.0.2.2", observed.destinationAddress(),
                    "Only the admitted socket preface supplies the native transport identity");
            assertFalse(shared.received().isDone());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "api", "plain", "no-sni"})
    void publicAlbTransportCannotFallBackToAnApiOrNonTlsListener(String request) throws Exception {
        Backend nativeOwner = backend("native.fixture.local", true);
        Backend shared = backend("shared.fixture.local", false);
        int[] ports = FreePorts.anyInterfacePorts(2);
        PublicApiProxySources sources = new PublicApiProxySources();
        sources.register(this, "127.0.0.1", Map.of("192.0.2.2", Set.of("172.18.1.10")));
        proxy = new TlsProxyServer(vertx, config(ports[0], ports[1], shared.server().actualPort()),
                "127.0.0.1", nativeOwner.server().actualPort(), nativeOwner.server().actualPort(), sources);
        assertTrue(proxy.registerTlsRoute(ports[1], nativeOwner, name -> nativeOwner.server().actualPort(),
                name -> 0));
        try (Socket tcp = publicAlbSocket(ports[1])) {
            if ("plain".equals(request)) {
                tcp.getOutputStream().write(PAYLOAD.getBytes(StandardCharsets.US_ASCII));
                assertEquals(-1, tcp.getInputStream().read());
            } else {
                Backend attempted = "api".equals(request) ? shared : nativeOwner;
                assertThrows(SSLException.class,
                        () -> publicAlbTls(tcp, ports[1], attempted, !"no-sni".equals(request)));
            }
            assertFalse(nativeOwner.received().isDone());
            assertFalse(shared.received().isDone());
        }
    }

    private static Socket publicAlbSocket(int port) throws Exception {
        Socket tcp = new Socket();
        tcp.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        tcp.setSoTimeout(5000);
        tcp.getOutputStream().write(
                "PROXY TCP4 172.18.1.10 192.0.2.2 49152 443\r\n".getBytes(StandardCharsets.US_ASCII));
        tcp.getOutputStream().flush();
        return tcp;
    }

    private static SSLSocket publicAlbTls(Socket tcp, int port, Backend backend) throws Exception {
        return publicAlbTls(tcp, port, backend, true);
    }

    private static SSLSocket publicAlbTls(Socket tcp, int port, Backend backend, boolean sni) throws Exception {
        SSLContext trust = TlsTrust.trusting(Pem.parseCertificate(
                Files.readString(Path.of(backend.certificate().certificatePath()))));
        SSLSocket socket = (SSLSocket) trust.getSocketFactory().createSocket(tcp, backend.name(), port, false);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setServerNames(sni ? List.of(new SNIHostName(backend.name())) : List.of());
        socket.setSSLParameters(parameters);
        try {
            socket.startHandshake();
            return socket;
        } catch (Exception failure) {
            socket.close();
            throw failure;
        }
    }

    private NetSocket connect(int port, Backend backend) throws Exception {
        NetClientOptions options = new NetClientOptions().setSsl(true).setConnectTimeout(2000)
                .setHostnameVerificationAlgorithm("HTTPS")
                .setTrustOptions(new PemTrustOptions().addCertPath(backend.certificate().certificatePath()));
        return vertx.createNetClient(options).connect(port, "127.0.0.1", backend.name())
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private Backend backend(String name, boolean proxyProtocol) throws Exception {
        SelfSignedCertificate certificate = SelfSignedCertificate.create(name);
        certificates.add(certificate);
        CompletableFuture<Received> received = new CompletableFuture<>();
        NetServer server = vertx.createNetServer(new NetServerOptions()
                .setSsl(true).setUseProxyProtocol(proxyProtocol).setKeyCertOptions(certificate.keyCertOptions()));
        server.connectHandler(socket -> {
            Buffer accumulated = Buffer.buffer();
            socket.handler(bytes -> {
                accumulated.appendBuffer(bytes);
                if (accumulated.length() >= PAYLOAD.length()) {
                    received.complete(new Received(accumulated.toString(), socket.remoteAddress().port(),
                            socket.remoteAddress().hostAddress(), socket.localAddress().hostAddress()));
                }
            });
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return new Backend(name, server, certificate, received);
    }

    private static EmulatorConfig config(int publicPort, int awsPort, int fallback) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.port()).thenReturn(publicPort);
        when(config.tls()).thenReturn(tls);
        when(config.security()).thenReturn(mock(EmulatorConfig.SecurityConfig.class));
        when(tls.enabled()).thenReturn(true);
        when(tls.awsHttpsPort()).thenReturn(awsPort);
        when(tls.awsHttpsFallbackPort()).thenReturn(fallback);
        return config;
    }

    private record Backend(String name, NetServer server, SelfSignedCertificate certificate,
                           CompletableFuture<Received> received) {
    }

    private record Received(String payload, int sourcePort, String sourceAddress, String destinationAddress) {
    }
}

package io.github.hectorvent.floci.config;

import io.netty.util.NetUtil;
import io.quarkus.runtime.Startup;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

/**
 * A TCP proxy server that enables HTTP and HTTPS on the same port (LocalStack parity).
 *
 * <p>When TLS is enabled, Quarkus serves HTTP on an internal port (4510) and HTTPS on
 * another internal port (4511). This proxy listens on the public Floci port (4566) and
 * inspects the first byte of each incoming connection:
 * <ul>
 *   <li>{@code 0x16} (TLS ClientHello) → proxy to HTTPS backend (port 4511)</li>
 *   <li>Anything else → proxy to HTTP backend (port 4510)</li>
 * </ul>
 *
 * <p>This achieves true dual-protocol support on a single port, matching LocalStack's
 * behavior where both {@code http://localhost:4566} and {@code https://localhost:4566}
 * work simultaneously.
 *
 * <p>The same protocol-detecting handler is also bound on the configurable
 * {@code floci.tls.aws-https-port} (443 by default). CDK/CloudFormation custom-resource
 * {@code cfn-response} callbacks hardcode {@code https://} and ignore the ResponseURL port,
 * so they PUT to 443 regardless of Floci's configured port; binding 443 lets those callbacks
 * reach Floci. The extra binding is skipped when the port is {@code 0} or equals the public port.
 * HTTPS load balancers may register a loopback backend for declared SNI hostnames on this
 * extra port. Other TLS uses the explicitly configured fallback, or the API backend by default.
 *
 * <p>Both listen on the configured {@code quarkus.http.host}, not the loopback pin
 * {@link TlsConfigSource} applies to Quarkus itself, and a non-loopback host needs the same consent
 * as without TLS; see {@link NetworkExposureGuard}.
 *
 * <p>This bean is only active when {@code floci.tls.enabled=true}. When TLS is disabled,
 * Quarkus serves HTTP directly on port 4566 and this proxy is not started.
 */
@ApplicationScoped
@Startup
public class TlsProxyServer {

    private static final Logger LOG = Logger.getLogger(TlsProxyServer.class);

    /** TLS record content type for Handshake (ClientHello). */
    public static final byte TLS_HANDSHAKE = 0x16;

    private static final int HTTP_BACKEND_PORT = TlsConfigSource.HTTP_INTERNAL_PORT;
    private static final int HTTPS_BACKEND_PORT = TlsConfigSource.HTTPS_INTERNAL_PORT;

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final String host;
    private final int httpBackendPort;
    private final int httpsBackendPort;
    private final PublicApiProxySources publicSources;
    private String fallbackHost;
    private Set<String> fallbackNames = Set.of();
    private final List<NetServer> proxyServers = new ArrayList<>();
    private volatile URI localApiUri;
    /**
     * Ports whose bind definitively failed. Absent means bound, or not resolved yet: callers
     * default to treating the port as the proxy's until the outcome is known, which is what stops
     * an ELBv2 listener racing the proxy for it during start-up.
     */
    // Package-private for TlsProxyServerReservedPortTest, which needs to stage a failed bind.
    final Set<Integer> failedPorts = ConcurrentHashMap.newKeySet();
    /**
     * Notified with a port the proxy has just given up on. A listener that yielded while the bind
     * was still unresolved has no other way to learn the port came free, and would otherwise stay
     * registered with nothing serving it.
     */
    private final List<IntConsumer> portReleasedHandlers = new CopyOnWriteArrayList<>();
    private final Map<Integer, SharedTlsRoute> sharedTlsRoutes = new ConcurrentHashMap<>();

    private record SharedTlsRoute(Object owner, ToIntFunction<String> backend, ToIntFunction<String> publicAlb) {}

    /** Registers a loopback TLS backend while this proxy retains the public socket. */
    public boolean registerTlsRoute(int port, Object owner, ToIntFunction<String> backend) {
        return registerTlsRoute(port, owner, backend, ignored -> 0);
    }

    /** The ALB selector admits only an owned internet-facing alias to the isolated public ALB transport. */
    public boolean registerTlsRoute(int port, Object owner, ToIntFunction<String> backend,
                                    ToIntFunction<String> publicAlb) {
        if (port == config.port() || !reservesPort(port)) {
            return false;
        }
        sharedTlsRoutes.compute(port, (ignored, existing) -> {
            if (existing != null && existing.owner() != owner) {
                throw new IllegalStateException("Another listener owns the shared TLS route");
            }
            return new SharedTlsRoute(owner, backend, publicAlb);
        });
        if (!reservesPort(port)) {
            unregisterTlsRoute(port, owner);
            return false;
        }
        return true;
    }

    public void unregisterTlsRoute(int port, Object owner) {
        sharedTlsRoutes.computeIfPresent(port, (ignored, existing) -> existing.owner() == owner ? null : existing);
    }

    /** Registers a callback invoked when a bind fails and the port stops being reserved. */
    public void onPortReleased(IntConsumer handler) {
        portReleasedHandlers.add(handler);
        failedPorts.forEach(handler::accept);
    }
    private NetClient client;

    /** In-process API calls must enter the proxy that supplies the private listener's PROXY preface. */
    public URI localApiEndpoint(URI directListener) {
        if (directListener == null || !config.tls().enabled()) {
            return directListener;
        }
        return localApiUri;
    }

    @Inject
    public TlsProxyServer(Vertx vertx, EmulatorConfig config, Config mpConfig, PublicApiProxySources publicSources) {
        this(vertx, config, NetworkExposureGuard.publicHost(mpConfig), HTTP_BACKEND_PORT, HTTPS_BACKEND_PORT,
                publicSources);
    }

    /** Visible for testing — lets tests point the proxy at backends on non-default ports. */
    TlsProxyServer(Vertx vertx, EmulatorConfig config, String host, int httpBackendPort, int httpsBackendPort) {
        this(vertx, config, host, httpBackendPort, httpsBackendPort, new PublicApiProxySources());
    }

    TlsProxyServer(Vertx vertx, EmulatorConfig config, String host, int httpBackendPort, int httpsBackendPort,
                   PublicApiProxySources publicSources) {
        this.vertx = vertx;
        this.config = config;
        this.host = host;
        this.httpBackendPort = httpBackendPort;
        this.httpsBackendPort = httpsBackendPort;
        this.publicSources = publicSources;
        startIfTlsEnabled();
    }

    private void startIfTlsEnabled() {
        if (!config.tls().enabled()) {
            return;
        }

        validateFallback();
        NetworkExposureGuard.requireConsent(host, config.security());

        client = vertx.createNetClient();
        Handler<NetSocket> connectHandler = buildConnectHandler();

        for (int port : listenPorts()) {
            NetServerOptions options = new NetServerOptions()
                    .setHost(host)
                    .setPort(port);
            NetServer server = vertx.createNetServer(options);
            server.connectHandler(connectHandler);
            proxyServers.add(server);
            Future<NetServer> bind = server.listen();
            bind.onComplete(ar -> {
                if (ar.succeeded()) {
                    failedPorts.remove(port);
                    LOG.infov("TLS proxy: listening on {0}:{1} (HTTP→{2}, HTTPS→{3})",
                            host, String.valueOf(port), String.valueOf(httpBackendPort), String.valueOf(httpsBackendPort));
                } else if (port == config.port()) {
                    LOG.errorv("TLS proxy: failed to start on public port {0}: {1}",
                            String.valueOf(port), ar.cause().getMessage());
                } else {
                    failedPorts.add(port);
                    portReleasedHandlers.forEach(h -> h.accept(port));
                    // The extra AWS-HTTPS port (443 by default) is privileged; binding it fails in
                    // unprivileged environments (e.g. CI/test). Non-fatal — HTTPS on that port is
                    // simply unavailable. Set floci.tls.aws-https-port=0 to skip the attempt.
                    LOG.warnv("TLS proxy: could not bind AWS-HTTPS port {0} ({1}); HTTPS on {0} unavailable. "
                            + "Binding privileged ports needs elevated privileges — set floci.tls.aws-https-port=0 to disable.",
                            String.valueOf(port), ar.cause().getMessage());
                }
            });
            if (port == config.port()) {
                try {
                    bind.toCompletionStage().toCompletableFuture().join();
                    localApiUri = connectUri(host, server.actualPort());
                } catch (RuntimeException e) {
                    stop();
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new IllegalStateException(
                            "TLS proxy failed to bind public port " + port, cause);
                }
            }
        }
    }

    private void validateFallback() {
        Set<String> names = new LinkedHashSet<>();
        for (String name : config.tls().awsHttpsFallbackHosts().orElse(List.of())) {
            String normalized = normalizeHostname(name);
            if (normalized.isEmpty() || normalized.length() > 253
                    || NetUtil.isValidIpV4Address(normalized) || NetUtil.isValidIpV6Address(normalized)
                    || !normalized.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                        + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
                    || !names.add(normalized)) {
                throw new IllegalArgumentException("TLS fallback hosts must be distinct exact DNS hostnames");
            }
        }
        fallbackNames = Set.copyOf(names);
        int fallbackPort = config.tls().awsHttpsFallbackPort();
        if (fallbackPort == 0) {
            if (config.tls().awsHttpsFallbackProxyProtocol()) {
                throw new IllegalArgumentException("External PROXY transport requires a configured TLS fallback");
            }
            if (!fallbackNames.isEmpty()) {
                throw new IllegalArgumentException("Explicit TLS fallback hosts require a fallback destination");
            }
            return;
        }
        int awsPort = config.tls().awsHttpsPort();
        if (fallbackPort < 1 || fallbackPort > 65535 || awsPort < 1 || awsPort > 65535
                || awsPort == config.port()) {
            throw new IllegalArgumentException("TLS fallback requires valid, distinct AWS HTTPS and fallback ports");
        }
        String selected = config.tls().awsHttpsFallbackHost().orElse("127.0.0.1");
        if (!NetUtil.isValidIpV4Address(selected)) {
            throw new IllegalArgumentException("TLS fallback must be an explicit local IPv4 address");
        }
        try {
            InetAddress address = InetAddress.getByName(selected);
            boolean reservedEmulation = Byte.toUnsignedInt(address.getAddress()[0]) >= 240
                    && !"255.255.255.255".equals(selected);
            if (!address.getHostAddress().equals(selected)
                    || (!address.isLoopbackAddress() && !address.isSiteLocalAddress() && !reservedEmulation)) {
                throw new IllegalArgumentException("TLS fallback must be a canonical local IPv4 address");
            }
            boolean local = address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null;
            if (local && (fallbackPort == config.port() || fallbackPort == awsPort
                    || fallbackPort == httpBackendPort || fallbackPort == httpsBackendPort)) {
                throw new IllegalArgumentException("TLS fallback cannot point to a local proxy or private API listener");
            }
            fallbackHost = selected;
        } catch (UnknownHostException | SocketException failure) {
            throw new IllegalArgumentException("Cannot validate the TLS fallback address", failure);
        }
    }

    private static String normalizeHostname(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static URI connectUri(String host, int port) {
        try {
            InetAddress address = InetAddress.getByName(host);
            String connectHost = address.isAnyLocalAddress()
                    ? (address instanceof Inet6Address ? "::1" : "127.0.0.1") : host;
            return new URI("http", null, connectHost, port, "/", null, null);
        } catch (UnknownHostException | URISyntaxException error) {
            throw new IllegalArgumentException("Cannot address the bound local API listener", error);
        }
    }

    /**
     * Whether the proxy holds, or is still expected to hold, {@code port}.
     *
     * <p>False once a bind has definitively failed. That is not fatal for the AWS-HTTPS port: it is
     * privileged, so binding it fails in unprivileged environments and HTTPS there is simply
     * unavailable. Anything that yields ports to the proxy has to consult this rather than
     * configuration alone, or a port the proxy never acquired ends up served by nobody.
     */
    public boolean reservesPort(int port) {
        return config.tls().enabled() && listenPorts().contains(port) && !failedPorts.contains(port);
    }

    /**
     * The set of ports the proxy listens on: always the public Floci {@link EmulatorConfig#port()},
     * plus {@code floci.tls.aws-https-port} (443 by default) so AWS-style HTTPS callbacks reach
     * Floci. Deduplicated (a coinciding aws-https-port yields a single listener); a non-positive
     * aws-https-port disables the extra binding.
     */
    Set<Integer> listenPorts() {
        Set<Integer> ports = new LinkedHashSet<>();
        ports.add(config.port());
        int awsHttpsPort = config.tls().awsHttpsPort();
        if (awsHttpsPort > 0) {
            ports.add(awsHttpsPort);
        }
        return ports;
    }

    /**
     * Builds the shared connect handler that peeks the first byte to detect TLS and pipes the
     * connection to the matching backend. A single instance is reused across all listen ports.
     */
    private Handler<NetSocket> buildConnectHandler() {
        return frontSocket -> {
            frontSocket.pause();
            int port = frontSocket.localAddress().port();
            PublicApiProxySources.Ticket ticket = port == config.tls().awsHttpsPort()
                    ? publicSources.ticket(frontSocket.remoteAddress().hostAddress()) : null;
            if (ticket != null) {
                readPublicProxy(frontSocket, ticket);
            } else {
                readFirst(frontSocket, new PublicApiProxySources.Addresses(
                        frontSocket.remoteAddress(), frontSocket.localAddress()));
            }
            frontSocket.resume();
        };
    }

    private void readFirst(NetSocket socket, PublicApiProxySources.Addresses addresses) {
        socket.handler(buffer -> dispatch(socket, buffer, addresses));
    }

    private void readPublicProxy(NetSocket socket, PublicApiProxySources.Ticket ticket) {
        StringBuilder header = new StringBuilder();
        long timer = vertx.setTimer(5000, ignored -> socket.close());
        socket.closeHandler(ignored -> vertx.cancelTimer(timer));
        socket.handler(buffer -> {
            try {
                for (int index = 0; index < buffer.length(); index++) {
                    int value = Byte.toUnsignedInt(buffer.getByte(index));
                    if (value > 127 || header.length() == 108) {
                        throw new IllegalArgumentException("Invalid public bridge preface");
                    }
                    header.append((char) value);
                    if (value == '\n') {
                        PublicApiProxySources.Addresses addresses = publicSources.decode(ticket, header.toString());
                        vertx.cancelTimer(timer);
                        if (index + 1 < buffer.length()) {
                            dispatch(socket, buffer.getBuffer(index + 1, buffer.length()), addresses);
                        } else {
                            readFirst(socket, addresses);
                        }
                        return;
                    }
                }
            } catch (IllegalArgumentException failure) {
                LOG.debug("TLS proxy: rejected public bridge preface");
                socket.close();
            }
        });
    }

    private void dispatch(NetSocket socket, Buffer buffer, PublicApiProxySources.Addresses addresses) {
        socket.handler(null);
        socket.pause();
        boolean tls = buffer.length() > 0 && buffer.getByte(0) == TLS_HANDSHAKE;
        int port = socket.localAddress().port();
        if (publicAlbTransport(addresses) && (!tls || !sharedTlsRoutes.containsKey(port))) {
            socket.close();
            return;
        }
        if (tls && sharedTlsRoutes.containsKey(port)) {
            readClientHello(socket, buffer, port, addresses);
        } else if (tls) {
            connectTlsBackend(socket, buffer, port, 0, addresses);
        } else {
            connectBackend(socket, buffer, httpBackendPort, addresses);
        }
    }

    private void readClientHello(NetSocket socket, Buffer first, int port, PublicApiProxySources.Addresses addresses) {
        TlsClientHello hello = new TlsClientHello();
        Buffer pending = Buffer.buffer();
        long timer = vertx.setTimer(5000, ignored -> socket.close());
        socket.closeHandler(ignored -> {
            vertx.cancelTimer(timer);
            hello.close();
        });
        Handler<Buffer> read = bytes -> {
            try {
                if (bytes.length() > 131072 - pending.length()) {
                    socket.close();
                    return;
                }
                pending.appendBuffer(bytes);
                if (!hello.append(bytes)) {
                    socket.resume();
                    return;
                }
                socket.pause();
                socket.handler(null);
                vertx.cancelTimer(timer);
                SharedTlsRoute route = sharedTlsRoutes.get(port);
                boolean shared = port == config.tls().awsHttpsPort() && port != config.port()
                        && fallbackNames.contains(normalizeHostname(hello.hostname()));
                int selected = 0;
                if (route != null && hello.hostname() != null) {
                    if (publicAlbTransport(addresses)) {
                        selected = route.publicAlb().applyAsInt(hello.hostname());
                    } else if (!shared) {
                        selected = route.backend().applyAsInt(hello.hostname());
                    }
                }
                hello.close();
                connectTlsBackend(socket, pending, port, selected, addresses);
            } catch (RuntimeException failure) {
                LOG.debugv("TLS proxy: invalid or unavailable shared TLS route: {0}", failure.getMessage());
                socket.close();
            }
        };
        socket.handler(read);
        read.handle(first);
    }

    private void connectTlsBackend(NetSocket socket, Buffer buffer, int port, int nativePort,
                                   PublicApiProxySources.Addresses addresses) {
        if (nativePort > 0) {
            connectBackend(socket, buffer, nativePort, addresses);
        } else if (publicAlbTransport(addresses)) {
            socket.close();
        } else if (fallbackHost != null && port == config.tls().awsHttpsPort() && port != config.port()) {
            connectBackend(socket, buffer, fallbackHost, config.tls().awsHttpsFallbackPort(),
                    config.tls().awsHttpsFallbackProxyProtocol(), addresses);
        } else {
            connectBackend(socket, buffer, httpsBackendPort, addresses);
        }
    }

    private static boolean publicAlbTransport(PublicApiProxySources.Addresses addresses) {
        return EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS.equals(addresses.destination().hostAddress());
    }

    private void connectBackend(NetSocket frontSocket, Buffer buffer, int backendPort,
                                PublicApiProxySources.Addresses addresses) {
        connectBackend(frontSocket, buffer, "127.0.0.1", backendPort, true, addresses);
    }

    private void connectBackend(NetSocket frontSocket, Buffer buffer, String backendHost,
                                int backendPort, boolean proxyPreface, PublicApiProxySources.Addresses addresses) {
        client.connect(backendPort, backendHost).onComplete(ar -> {
            if (ar.succeeded()) {
                NetSocket backSocket = ar.result();

                Buffer initial = Buffer.buffer();
                if (proxyPreface) {
                    SocketAddress source = addresses.source();
                    SocketAddress destination = addresses.destination();
                    String family = source.hostAddress().contains(":") ? "TCP6" : "TCP4";
                    initial.appendString("PROXY " + family + " " + source.hostAddress() + " "
                            + destination.hostAddress() + " " + source.port() + " " + destination.port() + "\r\n");
                }
                backSocket.write(initial.appendBuffer(buffer));

                // pipeTo propagates end-of-stream and resumes the paused client.
                frontSocket.pipeTo(backSocket).onFailure(err ->
                        LOG.debugv("TLS proxy: pipe front→back failed: {0}", err.getMessage()));
                backSocket.pipeTo(frontSocket).onFailure(err ->
                        LOG.debugv("TLS proxy: pipe back→front failed: {0}", err.getMessage()));
            } else {
                LOG.warnv("TLS proxy: failed to connect to backend port {0}: {1}",
                        String.valueOf(backendPort), ar.cause().getMessage());
                frontSocket.close();
            }
        });
    }

    @PreDestroy
    void stop() {
        localApiUri = null;
        sharedTlsRoutes.clear();
        for (NetServer server : proxyServers) {
            server.close();
        }
        if (client != null) {
            client.close();
        }
        LOG.info("TLS proxy: stopped");
    }
}

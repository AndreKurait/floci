package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.tls.ReloadingKeyManager;
import io.netty.util.NetUtil;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A listener-specific TLS endpoint behind one owned frontend namespace.
 * The namespace supplies the original socket addresses; it cannot select another listener.
 */
final class ElbV2ListenerTransport {
    private static final Logger LOG = Logger.getLogger(ElbV2ListenerTransport.class);
    private static final int MAX_PROXY_HEADER = 108;
    private final Vertx vertx;
    private final String frontendAddress;
    private final int listenerPort;
    private final ReloadingKeyManager keyManager;
    private final HttpServer listener;
    private final NetServer ingress;
    private final NetClient client;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ElbV2ListenerTransport(Vertx vertx, String frontendAddress, int listenerPort,
                                  ReloadingKeyManager keyManager, HttpServer listener,
                                  NetServer ingress, NetClient client) {
        this.vertx = vertx;
        this.frontendAddress = frontendAddress;
        this.listenerPort = listenerPort;
        this.keyManager = keyManager;
        this.listener = listener;
        this.ingress = ingress;
        this.client = client;
    }

    static Future<ElbV2ListenerTransport> start(Vertx vertx, String controllerAddress,
                                               String frontendAddress, int listenerPort,
                                               KeyCertOptions certificates,
                                               Handler<HttpServerRequest> handler) {
        requireAddress(controllerAddress);
        requireAddress(frontendAddress);
        if (listenerPort < 1 || listenerPort > 65535) {
            throw new IllegalArgumentException("A native listener requires a valid TCP port");
        }
        HttpServerOptions options = new HttpServerOptions()
                .setHost("127.0.0.1").setPort(0).setUseProxyProtocol(true)
                .setMaxHeaderSize(ElbV2DataPlane.MAX_REQUEST_HEADER_SIZE)
                .setMaxInitialLineLength(ElbV2DataPlane.MAX_REQUEST_LINE_LENGTH);
        ReloadingKeyManager manager = certificates == null ? null
                : new ReloadingKeyManager(new ElbV2TlsKeyManager(vertx, certificates));
        if (manager != null) {
            options.setSsl(true).setSslEngineOptions(new JdkSSLEngineOptions())
                    .setKeyCertOptions(KeyCertOptions.wrap(manager));
        }
        HttpServer listener = vertx.createHttpServer(options).requestHandler(handler);
        NetServer ingress = vertx.createNetServer(new NetServerOptions()
                .setHost(controllerAddress).setPort(0));
        NetClient client = vertx.createNetClient();
        ElbV2ListenerTransport transport = new ElbV2ListenerTransport(
                vertx, frontendAddress, listenerPort, manager, listener, ingress, client);
        ingress.connectHandler(transport::accept);
        return listener.listen().compose(ignored -> ingress.listen())
                .map(ignored -> transport)
                .recover(failure -> transport.close().recover(cleanup -> {
                    failure.addSuppressed(cleanup);
                    return Future.succeededFuture();
                }).compose(ignored -> Future.failedFuture(failure)));
    }

    int controlPort() {
        return ingress.actualPort();
    }

    void reload(KeyCertOptions certificates) {
        if (closed.get() || keyManager == null || certificates == null) {
            throw new IllegalStateException("Only a running HTTPS listener can reload its certificates");
        }
        // Construct before publishing, so an invalid replacement retains the prior material.
        keyManager.reload(new ElbV2TlsKeyManager(vertx, certificates));
    }

    Future<Void> close() {
        closed.set(true);
        return Future.join(ingress.close(), listener.close(), client.close()).mapEmpty();
    }

    private void accept(NetSocket socket) {
        if (closed.get() || !frontendAddress.equals(socket.remoteAddress().hostAddress())) {
            socket.close();
            return;
        }
        StringBuilder header = new StringBuilder();
        long timer = vertx.setTimer(5000, ignored -> socket.close());
        socket.closeHandler(ignored -> vertx.cancelTimer(timer));
        socket.handler(bytes -> {
            try {
                for (int index = 0; index < bytes.length(); index++) {
                    int value = Byte.toUnsignedInt(bytes.getByte(index));
                    if (value > 127 || header.length() == MAX_PROXY_HEADER) {
                        throw new IllegalArgumentException("Invalid native frontend preface");
                    }
                    header.append((char) value);
                    if (value == '\n') {
                        validatePreface(header.toString(), frontendAddress, listenerPort);
                        socket.pause();
                        socket.handler(null);
                        vertx.cancelTimer(timer);
                        connect(socket, Buffer.buffer(header.toString())
                                .appendBuffer(bytes.getBuffer(index + 1, bytes.length())));
                        return;
                    }
                }
            } catch (IllegalArgumentException failure) {
                LOG.debug("Rejected native load-balancer frontend preface");
                socket.close();
            }
        });
    }

    private void connect(NetSocket socket, Buffer first) {
        client.connect(listener.actualPort(), "127.0.0.1").onComplete(result -> {
            if (result.failed() || closed.get()) {
                if (result.succeeded()) {
                    result.result().close();
                }
                socket.close();
                return;
            }
            NetSocket backend = result.result();
            backend.write(first);
            socket.pipeTo(backend).onFailure(failure ->
                    LOG.debugv("Native frontend request transport closed: {0}", failure.getMessage()));
            backend.pipeTo(socket).onFailure(failure ->
                    LOG.debugv("Native frontend response transport closed: {0}", failure.getMessage()));
        });
    }

    static void validatePreface(String header, String frontendAddress, int listenerPort) {
        if (!header.endsWith("\r\n") || header.length() > MAX_PROXY_HEADER) {
            throw new IllegalArgumentException("Invalid native frontend preface");
        }
        String[] fields = header.substring(0, header.length() - 2).split(" ", -1);
        if (fields.length != 6 || !"PROXY".equals(fields[0]) || !"TCP4".equals(fields[1])
                || !frontendAddress.equals(fields[3])) {
            throw new IllegalArgumentException("Native frontend destination changed");
        }
        requireAddress(fields[2]);
        requirePort(fields[4]);
        if (requirePort(fields[5]) != listenerPort) {
            throw new IllegalArgumentException("Native frontend listener changed");
        }
    }

    private static int requirePort(String value) {
        if (!value.matches("[1-9][0-9]{0,4}")) {
            throw new IllegalArgumentException("Invalid native frontend TCP port");
        }
        int port = Integer.parseInt(value);
        if (port > 65535) {
            throw new IllegalArgumentException("Invalid native frontend TCP port");
        }
        return port;
    }

    private static void requireAddress(String address) {
        if (address == null || !NetUtil.isValidIpV4Address(address)) {
            throw new IllegalArgumentException("A native frontend requires an IPv4 address");
        }
    }
}

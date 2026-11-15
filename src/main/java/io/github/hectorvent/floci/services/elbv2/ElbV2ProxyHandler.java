package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.vertx.http.runtime.ProxyConfig;
import io.quarkus.vertx.http.runtime.options.HttpServerCommonHandlers;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.http.impl.HttpServerRequestWrapper;
import io.vertx.core.net.SocketAddress;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies proxy identity checks without changing the resource listener's routing inputs.
 */
final class ElbV2ProxyHandler implements Handler<HttpServerRequest> {
    private final Handler<HttpServerRequest> delegate;
    private final Handler<HttpServerRequest> proxy;
    private final Map<HttpServerResponse, PendingRequest> pending = new ConcurrentHashMap<>();

    static Handler<HttpServerRequest> create(ProxyConfig config, Vertx vertx,
                                             Handler<HttpServerRequest> delegate) {
        if (!config.proxyAddressForwarding() || !config.trustedProxy().isEmpty()) {
            // Resource listeners do not authenticate client certificates.
            return delegate;
        }
        return new ElbV2ProxyHandler(config, vertx, delegate);
    }

    private ElbV2ProxyHandler(ProxyConfig config, Vertx vertx, Handler<HttpServerRequest> delegate) {
        this.delegate = delegate;
        this.proxy = HttpServerCommonHandlers.applyProxy(config, this::forward, () -> vertx,
                ClientAuth.NONE, Optional.empty(), "quarkus.http");
    }

    @Override
    public void handle(HttpServerRequest request) {
        HttpServerResponse response = request.response();
        pending.put(response, new PendingRequest(request, request.getHeader("Host")));
        response.endHandler(ignored -> {
            if (pending.remove(response) != null) {
                request.resume();
            }
        });
        response.closeHandler(ignored -> pending.remove(response));
        request.pause();
        try {
            proxy.handle(request);
        } catch (RuntimeException failure) {
            pending.remove(response);
            request.resume();
            throw failure;
        }
    }

    private void forward(HttpServerRequest forwarded) {
        PendingRequest original = pending.remove(forwarded.response());
        if (original == null) {
            return;
        }
        SocketAddress address = forwarded.remoteAddress();
        HttpServerRequest request = original.request();
        if (original.host() == null) {
            request.headers().remove("Host");
        } else {
            request.headers().set("Host", original.host());
        }
        ClientAddressRequest addressed = new ClientAddressRequest(request, address);
        try {
            delegate.handle(addressed);
        } finally {
            if (!addressed.paused) {
                request.resume();
            }
        }
    }

    private record PendingRequest(HttpServerRequest request, String host) {}

    private static final class ClientAddressRequest extends HttpServerRequestWrapper {
        private final SocketAddress address;
        private boolean paused;

        ClientAddressRequest(HttpServerRequest request, SocketAddress address) {
            super((HttpServerRequestInternal) request);
            this.address = address;
        }

        @Override
        public SocketAddress remoteAddress() {
            return address;
        }

        @Override
        public HttpServerRequest pause() {
            paused = true;
            super.pause();
            return this;
        }

        @Override
        public HttpServerRequest resume() {
            paused = false;
            super.resume();
            return this;
        }
    }
}

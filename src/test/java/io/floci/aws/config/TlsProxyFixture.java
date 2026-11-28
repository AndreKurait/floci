package io.floci.aws.config;

import io.vertx.core.Vertx;

/** Runs the production proxy with isolated test backend ports. */
public final class TlsProxyFixture implements AutoCloseable {
    private final TlsProxyServer server;

    public TlsProxyFixture(Vertx vertx, EmulatorConfig config, int httpPort, int httpsPort) {
        server = new TlsProxyServer(vertx, config, "127.0.0.1", httpPort, httpsPort);
    }

    public TlsProxyServer server() {
        return server;
    }

    @Override
    public void close() {
        server.stop();
    }
}

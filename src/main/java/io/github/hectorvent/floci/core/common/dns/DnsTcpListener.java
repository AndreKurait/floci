package io.github.hectorvent.floci.core.common.dns;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** DNS TCP framing, connection reuse, and bounded connection/request admission. */
final class DnsTcpListener {

    static final int MAX_MESSAGE_SIZE = 65535;
    static final int MAX_CONNECTIONS = 128;
    static final int MAX_PENDING_QUERIES = 16;
    private static final Logger LOG = Logger.getLogger(DnsTcpListener.class);

    private DnsTcpListener() {}

    static Future<NetServer> listen(Vertx vertx, String address, int port,
                                   BiFunction<byte[], String, Future<byte[]>> queryHandler) {
        AtomicInteger connections = new AtomicInteger();
        NetServer server = vertx.createNetServer(new NetServerOptions().setIdleTimeout(30));
        server.connectHandler(socket -> {
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet();
                socket.close();
                return;
            }
            new Connection(socket, connections, queryHandler).start();
        });
        return server.listen(port, address).onFailure(error -> server.close());
    }

    private static final class Connection {
        private final NetSocket socket;
        private final AtomicInteger connections;
        private final BiFunction<byte[], String, Future<byte[]>> queryHandler;
        private final RecordParser parser;
        private int messageLength;
        private int pendingQueries;
        private boolean closed;

        private Connection(NetSocket socket, AtomicInteger connections,
                           BiFunction<byte[], String, Future<byte[]>> queryHandler) {
            this.socket = socket;
            this.connections = connections;
            this.queryHandler = queryHandler;
            this.parser = RecordParser.newFixed(2, socket);
        }

        private void start() {
            socket.setWriteQueueMaxSize(MAX_MESSAGE_SIZE);
            socket.closeHandler(ignored -> {
                closed = true;
                connections.decrementAndGet();
            });
            socket.exceptionHandler(error -> {
                LOG.debugv("DNS TCP connection failed: {0}", error.getMessage());
                socket.close();
            });
            parser.handler(this::received);
        }

        private void received(Buffer frame) {
            if (closed) {
                return;
            }
            if (messageLength == 0) {
                messageLength = frame.getUnsignedShort(0);
                if (messageLength < 12) {
                    closed = true;
                    socket.close();
                } else {
                    parser.fixedSizeMode(messageLength);
                }
                return;
            }
            messageLength = 0;
            parser.fixedSizeMode(2);
            if (++pendingQueries > MAX_PENDING_QUERIES) {
                closed = true;
                socket.close();
                return;
            }
            queryHandler.apply(frame.getBytes(), socket.remoteAddress().host())
                    .onComplete(result -> {
                        pendingQueries--;
                        if (closed) {
                            return;
                        }
                        if (result.failed()) {
                            LOG.debugv("DNS TCP query failed: {0}", result.cause().getMessage());
                            return;
                        }
                        byte[] response = result.result();
                        if (response == null) {
                            return;
                        }
                        if (response.length > MAX_MESSAGE_SIZE || socket.writeQueueFull()) {
                            closed = true;
                            socket.close();
                            return;
                        }
                        socket.write(Buffer.buffer(2 + response.length)
                                        .appendShort((short) response.length).appendBytes(response))
                                .onFailure(error -> socket.close());
                    });
        }
    }
}

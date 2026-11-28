package io.floci.aws.config;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.AbstractSniHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.vertx.core.buffer.Buffer;

/** Uses Netty's TLS record and ClientHello decoder without terminating the handshake. */
final class TlsClientHello implements AutoCloseable {
    private static final int MAX_HANDSHAKE_BYTES = 65536;
    private final EmbeddedChannel decoder;
    private boolean complete;
    private boolean closed;
    private String hostname;

    TlsClientHello() {
        decoder = new EmbeddedChannel(new AbstractSniHandler<String>(MAX_HANDSHAKE_BYTES, 0) {
            @Override
            protected Future<String> lookup(ChannelHandlerContext context, String host) {
                return context.executor().newSucceededFuture(host);
            }

            @Override
            protected void onLookupComplete(ChannelHandlerContext context, String host, Future<String> result) {
                hostname = host;
                complete = true;
                context.pipeline().remove(this);
            }
        });
    }

    boolean append(Buffer bytes) {
        decoder.writeInbound(Unpooled.wrappedBuffer(bytes.getBytes()));
        decoder.runPendingTasks();
        decoder.checkException();
        Object forwarded;
        while ((forwarded = decoder.readInbound()) != null) {
            ReferenceCountUtil.release(forwarded);
        }
        return complete;
    }

    String hostname() {
        return hostname;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            decoder.finishAndReleaseAll();
        }
    }
}

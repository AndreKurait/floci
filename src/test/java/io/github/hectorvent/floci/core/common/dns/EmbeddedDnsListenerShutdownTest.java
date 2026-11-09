package io.github.hectorvent.floci.core.common.dns;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddedDnsListenerShutdownTest {
    @Test
    void bothTransportsFinishClosingBeforeFailureIsReported() {
        DatagramSocket udp = mock(DatagramSocket.class);
        NetServer tcp = mock(NetServer.class);
        IllegalStateException failure = new IllegalStateException("UDP close failed");
        Promise<Void> tcpClosed = Promise.promise();
        when(udp.close()).thenReturn(Future.failedFuture(failure));
        when(tcp.close()).thenReturn(tcpClosed.future());

        Future<Void> closed = new EmbeddedDnsServer.Listeners(udp, tcp).close();

        verify(udp).close();
        verify(tcp).close();
        assertFalse(closed.isComplete());
        tcpClosed.complete();
        assertSame(failure, assertThrows(Exception.class,
                () -> closed.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)).getCause());
    }

    @Test
    void failedShutdownKeepsItsTransportHandlesForRetry() throws Exception {
        DatagramSocket udp = mock(DatagramSocket.class);
        NetServer tcp = mock(NetServer.class);
        when(udp.close()).thenReturn(Future.failedFuture(new IllegalStateException("UDP close failed")));
        when(tcp.close()).thenReturn(Future.succeededFuture());
        EmbeddedDnsServer.Listeners owned = new EmbeddedDnsServer.Listeners(udp, tcp);
        EmbeddedDnsServer server = new EmbeddedDnsServer(List.of());
        Field listeners = EmbeddedDnsServer.class.getDeclaredField("listeners");
        listeners.setAccessible(true);
        listeners.set(server, owned);

        assertThrows(IllegalStateException.class, server::stop);
        verify(tcp).close();
        assertSame(owned, listeners.get(server));

        when(udp.close()).thenReturn(Future.succeededFuture());
        server.stop();
        assertNull(listeners.get(server));
    }

}

package io.floci.aws.core.common.dns;

import io.floci.aws.testing.ParallelLocks;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ResourceLock(ParallelLocks.PORTS)
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

    @Test
    void aFailedAdditionalCloseStillClosesThePrimaryAndOtherListeners() {
        DatagramSocket additionalUdp = mock(DatagramSocket.class);
        NetServer additionalTcp = mock(NetServer.class);
        DatagramSocket primaryUdp = mock(DatagramSocket.class);
        NetServer primaryTcp = mock(NetServer.class);
        when(additionalUdp.close()).thenReturn(Future.failedFuture("additional close failed"));
        when(additionalTcp.close()).thenReturn(Future.succeededFuture());
        when(primaryUdp.close()).thenReturn(Future.succeededFuture());
        when(primaryTcp.close()).thenReturn(Future.succeededFuture());
        Future<Void> closed = EmbeddedDnsServer.closeListeners(List.of(
                new EmbeddedDnsServer.Listeners(additionalUdp, additionalTcp),
                new EmbeddedDnsServer.Listeners(primaryUdp, primaryTcp)));
        assertTrue(closed.failed());
        verify(additionalUdp).close();
        verify(additionalTcp).close();
        verify(primaryUdp).close();
        verify(primaryTcp).close();
    }

}

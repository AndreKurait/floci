package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.vertx.http.runtime.ProxyConfig;
import io.quarkus.vertx.http.runtime.TrustedProxyCheckPartConverter;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.dns.DnsClient;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElbV2ProxyHandlerTest {
    @Test
    void certificateOnlyTrustCannotReplaceUnauthenticatedResourcePeerIdentity() {
        ProxyConfig config = mock(ProxyConfig.class);
        when(config.proxyAddressForwarding()).thenReturn(true);
        when(config.trustedProxy()).thenReturn(List.of(mock(ProxyConfig.TrustedProxyConfig.class)));
        Vertx vertx = mock(Vertx.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        SocketAddress peer = SocketAddress.inetSocketAddress(1234, "127.0.0.3");
        when(request.remoteAddress()).thenReturn(peer);
        when(request.getHeader("Forwarded")).thenReturn("for=192.0.2.10");
        AtomicReference<HttpServerRequest> received = new AtomicReference<>();

        ElbV2ProxyHandler.create(config, vertx, received::set).handle(request);

        assertSame(request, received.get());
        assertEquals(peer, received.get().remoteAddress());
        verifyNoInteractions(vertx);
    }

    @Test
    void pausesBeforeHostnameLookupAndRetainsOriginalRoutingAfterTrustResolution() {
        Fixture fixture = new Fixture();
        fixture.handler.handle(fixture.request);

        InOrder order = inOrder(fixture.request, fixture.dns);
        order.verify(fixture.request).pause();
        order.verify(fixture.dns).resolveA("proxy.example.test");
        assertNull(fixture.forwarded.get());
        verify(fixture.request, never()).resume();

        fixture.lookup.complete(List.of("127.0.0.1"));

        HttpServerRequest forwarded = fixture.forwarded.get();
        assertNotNull(forwarded);
        assertAll(
                () -> assertEquals("192.0.2.10", forwarded.remoteAddress().host()),
                () -> assertEquals("listener.example.test:8080", forwarded.host()),
                () -> assertEquals("listener.example.test:8080", forwarded.getHeader("Host")),
                () -> assertEquals("/a%2Fb?q=x%2F", forwarded.uri()),
                () -> assertEquals("/a%2Fb", forwarded.path()),
                () -> assertEquals("q=x%2F", forwarded.query()),
                () -> assertEquals("http", forwarded.scheme()));
        verify(fixture.request).resume();
    }

    @Test
    void preservesConsumerBackpressureAfterHostnameLookup() {
        Fixture fixture = new Fixture(true);
        fixture.handler.handle(fixture.request);
        fixture.lookup.complete(List.of("127.0.0.1"));

        assertNotNull(fixture.forwarded.get());
        verify(fixture.request, never()).resume();
        fixture.forwarded.get().resume();
        verify(fixture.request).resume();
    }

    @Test
    @SuppressWarnings("unchecked")
    void closedRequestCannotDispatchAfterHostnameLookupCompletes() {
        Fixture fixture = new Fixture();
        fixture.handler.handle(fixture.request);
        ArgumentCaptor<Handler<Void>> close = ArgumentCaptor.forClass(Handler.class);
        verify(fixture.response).closeHandler(close.capture());
        close.getValue().handle(null);

        fixture.lookup.complete(List.of("127.0.0.1"));

        assertNull(fixture.forwarded.get());
    }

    @Test
    void unresolvedHostnameRetainsSocketIdentityAndOriginalRouting() {
        Fixture fixture = new Fixture();
        fixture.handler.handle(fixture.request);
        fixture.lookup.fail("DNS unavailable");

        HttpServerRequest forwarded = fixture.forwarded.get();
        assertNotNull(forwarded);
        assertEquals("127.0.0.1", forwarded.remoteAddress().host());
        assertEquals("listener.example.test:8080", forwarded.host());
        assertEquals("/a%2Fb?q=x%2F", forwarded.uri());
    }

    private static final class Fixture {
        private final HttpServerRequestInternal request = mock(HttpServerRequestInternal.class);
        private final HttpServerResponse response = mock(HttpServerResponse.class);
        private final DnsClient dns = mock(DnsClient.class);
        private final Promise<List<String>> lookup = Promise.promise();
        private final AtomicReference<HttpServerRequest> forwarded = new AtomicReference<>();
        private final Handler<HttpServerRequest> handler;

        Fixture() {
            this(false);
        }

        Fixture(boolean pauseConsumer) {
            ProxyConfig config = mock(ProxyConfig.class);
            when(config.proxyAddressForwarding()).thenReturn(true);
            when(config.allowForwarded()).thenReturn(true);
            when(config.allowXForwarded()).thenReturn(Optional.of(true));
            when(config.forwardedPrecedence()).thenReturn(ProxyConfig.ForwardedPrecedence.X_FORWARDED);
            when(config.enableForwardedHost()).thenReturn(true);
            when(config.forwardedHostHeader()).thenReturn("X-Forwarded-Host");
            when(config.enableForwardedPrefix()).thenReturn(true);
            when(config.forwardedPrefixHeader()).thenReturn("X-Forwarded-Prefix");
            when(config.forwardedProtoValidation()).thenReturn(ProxyConfig.ForwardedProtoValidation.REJECT);
            when(config.trustedProxy()).thenReturn(List.of());
            when(config.trustedProxies()).thenReturn(Optional.of(List.of(
                    new TrustedProxyCheckPartConverter().convert("proxy.example.test"))));
            Vertx vertx = mock(Vertx.class);
            when(vertx.createDnsClient()).thenReturn(dns);
            when(dns.resolveA("proxy.example.test")).thenReturn(lookup.future());
            MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                    .set("Host", "listener.example.test:8080")
                    .set("Forwarded", "for=192.0.2.10;proto=https;host=other.example.test")
                    .set("X-Forwarded-For", "192.0.2.10")
                    .set("X-Forwarded-Proto", "https")
                    .set("X-Forwarded-Host", "other.example.test")
                    .set("X-Forwarded-Prefix", "/edge");
            when(request.response()).thenReturn(response);
            when(request.context()).thenReturn(mock(Context.class));
            when(request.headers()).thenReturn(headers);
            when(request.getHeader(anyString())).thenAnswer(invocation -> headers.get(
                    invocation.<String>getArgument(0)));
            when(request.getHeader(any(CharSequence.class))).thenAnswer(invocation -> headers.get(
                    invocation.<CharSequence>getArgument(0)));
            when(request.host()).thenReturn("listener.example.test:8080");
            when(request.authority()).thenReturn(HostAndPort.create("listener.example.test", 8080));
            when(request.scheme()).thenReturn("http");
            when(request.uri()).thenReturn("/a%2Fb?q=x%2F");
            when(request.path()).thenReturn("/a%2Fb");
            when(request.query()).thenReturn("q=x%2F");
            when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(45678, "127.0.0.1"));
            handler = ElbV2ProxyHandler.create(config, vertx, accepted -> {
                forwarded.set(accepted);
                if (pauseConsumer) {
                    accepted.pause();
                }
            });
        }
    }
}

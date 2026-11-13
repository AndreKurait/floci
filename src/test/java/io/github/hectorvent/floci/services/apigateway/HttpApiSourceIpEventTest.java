package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpApiSourceIpEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uriInfo;
    private SocketAddress socketPeer;
    private HttpServerRequest request;

    @BeforeEach
    void setUp() {
        socketPeer = mock(SocketAddress.class);
        HttpConnection connection = mock(HttpConnection.class);
        when(connection.remoteAddress()).thenReturn(socketPeer);
        request = mock(HttpServerRequest.class);
        when(request.connection()).thenReturn(connection);
        RoutingContext routingContext = mock(RoutingContext.class);
        when(routingContext.request()).thenReturn(request);
        CurrentVertxRequest currentRequest = mock(CurrentVertxRequest.class);
        when(currentRequest.getCurrent()).thenReturn(routingContext);

        MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.add("Forwarded", "for=198.51.100.8;proto=https");
        requestHeaders.add("X-Forwarded-For", "198.51.100.8");
        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);
        uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:4566/api/test/echo"));
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        controller = new ApiGatewayExecuteController(
                null, null, null, null, regionResolver, objectMapper, null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(currentRequest),
                null, null, null, null, null, mock(ServiceCallAuthorizer.class),
                mock(ServiceRoleCallAuthorizer.class), null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.9", "2001:db8::9"})
    void proxyEventReportsSocketPeerDespiteForwardedHeaders(String sourceIp) throws Exception {
        when(socketPeer.hostAddress()).thenReturn(sourceIp);

        JsonNode event = objectMapper.readTree(controller.buildV2ProxyEvent(
                "GET", "/echo", "GET /echo", "api", "us-east-1", "test",
                headers, uriInfo, new byte[0], "request"));

        assertEquals(sourceIp, event.at("/requestContext/http/sourceIp").asText());
        verify(request, never()).remoteAddress();
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.9", "2001:db8::9"})
    void formatOneAuthorizerReportsSocketPeerDespiteForwardedHeaders(String sourceIp) {
        when(socketPeer.hostAddress()).thenReturn(sourceIp);

        JsonNode event = controller.buildRequestAuthorizerEventV1(
                "GET", "/echo", "api", "test", "us-east-1", headers, uriInfo, null);

        assertEquals(sourceIp, event.at("/requestContext/identity/sourceIp").asText());
        verify(request, never()).remoteAddress();
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.9", "2001:db8::9"})
    void formatTwoAuthorizerReportsSocketPeerDespiteForwardedHeaders(String sourceIp) {
        when(socketPeer.hostAddress()).thenReturn(sourceIp);

        JsonNode event = controller.buildRequestAuthorizerEventV2(
                "GET", "/echo", "GET /echo", "api", "test", "us-east-1", headers, uriInfo, null);

        assertEquals(sourceIp, event.at("/requestContext/http/sourceIp").asText());
        verify(request, never()).remoteAddress();
    }
}

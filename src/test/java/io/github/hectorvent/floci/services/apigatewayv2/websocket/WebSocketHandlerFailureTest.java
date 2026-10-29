package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.apigatewayv2.model.Stage;
import io.github.hectorvent.floci.testutil.LogCapture;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketHandlerFailureTest {
    private static final String REGION = "us-east-1";
    private final ApiGatewayV2Service apiService = mock(ApiGatewayV2Service.class);
    private final WebSocketConnectionManager connections = mock(WebSocketConnectionManager.class);
    private final RouteSelectionEvaluator selection = mock(RouteSelectionEvaluator.class);
    private final WebSocketProxyEventBuilder events = mock(WebSocketProxyEventBuilder.class);
    private final WebSocketIntegrationInvoker invoker = mock(WebSocketIntegrationInvoker.class);
    private final WebSocketAuthorizerService authorizer = mock(WebSocketAuthorizerService.class);
    private final Vertx vertx = mock(Vertx.class);
    private final RoutingContext context = mock(RoutingContext.class);
    private final HttpServerRequest request = mock(HttpServerRequest.class);
    private final HttpServerResponse response = mock(HttpServerResponse.class);
    private final ServerWebSocket socket = mock(ServerWebSocket.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Callable<Object>> tasks = new ArrayList<>();
    private final List<Promise<Object>> completions = new ArrayList<>();
    private final Integration integration = new Integration();
    private WebSocketHandler handler;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        handler = new WebSocketHandler(apiService, connections, selection, events, invoker,
                authorizer, mock(RegionResolver.class), config, mapper, vertx);
        when(vertx.executeBlocking(any(Callable.class))).thenAnswer(invocation -> {
            Callable<Object> task = invocation.getArgument(0);
            Promise<Object> completion = Promise.promise();
            tasks.add(task);
            completions.add(completion);
            return completion.future();
        });
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.params()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        Api api = new Api();
        api.setProtocolType("WEBSOCKET");
        api.setRouteSelectionExpression("$request.body.action");
        when(apiService.getApi(REGION, "api")).thenReturn(api);
        when(apiService.getStage(REGION, "api", "prod")).thenReturn(new Stage());
        when(apiService.getIntegration(REGION, "api", "integration")).thenReturn(integration);
        when(invoker.invoke(eq(REGION), same(integration), nullable(String.class), anyMap(), anyMap(), anyMap()))
                .thenReturn(new WebSocketIntegrationInvoker.IntegrationResult(200, null, null, Map.of()));
    }

    @Test
    void authorizerFailureRejectsUpgradeAndRetainsCause() {
        Route route = route("$connect");
        route.setAuthorizationType("CUSTOM");
        route.setAuthorizerId("auth");
        RuntimeException failure = failure();
        when(authorizer.invokeAndEvaluate(eq(REGION), eq("api"), eq("prod"), eq("auth"),
                anyString(), anyLong(), anyMap(), anyMap(), anyString(), anyBoolean(),
                nullable(String.class), anyMap())).thenThrow(failure);
        List<LogRecord> records = LogCapture.capture(WebSocketHandler.class, () -> {
            upgrade();
            verifyNoInteractions(response, invoker, connections);
            completeTask();
            verify(response).setStatusCode(500);
            verify(response).end();
            verify(request, never()).toWebSocket();
            verifyNoInteractions(invoker, connections);
        });
        verify(authorizer).invokeAndEvaluate(eq(REGION), eq("api"), eq("prod"), eq("auth"),
                anyString(), anyLong(), anyMap(), anyMap(), anyString(), anyBoolean(),
                nullable(String.class), anyMap());
        assertCause(records, "Lambda authorizer invocation failed for API {0}: {1}", failure,
                "api", failure.getMessage());
    }

    @Test
    void connectFailureRejectsUpgradeAndRetainsCause() {
        route("$connect");
        RuntimeException failure = failure();
        failInvocation(failure);
        List<LogRecord> records = LogCapture.capture(WebSocketHandler.class, () -> {
            upgrade();
            verifyNoInteractions(response, invoker, connections);
            completeTask();
            verify(response).setStatusCode(500);
            verify(response).end();
            verify(request, never()).toWebSocket();
            verifyNoInteractions(connections);
        });
        verifyInvocation();
        assertCause(records, "$connect integration invocation failed for API {0}: {1}", failure,
                "api", failure.getMessage());
    }

    @Test
    void messageFailureSendsErrorFrameAndRetainsCause() throws Exception {
        route("$default");
        RuntimeException failure = failure();
        failInvocation(failure);
        List<LogRecord> records = LogCapture.capture(WebSocketHandler.class, () -> {
            message();
            verifyNoInteractions(socket, invoker);
            completeTask();
        });
        verify(connections).updateLastActiveAt("connection");
        verifyInvocation();
        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(socket).writeTextMessage(frame.capture());
        JsonNode json = mapper.readTree(frame.getValue());
        assertEquals("Internal server error", json.path("message").asText());
        assertEquals("connection", json.path("connectionId").asText());
        assertFalse(json.path("requestId").asText().isBlank());
        assertCause(records, "Integration invocation failed for route {0} on connection {1}: {2}",
                failure, "$default", "connection", failure.getMessage());
    }

    @Test
    void disconnectFailureRetainsCauseAfterImmediateUnregistration() {
        route("$disconnect");
        RuntimeException failure = failure();
        failInvocation(failure);
        List<LogRecord> records = LogCapture.capture(WebSocketHandler.class, () -> {
            close();
            verify(connections).unregister("connection");
            verifyNoInteractions(invoker);
            completeTask();
            verify(connections, times(1)).unregister("connection");
            verifyNoInteractions(socket);
        });
        verifyInvocation();
        assertCause(records, "Error invoking $disconnect route for connection {0}: {1}", failure,
                "connection", failure.getMessage());
    }

    @Test
    void modeledAuthorizerDenialDoesNotInvokeOrLogFailure() {
        Route route = route("$connect");
        route.setAuthorizationType("CUSTOM");
        route.setAuthorizerId("auth");
        when(authorizer.invokeAndEvaluate(eq(REGION), eq("api"), eq("prod"), eq("auth"),
                anyString(), anyLong(), anyMap(), anyMap(), anyString(), anyBoolean(),
                nullable(String.class), anyMap()))
                .thenReturn(new WebSocketAuthorizerService.AuthorizerResult(false, 403, Map.of()));
        List<LogRecord> records = LogCapture.capture(WebSocketHandler.class, () -> {
            upgrade();
            completeTask();
            verify(response).setStatusCode(403);
            verify(response).end();
            verifyNoInteractions(invoker, connections);
        });
        assertNoFailureWarning(records);
    }

    @Test
    void successfulDisconnectKeepsImmediateCleanupWithoutFailureWarning() {
        route("$disconnect");
        List<LogRecord> records = LogCapture.capture(WebSocketHandler.class, () -> {
            close();
            verify(connections).unregister("connection");
            verifyNoInteractions(invoker);
            completeTask();
            verify(connections, times(1)).unregister("connection");
        });
        verifyInvocation();
        assertNoFailureWarning(records);
    }

    private Route route(String key) {
        Route route = new Route();
        route.setRouteKey(key);
        route.setTarget("integrations/integration");
        when(apiService.findRouteByKey(REGION, "api", key)).thenReturn(route);
        return route;
    }

    private void failInvocation(RuntimeException failure) {
        when(invoker.invoke(eq(REGION), same(integration), nullable(String.class), anyMap(), anyMap(), anyMap()))
                .thenThrow(failure);
    }

    private void verifyInvocation() {
        verify(invoker).invoke(eq(REGION), same(integration), nullable(String.class), anyMap(), anyMap(), anyMap());
    }

    private void completeTask() {
        assertEquals(1, tasks.size());
        Object result;
        try {
            result = tasks.getFirst().call();
        } catch (Exception failure) {
            completions.getFirst().fail(failure);
            return;
        }
        completions.getFirst().complete(result);
    }

    private void upgrade() {
        invoke("processUpgrade", new Class<?>[]{RoutingContext.class, String.class, String.class, String.class},
                context, "api", "prod", REGION);
    }

    private void message() {
        invoke("onMessage", new Class<?>[]{ServerWebSocket.class, String.class, String.class, String.class,
                String.class, String.class, boolean.class}, socket, "connection", "api", "prod", REGION, "{}", false);
    }

    private void close() {
        invoke("onClose", new Class<?>[]{ServerWebSocket.class, String.class, String.class, String.class, String.class},
                socket, "connection", "api", "prod", REGION);
    }

    private void invoke(String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = WebSocketHandler.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(handler, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static RuntimeException failure() {
        return new IllegalStateException("callback failure", new IllegalArgumentException("underlying failure"));
    }

    private static void assertCause(List<LogRecord> records, String template, RuntimeException failure,
                                    Object... parameters) {
        LogRecord warning = records.stream().filter(record -> template.equals(record.getMessage()))
                .findFirst().orElseThrow();
        assertArrayEquals(parameters, warning.getParameters());
        assertSame(failure, warning.getThrown());
        assertSame(failure.getCause(), warning.getThrown().getCause());
    }

    private static void assertNoFailureWarning(List<LogRecord> records) {
        assertTrue(records.stream().noneMatch(record -> record.getThrown() != null
                || record.getLevel().intValue() >= 900));
    }
}

package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.websocket.ConnectionInfo;
import io.github.hectorvent.floci.services.apigatewayv2.websocket.WebSocketConnectionManager;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManageConnectionsIamTest {
    private static final String OWNER = "111122223333";
    private static final String USER = "arn:aws:iam::" + OWNER + ":user/caller";
    private static final String ARN = "arn:aws:execute-api:us-west-2:" + OWNER + ":api/prod/*/@connections";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:ManageConnections\",\"Resource\":\"" + ARN + "\"}}";
    private IamService iam;
    private EmulatorConfig config;
    private ExecuteApiSigV4Authorizer signature;
    private WebSocketConnectionManager connections;
    private ApiGatewayExecuteController controller;
    private UriInfo uri;
    private HttpHeaders headers;
    private ScpProvider scps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iam = mock(IamService.class);
        when(iam.resolveAccountId("key")).thenReturn(Optional.of(OWNER));
        when(iam.resolveCallerArns("key")).thenReturn(Optional.of(new IamService.CallerArns(USER, USER)));
        policy(ALLOW);
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(ignored -> List.<ResourcePolicyProvider>of().iterator());
        scps = mock(ScpProvider.class);
        Instance<ScpProvider> scpInstance = mock(Instance.class);
        when(scpInstance.isResolvable()).thenReturn(true);
        when(scpInstance.get()).thenReturn(scps);
        ObjectMapper mapper = new ObjectMapper();
        CallerCallAuthorizer callers = new CallerCallAuthorizer(config, iam,
                new IamPolicyEvaluator(mapper), providers, scpInstance);
        signature = mock(ExecuteApiSigV4Authorizer.class);
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity("key", OWNER, USER, "id")));
        ApiGatewayV2Service gateway = mock(ApiGatewayV2Service.class);
        when(gateway.hasWebSocketApi("api")).thenReturn(true);
        when(gateway.findApiOwner("api")).thenReturn(Optional.of(new ApiGatewayV2Service.ApiOwner(OWNER, "us-west-2")));
        connections = mock(WebSocketConnectionManager.class);
        when(connections.getConnectionInfo("connection")).thenReturn(
                new ConnectionInfo("connection", "api", "prod", "us-west-2", 1, 2, "127.0.0.1", "test"));
        headers = mock(HttpHeaders.class);
        uri = mock(UriInfo.class);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/execute-api/api/prod/@connections/connection"));
        controller = new ApiGatewayExecuteController(null, null, gateway, null,
                new RegionResolver("us-east-1", "999999999999"), mapper, null, null, connections,
                null, null, new ApiGatewayExecuteRouteContext(), null, new RequestContext(), signature,
                null, null, null, null, callers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void verifiedCallerNeedsManageConnectionsPermission(String method) {
        policy(ALLOW.replace("ManageConnections", "Invoke"));
        assertEquals(403, invoke(method));
        verifyNoInteractions(connections);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void missingSignatureDoesNotReachConnections(String method) {
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(ExecuteApiSigV4Authorizer.Failure.MISSING, "missing", null));
        assertEquals(403, invoke(method));
        verifyNoInteractions(connections);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void exactGrantWorksAndRevocationStopsFurtherAccess(String method) {
        assertEquals("DELETE".equals(method) ? 204 : 200, invoke(method));
        clearInvocations(connections);
        policy(ALLOW.replace("Allow", "Deny"));
        assertEquals(403, invoke(method));
        verifyNoInteractions(connections);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stage", "method", "region", "owner", "boundary", "session", "scp", "expired", "inactive"})
    void sharedPolicyRestrictionsStopConnectionAccess(String cause) {
        switch (cause) {
            case "stage" -> policy(ALLOW.replace("/prod/", "/other/"));
            case "method" -> policy(ALLOW.replace("/*/", "/POST/"));
            case "region" -> policy(ALLOW.replace("us-west-2", "us-east-1"));
            case "owner" -> policy(ALLOW.replace(OWNER, "444455556666"));
            case "boundary" -> when(iam.resolveCallerContext(eq("key"), any()))
                    .thenReturn(new CallerContext(List.of(ALLOW), null, ALLOW.replace("Allow", "Deny")));
            case "session" -> when(iam.resolveCallerContext(eq("key"), any()))
                    .thenReturn(new CallerContext(List.of(ALLOW), ALLOW.replace("Allow", "Deny"), null));
            case "scp" -> when(scps.effectiveScpLevels(OWNER)).thenReturn(List.of(List.of(ALLOW.replace("Allow", "Deny"))));
            case "expired" -> when(iam.isExpiredSession(eq("key"), any())).thenReturn(true);
            case "inactive" -> when(iam.isInactiveAccessKey("key")).thenReturn(true);
            default -> throw new AssertionError(cause);
        }
        assertEquals(403, invoke("GET"));
        verifyNoInteractions(connections);
    }

    @Test
    void denialPrecedesOversizePayloadAndMissingConnection() {
        policy(ALLOW.replace("Allow", "Deny"));
        try (Response response = controller.handlePost(headers, uri, "api", "prod", "@connections/missing", new byte[131073])) {
            assertEquals(403, response.getStatus());
            assertEquals("ForbiddenException", response.getHeaderString("x-amzn-ErrorType"));
        }
        verifyNoInteractions(connections);
    }

    @Test
    void allowedGoneConnectionRetainsGoneResponse() {
        when(connections.getConnectionInfo("connection")).thenReturn(null);
        assertEquals(410, invoke("GET"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void disabledIamRetainsUnsignedLocalBehavior(String method) {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(ExecuteApiSigV4Authorizer.Failure.MISSING, "missing", null));
        assertEquals("DELETE".equals(method) ? 204 : 200, invoke(method));
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "stage", "region"})
    void connectionMustBelongToRequestedApiStageAndRegion(String mismatch) {
        ConnectionInfo info = new ConnectionInfo("connection", "api", "prod", "us-west-2", 1, 2, null, null);
        switch (mismatch) {
            case "api" -> info.setApiId("other");
            case "stage" -> info.setStageName("other");
            case "region" -> info.setRegion("us-east-1");
            default -> throw new AssertionError(mismatch);
        }
        when(connections.getConnectionInfo("connection")).thenReturn(info);
        assertEquals(410, invoke("POST"));
        verify(connections, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void allowedOversizedPayloadRetainsPrecedenceOverMissingConnection() {
        try (Response response = controller.handlePost(headers, uri, "api", "prod", "@connections/missing", new byte[131073])) {
            assertEquals(413, response.getStatus());
        }
        verifyNoInteractions(connections);
    }

    private void policy(String value) {
        when(iam.resolveCallerContext(eq("key"), any())).thenReturn(CallerContext.of(List.of(value)));
    }

    private int invoke(String method) {
        try (Response response = switch (method) {
            case "GET" -> controller.handleGet(headers, uri, "api", "prod", "@connections/connection");
            case "POST" -> controller.handlePost(headers, uri, "api", "prod", "@connections/connection", new byte[0]);
            case "DELETE" -> controller.handleDelete(headers, uri, "api", "prod", "@connections/connection");
            default -> throw new AssertionError(method);
        }) {
            return response.getStatus();
        }
    }
}

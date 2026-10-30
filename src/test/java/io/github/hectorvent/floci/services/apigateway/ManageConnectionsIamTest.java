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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManageConnectionsIamTest {
    private static final String OWNER = "111122223333";
    private static final String USER = "arn:aws:iam::" + OWNER + ":user/caller";
    private static final String ARN = "arn:aws:execute-api:us-west-2:" + OWNER + ":api/prod/POST/@connections";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:ManageConnections\",\"Resource\":\"" + ARN + "\"}}";
    private IamService iam;
    private ScpProvider scps;
    private EmulatorConfig config;
    private ExecuteApiSigV4Authorizer signature;
    private WebSocketConnectionManager connections;
    private ApiGatewayExecuteController controller;
    private ApiGatewayV2Service gateway;
    private ApiGatewayExecuteRouteContext routeContext;
    private HttpHeaders headers;
    private UriInfo uri;
    private ConnectionInfo info;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iam = mock(IamService.class);
        when(iam.resolveAccountId("key")).thenReturn(Optional.of(OWNER));
        when(iam.resolveCallerArns("key")).thenReturn(Optional.of(new IamService.CallerArns(USER, USER)));
        policy(ALLOW);
        scps = mock(ScpProvider.class);
        Instance<ScpProvider> scp = mock(Instance.class);
        when(scp.isResolvable()).thenReturn(true);
        when(scp.get()).thenReturn(scps);
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(call -> List.<ResourcePolicyProvider>of().iterator());
        ObjectMapper mapper = new ObjectMapper();
        CallerCallAuthorizer callers = new CallerCallAuthorizer(config, iam,
                new IamPolicyEvaluator(mapper), providers, scp);
        gateway = mock(ApiGatewayV2Service.class);
        when(gateway.hasWebSocketApi("api")).thenReturn(true);
        when(gateway.findApiOwner("api")).thenReturn(Optional.of(new ApiGatewayV2Service.ApiOwner(OWNER, "us-west-2")));
        connections = mock(WebSocketConnectionManager.class);
        info = new ConnectionInfo("id", "api", "prod", "us-west-2", 0, 0, "127.0.0.1", "test");
        when(connections.getConnectionInfo("id")).thenReturn(info);
        signature = mock(ExecuteApiSigV4Authorizer.class);
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity("key", OWNER, USER, "caller")));
        headers = mock(HttpHeaders.class);
        uri = mock(UriInfo.class);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/prod/@connections/id"));
        routeContext = new ApiGatewayExecuteRouteContext();
        controller = new ApiGatewayExecuteController(null, null, gateway, null,
                new RegionResolver("us-east-1", "999999999999"), mapper, null, null, connections, null, null,
                routeContext, null, new RequestContext(), signature, null, null, null, null, callers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deny", "action", "stage", "method", "connectionId", "region", "owner",
            "boundary", "session", "scp", "expired", "inactive", "removed", "unverified", "partition"})
    void deniedRequestNeverReadsOrMutatesConnection(String cause) {
        switch (cause) {
            case "missing" -> policy("{\"Statement\":[]}");
            case "deny" -> policy(ALLOW.replace("Allow", "Deny"));
            case "action" -> policy(ALLOW.replace("ManageConnections", "Invoke"));
            case "stage" -> policy(ALLOW.replace("/prod/", "/other/"));
            case "method" -> policy(ALLOW.replace("/POST/", "/GET/"));
            case "connectionId" -> policy(ALLOW.replace("/@connections", "/@connections/id"));
            case "region" -> policy(ALLOW.replace("us-west-2", "us-east-1"));
            case "owner" -> policy(ALLOW.replace(OWNER, "444455556666"));
            case "boundary" -> when(iam.resolveCallerContext(eq("key"), any()))
                    .thenReturn(new CallerContext(List.of(ALLOW), null, ALLOW.replace("Allow", "Deny")));
            case "session" -> when(iam.resolveCallerContext(eq("key"), any()))
                    .thenReturn(new CallerContext(List.of(ALLOW), ALLOW.replace("Allow", "Deny"), null));
            case "scp" -> when(scps.effectiveScpLevels(OWNER)).thenReturn(List.of(List.of(ALLOW.replace("Allow", "Deny"))));
            case "expired" -> when(iam.isExpiredSession(eq("key"), any())).thenReturn(true);
            case "inactive" -> when(iam.isInactiveAccessKey("key")).thenReturn(true);
            case "removed" -> when(iam.resolveAccountId("key")).thenReturn(Optional.empty());
            case "unverified" -> when(signature.authorize(anyString(), any(), any(), any(), any()))
                    .thenReturn(new ExecuteApiSigV4Authorizer.Result(null, null, null));
            case "partition" -> when(gateway.findApiOwner("api"))
                    .thenReturn(Optional.of(new ApiGatewayV2Service.ApiOwner(OWNER, "cn-north-1")));
            default -> throw new AssertionError(cause);
        }
        assertEquals(403, invoke("POST"));
        verifyNoInteractions(connections);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET", "DELETE"})
    void methodGrantIsReevaluatedAfterRevocation(String method) {
        policy(ALLOW.replace("/POST/", "/" + method + "/"));
        assertEquals("DELETE".equals(method) ? 204 : 200, invoke(method));
        clearInvocations(connections);
        policy(ALLOW.replace("Allow", "Deny"));
        assertEquals(403, invoke(method));
        verifyNoInteractions(connections);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "stage", "region", "gone"})
    void grantedArnCannotManageAnotherEndpointsConnection(String mismatch) {
        switch (mismatch) {
            case "api" -> info.setApiId("other");
            case "stage" -> info.setStageName("other");
            case "region" -> info.setRegion("us-east-1");
            case "gone" -> when(connections.getConnectionInfo("id")).thenReturn(null);
            default -> throw new AssertionError(mismatch);
        }
        assertEquals(410, invoke("POST"));
        verify(connections, never()).sendMessage(anyString(), anyString());
        verify(connections, never()).closeConnection(anyString());
    }

    @Test
    void optionalIamPreservesUnsignedAccessToOwnedConnection() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        when(signature.authorize(anyString(), any(), any(), any(), any()))
                .thenReturn(new ExecuteApiSigV4Authorizer.Result(null, null, null));
        assertEquals(200, invoke("POST"));
        verify(connections).sendMessage("id", "hello");
        verifyNoInteractions(signature);
    }

    @Test
    void foreignIdentityAllowCannotGrantAccessToApiOwnerConnection() {
        String foreign = "444455556666";
        String arn = USER.replace(OWNER, foreign);
        when(iam.resolveAccountId("key")).thenReturn(Optional.of(foreign));
        when(iam.resolveCallerArns("key")).thenReturn(Optional.of(new IamService.CallerArns(arn, arn)));
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity("key", foreign, arn, "caller")));
        assertEquals(403, invoke("POST"));
        verifyNoInteractions(connections);
    }

    @Test
    void originalSignedPathAndPayloadReachVerifier() {
        routeContext.recordSignedRequestPath("/prod/%40connections/id");
        assertEquals(200, invoke("POST"));
        verify(signature).authorize(eq("POST"), same(headers), same(uri),
                eq("hello".getBytes(StandardCharsets.UTF_8)), eq("/prod/%40connections/id"));
    }

    private void policy(String document) {
        when(iam.resolveCallerContext(eq("key"), any())).thenReturn(CallerContext.of(List.of(document)));
    }

    private int invoke(String method) {
        try (Response result = switch (method) {
            case "POST" -> controller.handlePost(headers, uri, "api", "prod", "@connections/id", "hello".getBytes(StandardCharsets.UTF_8));
            case "GET" -> controller.handleGet(headers, uri, "api", "prod", "@connections/id");
            case "DELETE" -> controller.handleDelete(headers, uri, "api", "prod", "@connections/id");
            default -> throw new AssertionError(method);
        }) {
            return result.getStatus();
        }
    }
}

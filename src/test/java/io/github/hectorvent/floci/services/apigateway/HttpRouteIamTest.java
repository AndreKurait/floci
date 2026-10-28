package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpRouteIamTest {

    private static final String OWNER = "111122223333";
    private static final String ROLE = "arn:aws:iam::" + OWNER + ":role/service-role/integration";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + OWNER + ":function:backend:live";
    private static final String INVOKE = "arn:aws:execute-api:us-east-1:" + OWNER + ":api/prod/GET/items";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + INVOKE + "\"}}";
    private static final String USER = "arn:aws:iam::" + OWNER + ":user/caller";
    private static final String KEY = "caller-key";
    private ExecuteApiSigV4Authorizer signature;
    private Route route;
    private Integration integration;
    private LambdaService lambda;
    private IamService iam;
    private EmulatorConfig config;
    private ScpProvider scps;
    private ResourcePolicyProvider provider;
    private ServiceCallAuthorizer direct;
    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uri;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iam = mock(IamService.class);
        provider = mock(ResourcePolicyProvider.class);
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        scps = mock(ScpProvider.class);
        Instance<ScpProvider> scpInstance = mock(Instance.class);
        when(scpInstance.isResolvable()).thenReturn(true);
        when(scpInstance.get()).thenReturn(scps);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        ServiceRoleCallAuthorizer roles = mock(ServiceRoleCallAuthorizer.class);
        direct = mock(ServiceCallAuthorizer.class);
        ApiGatewayV2Service gateway = mock(ApiGatewayV2Service.class);
        lambda = mock(LambdaService.class);
        integration = new Integration();
        integration.setIntegrationType("AWS_PROXY");
        integration.setIntegrationUri("backend:live");
        integration.setCredentialsArn("*");
        integration.setPayloadFormatVersion("2.0");
        route = new Route();
        route.setRouteKey("GET /items");
        route.setAuthorizationType("AWS_IAM");
        route.setTarget("integrations/backend");
        when(gateway.getApi("us-east-1", "api")).thenReturn(new Api());
        when(gateway.findMatchingRoute("us-east-1", "api", "GET", "/items")).thenReturn(route);
        when(gateway.getIntegration("us-east-1", "api", "backend")).thenReturn(integration);
        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/execute-api/api/prod/"));
        when(lambda.invokeTarget(anyString(), anyString(), any(byte[].class), any())).thenReturn(
                new InvokeResult(200, null, "{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8), null, "request"));
        signature = mock(ExecuteApiSigV4Authorizer.class);
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity(KEY, OWNER, USER, "caller")));
        when(iam.resolveAccountId(KEY)).thenReturn(Optional.of(OWNER));
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(iam.resolveCallerArns(KEY)).thenReturn(Optional.of(new IamService.CallerArns(USER, USER)));
        CallerCallAuthorizer callers = new CallerCallAuthorizer(config, iam, evaluator, providers, scpInstance);
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();
        routeContext.routeToHttpApi("us-east-1");
        controller = new ApiGatewayExecuteController(null, null, gateway, lambda,
                new RegionResolver("us-east-1", OWNER), mapper, null, null, null, null, null,
                routeContext, null, new RequestContext(), signature, null, null, direct, roles, callers, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deny", "action", "stage", "method", "path", "owner", "region",
            "boundary", "session", "scp", "expired", "inactive", "removed", "partition", "identity"})
    void routePermissionDenialStopsBackend(String cause) {
        switch (cause) {
            case "missing" -> policy("{\"Statement\":[]}");
            case "deny" -> policy(ALLOW.replace("Allow", "Deny"));
            case "action" -> policy(ALLOW.replace("execute-api:Invoke", "lambda:InvokeFunction"));
            case "stage" -> policy(ALLOW.replace("/prod/", "/other/"));
            case "method" -> policy(ALLOW.replace("/GET/", "/POST/"));
            case "path" -> policy(ALLOW.replace("/items", "/other"));
            case "owner" -> policy(ALLOW.replace(OWNER, "444455556666"));
            case "region" -> policy(ALLOW.replace("us-east-1", "us-west-2"));
            case "boundary" -> when(iam.resolveCallerContext(eq(KEY), any()))
                    .thenReturn(new CallerContext(List.of(ALLOW), null, ALLOW.replace("Allow", "Deny")));
            case "session" -> when(iam.resolveCallerContext(eq(KEY), any()))
                    .thenReturn(new CallerContext(List.of(ALLOW), ALLOW.replace("Allow", "Deny"), null));
            case "scp" -> when(scps.effectiveScpLevels(OWNER))
                    .thenReturn(List.of(List.of(ALLOW.replace("Allow", "Deny"))));
            case "expired" -> when(iam.isExpiredSession(eq(KEY), any())).thenReturn(true);
            case "inactive" -> when(iam.isInactiveAccessKey(KEY)).thenReturn(true);
            case "removed" -> when(iam.resolveAccountId(KEY)).thenReturn(Optional.empty());
            case "partition" -> when(iam.resolveCallerArns(KEY)).thenReturn(Optional.of(
                    new IamService.CallerArns(USER.replace("arn:aws:", "arn:aws-cn:"), USER)));
            case "identity" -> when(signature.authorize(anyString(), any(), any(), any(), any()))
                    .thenReturn(new ExecuteApiSigV4Authorizer.Result(null, null, null));
            default -> throw new AssertionError(cause);
        }
        assertEquals(403, invoke());
        verifyNoInteractions(lambda, direct);
    }

    @Test
    void exactGrantIsRecheckedAfterRevocation() {
        assertEquals(200, invoke());
        policy(ALLOW.replace("Allow", "Deny"));
        assertEquals(403, invoke());
        verify(lambda).invokeTarget(anyString(), anyString(), any(byte[].class), any());
    }

    @Test
    void conditionsUseApiOwnerAndRegion() {
        policy("{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceAccount\":\"" + OWNER
                + "\",\"aws:RequestedRegion\":\"us-east-1\",\"aws:PrincipalArn\":\"" + USER + "\"}}}}");
        assertEquals(200, invoke());
        verify(signature).authorize(anyString(), any(), any(), any(), any());
    }

    @Test
    void disabledIamStillRequiresValidSignatureButNoPolicy() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        policy("{\"Statement\":[]}");
        assertEquals(200, invoke());
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(ExecuteApiSigV4Authorizer.Failure.MISMATCH, "bad", null));
        assertEquals(403, invoke());
    }

    @Test
    void noneRouteRetainsExistingAuthorization() {
        route.setAuthorizationType("NONE");
        policy("{\"Statement\":[]}");
        assertEquals(200, invoke());
        verifyNoInteractions(signature);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verifiedLocalRootStillHonorsScp(boolean deny) {
        String root = "arn:aws:iam::" + OWNER + ":root";
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity("test", OWNER, root, "test")));
        if (deny) {
            when(scps.effectiveScpLevels(OWNER)).thenReturn(List.of(List.of(ALLOW.replace("Allow", "Deny"))));
        }
        assertEquals(deny ? 403 : 200, invoke());
    }

    @Test
    void localKeyCannotClaimUserIdentity() {
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity("test", OWNER, USER, "test")));
        assertEquals(403, invoke());
        verifyNoInteractions(lambda);
    }

    @Test
    void defaultRouteUsesDefaultResourceInsteadOfConcreteMethodPath() {
        route.setRouteKey("$default");
        assertEquals(403, invoke());
        policy(ALLOW.replace("/GET/items", "/$default"));
        assertEquals(200, invoke());
        policy(ALLOW.replace("/prod/GET/items", "/*/$default"));
        assertEquals(200, invoke());
    }

    @Test
    void foreignIdentityCannotUseAnIdentityGrantWithoutResourcePermission() {
        String foreign = "444455556666";
        String principal = USER.replace(OWNER, foreign);
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity(KEY, foreign, principal, "caller")));
        when(iam.resolveAccountId(KEY)).thenReturn(Optional.of(foreign));
        when(iam.resolveCallerArns(KEY)).thenReturn(Optional.of(new IamService.CallerArns(principal, principal)));
        assertEquals(403, invoke());
        verifyNoInteractions(lambda);
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "boundary", "session", "scp"})
    void transportConditionsReachEveryPolicyLayer(String layer) {
        String condition = "\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.0/8\"},"
                + "\"Bool\":{\"aws:SecureTransport\":\"false\"}";
        String document = conditionalPolicy("Allow", condition);
        applyPolicyLayer(layer, document);
        assertEquals(200, invoke());
        applyPolicyLayer(layer, document.replace("127.0.0.0/8", "192.0.2.0/24"));
        assertEquals(403, invoke());
        verify(lambda).invokeTarget(anyString(), anyString(), any(byte[].class), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "boundary", "session", "scp"})
    void conditionalTransportDenyStopsBackend(String layer) {
        String deny = conditionalPolicy("Deny", "\"Bool\":{\"aws:SecureTransport\":\"false\"}");
        String document = "{\"Statement\":[" + ALLOW.substring(13, ALLOW.length() - 1) + ","
                        + deny.substring(13, deny.length() - 1) + "]}";
        applyPolicyLayer(layer, document);
        assertEquals(403, invoke());
        verifyNoInteractions(lambda, direct);
    }

    @Test
    void secureRequestSuppliesTrue() {
        when(uri.getRequestUri()).thenReturn(URI.create("https://localhost/execute-api/api/prod/"));
        policy(conditionalPolicy("Allow", "\"Bool\":{\"aws:SecureTransport\":\"true\"}"));
        assertEquals(200, invoke());
    }

    private static String conditionalPolicy(String effect, String condition) {
        return "{\"Statement\":{\"Effect\":\"" + effect + "\","
                + "\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + INVOKE
                + "\",\"Condition\":{" + condition + "}}}";
    }

    private void applyPolicyLayer(String layer, String document) {
        switch (layer) {
            case "identity" -> policy(document);
            case "boundary" -> when(iam.resolveCallerContext(eq(KEY), any()))
                    .thenReturn(new CallerContext(List.of(ALLOW), null, document));
            case "session" -> when(iam.resolveCallerContext(eq(KEY), any()))
                    .thenReturn(new CallerContext(List.of(ALLOW), document, null));
            case "scp" -> when(scps.effectiveScpLevels(OWNER)).thenReturn(List.of(List.of(document)));
            default -> throw new AssertionError(layer);
        }
    }

    private void policy(String document) {
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of(document)));
    }

    private int invoke() {
        try (Response response = controller.handleGet(headers, uri, "api", "prod", "items")) {
            return response.getStatus();
        }
    }
}

package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpIntegrationPermissionTest {

    private final Integration integration = new Integration();
    private final EmulatorConfig.IamServiceConfig iam = mock(EmulatorConfig.IamServiceConfig.class);
    private String policy;
    private ServiceCallAuthorizer serviceCallAuthorizer;
    private ObjectMapper mapper;
    private ApiGatewayV2Service gateway;
    private LambdaService lambda;
    private HttpHeaders headers;
    private UriInfo uri;
    private CurrentVertxRequest currentVertxRequest;
    private SocketAddress remoteAddress;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iam);
        when(iam.enforcementEnabled()).thenReturn(true);
        ResourcePolicyProvider provider = (scope, arn) -> {
            assertEquals("lambda", scope);
            assertEquals("arn:aws:lambda:us-west-2:444455556666:function:backend:live", arn);
            return policy == null ? List.of() : List.of(new ResourcePolicyProvider.ResourcePolicy(policy,
                    "444455556666"));
        };
        @SuppressWarnings("unchecked")
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        serviceCallAuthorizer = new ServiceCallAuthorizer(config, new IamPolicyEvaluator(mapper), providers);
        gateway = mock(ApiGatewayV2Service.class);
        lambda = mock(LambdaService.class);
        headers = mock(HttpHeaders.class);
        uri = mock(UriInfo.class);
        currentVertxRequest = mock(CurrentVertxRequest.class);
        RoutingContext routingContext = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpConnection connection = mock(HttpConnection.class);
        remoteAddress = mock(SocketAddress.class);
        when(currentVertxRequest.getCurrent()).thenReturn(routingContext);
        when(routingContext.request()).thenReturn(request);
        when(request.connection()).thenReturn(connection);
        when(connection.remoteAddress()).thenReturn(remoteAddress);
        when(remoteAddress.hostAddress()).thenReturn("127.0.0.1");
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/execute-api/api/test/items"));
        integration.setIntegrationType("AWS_PROXY");
        integration.setIntegrationUri("arn:aws:lambda:us-west-2:444455556666:function:backend:live");
        when(lambda.invokeTarget(eq("us-east-1"), anyString(), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenReturn(result("{\"statusCode\":200,\"body\":\"allowed\"}"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2.0"})
    void missingPermissionPreventsInvocation(String version) {
        assertEquals(500, invoke(version));
        verifyNoInteractions(lambda);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2.0"})
    void qualifiedForeignGrantThenRevocation(String version) {
        policy = grant();
        assertEquals(200, invoke(version));
        policy = null;
        assertEquals(500, invoke(version));
        verify(lambda, times(1)).invokeTarget(eq("us-east-1"),
                eq("arn:aws:lambda:us-west-2:444455556666:function:backend:live"),
                any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @ParameterizedTest
    @ValueSource(strings = {"principal", "account", "api", "stage", "method", "path", "region", "qualifier", "deny"})
    void mismatchedPermissionPreventsInvocation(String mismatch) {
        policy = switch (mismatch) {
            case "principal" -> grant().replace("apigateway.amazonaws.com", "sns.amazonaws.com");
            case "account" -> grant().replace("AWS:SourceAccount\":\"000000000000", "AWS:SourceAccount\":\"999900001111");
            case "api" -> grant().replace("api/test", "other/test");
            case "stage" -> grant().replace("api/test", "api/other");
            case "method" -> grant().replace("/GET/", "/POST/");
            case "path" -> grant().replace("/items", "/other");
            case "region" -> grant().replace("execute-api:us-west-2", "execute-api:us-east-1");
            case "qualifier" -> grant().replace("backend:live", "backend:other");
            case "deny" -> grant().replace("\"Effect\":\"Allow\"", "\"Effect\":\"Deny\"");
            default -> throw new AssertionError(mismatch);
        };
        assertEquals(500, invoke("2.0"));
        verifyNoInteractions(lambda);
    }

    @Test
    void disabledIamPreservesInvocation() {
        when(iam.enforcementEnabled()).thenReturn(false);
        assertEquals(200, invoke("2.0"));
    }

    @Test
    void configuredRoleRetainsItsSeparatePath() {
        integration.setCredentialsArn("arn:aws:iam::000000000000:role/authorizer");
        assertEquals(200, invoke("2.0"));
    }

    @Test
    void callerPassthroughRetainsItsSeparatePath() {
        integration.setCredentialsArn("arn:aws:iam::*:user/*");
        assertEquals(200, invoke("2.0"));
    }

    @Test
    void awsIntegrationAlsoRequiresPermission() {
        integration.setIntegrationType("AWS");
        assertEquals(500, invoke("2.0"));
        verifyNoInteractions(lambda);
        policy = grant();
        assertEquals(200, invoke("2.0"));
    }

    @Test
    void retargetDuringAuthorizationInvokesOnlyTheCheckedTarget() {
        policy = grant();
        ResourcePolicyProvider provider = (scope, arn) -> {
            integration.setIntegrationUri("arn:aws:lambda:us-west-2:444455556666:function:other:live");
            return List.of(new ResourcePolicyProvider.ResourcePolicy(policy, "444455556666"));
        };
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iam);
        @SuppressWarnings("unchecked")
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        serviceCallAuthorizer = new ServiceCallAuthorizer(config, new IamPolicyEvaluator(mapper), providers);
        assertEquals(200, invoke("2.0"));
        verify(lambda).invokeTarget(eq("us-east-1"),
                eq("arn:aws:lambda:us-west-2:444455556666:function:backend:live"),
                any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void bareTargetUsesApiOwnerAndPreservesTheInvocationTarget() {
        integration.setIntegrationUri("backend:live");
        String arn = "arn:aws:lambda:us-east-1:000000000000:function:backend:live";
        ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
        serviceCallAuthorizer = authorizer;
        assertEquals(200, invoke("2.0"));
        verify(authorizer).authorize("apigateway", "lambda:InvokeFunction", arn,
                "arn:aws:execute-api:us-east-1:000000000000:api/test/GET/items", "000000000000");
        verify(lambda).invokeTarget(eq("us-east-1"), eq(arn), any(byte[].class),
                eq(InvocationType.RequestResponse));
    }

    private int invoke(String version) {
        try (Response response = controller(version).handleGet(headers, uri, "api", "test", "items")) {
            return response.getStatus();
        }
    }

    private String grant() {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"apigateway.amazonaws.com"},"Action":"lambda:InvokeFunction",
                "Resource":"arn:aws:lambda:us-west-2:444455556666:function:backend:live",
                "Condition":{"StringEquals":{"AWS:SourceArn":"arn:aws:execute-api:us-west-2:000000000000:api/test/GET/items",
                "AWS:SourceAccount":"000000000000"}}}]}
                """;
    }

    private ApiGatewayExecuteController controller(String version) {
        Route route = new Route();
        route.setRouteKey("GET /items");
        route.setAuthorizationType("NONE");
        route.setTarget("integrations/backend");
        integration.setPayloadFormatVersion(version);
        when(gateway.getApi("us-east-1", "api")).thenReturn(new Api());
        when(gateway.findMatchingRoute("us-east-1", "api", "GET", "/items")).thenReturn(route);
        when(gateway.getIntegration("us-east-1", "api", "backend")).thenReturn(integration);

        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext(currentVertxRequest);
        routeContext.routeToHttpApi("us-east-1");
        return new ApiGatewayExecuteController(null, null, gateway, lambda,
                new RegionResolver("us-east-1", "000000000000"), mapper, null, null, null, null, null,
                routeContext, null, null, null, null, new AuthorizerPolicyEvaluator(new IamPolicyEvaluator(mapper)), serviceCallAuthorizer);
    }

    private InvokeResult result(String payload) {
        return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "request");
    }
}

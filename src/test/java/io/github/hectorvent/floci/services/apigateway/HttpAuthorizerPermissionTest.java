package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
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

class HttpAuthorizerPermissionTest {

    private final Authorizer authorizer = new Authorizer();
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
    private IamPolicyEvaluator.Decision roleDecision;

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
            if ("arn:aws:lambda:us-east-1:000000000000:function:backend".equals(arn)) {
                String backendGrant = """
                        {"Statement":{"Effect":"Allow","Principal":{"Service":"apigateway.amazonaws.com"},
                        "Action":"lambda:InvokeFunction","Resource":"arn:aws:lambda:us-east-1:000000000000:function:backend",
                        "Condition":{"StringEquals":{"AWS:SourceAccount":"000000000000",
                        "AWS:SourceArn":"arn:aws:execute-api:us-east-1:000000000000:api/test/GET/items"}}}}
                        """;
                return List.of(new ResourcePolicyProvider.ResourcePolicy(backendGrant, "000000000000"));
            }
            assertEquals("arn:aws:lambda:us-west-2:444455556666:function:authorizer:live", arn);
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
        when(lambda.invokeTarget(eq("us-east-1"), eq("arn:aws:lambda:us-east-1:000000000000:function:" + "backend"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = mapper.readTree((byte[]) invocation.getArgument(2));
                    assertEquals(7, event.at("/requestContext/authorizer/lambda/tenant/id").asInt());
                    return result("{\"statusCode\":200,\"body\":\"allowed\"}");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2.0", "simple"})
    void missingPermissionPreventsInvocation(String version) {
        assertEquals(500, invoke(version));
        verifyNoInteractions(lambda);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2.0", "simple"})
    void qualifiedForeignGrantThenRevocation(String version) {
        policy = grant();
        assertEquals(200, invoke(version));
        policy = null;
        assertEquals(500, invoke(version));
        verify(lambda, times(1)).invokeTarget(eq("us-east-1"),
                eq("arn:aws:lambda:us-west-2:444455556666:function:authorizer:live"),
                any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @ParameterizedTest
    @ValueSource(strings = {"principal", "account", "api", "authorizer", "region", "qualifier", "deny"})
    void mismatchedPermissionPreventsInvocation(String mismatch) {
        policy = switch (mismatch) {
            case "principal" -> grant().replace("apigateway.amazonaws.com", "sns.amazonaws.com");
            case "account" -> grant().replace("AWS:SourceAccount\":\"000000000000", "AWS:SourceAccount\":\"999900001111");
            case "api" -> grant().replace("api/authorizers", "other/authorizers");
            case "authorizer" -> grant().replace("authorizers/auth", "authorizers/other");
            case "region" -> grant().replace("execute-api:us-east-1", "execute-api:us-west-2");
            case "qualifier" -> grant().replace("authorizer:live", "authorizer:other");
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
    void configuredRoleDenialStopsInvocation() {
        authorizer.setAuthorizerCredentialsArn("arn:aws:iam::000000000000:role/authorizer");
        roleDecision = IamPolicyEvaluator.Decision.DENY;
        assertEquals(500, invoke("2.0"));
        verifyNoInteractions(lambda);
    }

    @Test
    void missingIdentityStillPrecedesPermission() {
        authorizer.setIdentitySource(List.of("$request.header.Authorization"));
        assertEquals(401, invoke("2.0"));
        verifyNoInteractions(lambda);
    }

    private int invoke(String version) {
        String context = "\"context\":{\"tenant\":{\"id\":7}}";
        String payload = "simple".equals(version) ? "{\"isAuthorized\":true," + context + "}"
                : "{\"principalId\":\"user\",\"policyDocument\":{\"Version\":\"2012-10-17\","
                + "\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"*\"}]},"
                + context + "}";
        when(lambda.invokeTarget(eq("us-east-1"),
                eq("arn:aws:lambda:us-west-2:444455556666:function:authorizer:live"),
                any(byte[].class), eq(InvocationType.RequestResponse))).thenReturn(result(payload));
        try (Response response = controller("simple".equals(version) ? "2.0" : version,
                "simple".equals(version)).handleGet(headers, uri, "api", "test", "items")) {
            return response.getStatus();
        }
    }

    private String grant() {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"apigateway.amazonaws.com"},"Action":"lambda:InvokeFunction",
                "Resource":"arn:aws:lambda:us-west-2:444455556666:function:authorizer:live",
                "Condition":{"StringEquals":{"AWS:SourceArn":"arn:aws:execute-api:us-east-1:000000000000:api/authorizers/auth",
                "AWS:SourceAccount":"000000000000"}}}]}
                """;
    }

    private ApiGatewayExecuteController controller(String version, boolean simpleResponses) {

        authorizer.setAuthorizerType("REQUEST");
        authorizer.setAuthorizerUri("arn:aws:lambda:us-west-2:444455556666:function:authorizer:live");
        authorizer.setAuthorizerPayloadFormatVersion(version);
        authorizer.setEnableSimpleResponses(simpleResponses);
        Route route = new Route();
        route.setRouteKey("GET /items");
        route.setAuthorizationType("CUSTOM");
        route.setAuthorizerId("auth");
        route.setTarget("integrations/backend");
        Integration integration = new Integration();
        integration.setIntegrationType("AWS_PROXY");
        integration.setIntegrationUri("arn:aws:lambda:us-east-1:000000000000:function:backend");
        integration.setPayloadFormatVersion("2.0");
        when(gateway.getApi("us-east-1", "api")).thenReturn(new Api());
        when(gateway.findMatchingRoute("us-east-1", "api", "GET", "/items")).thenReturn(route);
        when(gateway.getAuthorizer("us-east-1", "api", "auth")).thenReturn(authorizer);
        when(gateway.getIntegration("us-east-1", "api", "backend")).thenReturn(integration);

        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext(currentVertxRequest);
        routeContext.routeToHttpApi("us-east-1");
        ServiceRoleCallAuthorizer roles = mock(ServiceRoleCallAuthorizer.class);
        when(roles.authorizeWithoutSourceContext(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenAnswer(call -> roleDecision);
        return new ApiGatewayExecuteController(null, null, gateway, lambda,
                new RegionResolver("us-east-1", "000000000000"), mapper, null, null, null, null, null,
                routeContext, null, null, null, null, new AuthorizerPolicyEvaluator(new IamPolicyEvaluator(mapper)), serviceCallAuthorizer, roles, null, null);
    }

    private InvokeResult result(String payload) {
        return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "request");
    }
}

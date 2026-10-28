package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.Integration;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestCustomResourcePolicyTest {

    private static final String OWNER = "111122223333";
    private static final String ROLE = "arn:aws:iam::" + OWNER + ":role/service-role/integration";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + OWNER + ":function:backend:live";
    private static final String INVOKE = "arn:aws:execute-api:us-east-1:" + OWNER + ":api/prod/POST/";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + INVOKE + "\"}}";
    private static final String USER = "arn:aws:iam::" + OWNER + ":user/caller";
    private static final String KEY = "caller-key";
    private ExecuteApiSigV4Authorizer signature;
    private MethodConfig method;
    private Integration integration;
    private Stage stage;
    private LambdaService lambda;
    private IamService iam;
    private EmulatorConfig config;
    private ScpProvider scps;
    private ResourcePolicyProvider provider;
    private ServiceCallAuthorizer direct;
    private ApiGatewayExecuteController controller;
    private CognitoUserPoolAuthorizer cognito;
    private RestLambdaAuthorizer cache;
    private Authorizer auth;
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
        ApiGatewayService gateway = mock(ApiGatewayService.class);
        lambda = mock(LambdaService.class);
        integration = new Integration();
        integration.setType("AWS_PROXY");
        integration.setUri("backend:live");
        integration.setCredentials("*");
        method = new MethodConfig();
        method.setAuthorizationType("CUSTOM");
        method.setAuthorizerId("auth");
        method.setMethodIntegration(integration);
        ApiGatewayResource resource = new ApiGatewayResource();
        resource.setId("root");
        resource.setPath("/");
        resource.setResourceMethods(Map.of("POST", method));
        when(gateway.resolveRestApiRegion("us-east-1", "api")).thenReturn("us-east-1");
        stage = new Stage();
        when(gateway.getStage("us-east-1", "api", "prod")).thenReturn(stage);
        when(gateway.getResources("us-east-1", "api")).thenReturn(List.of(resource));
        auth = new Authorizer();
        auth.setId("auth");
        auth.setType("TOKEN");
        auth.setAuthorizerUri("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:" + OWNER + ":function:auth/invocations");
        auth.setIdentitySource("method.request.header.Authorization");
        auth.setAuthorizerResultTtlInSeconds("300");
        when(gateway.getAuthorizer("us-east-1", "api", "auth")).thenReturn(auth);
        headers = mock(HttpHeaders.class);
        when(headers.getHeaderString("Authorization")).thenReturn("token");
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
        CallerCallAuthorizer callers = new CallerCallAuthorizer(config, iam, evaluator, providers, scpInstance);
        cognito = mock(CognitoUserPoolAuthorizer.class);
        when(cognito.authorize(anyString(), anyString(), any(), any())).thenReturn(
                new CognitoUserPoolAuthorizer.Result(null, "subject", Map.of()));
        cache = new RestLambdaAuthorizer(mapper, new AuthorizerPolicyEvaluator(evaluator));
        authorizerPolicy("Allow", INVOKE);
        controller = new ApiGatewayExecuteController(gateway, cognito, null, lambda,
                new RegionResolver("us-east-1", OWNER), mapper, null, null, null, null, null,
                new ApiGatewayExecuteRouteContext(), null, new RequestContext(), signature, cache, new AuthorizerPolicyEvaluator(evaluator), direct, roles, callers, null);
    }

    @Test
    void absentSignedPrincipalFlagDenyStopsAuthorizerBeforeMissingIdentity() {
        when(headers.getHeaderString("Authorization")).thenReturn(null);
        policy(document("Deny", "*", INVOKE,
                ",\"Condition\":{\"Null\":{\"aws:PrincipalIsAWSService\":\"true\"}}"));
        assertEquals(403, invoke());
        verifyNoInteractions(lambda);
    }

    @Test
    void absentSignedPrincipalFlagGrantStillRequiresCustomIdentity() {
        authorizerPolicy("Allow", INVOKE + "other");
        policy(document("Allow", "*", INVOKE,
                ",\"Condition\":{\"Null\":{\"aws:PrincipalIsAWSService\":\"true\"}}"));
        assertEquals(200, invoke());
        when(headers.getHeaderString("Authorization")).thenReturn(null);
        assertEquals(401, invoke());
    }

    @ParameterizedTest
    @CsvSource({"Allow,Allow,200", "Allow,Neutral,200", "Allow,Deny,403",
            "Neutral,Allow,200", "Neutral,Neutral,403", "Neutral,Deny,403",
            "Deny,Allow,403", "Deny,Neutral,403", "Deny,Deny,403"})
    void combinesBothPoliciesWithExplicitDenyPrecedence(String authorizer, String resource, int expected) {
        authorizerPolicy("Neutral".equals(authorizer) ? "Allow" : authorizer,
                "Neutral".equals(authorizer) ? INVOKE + "other" : INVOKE);
        policy(document("Neutral".equals(resource) ? "Allow" : resource, "*",
                "Neutral".equals(resource) ? INVOKE + "other" : INVOKE, ""));
        assertEquals(expected, invoke());
        if ("Deny".equals(resource)) {
            verifyNoInteractions(lambda);
        }
    }

    @Test
    void explicitDenyPrecedesMissingIdentityAndBackendConfiguration() {
        when(headers.getHeaderString("Authorization")).thenReturn(null);
        method.setMethodIntegration(null);
        method.setApiKeyRequired(true);
        policy(document("Deny", "*", INVOKE, ""));
        assertEquals(403, invoke());
        verifyNoInteractions(lambda);
    }

    @Test
    void conditionalDenyUsesTransportAddressNotForwardedHeader() {
        when(headers.getHeaderString("X-Forwarded-For")).thenReturn("192.0.2.1");
        policy(document("Deny", "*", INVOKE,
                ",\"Condition\":{\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.0/8\"}}"));
        assertEquals(403, invoke());
        verifyNoInteractions(lambda);
    }

    @Test
    void resourcePolicyRevocationIsCheckedEvenOnCacheHit() {
        authorizerPolicy("Allow", INVOKE + "other");
        policy(document("Allow", "*", INVOKE, ""));
        assertEquals(200, invoke());
        policy(document("Deny", "*", INVOKE, ""));
        assertEquals(403, invoke());
        policy(document("Allow", "*", INVOKE + "other", ""));
        assertEquals(403, invoke());
        policy(document("Allow", "*", INVOKE, ""));
        assertEquals(200, invoke());
        verify(lambda, times(1)).invokeTarget(eq("us-east-1"), contains(":function:auth"), any(byte[].class), any());
        cache.flush(new RestLambdaAuthorizer.Scope(OWNER, "us-east-1", "api", "prod"));
        authorizerPolicy("Deny", INVOKE);
        assertEquals(403, invoke());
        verify(lambda, times(2)).invokeTarget(eq("us-east-1"), contains(":function:auth"), any(byte[].class), any());
    }

    @Test
    void changedPolicyDuringAuthorizerInvocationIsReevaluated() {
        when(lambda.invokeTarget(eq("us-east-1"), contains(":function:auth"), any(byte[].class), any()))
                .thenAnswer(invocation -> {
                    policy(document("Deny", "*", INVOKE, ""));
                    return result("Allow", INVOKE);
                });
        assertEquals(403, invoke());
        verify(lambda, never()).invokeTarget(anyString(), eq("backend:live"), any(byte[].class), any());
    }

    @Test
    void arbitraryPrincipalIdDoesNotAcquireIamIdentity() {
        authorizerPolicy("Allow", INVOKE + "other");
        policy(document("Allow", "{\"AWS\":\"" + USER + "\"}", INVOKE, ""));
        assertEquals(403, invoke());
        verifyNoInteractions(iam, signature, scps);
    }

    @Test
    void missingIdentityStillFailsWhenResourcePolicyAllows() {
        policy(document("Allow", "*", INVOKE, ""));
        when(headers.getHeaderString("Authorization")).thenReturn(null);
        assertEquals(401, invoke());
        verifyNoInteractions(lambda);
    }

    @Test
    void noPolicyAndDisabledIamRetainAuthorizerEvaluation() {
        assertEquals(200, invoke());
        policy(document("Deny", "*", INVOKE, ""));
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        assertEquals(200, invoke());
    }

    private void authorizerPolicy(String effect, String resource) {
        when(lambda.invokeTarget(eq("us-east-1"), contains(":function:auth"), any(byte[].class), any()))
                .thenReturn(result(effect, resource));
    }

    private InvokeResult result(String effect, String resource) {
        String payload = "{\"principalId\":\"" + USER + "\",\"policyDocument\":{\"Statement\":{\"Effect\":\""
                + effect + "\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + resource + "\"}}}";
        return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "request");
    }

    private static String document(String effect, String principal, String resource, String condition) {
        String value = "*".equals(principal) ? "\"*\"" : principal;
        return "{\"Statement\":{\"Effect\":\"" + effect + "\",\"Principal\":" + value
                + ",\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + resource + "\"" + condition + "}}";
    }

    private void policy(String document) {
        when(provider.getResourcePolicies("execute-api", INVOKE))
                .thenReturn(List.of(new ResourcePolicyProvider.ResourcePolicy(document, OWNER)));
    }

    private int invoke() {
        try (Response response = controller.handlePost(headers, uri, "api", "prod", "", new byte[0])) {
            return response.getStatus();
        }
    }
}

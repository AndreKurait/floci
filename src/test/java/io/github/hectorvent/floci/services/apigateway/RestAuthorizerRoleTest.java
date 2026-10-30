package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.Integration;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
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

class RestAuthorizerRoleTest {

    private static final String OWNER = "111122223333";
    private static final String ROLE = "arn:aws:iam::" + OWNER + ":role/service-role/integration";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + OWNER + ":function:backend:live";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\"" + FUNCTION + "\"}}";
    private static final String TRUST = """
            {"Statement":{"Effect":"Allow","Principal":{"Service":"apigateway.amazonaws.com"},
            "Action":"sts:AssumeRole"}}""";

    private Integration integration;
    private Authorizer authorizer;
    private RestLambdaAuthorizer cache;
    private Stage stage;
    private LambdaService lambda;
    private IamService iam;
    private IamRole role;
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
        role = new IamRole();
        role.setRoleName("integration");
        role.setArn(ROLE);
        role.setAssumeRolePolicyDocument(TRUST);
        when(iam.findRole(OWNER, "integration")).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW)));
        provider = mock(ResourcePolicyProvider.class);
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        scps = mock(ScpProvider.class);
        Instance<ScpProvider> scpInstance = mock(Instance.class);
        when(scpInstance.isResolvable()).thenReturn(true);
        when(scpInstance.get()).thenReturn(scps);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        ServiceRoleCallAuthorizer roles = new ServiceRoleCallAuthorizer(config, iam,
                new AssumeRolePolicyEvaluator(mapper, evaluator), evaluator, providers, scpInstance);
        direct = mock(ServiceCallAuthorizer.class);
        ApiGatewayService gateway = mock(ApiGatewayService.class);
        lambda = mock(LambdaService.class);
        integration = new Integration();
        integration.setType("MOCK");
        integration.setRequestTemplates(Map.of("application/json", "{\"statusCode\":200}"));
        integration.setUri("backend:live");
        integration.setCredentials(ROLE);
        MethodConfig method = new MethodConfig();
        method.setAuthorizationType("CUSTOM");
        method.setAuthorizerId("auth");
        authorizer = new Authorizer();
        authorizer.setId("auth");
        authorizer.setType("TOKEN");
        authorizer.setAuthorizerUri("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                + FUNCTION + "/invocations");
        authorizer.setAuthorizerCredentials(ROLE);
        authorizer.setAuthorizerResultTtlInSeconds("0");
        authorizer.setIdentitySource("method.request.header.Authorization");
        when(gateway.getAuthorizer("us-east-1", "api", "auth")).thenReturn(authorizer);
        cache = new RestLambdaAuthorizer(mapper, new AuthorizerPolicyEvaluator(evaluator));
        method.setMethodIntegration(integration);
        ApiGatewayResource resource = new ApiGatewayResource();
        resource.setId("root");
        resource.setPath("/");
        resource.setResourceMethods(Map.of("POST", method));
        when(gateway.resolveRestApiRegion("us-east-1", "api")).thenReturn("us-east-1");
        stage = new Stage();
        when(gateway.getStage("us-east-1", "api", "prod")).thenReturn(stage);
        when(gateway.getResources("us-east-1", "api")).thenReturn(List.of(resource));
        headers = mock(HttpHeaders.class);
        MultivaluedHashMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.putSingle("Authorization", "token");
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);
        when(headers.getHeaderString("Authorization")).thenReturn("token");
        uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/execute-api/api/prod/"));
        when(lambda.invokeTarget(anyString(), anyString(), any(byte[].class), any())).thenReturn(
                new InvokeResult(200, null, "{\"principalId\":\"caller\",\"policyDocument\":{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"*\"}]}}".getBytes(StandardCharsets.UTF_8), null, "request"));
        controller = new ApiGatewayExecuteController(gateway, null, null, lambda,
                new RegionResolver("us-east-1", OWNER), mapper, null, null, null, null, null,
                new ApiGatewayExecuteRouteContext(), null, new RequestContext(), null, cache, new AuthorizerPolicyEvaluator(evaluator), direct, roles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-role", "wrong-path", "foreign-role", "trust", "identity", "qualifier", "boundary", "scp", "resource", "partition", "caller-sentinel", "star"})
    void roleDenialStopsInvocation(String cause) {
        switch (cause) {
            case "missing-role" -> when(iam.findRole(OWNER, "integration")).thenReturn(Optional.empty());
            case "wrong-path" -> authorizer.setAuthorizerCredentials(ROLE.replace("service-role/", "other/"));
            case "foreign-role" -> authorizer.setAuthorizerCredentials(ROLE.replace(OWNER, "444455556666"));
            case "partition" -> authorizer.setAuthorizerCredentials(ROLE.replace("arn:aws:", "arn:aws-cn:"));
            case "caller-sentinel" -> authorizer.setAuthorizerCredentials("arn:aws:iam::*:user/*");
            case "star" -> authorizer.setAuthorizerCredentials("*");
            case "trust" -> role.setAssumeRolePolicyDocument(TRUST.replace("apigateway.amazonaws.com", "events.amazonaws.com"));
            case "identity" -> when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
            case "qualifier" -> when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW.replace(":live", ":other"))));
            case "boundary" -> when(iam.resolvePrincipalContext(ROLE)).thenReturn(new CallerContext(List.of(ALLOW), null, ALLOW.replace("Allow", "Deny")));
            case "scp" -> when(scps.effectiveScpLevels(OWNER)).thenReturn(List.of(List.of(ALLOW.replace("Allow", "Deny"))));
            case "resource" -> when(provider.getResourcePolicies("lambda", FUNCTION)).thenReturn(List.of(
                    new ResourcePolicyProvider.ResourcePolicy(ALLOW.replace("\"Effect\":\"Allow\"", "\"Effect\":\"Deny\",\"Principal\":\"*\""), OWNER)));
            default -> throw new AssertionError(cause);
        }
        assertEquals(500, invoke());
        verifyNoInteractions(lambda, direct);
    }

    @Test
    void roleGrantAndRevocationUseSameQualifiedTarget() {
        assertEquals(200, invoke());
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        assertEquals(500, invoke());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse));
        verifyNoInteractions(direct);
    }

    @Test
    void retargetDuringPolicyEvaluationCannotChangeInvokedTarget() {
        when(iam.resolvePrincipalContext(ROLE)).thenAnswer(call -> {
            authorizer.setAuthorizerUri(authorizer.getAuthorizerUri().replace("backend", "other"));
            return CallerContext.of(List.of(ALLOW));
        });
        assertEquals(200, invoke());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void disabledIamDoesNotResolveConfiguredRole() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        assertEquals(200, invoke());
        verifyNoInteractions(iam);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundaryAttachmentControlsNotPrincipalExclusion(boolean boundary) {
        String deny = """
                {"Statement":{"Effect":"Deny","NotPrincipal":{"AWS":["arn:aws:iam::%s:root","%s"]},
                "Action":"lambda:InvokeFunction","Resource":"%s"}}""".formatted(OWNER, ROLE, FUNCTION);
        when(provider.getResourcePolicies("lambda", FUNCTION)).thenReturn(List.of(
                new ResourcePolicyProvider.ResourcePolicy(deny, OWNER)));
        if (boundary) {
            role.setPermissionsBoundaryArn("arn:aws:iam::" + OWNER + ":policy/boundary");
            when(iam.resolvePrincipalContext(ROLE)).thenReturn(new CallerContext(List.of(ALLOW), null, ALLOW));
        }
        assertEquals(boundary ? 500 : 200, invoke());
        verify(lambda, times(boundary ? 0 : 1)).invokeTarget(eq("us-east-1"), eq(FUNCTION),
                any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void cachedResponseDoesNotInvokeAgainButUncachedRequestRechecksRole() {
        authorizer.setAuthorizerResultTtlInSeconds("300");
        assertEquals(200, invoke());
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        assertEquals(200, invoke());
        cache.flush(new RestLambdaAuthorizer.Scope(OWNER, "us-east-1", "api", "prod"));
        assertEquals(500, invoke());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    private int invoke() {
        try (Response response = controller.dispatch("POST", "api", "prod", "", headers, uri, new byte[0])) {
            return response.getStatus();
        }
    }
}

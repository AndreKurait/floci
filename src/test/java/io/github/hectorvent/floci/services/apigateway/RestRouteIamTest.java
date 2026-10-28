package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Integration;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import io.github.hectorvent.floci.services.apigateway.model.MethodSetting;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
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

class RestRouteIamTest {

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
        method.setAuthorizationType("AWS_IAM");
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
        controller = new ApiGatewayExecuteController(gateway, null, null, lambda,
                new RegionResolver("us-east-1", OWNER), mapper, null, null, null, null, null,
                new ApiGatewayExecuteRouteContext(), null, new RequestContext(), signature, null, null, direct, roles, callers, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deny", "action", "stage", "method", "path", "owner", "region",
            "boundary", "session", "scp", "expired", "inactive", "removed", "identity"})
    void routeDenialStopsBackend(String cause) {
        switch (cause) {
            case "missing" -> policy("{\"Statement\":[]}");
            case "deny" -> policy(ALLOW.replace("Allow", "Deny"));
            case "action" -> policy(ALLOW.replace("execute-api:Invoke", "lambda:InvokeFunction"));
            case "stage" -> policy(ALLOW.replace("/prod/", "/other/"));
            case "method" -> policy(ALLOW.replace("/POST/", "/GET/"));
            case "path" -> policy(ALLOW.replace("POST/", "POST/other"));
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
            case "identity" -> when(signature.authorize(anyString(), any(), any(), any(), any()))
                    .thenReturn(new ExecuteApiSigV4Authorizer.Result(null, null, null));
            default -> throw new AssertionError(cause);
        }
        assertEquals(403, invoke());
        verifyNoInteractions(lambda, direct);
    }

    @Test
    void grantRevocationIsCheckedOnEveryRequest() {
        assertEquals(200, invoke());
        policy(ALLOW.replace("Allow", "Deny"));
        assertEquals(403, invoke());
        verify(lambda).invokeTarget(anyString(), anyString(), any(byte[].class), any());
    }

    @Test
    void sameAccountResourceGrantAndExplicitDenyUseSharedEvaluator() {
        policy("{\"Statement\":[]}");
        resourcePolicy("Allow");
        assertEquals(200, invoke());
        policy(ALLOW);
        resourcePolicy("Deny");
        assertEquals(403, invoke());
    }

    @Test
    void disabledIamKeepsSignatureRequirement() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        policy("{\"Statement\":[]}");
        assertEquals(200, invoke());
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(ExecuteApiSigV4Authorizer.Failure.MISMATCH, "bad", null));
        assertEquals(403, invoke());
    }

    @Test
    void noneRoutePreservesExistingBehavior() {
        method.setAuthorizationType("NONE");
        policy("{\"Statement\":[]}");
        assertEquals(200, invoke());
        verifyNoInteractions(signature);
    }

    @Test
    void foreignCallerRequiresBothIdentityAndResourceGrants() {
        String foreign = "444455556666";
        String user = USER.replace(OWNER, foreign);
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity(KEY, foreign, user, "caller")));
        when(iam.resolveAccountId(KEY)).thenReturn(Optional.of(foreign));
        when(iam.resolveCallerArns(KEY)).thenReturn(Optional.of(new IamService.CallerArns(user, user)));
        assertEquals(403, invoke());
        String document = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + user
                + "\"},\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + INVOKE + "\"}}";
        when(provider.getResourcePolicies("execute-api", INVOKE))
                .thenReturn(List.of(new ResourcePolicyProvider.ResourcePolicy(document, OWNER)));
        assertEquals(200, invoke());
        policy("{\"Statement\":[]}");
        assertEquals(403, invoke());
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "boundary", "session", "scp", "resource"})
    void transportConditionsReachEveryPolicyLayer(String layer) {
        String condition = "\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.0/8\"},"
                + "\"Bool\":{\"aws:SecureTransport\":\"false\"}";
        String document = conditionalPolicy("Allow", condition, "resource".equals(layer));
        applyPolicyLayer(layer, document);
        assertEquals(200, invoke());
        applyPolicyLayer(layer, document.replace("127.0.0.0/8", "192.0.2.0/24"));
        assertEquals(403, invoke());
        verify(lambda).invokeTarget(anyString(), anyString(), any(byte[].class), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "boundary", "session", "scp", "resource"})
    void conditionalTransportDenyStopsBackend(String layer) {
        String deny = conditionalPolicy("Deny", "\"Bool\":{\"aws:SecureTransport\":\"false\"}",
                "resource".equals(layer));
        String document = "resource".equals(layer) ? deny
                : "{\"Statement\":[" + ALLOW.substring(13, ALLOW.length() - 1) + ","
                        + deny.substring(13, deny.length() - 1) + "]}";
        applyPolicyLayer(layer, document);
        assertEquals(403, invoke());
        verifyNoInteractions(lambda, direct);
    }

    @Test
    void secureRequestSuppliesTrue() {
        when(uri.getRequestUri()).thenReturn(URI.create("https://localhost/execute-api/api/prod/"));
        policy(conditionalPolicy("Allow", "\"Bool\":{\"aws:SecureTransport\":\"true\"}", false));
        assertEquals(200, invoke());
    }

    private static String conditionalPolicy(String effect, String condition, boolean resource) {
        return "{\"Statement\":{\"Effect\":\"" + effect + "\","
                + (resource ? "\"Principal\":{\"AWS\":\"" + USER + "\"}," : "")
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
            case "resource" -> {
                policy(document.contains("Deny") ? ALLOW : "{\"Statement\":[]}");
                when(provider.getResourcePolicies("execute-api", INVOKE))
                        .thenReturn(List.of(new ResourcePolicyProvider.ResourcePolicy(document, OWNER)));
            }
            default -> throw new AssertionError(layer);
        }
    }

    private void resourcePolicy(String effect) {
        String document = "{\"Statement\":{\"Effect\":\"" + effect
                + "\",\"Principal\":{\"AWS\":\"" + USER + "\"},\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + INVOKE + "\"}}";
        when(provider.getResourcePolicies("execute-api", INVOKE))
                .thenReturn(List.of(new ResourcePolicyProvider.ResourcePolicy(document, OWNER)));
    }

    private void policy(String document) {
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of(document)));
    }

    private int invoke() {
        try (Response response = controller.handlePost(headers, uri, "api", "prod", "", new byte[0])) {
            return response.getStatus();
        }
    }
}

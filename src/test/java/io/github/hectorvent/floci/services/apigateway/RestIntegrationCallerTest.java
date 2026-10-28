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

class RestIntegrationCallerTest {

    private static final String OWNER = "111122223333";
    private static final String ROLE = "arn:aws:iam::" + OWNER + ":role/service-role/integration";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + OWNER + ":function:backend:live";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\"" + FUNCTION + "\"}}";
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
        integration.setCredentials("arn:aws:iam::*:user/*");
        method = new MethodConfig();
        method.setAuthorizationType("NONE");
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
                new ApiGatewayExecuteRouteContext(), null, new RequestContext(), signature, null, null, direct, roles, callers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "qualifier", "boundary", "session", "scp", "resource", "inactive",
            "expired", "removed", "changed-principal", "foreign-key", "unverified", "anonymous", "partition"})
    void callerDenialStopsInvocation(String cause) {
        switch (cause) {
            case "identity" -> when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of()));
            case "qualifier" -> when(iam.resolveCallerContext(eq(KEY), any()))
                    .thenReturn(CallerContext.of(List.of(ALLOW.replace(":live", ":other"))));
            case "boundary" -> when(iam.resolveCallerContext(eq(KEY), any()))
                    .thenReturn(new CallerContext(List.of(ALLOW), null, ALLOW.replace("Allow", "Deny")));
            case "session" -> when(iam.resolveCallerContext(eq(KEY), any()))
                    .thenReturn(new CallerContext(List.of(ALLOW), ALLOW.replace("Allow", "Deny"), null));
            case "scp" -> when(scps.effectiveScpLevels(OWNER)).thenReturn(List.of(List.of(ALLOW.replace("Allow", "Deny"))));
            case "resource" -> resourcePolicy(FUNCTION, "Deny", USER);
            case "inactive" -> when(iam.isInactiveAccessKey(KEY)).thenReturn(true);
            case "expired" -> when(iam.isExpiredSession(eq(KEY), any())).thenReturn(true);
            case "removed" -> when(iam.resolveAccountId(KEY)).thenReturn(Optional.empty());
            case "changed-principal" -> when(iam.resolveCallerArns(KEY))
                    .thenReturn(Optional.of(new IamService.CallerArns(ROLE, ROLE)));
            case "foreign-key" -> when(iam.resolveAccountId(KEY)).thenReturn(Optional.of("444455556666"));
            case "unverified", "anonymous" -> when(signature.authorize(anyString(), any(), any(), any(), any()))
                    .thenReturn(new ExecuteApiSigV4Authorizer.Result(ExecuteApiSigV4Authorizer.Failure.MISMATCH, "bad", null));
            case "partition" -> integration.setUri(FUNCTION.replace("arn:aws:", "arn:aws-cn:"));
            default -> throw new AssertionError(cause);
        }
        assertEquals(500, invoke());
        verifyNoInteractions(lambda, direct);
    }

    @Test
    void verifiedIamRouteIdentityIsReusedWithoutSecondVerification() {
        method.setAuthorizationType("AWS_IAM");
        assertEquals(200, invoke());
        verify(signature).authorize(anyString(), any(), any(), any(), any());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(FUNCTION), any(byte[].class), any());
    }

    @Test
    void grantAndRevocationRecheckSameQualifiedTarget() {
        assertEquals(200, invoke());
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of()));
        assertEquals(500, invoke());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(FUNCTION), any(byte[].class), any());
    }

    @Test
    void retargetDuringPolicyResolutionCannotChangeInvokedTarget() {
        when(iam.resolveCallerContext(eq(KEY), any())).thenAnswer(call -> {
            integration.setUri("other:live");
            return CallerContext.of(List.of(ALLOW));
        });
        assertEquals(200, invoke());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(FUNCTION), any(byte[].class), any());
    }

    @Test
    void foreignTargetRequiresIdentityAndResourceGrant() {
        String foreign = FUNCTION.replace(OWNER, "444455556666");
        integration.setUri(foreign);
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of(ALLOW.replace(FUNCTION, foreign))));
        assertEquals(500, invoke());
        resourcePolicy(foreign, "Allow", USER);
        assertEquals(200, invoke());
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of()));
        assertEquals(500, invoke());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(foreign), any(byte[].class), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualSessionArnAndFullRolePathArePreserved(boolean attached) {
        String session = "arn:aws:sts::" + OWNER + ":assumed-role/integration/issued-session";
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity(KEY, OWNER, session, "session")));
        when(iam.resolveCallerArns(KEY)).thenReturn(Optional.of(new IamService.CallerArns(
                session, ROLE, IamService.CallerArns.TYPE_ASSUMED_ROLE, "role:issued-session", null, attached)));
        when(iam.resolveCallerContext(eq(KEY), any()))
                .thenReturn(new CallerContext(List.of(ALLOW), null, attached ? ALLOW : null));
        String deny = """
                {"Statement":{"Effect":"Deny","NotPrincipal":{"AWS":["arn:aws:iam::%s:root","%s"]},
                "Action":"lambda:InvokeFunction","Resource":"%s"}}""".formatted(OWNER, ROLE, FUNCTION);
        when(provider.getResourcePolicies("lambda", FUNCTION)).thenReturn(List.of(
                new ResourcePolicyProvider.ResourcePolicy(deny, OWNER)));
        assertEquals(attached ? 500 : 200, invoke());
    }

    @Test
    void resourceGrantUsesIssuedSessionArnWithoutSynthesizingAnother() {
        String session = "arn:aws:sts::" + OWNER + ":assumed-role/integration/issued-session";
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity(KEY, OWNER, session, "session")));
        when(iam.resolveCallerArns(KEY)).thenReturn(Optional.of(new IamService.CallerArns(session, ROLE)));
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of()));
        resourcePolicy(FUNCTION, "Allow", session.replace("issued-session", "other-session"));
        assertEquals(500, invoke());
        resourcePolicy(FUNCTION, "Allow", session);
        assertEquals(200, invoke());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(FUNCTION), any(byte[].class), any());
    }

    @Test
    void disabledIamPreservesUnsignedCallerMode() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(ExecuteApiSigV4Authorizer.Failure.MISSING, "missing", null));
        assertEquals(200, invoke());
        verify(iam, never()).resolveAccountId(anyString());
    }

    @Test
    void legacyStarRetainsExistingLocalCompatibility() {
        integration.setCredentials("*");
        assertEquals(200, invoke());
        verifyNoInteractions(signature);
    }

    @Test
    void cacheHitDoesNotInvokeButCacheMissRechecksCaller() {
        stage.setCacheClusterEnabled(true);
        MethodSetting setting = new MethodSetting();
        setting.setCachingEnabled(true);
        setting.setCacheTtlInSeconds(3600);
        stage.setMethodSettings(Map.of("*/*", setting));
        assertEquals(200, invoke());
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of()));
        assertEquals(200, invoke());
        stage.setCacheClusterEnabled(false);
        assertEquals(500, invoke());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(FUNCTION), any(byte[].class), any());
    }

    private void resourcePolicy(String target, String effect, String principal) {
        String policy = """
                {"Statement":{"Effect":"%s","Principal":{"AWS":"%s"},
                "Action":"lambda:InvokeFunction","Resource":"%s"}}""".formatted(effect, principal, target);
        when(provider.getResourcePolicies("lambda", target)).thenReturn(List.of(
                new ResourcePolicyProvider.ResourcePolicy(policy, target.contains("444455556666") ? "444455556666" : OWNER)));
    }

    private int invoke() {
        try (Response response = controller.dispatch("POST", "api", "prod", "", headers, uri, new byte[0])) {
            return response.getStatus();
        }
    }
}

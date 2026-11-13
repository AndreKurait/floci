package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.apigateway.AwsServiceRouter;
import io.github.hectorvent.floci.services.apigateway.ExecuteApiSigV4Authorizer.CallerIdentity;
import io.github.hectorvent.floci.services.apigateway.VtlTemplateEngine;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketIntegrationCallerTest {

    private static final String OWNER = "111122223333";
    private static final String ROLE = "arn:aws:iam::" + OWNER + ":role/service-role/integration";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + OWNER + ":function:backend:live";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\"" + FUNCTION + "\"}}";
    private static final String USER = "arn:aws:iam::" + OWNER + ":user/caller";
    private static final String KEY = "caller-key";
    private CallerIdentity caller;
    private String routeKey = "$connect";
    private Integration integration;
    private LambdaService lambda;
    private IamService iam;
    private EmulatorConfig config;
    private ScpProvider scps;
    private ResourcePolicyProvider provider;
    private ServiceCallAuthorizer direct;
    private WebSocketIntegrationInvoker invoker;

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
        lambda = mock(LambdaService.class);
        integration = new Integration();
        integration.setIntegrationType("AWS_PROXY");
        integration.setIntegrationUri("backend:live");
        integration.setCredentialsArn("arn:aws:iam::*:user/*");
        integration.setPayloadFormatVersion("2.0");
        when(lambda.invokeTarget(anyString(), anyString(), any(byte[].class), any())).thenReturn(
                new InvokeResult(200, null, "{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8), null, "request"));
        caller = new CallerIdentity(KEY, OWNER, USER, "caller");
        when(iam.resolveAccountId(KEY)).thenReturn(Optional.of(OWNER));
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(iam.resolveCallerArns(KEY)).thenReturn(Optional.of(new IamService.CallerArns(USER, USER)));
        CallerCallAuthorizer callers = new CallerCallAuthorizer(config, iam, evaluator, providers, scpInstance);
        invoker = new WebSocketIntegrationInvoker(lambda, mock(AwsServiceRouter.class), mapper,
                mock(VtlTemplateEngine.class), direct, new RegionResolver("us-east-1", OWNER), roles, callers);

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
            case "unverified", "anonymous" -> caller = null;
            case "partition" -> integration.setIntegrationUri(FUNCTION.replace("arn:aws:", "arn:aws-cn:"));
            default -> throw new AssertionError(cause);
        }
        assertEquals(500, invoke());
        verifyNoInteractions(lambda, direct);
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
            integration.setIntegrationUri("other:live");
            return CallerContext.of(List.of(ALLOW));
        });
        assertEquals(200, invoke());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(FUNCTION), any(byte[].class), any());
    }

    @Test
    void foreignTargetRequiresIdentityAndResourceGrant() {
        String foreign = FUNCTION.replace(OWNER, "444455556666");
        integration.setIntegrationUri(foreign);
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
        caller = new CallerIdentity(KEY, OWNER, session, "session");
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
        caller = new CallerIdentity(KEY, OWNER, session, "session");
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
        caller = null;
        assertEquals(200, invoke());
        verify(iam, never()).resolveAccountId(anyString());
    }

    @Test
    void legacyStarRetainsExistingLocalCompatibility() {
        integration.setCredentialsArn("*");
        assertEquals(200, invoke());
        verifyNoInteractions(iam);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWS", "AWS_PROXY"})
    void bothLambdaIntegrationTypesRequireCallerPolicy(String type) {
        integration.setIntegrationType(type);
        assertEquals(200, invoke());
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of()));
        assertEquals(500, invoke());
    }

    private void resourcePolicy(String target, String effect, String principal) {
        String policy = """
                {"Statement":{"Effect":"%s","Principal":{"AWS":"%s"},
                "Action":"lambda:InvokeFunction","Resource":"%s"}}""".formatted(effect, principal, target);
        when(provider.getResourcePolicies("lambda", target)).thenReturn(List.of(
                new ResourcePolicyProvider.ResourcePolicy(policy, target.contains("444455556666") ? "444455556666" : OWNER)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"$connect", "$default", "send", "$disconnect"})
    void eachPhaseReevaluatesRetainedIdentity(String phase) {
        routeKey = phase;
        assertEquals(200, invoke());
        when(iam.isExpiredSession(eq(KEY), any())).thenReturn(true);
        assertEquals(500, invoke());
        verify(lambda).invokeTarget(eq("us-east-1"), eq(FUNCTION), any(byte[].class), any());
    }

    @Test
    void connectionIdentityIsPrivateAndCannotBeDeserializedFromPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ConnectionInfo connection = new ConnectionInfo();
        connection.callerIdentity(caller);
        assertFalse(mapper.writeValueAsString(connection).contains(KEY));
        assertNull(mapper.readValue("{\"callerIdentity\":{\"accessKey\":\"forged\"}}", ConnectionInfo.class)
                .callerIdentity());
    }

    @AfterEach
    void close() { invoker.shutdown(); }

    private int invoke() {
        return invoker.invoke("us-east-1", "api", "prod", routeKey, integration, "{}",
                Map.of(), Map.of(), Map.of(), caller).statusCode();
    }
}

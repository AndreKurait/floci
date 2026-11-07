package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigateway.ExecuteApiSigV4Authorizer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketRouteIamTest {

    private static final String OWNER = "111122223333";
    private static final String ROLE = "arn:aws:iam::" + OWNER + ":role/service-role/integration";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + OWNER + ":function:backend:live";
    private static final String INVOKE = "arn:aws:execute-api:us-east-1:" + OWNER + ":api/prod/$connect";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + INVOKE + "\"}}";
    private static final String USER = "arn:aws:iam::" + OWNER + ":user/caller";
    private static final String KEY = "caller-key";
    private ExecuteApiSigV4Authorizer.Result verified;
    private Route route;
    private Integration integration;
    private LambdaService lambda;
    private IamService iam;
    private EmulatorConfig config;
    private ScpProvider scps;
    private ResourcePolicyProvider provider;
    private WebSocketHandler controller;

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
        route = new Route();
        route.setRouteKey("$connect");
        route.setAuthorizationType("AWS_IAM");
        verified = new ExecuteApiSigV4Authorizer.Result(null, null,
                new ExecuteApiSigV4Authorizer.CallerIdentity(KEY, OWNER, USER, "caller"));
        when(iam.resolveAccountId(KEY)).thenReturn(Optional.of(OWNER));
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(iam.resolveCallerArns(KEY)).thenReturn(Optional.of(new IamService.CallerArns(USER, USER)));
        CallerCallAuthorizer callers = new CallerCallAuthorizer(config, iam, evaluator, providers, scpInstance);
        controller = new WebSocketHandler(null, null, null, null, null, null,
                new RegionResolver("us-east-1", OWNER), config, mapper, null, null, callers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deny", "action", "stage", "method", "path", "owner", "region",
            "boundary", "session", "scp", "expired", "inactive", "removed", "partition", "identity"})
    void routePermissionDenialRejectsConnection(String cause) {
        switch (cause) {
            case "missing" -> policy("{\"Statement\":[]}");
            case "deny" -> policy(ALLOW.replace("Allow", "Deny"));
            case "action" -> policy(ALLOW.replace("execute-api:Invoke", "lambda:InvokeFunction"));
            case "stage" -> policy(ALLOW.replace("/prod/", "/other/"));
            case "method" -> policy(ALLOW.replace("/$connect", "/GET/$connect"));
            case "path" -> policy(ALLOW.replace("/$connect", "/other"));
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
            case "identity" -> verified = new ExecuteApiSigV4Authorizer.Result(null, null, null);
            default -> throw new AssertionError(cause);
        }
        assertEquals(403, invoke());
    }

    @Test
    void exactGrantIsRecheckedAfterRevocation() {
        assertEquals(200, invoke());
        policy(ALLOW.replace("Allow", "Deny"));
        assertEquals(403, invoke());
    }

    @Test
    void conditionsUseApiOwnerAndRegion() {
        policy("{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceAccount\":\"" + OWNER
                + "\",\"aws:RequestedRegion\":\"us-east-1\",\"aws:PrincipalArn\":\"" + USER + "\"}}}}");
        assertEquals(200, invoke());
    }

    @Test
    void disabledIamRetainsUnsignedLocalConnections() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        policy("{\"Statement\":[]}");
        assertEquals(200, invoke());
        verified = new ExecuteApiSigV4Authorizer.Result(ExecuteApiSigV4Authorizer.Failure.MISMATCH, "bad", null);
        assertEquals(200, invoke());
    }

    @Test
    void noneRouteRetainsExistingAuthorization() {
        route.setAuthorizationType("NONE");
        policy("{\"Statement\":[]}");
        assertEquals(200, invoke());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verifiedLocalRootStillHonorsScp(boolean deny) {
        String root = "arn:aws:iam::" + OWNER + ":root";
        verified = new ExecuteApiSigV4Authorizer.Result(null, null,
                new ExecuteApiSigV4Authorizer.CallerIdentity("test", OWNER, root, "test"));
        if (deny) {
            when(scps.effectiveScpLevels(OWNER)).thenReturn(List.of(List.of(ALLOW.replace("Allow", "Deny"))));
        }
        assertEquals(deny ? 403 : 200, invoke());
    }

    @Test
    void localKeyCannotClaimUserIdentity() {
        verified = new ExecuteApiSigV4Authorizer.Result(null, null,
                new ExecuteApiSigV4Authorizer.CallerIdentity("test", OWNER, USER, "test"));
        assertEquals(403, invoke());
    }

    @Test
    void foreignIdentityCannotUseAnIdentityGrantWithoutResourcePermission() {
        String foreign = "444455556666";
        String principal = USER.replace(OWNER, foreign);
        verified = new ExecuteApiSigV4Authorizer.Result(null, null,
                new ExecuteApiSigV4Authorizer.CallerIdentity(KEY, foreign, principal, "caller"));
        when(iam.resolveAccountId(KEY)).thenReturn(Optional.of(foreign));
        when(iam.resolveCallerArns(KEY)).thenReturn(Optional.of(new IamService.CallerArns(principal, principal)));
        assertEquals(403, invoke());
    }

    private void policy(String document) {
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of(document)));
    }

    private int invoke() {
        return controller.permitsConnect(route, verified, "us-east-1", "api", "prod") ? 200 : 403;
    }
}

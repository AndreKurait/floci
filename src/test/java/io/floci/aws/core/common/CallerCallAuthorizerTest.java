package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import io.floci.aws.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.floci.aws.services.iam.ScpProvider;
import io.floci.aws.services.iam.model.CallerContext;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallerCallAuthorizerTest {

    private static final String OWNER = "111122223333";
    private static final String USER = "arn:aws:iam::" + OWNER + ":user/caller";
    private static final String KEY = "caller-key";
    private static final String INVOKE = "arn:aws:execute-api:us-east-1:" + OWNER + ":api/prod/GET/items";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\","
            + "\"Resource\":\"" + INVOKE + "\"}}";

    private IamService iam;
    private ScpProvider scps;
    private ResourcePolicyProvider provider;
    private CallerCallAuthorizer authorizer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iam = mock(IamService.class);
        when(iam.resolveAccountId(KEY)).thenReturn(Optional.of(OWNER));
        when(iam.resolveCallerArns(KEY)).thenReturn(Optional.of(new IamService.CallerArns(USER, USER)));
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of(ALLOW)));

        provider = mock(ResourcePolicyProvider.class);
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        when(provider.getResourcePolicies("execute-api", INVOKE))
                .thenReturn(List.of(new ResourcePolicy(null, OWNER)));
        scps = mock(ScpProvider.class);
        Instance<ScpProvider> scpInstance = mock(Instance.class);
        when(scpInstance.isResolvable()).thenReturn(true);
        when(scpInstance.get()).thenReturn(scps);
        authorizer = new CallerCallAuthorizer(config, iam, new IamPolicyEvaluator(new ObjectMapper()),
                providers, scpInstance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deny", "action", "stage", "method", "path", "owner", "region",
            "boundary", "session", "scp", "expired", "inactive", "removed", "identity"})
    void currentCredentialsAndPolicyCeilingsCanRefuseTheVerifiedCaller(String cause) {
        String caller = USER;
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
            case "identity" -> caller = null;
            default -> throw new AssertionError(cause);
        }
        assertEquals(Decision.DENY, authorize(KEY, caller));
    }

    @Test
    void policyRevocationIsCheckedOnEachCall() {
        assertEquals(Decision.ALLOW, authorize(KEY, USER));
        policy(ALLOW.replace("Allow", "Deny"));
        assertEquals(Decision.DENY, authorize(KEY, USER));
    }

    @Test
    void resourceGrantDoesNotOverrideAnExplicitIdentityDeny() {
        policy("{\"Statement\":[]}");
        String grant = ALLOW.replace("\"Effect\":\"Allow\"",
                "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + USER + "\"}");
        when(provider.getResourcePolicies("execute-api", INVOKE))
                .thenReturn(List.of(new ResourcePolicy(grant, OWNER)));
        assertEquals(Decision.ALLOW, authorize(KEY, USER));
        policy(ALLOW.replace("Allow", "Deny"));
        assertEquals(Decision.DENY, authorize(KEY, USER));
    }

    @Test
    void localRootStillObservesResourceDenials() {
        String root = "arn:aws:iam::" + OWNER + ":root";
        assertEquals(Decision.ALLOW, authorize("test", root));
        String deny = ALLOW.replace("\"Effect\":\"Allow\"", "\"Effect\":\"Deny\",\"Principal\":\"*\"");
        when(provider.getResourcePolicies("execute-api", INVOKE))
                .thenReturn(List.of(new ResourcePolicy(deny, OWNER)));
        assertEquals(Decision.DENY, authorize("test", root));
    }

    private Decision authorize(String key, String caller) {
        return authorizer.authorizeRequest(key, OWNER, caller, "execute-api:Invoke", INVOKE,
                "aws", "127.0.0.1", false);
    }

    private void policy(String document) {
        when(iam.resolveCallerContext(eq(KEY), any())).thenReturn(CallerContext.of(List.of(document)));
    }
}

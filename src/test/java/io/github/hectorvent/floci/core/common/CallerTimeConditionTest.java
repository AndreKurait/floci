package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CallerTimeConditionTest {

    private static final String ACCOUNT = "111122223333";
    private static final String USER = "arn:aws:iam::" + ACCOUNT + ":user/team/caller";
    private static final String TARGET = "arn:aws:execute-api:us-east-1:" + ACCOUNT + ":api/prod/GET/items";
    private static final String ACTION = "execute-api:Invoke";
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00.123456Z");
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}";
    private IamService iam;
    private ResourcePolicyProvider provider;
    private ScpProvider scps;
    private EmulatorConfig config;
    private CallerCallAuthorizer authorizer;
    private Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iam = mock(IamService.class);
        when(iam.resolveAccountId("key")).thenReturn(Optional.of(ACCOUNT));
        when(iam.resolveCallerArns("key")).thenReturn(Optional.of(new IamService.CallerArns(USER, USER)));
        when(iam.resolveCallerContext(eq("key"), any())).thenReturn(CallerContext.of(List.of(ALLOW)));
        provider = mock(ResourcePolicyProvider.class);
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(ignored -> List.of(provider).iterator());
        scps = mock(ScpProvider.class);
        Instance<ScpProvider> scpInstance = mock(Instance.class);
        when(scpInstance.isResolvable()).thenReturn(true);
        when(scpInstance.get()).thenReturn(scps);
        clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        authorizer = new CallerCallAuthorizer(config, iam, new IamPolicyEvaluator(new ObjectMapper()),
                providers, scpInstance, clock);
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "session", "boundary", "scp", "resource"})
    void timeWindowAllowsAtBoundaryAndRevokesWithoutChangingPolicy(String layer) {
        String conditional = policy("Allow", "DateLessThanEquals", "aws:CurrentTime", NOW.toString());
        apply(layer, conditional);
        assertEquals(Decision.ALLOW, invoke());
        now.set(NOW.plusNanos(1));
        assertEquals(Decision.DENY, invoke());
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "session", "boundary", "scp", "resource"})
    void epochTimeExplicitDenyOverridesUnconditionalAllow(String layer) {
        String deny = policy("Deny", "NumericGreaterThanEquals", "aws:EpochTime", Long.toString(NOW.getEpochSecond()));
        String combined = "{\"Statement\":[" + ALLOW.substring(ALLOW.indexOf(':') + 1, ALLOW.length() - 1)
                + "," + deny.substring(deny.indexOf(':') + 1, deny.length() - 1) + "]}";
        apply(layer, combined);
        now.set(NOW.minusSeconds(1));
        assertEquals(Decision.ALLOW, invoke());
        now.set(NOW);
        assertEquals(Decision.DENY, invoke());
    }

    @Test
    void expiryAndBothTimeKeysUseOneClockRead() {
        String date = policy("Allow", "DateEquals", "aws:CurrentTime", NOW.toString());
        String epoch = policy("Allow", "NumericEquals", "aws:EpochTime", Long.toString(NOW.getEpochSecond()));
        when(iam.resolveCallerContext(eq("key"), any())).thenReturn(new CallerContext(List.of(date), epoch, epoch));
        assertEquals(Decision.ALLOW, invoke());
        verify(clock).instant();
        verify(iam).isExpiredSession("key", NOW);
        verify(iam).resolveCallerContext("key", NOW);
    }

    @Test
    void anonymousResourceTimeDenyIsReevaluated() {
        resource(policy("Allow", "DateLessThanEquals", "aws:CurrentTime", NOW.toString()));
        assertEquals(Decision.ALLOW, authorizer.authorizeAnonymousResource(ACTION, TARGET, "127.0.0.1", false));
        now.set(NOW.plusSeconds(1));
        assertEquals(Decision.DENY, authorizer.authorizeAnonymousResource(ACTION, TARGET, "127.0.0.1", false));
    }

    @Test
    void customAuthorizerPrecheckReceivesCurrentEpoch() {
        resource(policy("Deny", "NumericGreaterThanEquals", "aws:EpochTime", Long.toString(NOW.getEpochSecond())));
        assertEquals(ResourcePolicyDecision.EXPLICIT_DENY,
                authorizer.evaluateAnonymousResourcePolicy(ACTION, TARGET, "127.0.0.1", false));
        now.set(NOW.minusSeconds(1));
        assertEquals(ResourcePolicyDecision.NEUTRAL,
                authorizer.evaluateAnonymousResourcePolicy(ACTION, TARGET, "127.0.0.1", false));
    }

    @Test
    void rootStillObeysTimeConditionedScp() {
        when(scps.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(
                policy("Allow", "DateLessThanEquals", "aws:CurrentTime", NOW.toString()))));
        String root = "arn:aws:iam::" + ACCOUNT + ":root";
        assertEquals(Decision.ALLOW, authorizer.authorize("test", ACCOUNT, root, ACTION, TARGET, "aws"));
        now.set(NOW.plusSeconds(1));
        assertEquals(Decision.DENY, authorizer.authorize("test", ACCOUNT, root, ACTION, TARGET, "aws"));
    }

    @Test
    void absentPolicyPublicEndpointRemainsPublic() {
        assertEquals(Decision.ALLOW, authorizer.authorizeAnonymousResource(ACTION, TARGET, "127.0.0.1", false));
    }

    @Test
    void expiredSessionStillDeniesBeforePolicyEvaluation() {
        when(iam.isExpiredSession("key", NOW)).thenReturn(true);
        assertEquals(Decision.DENY, invoke());
        verify(iam, never()).resolveCallerContext(any(), any());
    }

    @Test
    void disabledIamDoesNotRequireIdentityOrReadClock() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        assertEquals(Decision.ALLOW, authorizer.authorize(null, null, null, ACTION, TARGET, "aws"));
        verifyNoInteractions(clock);
    }

    private Decision invoke() {
        return authorizer.authorizeRequest("key", ACCOUNT, USER, ACTION, TARGET, "aws", "127.0.0.1", false);
    }

    private void apply(String layer, String policy) {
        switch (layer) {
            case "identity" -> when(iam.resolveCallerContext(eq("key"), any())).thenReturn(CallerContext.of(List.of(policy)));
            case "session" -> when(iam.resolveCallerContext(eq("key"), any())).thenReturn(new CallerContext(List.of(ALLOW), policy, null));
            case "boundary" -> when(iam.resolveCallerContext(eq("key"), any())).thenReturn(new CallerContext(List.of(ALLOW), null, policy));
            case "scp" -> when(scps.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(policy)));
            case "resource" -> {
                when(iam.resolveCallerContext(eq("key"), any())).thenReturn(CallerContext.of(List.of()));
                resource(policy);
            }
            default -> throw new AssertionError(layer);
        }
    }

    private void resource(String policy) {
        when(provider.getResourcePolicies("execute-api", TARGET)).thenReturn(List.of(
                new ResourcePolicy(policy.replace("\"Effect\":", "\"Principal\":\"*\",\"Effect\":"), ACCOUNT)));
    }

    private static String policy(String effect, String operator, String key, String value) {
        return "{\"Statement\":{\"Effect\":\"" + effect + "\",\"Action\":\"*\",\"Resource\":\"*\",\"Condition\":{\""
                + operator + "\":{\"" + key + "\":\"" + value + "\"}}}}";
    }
}

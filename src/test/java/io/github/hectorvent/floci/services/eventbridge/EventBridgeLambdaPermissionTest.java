package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.lambda.LambdaResourcePolicyProvider;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventBridgeLambdaPermissionTest {

    private static final String REGION = "us-east-1";
    private static final String SOURCE = "111122223333";
    private static final String OWNER = "444455556666";
    private static final String RULE = "arn:aws:events:us-east-1:" + SOURCE + ":rule/custom/orders";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + OWNER + ":function:receiver";
    private static final String DLQ = "arn:aws:sqs:us-east-1:" + SOURCE + ":errors";
    private static final String EVENT = "{\"account\":\"999999999999\",\"resources\":[\"spoofed\"],\"detail\":{}}";

    private LambdaService lambda;
    private SqsService sqs;
    private EmulatorConfig.IamServiceConfig iam;
    private TargetDispatcher dispatcher;
    private AtomicReference<Instant> now;
    private AtomicReference<List<Map<String, Object>>> statements;

    @BeforeEach
    void setUp() {
        lambda = mock(LambdaService.class);
        sqs = mock(SqsService.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        iam = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(services.iam()).thenReturn(iam);
        when(iam.enforcementEnabled()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        ResourcePolicyProvider provider = new LambdaResourcePolicyProvider(lambda, mapper);
        @SuppressWarnings("unchecked")
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        ServiceCallAuthorizer authorizer = new ServiceCallAuthorizer(config, new IamPolicyEvaluator(mapper), providers);
        EventBridgeInvoker invoker = new EventBridgeInvoker(lambda, sqs, mock(SnsService.class), mapper, config);
        Clock clock = mock(Clock.class);
        now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        when(clock.instant()).thenAnswer(invocation -> now.get());
        statements = new AtomicReference<>(List.of());
        when(lambda.policyStatements(OWNER, REGION, "receiver")).thenAnswer(invocation -> statements.get());
        dispatcher = new TargetDispatcher(invoker, sqs, authorizer, config.baseUrl(), clock, null);
    }

    @ParameterizedTest
    @CsvSource({"true,0", "false,1"})
    void missingPolicyHonorsOptionalIam(boolean enforced, int invocations) {
        when(iam.enforcementEnabled()).thenReturn(enforced);
        dispatch(target(FUNCTION));
        verifyCalls(FUNCTION, invocations);
        if (enforced) {
            assertDeadLetter("0");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "events.amazonaws.com,true,true,true,1",
            "sns.amazonaws.com,true,true,true,0",
            "events.amazonaws.com,false,true,true,0",
            "events.amazonaws.com,true,false,true,0",
            "events.amazonaws.com,true,true,false,0"
    })
    void policyChecksActualRulePrincipalAccountAndQualifier(String principal, boolean sourceArn,
                                                           boolean sourceAccount, boolean qualifier, int invocations) {
        statements.set(List.of(grant(principal, sourceArn ? RULE : RULE + "-other",
                sourceAccount ? SOURCE : OWNER, qualifier ? FUNCTION + ":live" : FUNCTION)));
        dispatch(target(FUNCTION + ":live"));
        verifyCalls(FUNCTION + ":live", invocations);
        if (invocations == 0) {
            assertDeadLetter("0");
        }
    }

    @ParameterizedTest
    @CsvSource({"111122223333", "444455556666"})
    void policyLookupUsesTargetOwner(String account) {
        String function = FUNCTION.replace(OWNER, account);
        when(lambda.policyStatements(account, REGION, "receiver"))
                .thenReturn(List.of(grant("events.amazonaws.com", RULE, SOURCE, function)));
        dispatch(target(function));
        verifyCalls(function, 1);
        verify(lambda).policyStatements(account, REGION, "receiver");
        verifyNoInteractions(sqs);
    }

    @Test
    void explicitDenyOverridesGrant() {
        statements.set(List.of(grant("events.amazonaws.com", RULE, SOURCE, FUNCTION), Map.of(
                "Effect", "Deny", "Principal", "*", "Action", "lambda:InvokeFunction", "Resource", FUNCTION)));
        dispatch(target(FUNCTION));
        verifyCalls(FUNCTION, 0);
        assertDeadLetter("0");
    }

    @Test
    void retryRechecksRevokedPolicyWithoutAnotherInvocation() {
        statements.set(List.of(grant("events.amazonaws.com", RULE, SOURCE, FUNCTION)));
        when(lambda.invokeArn(eq(FUNCTION), any(byte[].class), eq(InvocationType.Event)))
                .thenThrow(new AwsException("TooManyRequestsException", "throttled", 429));
        dispatch(target(FUNCTION));
        verifyCalls(FUNCTION, 1);
        verifyNoInteractions(sqs);
        statements.set(List.of());
        now.updateAndGet(time -> time.plusSeconds(1));
        dispatcher.tick();
        verifyCalls(FUNCTION, 1);
        assertDeadLetter("1");
        now.updateAndGet(time -> time.plusSeconds(3600));
        dispatcher.tick();
        verifyCalls(FUNCTION, 1);
    }

    @Test
    void retryUsesCurrentQualifiedTargetAndOriginalRule() {
        statements.set(List.of(grant("events.amazonaws.com", RULE, SOURCE, FUNCTION)));
        when(lambda.invokeArn(eq(FUNCTION), any(byte[].class), eq(InvocationType.Event)))
                .thenThrow(new AwsException("TooManyRequestsException", "throttled", 429));
        Target first = target(FUNCTION);
        AtomicReference<List<Target>> targets = new AtomicReference<>(List.of(first));
        dispatcher.dispatch(RULE, first, EVENT, REGION, targets::get);
        targets.set(List.of(target(FUNCTION + ":live")));
        statements.set(List.of(grant("events.amazonaws.com", RULE, SOURCE, FUNCTION + ":live")));
        now.updateAndGet(time -> time.plusSeconds(1));
        dispatcher.tick();
        verifyCalls(FUNCTION, 1);
        verifyCalls(FUNCTION + ":live", 1);
        verifyNoInteractions(sqs);
    }

    @Test
    void executionRoleTargetDoesNotEnterServicePrincipalPolicyPath() {
        Target target = target(FUNCTION);
        target.setRoleArn("arn:aws:iam::" + SOURCE + ":role/execution");
        dispatch(target);
        verifyCalls(FUNCTION, 1);
        verify(lambda, never()).policyStatements(anyString(), anyString(), anyString());
    }

    private static Map<String, Object> grant(String principal, String rule, String account, String function) {
        return Map.of("Effect", "Allow", "Principal", Map.of("Service", principal),
                "Action", "lambda:InvokeFunction", "Resource", function,
                "Condition", Map.of("ArnLike", Map.of("AWS:SourceArn", rule),
                        "StringEquals", Map.of("AWS:SourceAccount", account)));
    }

    private static Target target(String arn) {
        Target target = new Target("receiver", arn, null, null);
        target.setDeadLetterConfig(new Target.DeadLetterConfig(DLQ));
        return target;
    }

    private void dispatch(Target target) {
        dispatcher.dispatch(RULE, target, EVENT, REGION, () -> List.of(target));
    }

    private void verifyCalls(String arn, int count) {
        verify(lambda, times(count)).invokeArn(eq(arn), any(byte[].class), eq(InvocationType.Event));
    }

    private void assertDeadLetter(String attempts) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, MessageAttributeValue>> attributes = ArgumentCaptor.forClass(Map.class);
        verify(sqs).sendMessage(eq("http://localhost:4566/" + SOURCE + "/errors"), eq(EVENT), eq(0),
                isNull(), isNull(), attributes.capture(), eq(REGION));
        assertEquals("NO_PERMISSIONS", attributes.getValue().get("ERROR_CODE").getStringValue());
        assertEquals(RULE, attributes.getValue().get("RULE_ARN").getStringValue());
        assertEquals(attempts, attributes.getValue().get("RETRY_ATTEMPTS").getStringValue());
        assertFalse(attributes.getValue().containsKey("EXHAUSTED_RETRY_CONDITION"));
    }
}

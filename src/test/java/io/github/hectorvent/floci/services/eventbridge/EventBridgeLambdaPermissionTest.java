package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
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
import java.util.Optional;
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

    private static final String ROLE = "arn:aws:iam::" + SOURCE + ":role/execution";
    private static final String ALLOW = """
            {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"*"}}""";
    private static final String TRUST = """
            {"Statement":{"Effect":"Allow","Principal":{"Service":"events.amazonaws.com"},
            "Action":"sts:AssumeRole"}}""";

    private IamService roleService;
    private IamRole role;
    private ScpProvider scps;
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
        roleService = mock(IamService.class);
        role = new IamRole();
        role.setArn(ROLE);
        role.setRoleName("execution");
        role.setAssumeRolePolicyDocument(TRUST);
        when(roleService.findRole(SOURCE, "execution")).thenReturn(Optional.of(role));
        when(roleService.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW)));
        scps = mock(ScpProvider.class);
        @SuppressWarnings("unchecked")
        Instance<ScpProvider> scpInstance = mock(Instance.class);
        when(scpInstance.isResolvable()).thenReturn(true);
        when(scpInstance.get()).thenReturn(scps);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        ServiceRoleCallAuthorizer roles = new ServiceRoleCallAuthorizer(config, roleService,
                new AssumeRolePolicyEvaluator(mapper, evaluator), evaluator, providers, scpInstance);
        dispatcher = new TargetDispatcher(invoker, sqs, authorizer, roles, config.baseUrl(), clock, null);
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

    @ParameterizedTest
    @CsvSource({"true,0", "false,1"})
    void missingRoleHonorsOptionalIam(boolean enforced, int calls) {
        when(iam.enforcementEnabled()).thenReturn(enforced);
        when(roleService.findRole(SOURCE, "execution")).thenReturn(Optional.empty());
        dispatch(roleTarget(FUNCTION));
        verifyCalls(FUNCTION, calls);
    }

    @Test
    void crossAccountRoleUsesIdentityAndResourcePolicies() {
        roleGrant(FUNCTION);
        dispatch(roleTarget(FUNCTION));
        verifyCalls(FUNCTION, 1);
        verifyNoInteractions(sqs);
    }

    @ParameterizedTest
    @CsvSource({"trust", "identity", "boundary", "scp", "resource", "qualifier"})
    void refusedRoleNeverInvokesAndGoesDirectlyToDeadLetter(String deniedBy) {
        roleGrant(FUNCTION + ":live");
        switch (deniedBy) {
            case "trust" -> role.setAssumeRolePolicyDocument(TRUST.replace("events.amazonaws.com", "sns.amazonaws.com"));
            case "identity" -> when(roleService.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
            case "boundary" -> when(roleService.resolvePrincipalContext(ROLE))
                    .thenReturn(new CallerContext(List.of(ALLOW), null, ALLOW.replace("Allow", "Deny")));
            case "scp" -> when(scps.effectiveScpLevels(SOURCE))
                    .thenReturn(List.of(List.of(ALLOW), List.of(ALLOW.replace("Allow", "Deny"))));
            case "qualifier" -> roleGrant(FUNCTION);
            default -> statements.set(List.of());
        }
        dispatch(roleTarget(FUNCTION + ":live"));
        verifyCalls(FUNCTION + ":live", 0);
        assertDeadLetter("0");
        now.updateAndGet(time -> time.plusSeconds(1));
        dispatcher.tick();
        verifyCalls(FUNCTION + ":live", 0);
    }

    @Test
    void retryRechecksRevokedRoleIdentity() {
        roleGrant(FUNCTION);
        when(lambda.invokeArn(eq(FUNCTION), any(byte[].class), eq(InvocationType.Event)))
                .thenThrow(new AwsException("TooManyRequestsException", "throttled", 429));
        dispatch(roleTarget(FUNCTION));
        verifyCalls(FUNCTION, 1);
        when(roleService.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        now.updateAndGet(time -> time.plusSeconds(1));
        dispatcher.tick();
        verifyCalls(FUNCTION, 1);
        assertDeadLetter("1");
    }

    @Test
    void authorizationAndInvocationUseSameTargetWhenPolicyLookupRetargetsObject() {
        Target target = roleTarget(FUNCTION);
        roleGrant(FUNCTION);
        when(lambda.policyStatements(OWNER, REGION, "receiver")).thenAnswer(invocation -> {
            target.setArn(FUNCTION + ":other");
            return statements.get();
        });
        dispatch(target);
        verifyCalls(FUNCTION, 1);
        verifyCalls(FUNCTION + ":other", 0);
    }

    @ParameterizedTest
    @CsvSource({"true,0", "false,1"})
    void roleBoundaryMetadataControlsNotPrincipalDenial(boolean attached, int calls) {
        String function = FUNCTION.replace(OWNER, SOURCE) + ":live";
        role.setPermissionsBoundaryArn(attached ? "arn:aws:iam::" + SOURCE + ":policy/limit" : null);
        when(roleService.resolvePrincipalContext(ROLE))
                .thenReturn(new CallerContext(List.of(ALLOW), null, attached ? ALLOW : null));
        when(lambda.policyStatements(SOURCE, REGION, "receiver")).thenReturn(List.of(Map.of(
                "Effect", "Deny", "NotPrincipal", Map.of("AWS", List.of("arn:aws:iam::" + SOURCE + ":root", ROLE)),
                "Action", "lambda:InvokeFunction", "Resource", function)));
        dispatch(roleTarget(function));
        verifyCalls(function, calls);
        if (attached) {
            assertDeadLetter("0");
        } else {
            verifyNoInteractions(sqs);
        }
    }

    @ParameterizedTest
    @CsvSource({"aws,us-east-1,1", "aws-cn,cn-north-1,0", "aws-us-gov,us-gov-west-1,0"})
    void roleTargetCannotCrossPartitionsEvenWithIdentityPermission(String partition, String region, int calls) {
        String function = FUNCTION.replace(OWNER, SOURCE).replace("arn:aws:", "arn:" + partition + ":")
                .replace(REGION, region) + ":live";
        dispatch(roleTarget(function));
        verifyCalls(function, calls);
        if (calls == 0) {
            assertDeadLetter("0");
            now.updateAndGet(time -> time.plusSeconds(1));
            dispatcher.tick();
            verifyCalls(function, 0);
        } else {
            verifyNoInteractions(sqs);
        }
    }

    private void roleGrant(String function) {
        statements.set(List.of(Map.of("Effect", "Allow", "Principal", Map.of("AWS", ROLE),
                "Action", "lambda:InvokeFunction", "Resource", function)));
    }

    private static Target roleTarget(String function) {
        Target target = target(function);
        target.setRoleArn(ROLE);
        return target;
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

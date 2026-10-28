package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.lambda.LambdaResourcePolicyProvider;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SnsLambdaPermissionTest {

    private static final String REGION = "us-east-1";
    private static final String SOURCE = "111122223333";
    private static final String TARGET = "444455556666";
    private static final String TOPIC = "arn:aws:sns:us-east-1:" + SOURCE + ":notifications";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + TARGET + ":function:receiver";

    private LambdaService lambda;
    private SnsService sns;
    private EmulatorConfig.IamServiceConfig iam;

    @BeforeEach
    void setUp() {
        lambda = mock(LambdaService.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        iam = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iam);
        when(iam.enforcementEnabled()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        ResourcePolicyProvider provider = new LambdaResourcePolicyProvider(lambda, mapper);
        @SuppressWarnings("unchecked")
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        ServiceCallAuthorizer authorizer = new ServiceCallAuthorizer(
                config, new IamPolicyEvaluator(mapper), providers);
        sns = new SnsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, SOURCE),
                null, lambda, null, authorizer);
        sns.createTopic("notifications", null, null, REGION);
    }

    @ParameterizedTest
    @CsvSource({"true,0", "false,1"})
    void missingPermissionHonorsOptionalIam(boolean enforced, int deliveries) {
        when(iam.enforcementEnabled()).thenReturn(enforced);
        deliver(FUNCTION, deliveries);
    }

    @ParameterizedTest
    @CsvSource({
            "sns.amazonaws.com,true,true,true,1",
            "events.amazonaws.com,true,true,true,0",
            "sns.amazonaws.com,false,true,true,0",
            "sns.amazonaws.com,true,false,true,0",
            "sns.amazonaws.com,true,true,false,0"
    })
    void crossAccountDeliveryChecksPrincipalSourceAndQualifier(String principal, boolean matchingArn,
                                                               boolean matchingAccount, boolean matchingQualifier,
                                                               int deliveries) {
        Map<String, Object> grant = Map.of("Effect", "Allow", "Principal", Map.of("Service", principal),
                "Action", "lambda:InvokeFunction", "Resource", matchingQualifier ? FUNCTION + ":live" : FUNCTION,
                "Condition", Map.of("ArnLike", Map.of("AWS:SourceArn", matchingArn ? TOPIC : TOPIC + "-other"),
                        "StringEquals", Map.of("AWS:SourceAccount", matchingAccount ? SOURCE : TARGET)));
        when(lambda.policyStatements(TARGET, REGION, "receiver")).thenReturn(List.of(grant));
        deliver(FUNCTION + ":live", deliveries);
    }

    @ParameterizedTest
    @CsvSource({"111122223333,1", "444455556666,1"})
    void bothSameAndCrossAccountFunctionsAcceptTheirOwnGrant(String account, int deliveries) {
        String function = FUNCTION.replace(TARGET, account);
        when(lambda.policyStatements(account, REGION, "receiver")).thenReturn(List.of(Map.of(
                "Effect", "Allow", "Principal", Map.of("Service", "sns.amazonaws.com"),
                "Action", "lambda:InvokeFunction", "Resource", function)));
        deliver(function, deliveries);
    }

    @ParameterizedTest
    @CsvSource({"111122223333", "444455556666"})
    void explicitDenyWinsOverAllow(String account) {
        String function = FUNCTION.replace(TARGET, account);
        Map<String, Object> allow = Map.of("Effect", "Allow", "Principal", "*",
                "Action", "lambda:InvokeFunction", "Resource", function);
        Map<String, Object> deny = Map.of("Effect", "Deny", "Principal", Map.of("Service", "sns.amazonaws.com"),
                "Action", "lambda:InvokeFunction", "Resource", function);
        when(lambda.policyStatements(account, REGION, "receiver")).thenReturn(List.of(allow, deny));
        deliver(function, 0);
    }

    @Test
    void deniedSubscriptionDoesNotStopAllowedDeliveryOrHoldTheTopicMonitor() {
        String allowed = FUNCTION + ":live";
        when(lambda.policyStatements(TARGET, REGION, "receiver")).thenReturn(List.of(Map.of(
                "Effect", "Allow", "Principal", Map.of("Service", "sns.amazonaws.com"),
                "Action", "lambda:InvokeFunction", "Resource", allowed)));
        sns.subscribe(TOPIC, "lambda", FUNCTION, REGION, Map.of());
        sns.subscribe(TOPIC, "lambda", allowed, REGION, Map.of());
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(sns));
            return null;
        }).when(lambda).invoke(eq(REGION), eq(allowed), any(byte[].class), eq(InvocationType.Event));

        sns.publish(TOPIC, null, "payload", null, REGION);

        verify(lambda, never()).invoke(eq(REGION), eq(FUNCTION), any(byte[].class), eq(InvocationType.Event));
        verify(lambda).invoke(eq(REGION), eq(allowed), any(byte[].class), eq(InvocationType.Event));
    }

    private void deliver(String endpoint, int deliveries) {
        sns.subscribe(TOPIC, "lambda", endpoint, REGION, Map.of());
        sns.publish(TOPIC, null, "payload", null, REGION);
        verify(lambda, times(deliveries)).invoke(eq(REGION), eq(endpoint), any(byte[].class), eq(InvocationType.Event));
        verify(lambda, never()).invokeArn(any(), any(byte[].class), any());
    }
}

package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.configservice.model.ConfigEvaluation;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleSource;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorder;
import io.github.hectorvent.floci.services.configservice.model.DeliveryChannel;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AwsConfigAccountIsolationTest {
    private static final String REGION = "us-east-1";
    private static final String FIRST = "111111111111";
    private static final String SECOND = "222222222222";
    private final RequestContext context = new RequestContext();
    private AwsConfigService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenAnswer(ignored -> context.getAccountId());
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("config"), anyString(), any())).thenAnswer(ignored ->
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, FIRST));
        context.setAccountId(FIRST);
        service = new AwsConfigService(resolver, factory);
        service.initializeStorage();
    }

    @Test
    void recorderStartStopAndDeletionStayInTheirAccount() {
        for (String account : List.of(FIRST, SECOND)) {
            context.setAccountId(account);
            service.putConfigurationRecorder(REGION, new ConfigurationRecorder("default", "role", null, null));
            service.putDeliveryChannel(REGION, new DeliveryChannel("default", "bucket", null, null, null, null));
        }
        context.setAccountId(FIRST);
        service.startConfigurationRecorder(REGION, "default");
        context.setAccountId(SECOND);
        assertFalse(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
        assertNull(service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStartTime());
        service.deleteDeliveryChannel(REGION, "default");
        service.putDeliveryChannel(REGION, new DeliveryChannel("replacement", "bucket", null, null, null, null));
        service.startConfigurationRecorder(REGION, "default");
        service.stopConfigurationRecorder(REGION, "default");
        context.setAccountId(FIRST);
        assertTrue(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
        assertNull(service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStopTime());
        context.setAccountId(SECOND);
        service.deleteConfigurationRecorder(REGION, "default");
        context.setAccountId(FIRST);
        assertTrue(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
        assertNotNull(service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStartTime());
    }

    @Test
    void recorderWithoutDeliveryChannelCannotStartOrAcquireStartTime() {
        service.putConfigurationRecorder(REGION, new ConfigurationRecorder("default", "role", null, null));

        AwsException error = assertThrows(AwsException.class,
                () -> service.startConfigurationRecorder(REGION, "default"));

        assertEquals("NoAvailableDeliveryChannelException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertFalse(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
        assertNull(service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStartTime());
        service.putDeliveryChannel(REGION, new DeliveryChannel("delivery", "bucket", null, null, null, null));
        service.startConfigurationRecorder(REGION, "default");
        assertTrue(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
        assertNotNull(service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStartTime());
    }

    @Test
    void deliveryChannelInAnotherAccountDoesNotAllowRecorderStart() {
        service.putConfigurationRecorder(REGION, new ConfigurationRecorder("default", "role", null, null));
        service.putDeliveryChannel(REGION, new DeliveryChannel("default", "bucket", null, null, null, null));
        context.setAccountId(SECOND);
        service.putConfigurationRecorder(REGION, new ConfigurationRecorder("default", "role", null, null));

        AwsException error = assertThrows(AwsException.class,
                () -> service.startConfigurationRecorder(REGION, "default"));

        assertEquals("NoAvailableDeliveryChannelException", error.getErrorCode());
        assertFalse(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
        assertNull(service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStartTime());
        context.setAccountId(FIRST);
        service.startConfigurationRecorder(REGION, "default");
        assertTrue(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
    }

    @Test
    void deliveryChannelInAnotherRegionDoesNotAllowRecorderStart() {
        service.putConfigurationRecorder(REGION, new ConfigurationRecorder("default", "role", null, null));
        service.putDeliveryChannel(REGION, new DeliveryChannel("default", "bucket", null, null, null, null));
        String otherRegion = "eu-west-1";
        service.putConfigurationRecorder(otherRegion, new ConfigurationRecorder("default", "role", null, null));

        AwsException error = assertThrows(AwsException.class,
                () -> service.startConfigurationRecorder(otherRegion, "default"));

        assertEquals("NoAvailableDeliveryChannelException", error.getErrorCode());
        assertFalse(service.describeConfigurationRecorderStatus(otherRegion, null).getFirst().recording());
        assertNull(service.describeConfigurationRecorderStatus(otherRegion, null).getFirst().lastStartTime());
        service.startConfigurationRecorder(REGION, "default");
        assertTrue(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
    }

    @Test
    void failedRestartAfterChannelDeletionPreservesPreviousRecorderTimes() {
        service.putConfigurationRecorder(REGION, new ConfigurationRecorder("default", "role", null, null));
        service.putDeliveryChannel(REGION, new DeliveryChannel("default", "bucket", null, null, null, null));
        service.startConfigurationRecorder(REGION, "default");
        service.stopConfigurationRecorder(REGION, "default");
        Long lastStart = service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStartTime();
        Long lastStop = service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStopTime();
        service.deleteDeliveryChannel(REGION, "default");

        AwsException error = assertThrows(AwsException.class,
                () -> service.startConfigurationRecorder(REGION, "default"));

        assertEquals("NoAvailableDeliveryChannelException", error.getErrorCode());
        assertFalse(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
        assertEquals(lastStart, service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStartTime());
        assertEquals(lastStop, service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStopTime());
    }

    @Test
    void missingRecorderKeepsItsOwnErrorWhenDeliveryChannelIsAlsoMissing() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.startConfigurationRecorder(REGION, "missing"));

        assertEquals("NoSuchConfigurationRecorderException", error.getErrorCode());
        assertTrue(service.describeConfigurationRecorders(REGION, null).isEmpty());
    }

    @Test
    void ruleInvocationAndDeletionDoNotChangeAnotherAccountsTiming() {
        for (String account : List.of(FIRST, SECOND)) {
            context.setAccountId(account);
            service.putConfigRule(REGION, new ConfigRule("shared", null, null, null, null,
                    new ConfigRuleSource("CUSTOM_LAMBDA", "check", null, null),
                    null, null, null, null, null));
        }
        context.setAccountId(FIRST);
        service.startConfigRulesEvaluation(REGION, List.of("shared"));
        context.setAccountId(SECOND);
        assertFalse(service.describeConfigRuleEvaluationStatus(REGION, null, null, null)
                .items().getFirst().firstEvaluationStarted());
        service.putEvaluations(REGION, "shared", List.of(new ConfigEvaluation("AWS::S3::Bucket", "bucket",
                "COMPLIANT", null, 1700000000.0, null, null)), false);
        service.deleteConfigRule(REGION, "shared");
        context.setAccountId(FIRST);
        assertNotNull(service.describeConfigRuleEvaluationStatus(REGION, null, null, null)
                .items().getFirst().firstActivatedTime());
        assertTrue(service.describeConfigRuleEvaluationStatus(REGION, null, null, null)
                .items().getFirst().firstEvaluationStarted());
        assertEquals("INSUFFICIENT_DATA", service.complianceForRule(REGION, "shared").complianceType());
    }
}

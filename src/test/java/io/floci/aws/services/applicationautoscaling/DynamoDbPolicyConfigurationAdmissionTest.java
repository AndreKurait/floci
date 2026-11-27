package io.floci.aws.services.applicationautoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.applicationautoscaling.model.PredefinedMetricSpecification;
import io.floci.aws.services.applicationautoscaling.model.ScalableTarget;
import io.floci.aws.services.applicationautoscaling.model.ScalingActivity;
import io.floci.aws.services.applicationautoscaling.model.ScalingPolicy;
import io.floci.aws.services.applicationautoscaling.model.ScheduledAction;
import io.floci.aws.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.floci.aws.services.cloudwatch.metrics.model.CompositeAlarm;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.dynamodb.DynamoDbService;
import io.floci.aws.services.dynamodb.DynamoDbService.ScalableCapacityDecision;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real owned stores capture admission and alarm preservation; the native capacity boundary is controlled. */
@Timeout(30)
class DynamoDbPolicyConfigurationAdmissionTest {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String ACCOUNT = "740000000003";
    private static final String REGION = "us-east-1";
    private static final String RESOURCE = "table/owned-kind";
    private static final String DIMENSION = "dynamodb:table:WriteCapacityUnits";
    @TempDir
    Path directory;
    private final RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
    private final DynamoDbService dynamo = mock(DynamoDbService.class);
    private StorageFactory factory;
    private StorageBackend<String, ScalableTarget> targets;
    private StorageBackend<String, ScalingPolicy> policies;
    private StorageBackend<String, ScalingActivity> activities;
    private StorageBackend<String, ScheduledAction> scheduled;
    private CloudWatchMetricsService metrics;
    private ApplicationAutoScalingService service;

    @BeforeEach
    void openSingleOwnerStoresAndSeedOneTarget() {
        factory = StorageFactories.open(directory, "memory", ACCOUNT);
        targets = factory.create("applicationautoscaling", "application-autoscaling-targets.json",
                new TypeReference<Map<String, ScalableTarget>>() {});
        policies = spy(factory.create("applicationautoscaling", "application-autoscaling-policies.json",
                new TypeReference<Map<String, ScalingPolicy>>() {}));
        activities = factory.create("applicationautoscaling", "application-autoscaling-activities.json",
                new TypeReference<Map<String, ScalingActivity>>() {});
        scheduled = factory.create("applicationautoscaling", "application-autoscaling-scheduled-actions.json",
                new TypeReference<Map<String, ScheduledAction>>() {});
        metrics = spy(new CloudWatchMetricsService(factory, regions));
        service = new ApplicationAutoScalingService(targets, policies, activities, scheduled,
                regions, metrics, mock(EcsCapacityAdjuster.class), dynamo);
        when(dynamo.decideScalableCapacity(anyString(), anyString(), anyString(), any(LongUnaryOperator.class)))
                .thenAnswer(invocation -> {
                    LongUnaryOperator decision = invocation.getArgument(3);
                    assertEquals(3, decision.applyAsLong(3));
                    return new ScalableCapacityDecision(true, 3, 3);
                });
        ScalableTarget target = new ScalableTarget();
        target.setServiceNamespace("dynamodb");
        target.setResourceId(RESOURCE);
        target.setScalableDimension(DIMENSION);
        target.setMinCapacity(1);
        target.setMaxCapacity(300);
        targets.put(REGION + "::dynamodb::" + DIMENSION + "::" + RESOURCE, target);
        assertEquals(1, targets.scan(key -> true).size());
        assertEquals(List.of(), policies.scan(key -> true));
        assertEquals(List.of(), metrics.describeAlarms(null, null, REGION));
    }

    @AfterEach
    void closeOwnedBackends() {
        if (factory != null) {
            factory.shutdownAll();
        }
    }

    @ParameterizedTest
    @EnumSource(ConfigurationCase.class)
    void invalidConfigurationCreatePreservesAllOwnedPolicyAndAlarmState(ConfigurationCase test) {
        refuse(test);
    }

    @ParameterizedTest
    @EnumSource(value = ConfigurationCase.class, names = {"CUSTOMIZED", "MISMATCH", "MISSING_TARGET"})
    void invalidConfigurationUpdatePreservesExistingPolicyAndFourCompleteAlarms(ConfigurationCase test) {
        TargetTrackingConfiguration configuration = new TargetTrackingConfiguration();
        configuration.setTargetValue(70.0);
        PredefinedMetricSpecification predefined = new PredefinedMetricSpecification();
        predefined.setPredefinedMetricType("DynamoDBWriteCapacityUtilization");
        configuration.setPredefinedMetricSpecification(predefined);
        ScalingPolicy original = service.putScalingPolicy("owned", "TargetTrackingScaling",
                "dynamodb", RESOURCE, DIMENSION, configuration, null, REGION);
        assertEquals(4, original.getAlarms().size());
        List<MetricAlarm> alarms = metrics.describeAlarms(null, null, REGION);
        assertEquals(4, alarms.size());
        for (MetricAlarm alarm : alarms) {
            assertTrue(alarm.getAlarmArn().startsWith("arn:aws:cloudwatch:" + REGION + ":" + ACCOUNT + ":alarm:"));
            metrics.setAlarmState(alarm.getAlarmName(), "ALARM", "retained owned state", null, REGION);
            metrics.tagResource(alarm.getAlarmArn(), Map.of("owned", "retained"), REGION);
        }
        PaginatedResult<AlarmHistoryItem> history = history();
        assertNull(history.nextToken());
        assertFalse(history.items().isEmpty());
        refuse(test);
    }

    @Test
    void reconcileRetainsBlockedObsoleteAlarmAndDeletesEveryOtherAlarm() throws Exception {
        ScalingPolicy original = trackingPolicy(70);
        String blocked = original.getAlarms().getFirst().getAlarmName();
        blockDeletion(blocked);
        original.getTargetTrackingConfiguration().setDisableScaleIn(true);
        service.savePolicy(original, REGION);
        service.reconcileDynamoDbPolicies(Instant.EPOCH);
        ScalingPolicy replacement = storedPolicy();
        assertEquals(3, replacement.getAlarms().size());
        assertEquals(List.of(blocked), replacement.getPendingAlarmDeletions().stream()
                .map(alarm -> alarm.getAlarmName()).toList());
        assertEquals(4, metrics.describeAlarms(null, null, REGION).size());
        for (int index = 1; index < original.getAlarms().size(); index++) {
            assertTrue(metrics.describeAlarms(List.of(original.getAlarms().get(index).getAlarmName()), null, REGION).isEmpty());
        }
        ScalingPolicy restored = JSON.readValue(JSON.writeValueAsBytes(replacement), ScalingPolicy.class);
        service.savePolicy(restored, REGION);
        metrics.deleteAlarms(List.of("cleanup-blocker"), REGION);
        service.reconcileDynamoDbPolicies(Instant.EPOCH);
        assertTrue(storedPolicy().getPendingAlarmDeletions().isEmpty());
        assertEquals(replacement.getAlarms().stream().map(alarm -> alarm.getAlarmName()).toList(),
                storedPolicy().getAlarms().stream().map(alarm -> alarm.getAlarmName()).toList());
        assertTrue(metrics.describeAlarms(List.of(blocked), null, REGION).isEmpty());
    }

    @Test
    void policyUpdatesKeepBlockedCleanupAcrossFurtherReplacements() {
        ScalingPolicy original = trackingPolicy(70);
        String blocked = original.getAlarms().getFirst().getAlarmName();
        blockDeletion(blocked);
        trackingPolicy(60);
        ScalingPolicy latest = trackingPolicy(50);
        assertEquals(4, latest.getAlarms().size());
        assertEquals(List.of(blocked), storedPolicy().getPendingAlarmDeletions().stream()
                .map(alarm -> alarm.getAlarmName()).toList());
        assertEquals(5, metrics.describeAlarms(null, null, REGION).size());
        metrics.deleteAlarms(List.of("cleanup-blocker"), REGION);
        service.reconcileDynamoDbPolicies(Instant.EPOCH);
        assertTrue(storedPolicy().getPendingAlarmDeletions().isEmpty());
        assertEquals(4, metrics.describeAlarms(null, null, REGION).size());
    }

    @Test
    void failedCleanupMetadataSaveKeepsRetryUntilAbsentAlarmIsRecorded() {
        ScalingPolicy original = trackingPolicy(70);
        String blocked = original.getAlarms().getFirst().getAlarmName();
        blockDeletion(blocked);
        trackingPolicy(60);
        assertEquals(1, storedPolicy().getPendingAlarmDeletions().size());
        metrics.deleteAlarms(List.of("cleanup-blocker"), REGION);
        doThrow(new IllegalStateException("controlled cleanup metadata failure")).doCallRealMethod()
                .when(policies).put(anyString(), any(ScalingPolicy.class));
        service.reconcileDynamoDbPolicies(Instant.EPOCH);
        assertTrue(metrics.describeAlarms(List.of(blocked), null, REGION).isEmpty());
        assertEquals(1, storedPolicy().getPendingAlarmDeletions().size());
        service.reconcileDynamoDbPolicies(Instant.EPOCH);
        assertTrue(storedPolicy().getPendingAlarmDeletions().isEmpty());
    }

    @Test
    void pendingCleanupNeverDeletesAnAlarmWhoseActionChanged() {
        ScalingPolicy original = trackingPolicy(70);
        String blocked = original.getAlarms().getFirst().getAlarmName();
        blockDeletion(blocked);
        trackingPolicy(60);
        assertEquals(1, storedPolicy().getPendingAlarmDeletions().size());
        metrics.deleteAlarms(List.of("cleanup-blocker"), REGION);
        MetricAlarm retained = metrics.describeAlarms(List.of(blocked), null, REGION).getFirst();
        retained.setAlarmActions(List.of("different-owner"));
        metrics.putMetricAlarm(retained, REGION);
        service.reconcileDynamoDbPolicies(Instant.EPOCH);
        assertEquals(1, storedPolicy().getPendingAlarmDeletions().size());
        assertEquals(List.of("different-owner"), metrics.describeAlarms(List.of(blocked), null, REGION)
                .getFirst().getAlarmActions());
    }

    @Test
    void successfulReplacementLeavesNoPendingDeletion() {
        trackingPolicy(70);
        ScalingPolicy replacement = trackingPolicy(60);
        assertEquals(4, replacement.getAlarms().size());
        assertTrue(storedPolicy().getPendingAlarmDeletions().isEmpty());
        assertEquals(4, metrics.describeAlarms(null, null, REGION).size());
    }

    @Test
    void committedReplacementSurvivesCleanupMetadataFailureAndRetries() {
        trackingPolicy(70);
        doCallRealMethod().doThrow(new IllegalStateException("controlled cleanup metadata failure"))
                .doCallRealMethod().when(policies).put(anyString(), any(ScalingPolicy.class));
        ScalingPolicy replacement = assertDoesNotThrow(() -> trackingPolicy(60));
        assertEquals(60, replacement.getTargetTrackingConfiguration().getTargetValue());
        assertEquals(60, storedPolicy().getTargetTrackingConfiguration().getTargetValue());
        assertEquals(4, replacement.getPendingAlarmDeletions().size());
        assertEquals(4, storedPolicy().getPendingAlarmDeletions().size());
        assertEquals(4, metrics.describeAlarms(null, null, REGION).size());
        service.reconcileDynamoDbPolicies(Instant.EPOCH);
        assertTrue(storedPolicy().getPendingAlarmDeletions().isEmpty());
        assertEquals(60, storedPolicy().getTargetTrackingConfiguration().getTargetValue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void obsoleteAlarmCleanupPreservesCompletedScalingMetadata(boolean scaleOut) {
        ScalingPolicy original = trackingPolicy(70);
        String blocked = original.getAlarms().getFirst().getAlarmName();
        blockDeletion(blocked);
        ScalingPolicy policy = trackingPolicy(60);
        policy.getTargetTrackingConfiguration().setScaleInCooldown(300);
        policy.setLastScaleInTime(scaleOut ? 1799999999L : 0);
        service.savePolicy(policy, REGION);
        assertEquals(1, storedPolicy().getPendingAlarmDeletions().size());
        metrics.deleteAlarms(List.of("cleanup-blocker"), REGION);
        AtomicLong capacity = new AtomicLong(scaleOut ? 50 : 200);
        when(dynamo.decideScalableCapacity(anyString(), anyString(), anyString(), any(LongUnaryOperator.class)))
                .thenAnswer(invocation -> {
                    long previous = capacity.get();
                    LongUnaryOperator decision = invocation.getArgument(3);
                    long next = decision.applyAsLong(previous);
                    capacity.set(next);
                    return new ScalableCapacityDecision(true, previous, next);
                });
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1800000000L), ZoneOffset.UTC);
        DynamoDbTargetTracking tracking = new DynamoDbTargetTracking(service, dynamo, metrics, clock);
        MetricAlarm alarm = metrics.describeAlarms(
                List.of(policy.getAlarms().get(scaleOut ? 0 : 1).getAlarmName()), null, REGION).getFirst();
        ScalableTarget target = targets.scan(key -> true).getFirst();
        tracking.execute(policy, target, alarm, 3600, REGION);
        assertEquals(100, capacity.get());
        ScalingPolicy stored = storedPolicy();
        assertEquals(100, stored.getLastAppliedCapacity());
        assertEquals(scaleOut ? 0 : 1800000000L, stored.getLastScaleInTime());
        assertEquals(scaleOut ? 1800000000L : 0, stored.getLastScaleOutTime());
        if (scaleOut) {
            assertEquals(100, stored.getLastScaleOutCapacity());
        }
        assertTrue(stored.getPendingAlarmDeletions().isEmpty());
        assertTrue(metrics.describeAlarms(List.of(blocked), null, REGION).isEmpty());
        if (!scaleOut) {
            ScalingPolicy reloaded = JSON.convertValue(stored, ScalingPolicy.class);
            DynamoDbTargetTracking next = new DynamoDbTargetTracking(service, dynamo, metrics,
                    Clock.fixed(clock.instant().plusSeconds(1), ZoneOffset.UTC));
            MetricAlarm current = metrics.describeAlarms(
                    List.of(reloaded.getAlarms().get(1).getAlarmName()), null, REGION).getFirst();
            next.execute(reloaded, target, current, 1800, REGION);
            assertEquals(100, capacity.get(), "Completed cleanup must not allow a second scale-in during cooldown");
        }
    }

    private ScalingPolicy trackingPolicy(double targetValue) {
        TargetTrackingConfiguration configuration = new TargetTrackingConfiguration();
        configuration.setTargetValue(targetValue);
        PredefinedMetricSpecification predefined = new PredefinedMetricSpecification();
        predefined.setPredefinedMetricType("DynamoDBWriteCapacityUtilization");
        configuration.setPredefinedMetricSpecification(predefined);
        return service.putScalingPolicy("owned", "TargetTrackingScaling", "dynamodb", RESOURCE, DIMENSION,
                configuration, null, REGION);
    }

    private ScalingPolicy storedPolicy() {
        return policies.scan(key -> true).getFirst();
    }

    private void blockDeletion(String name) {
        CompositeAlarm composite = new CompositeAlarm();
        composite.setAlarmName("cleanup-blocker");
        composite.setAlarmRule("ALARM(\"" + name + "\")");
        metrics.putCompositeAlarm(composite, REGION);
    }

    private void refuse(ConfigurationCase test) {
        TargetTrackingConfiguration configuration = test.configuration();
        JsonNode supplied = JSON.valueToTree(configuration);
        JsonNode before = snapshot();
        clearInvocations(metrics, policies, dynamo);
        AwsException failure = null;
        try {
            service.putScalingPolicy("owned", "TargetTrackingScaling", "dynamodb", RESOURCE, DIMENSION,
                    configuration, null, REGION);
        } catch (AwsException caught) {
            failure = caught;
        }
        JsonNode after = snapshot();
        AwsException observed = failure;
        System.out.println("DDB_POLICY_CONFIGURATION_ADMISSION case=" + test + " code="
                + (observed == null ? "ACCEPTED" : observed.getErrorCode())
                + " before=" + before + " after=" + after);
        assertAll("Invalid DynamoDB policy configurations refuse before any owned-state mutation",
                () -> {
                    assertNotNull(observed, "The invalid configuration must be refused");
                    assertEquals("ValidationException", observed.getErrorCode());
                    assertEquals(test.message(), observed.getMessage());
                    assertEquals(400, observed.getHttpStatus());
                },
                () -> assertEquals(before, after),
                () -> assertEquals(supplied, JSON.valueToTree(configuration), "Supplied configuration is not mutated"),
                () -> verify(policies, never()).put(anyString(), any(ScalingPolicy.class)),
                () -> verify(metrics, never()).deleteAlarms(anyList(), anyString()),
                () -> verify(metrics, never()).putMetricAlarm(any(MetricAlarm.class), anyString()),
                () -> verifyNoInteractions(dynamo));
    }

    private JsonNode snapshot() {
        PaginatedResult<AlarmHistoryItem> history = history();
        assertNull(history.nextToken());
        List<MetricAlarm> alarms = metrics.describeAlarms(null, null, REGION);
        Map<String, Map<String, String>> tags = new LinkedHashMap<>();
        for (MetricAlarm alarm : alarms) {
            tags.put(alarm.getAlarmArn(), metrics.listTagsForResource(alarm.getAlarmArn(), REGION));
        }
        return JSON.valueToTree(Map.of(
                "targets", targets.scan(key -> true),
                "policies", policies.scan(key -> true),
                "activities", activities.scan(key -> true),
                "scheduled", scheduled.scan(key -> true),
                "alarms", alarms,
                "alarmTags", tags,
                "alarmHistory", history.items()));
    }

    private PaginatedResult<AlarmHistoryItem> history() {
        return metrics.describeAlarmHistory(null, List.of("MetricAlarm"), null, null, null,
                100, null, "TimestampAscending", REGION);
    }

    enum ConfigurationCase {
        CUSTOMIZED, MISMATCH, MISSING_TARGET, MISSING_CONFIG, MISSING_SPEC;

        TargetTrackingConfiguration configuration() {
            if (this == MISSING_CONFIG) {
                return null;
            }
            TargetTrackingConfiguration configuration = new TargetTrackingConfiguration();
            if (this != MISSING_TARGET) {
                configuration.setTargetValue(50.0);
            }
            if (this == CUSTOMIZED) {
                configuration.setCustomizedMetricSpecification(Map.of("Namespace", "Floci/Parity",
                        "MetricName", "Capacity", "Statistic", "Sum", "Unit", "Count"));
            } else if (this != MISSING_SPEC) {
                PredefinedMetricSpecification metric = new PredefinedMetricSpecification();
                metric.setPredefinedMetricType(this == MISMATCH
                        ? "DynamoDBReadCapacityUtilization" : "DynamoDBWriteCapacityUtilization");
                configuration.setPredefinedMetricSpecification(metric);
            }
            return configuration;
        }

        String message() {
            return switch (this) {
                case CUSTOMIZED -> "Scalable dimension dynamodb:table:WriteCapacityUnits "
                        + "does not support CustomizedMetricSpecification.";
                case MISMATCH -> "Scalable dimension dynamodb:table:WriteCapacityUnits "
                        + "only supports the following predefined metric types: DynamoDBWriteCapacityUtilization";
                case MISSING_TARGET -> "1 validation error detected: Value null at "
                        + "'targetTrackingScalingPolicyConfiguration.targetValue' failed to satisfy constraint: "
                        + "Member must not be null";
                case MISSING_CONFIG -> "TargetTrackingScaling policy configuration must be specified";
                case MISSING_SPEC -> "PredefinedMetricSpecification or CustomizedMetricSpecification "
                        + "must be set for target tracking scaling.";
            };
        }
    }
}

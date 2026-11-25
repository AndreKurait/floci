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
import io.floci.aws.services.applicationautoscaling.model.StepAdjustment;
import io.floci.aws.services.applicationautoscaling.model.StepScalingConfiguration;
import io.floci.aws.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.dynamodb.DynamoDbService;
import io.floci.aws.services.dynamodb.DynamoDbService.ScalableCapacityDecision;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real owned stores capture admission and alarm preservation; the native capacity boundary is controlled. */
@Timeout(30)
class DynamoDbPolicyKindAdmissionTest {
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
    @ValueSource(strings = {"StepScaling", "PredictiveScaling"})
    void unsupportedKindCreatePreservesAllOwnedPolicyAndAlarmState(String kind) {
        refuse(kind);
    }

    @ParameterizedTest
    @ValueSource(strings = {"StepScaling", "PredictiveScaling"})
    void unsupportedKindUpdatePreservesExistingPolicyAndFourCompleteAlarms(String kind) {
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
        refuse(kind);
    }

    private void refuse(String kind) {
        JsonNode before = snapshot();
        clearInvocations(metrics, policies, dynamo);
        AwsException failure = null;
        try {
            service.putScalingPolicy("owned", kind, "dynamodb", RESOURCE, DIMENSION,
                    null, "StepScaling".equals(kind) ? step() : null, REGION);
        } catch (AwsException caught) {
            failure = caught;
        }
        JsonNode after = snapshot();
        AwsException observed = failure;
        System.out.println("DDB_POLICY_KIND_ADMISSION kind=" + kind + " code="
                + (observed == null ? "ACCEPTED" : observed.getErrorCode())
                + " before=" + before + " after=" + after);
        assertAll("Unsupported DynamoDB policy kinds refuse before any owned-state mutation",
                () -> {
                    assertNotNull(observed, "The unsupported policy kind must be refused");
                    assertEquals("AccessDeniedException", observed.getErrorCode());
                    assertEquals("Account is not authorized", observed.getMessage());
                    assertEquals(400, observed.getHttpStatus());
                },
                () -> assertEquals(before, after),
                () -> verify(policies, never()).put(anyString(), any(ScalingPolicy.class)),
                () -> verify(metrics, never()).deleteAlarms(anyList(), anyString()),
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

    private static StepScalingConfiguration step() {
        StepScalingConfiguration step = new StepScalingConfiguration();
        step.setAdjustmentType("ChangeInCapacity");
        step.setCooldown(0);
        step.setMetricAggregationType("Average");
        StepAdjustment adjustment = new StepAdjustment();
        adjustment.setMetricIntervalLowerBound(0.0);
        adjustment.setScalingAdjustment(1);
        step.setStepAdjustments(List.of(adjustment));
        return step;
    }
}

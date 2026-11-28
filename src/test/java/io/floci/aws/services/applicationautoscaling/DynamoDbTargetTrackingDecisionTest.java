package io.floci.aws.services.applicationautoscaling;

import io.floci.aws.services.applicationautoscaling.model.Alarm;
import io.floci.aws.services.applicationautoscaling.model.PredefinedMetricSpecification;
import io.floci.aws.services.applicationautoscaling.model.ScalableTarget;
import io.floci.aws.services.applicationautoscaling.model.ScalingPolicy;
import io.floci.aws.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.dynamodb.DynamoDbService;
import io.floci.aws.services.dynamodb.DynamoDbService.ScalableCapacityDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic decision inputs are separate from the real-request SDK producer workload. */
class DynamoDbTargetTrackingDecisionTest {
    private static final String REGION = "us-east-1";
    private static final long NOW = 1800000000L;
    private final ApplicationAutoScalingService service = mock(ApplicationAutoScalingService.class);
    private final DynamoDbService dynamo = mock(DynamoDbService.class);
    private final CloudWatchMetricsService metrics = mock(CloudWatchMetricsService.class);
    private final AtomicLong capacity = new AtomicLong(3);
    private final AtomicBoolean deciding = new AtomicBoolean();
    private final Map<String, MetricAlarm> stored = new LinkedHashMap<>();
    private final DynamoDbTargetTracking helper = new DynamoDbTargetTracking(service, dynamo, metrics,
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    private ScalingPolicy policy;
    private ScalableTarget target;

    @BeforeEach
    void prepareOwnedPolicyAndIndependentCapacityBoundary() {
        policy = policy();
        target = new ScalableTarget();
        target.setServiceNamespace("dynamodb");
        target.setResourceId(policy.getResourceId());
        target.setScalableDimension(policy.getScalableDimension());
        target.setMinCapacity(1);
        target.setMaxCapacity(300);
        when(dynamo.decideScalableCapacity(eq(policy.getResourceId()), eq(policy.getScalableDimension()),
                eq(REGION), any(LongUnaryOperator.class))).thenAnswer(invocation -> {
                    long before = capacity.get();
                    assertTrue(deciding.compareAndSet(false, true));
                    try {
                        LongUnaryOperator operation = invocation.getArgument(3);
                        long next = operation.applyAsLong(before);
                        capacity.set(next);
                        return new ScalableCapacityDecision(true, before, next);
                    } finally {
                        deciding.set(false);
                    }
                });
        doAnswer(invocation -> {
            assertFalse(deciding.get(), "CloudWatch must be outside the pure native capacity decision");
            MetricAlarm alarm = invocation.getArgument(0);
            alarm.setAlarmArn("arn:aws:cloudwatch:us-east-1:740000000001:alarm:" + alarm.getAlarmName());
            stored.put(alarm.getAlarmName(), alarm);
            return null;
        }).when(metrics).putMetricAlarm(any(MetricAlarm.class), eq(REGION));
        when(metrics.describeAlarms(anyList(), isNull(), eq(REGION))).thenAnswer(invocation -> {
            assertFalse(deciding.get());
            List<String> names = invocation.getArgument(0);
            return names.stream().filter(stored::containsKey).map(stored::get).toList();
        });
        doAnswer(invocation -> {
            assertFalse(deciding.get(), "Policy publication follows the completed native decision");
            return null;
        }).when(service).savePolicy(any(ScalingPolicy.class), eq(REGION));
        doAnswer(invocation -> {
            assertFalse(deciding.get());
            return null;
        }).when(service).recordScalingActivity(any(ScalingPolicy.class), anyString(), anyString(), eq(REGION));
        policy.setAlarms(helper.createAlarms(policy, null, REGION));
        clearInvocations(service, dynamo, metrics);
    }

    @ParameterizedTest
    @CsvSource({"9000,3,300,215", "128,3,300,4", "9000,3,100,100",
            "126,3,300,3", "126.0001,3,300,4", "9000,215,300,215",
            "1.7976931348623157E308,3,300,300"})
    void sumPerMinuteUsesCeilingAndActualCurrentThreshold(double sum, long current, int maximum, long expected) {
        capacity.set(current);
        target.setMaxCapacity(maximum);
        helper.execute(policy, target, alarm(0), sum, REGION);
        assertEquals(expected, capacity.get());
        if (expected == current) {
            verify(service, never()).savePolicy(any(), anyString());
            verify(service, never()).recordScalingActivity(any(), anyString(), anyString(), anyString());
        } else {
            ArgumentCaptor<ScalingPolicy> captured = ArgumentCaptor.forClass(ScalingPolicy.class);
            verify(service).savePolicy(captured.capture(), eq(REGION));
            assertNotSame(policy, captured.getValue());
            assertEquals(expected, captured.getValue().getLastAppliedCapacity().longValue());
            assertEquals(NOW, captured.getValue().getLastScaleOutTime());
            assertNull(policy.getLastAppliedCapacity());
            assertEquals(0, policy.getLastScaleOutTime());
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1})
    void nonFiniteOrNegativeMetricsDoNotReadOrWriteCapacity(double metric) {
        helper.execute(policy, target, alarm(0), metric, REGION);
        assertEquals(3, capacity.get());
        verifyNoInteractions(dynamo, service);
    }

    @Test
    void omittedScaleInCooldownAllowsRepeatedRealDecreases() {
        capacity.set(215);
        policy.setLastScaleInTime(NOW);
        helper.execute(policy, target, alarm(1), 0, REGION);
        assertEquals(1, capacity.get());
        ArgumentCaptor<ScalingPolicy> saved = ArgumentCaptor.forClass(ScalingPolicy.class);
        verify(service).savePolicy(saved.capture(), eq(REGION));
        assertEquals(NOW, saved.getValue().getLastScaleInTime());
        assertNull(saved.getValue().getTargetTrackingConfiguration().getScaleInCooldown());
    }

    @Test
    void omittedScaleOutCooldownAllowsTheSameDesiredCapacityAtTheSameInstant() {
        capacity.set(150);
        policy.setLastScaleOutTime(NOW);
        policy.setLastScaleOutCapacity(215);
        policy.setLastAppliedCapacity(150);
        helper.execute(policy, target, alarm(0), 9000, REGION);
        assertEquals(215, capacity.get());
    }

    @Test
    void idleScaleInStopsAtTheCurrentMinimumCapacity() {
        capacity.set(215);
        target.setMinCapacity(10);
        helper.execute(policy, target, alarm(1), 0, REGION);
        assertEquals(10, capacity.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "suspended", "cooldown"})
    void eachScaleInGuardRetainsCurrentCapacity(String guard) {
        capacity.set(215);
        if ("disabled".equals(guard)) {
            policy.getTargetTrackingConfiguration().setDisableScaleIn(true);
        } else if ("suspended".equals(guard)) {
            target.getSuspendedState().setDynamicScalingInSuspended(true);
        } else {
            policy.getTargetTrackingConfiguration().setScaleInCooldown(300);
            policy.setLastScaleInTime(NOW - 299);
        }
        helper.execute(policy, target, alarm(1), 0, REGION);
        assertEquals(215, capacity.get());
        verify(service, never()).savePolicy(any(), anyString());
    }

    @Test
    void scaleInCooldownExpiresAtItsExactBoundary() {
        capacity.set(215);
        policy.getTargetTrackingConfiguration().setScaleInCooldown(300);
        policy.setLastScaleInTime(NOW - 300);
        helper.execute(policy, target, alarm(1), 0, REGION);
        assertEquals(1, capacity.get());
    }

    @ParameterizedTest
    @CsvSource({"false,false,150", "true,false,215", "false,true,215"})
    void scaleOutCooldownRetainsItsPriorOutReferenceUnlessDriftedOrLarger(
            boolean drift, boolean larger, long expected) {
        capacity.set(150);
        policy.getTargetTrackingConfiguration().setScaleOutCooldown(300);
        policy.setLastScaleOutTime(NOW - 1);
        policy.setLastAppliedCapacity(drift ? 151 : 150);
        policy.setLastScaleOutCapacity(larger ? 200 : 215);
        helper.execute(policy, target, alarm(0), 9000, REGION);
        assertEquals(expected, capacity.get());
    }

    @Test
    void suspensionUsesDirectionAfterCapacityBoundsAreApplied() {
        capacity.set(215);
        target.setMaxCapacity(100);
        target.getSuspendedState().setDynamicScalingInSuspended(true);
        helper.execute(policy, target, alarm(0), 20000, REGION);
        assertEquals(215, capacity.get());
        target.getSuspendedState().setDynamicScalingInSuspended(false);
        target.getSuspendedState().setDynamicScalingOutSuspended(true);
        helper.execute(policy, target, alarm(0), 20000, REGION);
        assertEquals(100, capacity.get());
    }

    @Test
    void staleAlarmThresholdCannotOverrideCurrentCapacityThreshold() {
        MetricAlarm captured = alarm(0);
        assertEquals(126.0, captured.getThreshold());
        capacity.set(300);
        helper.execute(policy, target, captured, 9000, REGION);
        assertEquals(300, capacity.get());
        verify(service, never()).savePolicy(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "arn", "metric", "statistic", "action", "period", "disabled", "datapoints"})
    void aChangedAlarmIdentityOrConfigurationCannotTriggerCapacity(String changed) {
        MetricAlarm alarm = alarm(0);
        switch (changed) {
            case "name" -> alarm.setAlarmName("unowned");
            case "arn" -> alarm.setAlarmArn("arn:aws:cloudwatch:us-east-1:740000000002:alarm:unowned");
            case "metric" -> alarm.setMetricName("unowned");
            case "statistic" -> alarm.setStatistic("Average");
            case "action" -> alarm.setAlarmActions(List.of("unowned"));
            case "period" -> alarm.setPeriod(300);
            case "disabled" -> alarm.setActionsEnabled(false);
            case "datapoints" -> alarm.setDatapointsToAlarm(1);
            default -> throw new AssertionError(changed);
        }
        helper.execute(policy, target, alarm, 9000, REGION);
        assertEquals(3, capacity.get());
        verifyNoInteractions(dynamo, service);
    }

    @Test
    void provisionedAlarmOnlyRefreshesThresholdsWithoutPublishingAnActivity() {
        MetricAlarm provisioned = alarm(2);
        capacity.set(17);
        helper.execute(policy, target, provisioned, 9000, REGION);
        assertEquals(17, capacity.get());
        assertEquals(714.0, alarm(0).getThreshold());
        assertEquals(510.0, alarm(1).getThreshold());
        assertEquals(17.0, alarm(2).getThreshold());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"policy", "activity"})
    void metadataFailureAfterCapacityCommitDoesNotReverseTheAppliedCapacity(String stage) {
        IllegalStateException failure = new IllegalStateException(stage + " persistence");
        if ("policy".equals(stage)) {
            doThrow(failure).when(service).savePolicy(any(), eq(REGION));
        } else {
            doThrow(failure).when(service).recordScalingActivity(any(), anyString(), anyString(), eq(REGION));
        }
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> helper.execute(policy, target, alarm(0), 9000, REGION)));
        assertEquals(215, capacity.get());
        assertNull(policy.getLastAppliedCapacity());
        assertEquals(0, policy.getLastScaleOutTime());
        verify(dynamo, times(1)).decideScalableCapacity(anyString(), anyString(), eq(REGION), any());
    }

    @Test
    void inactiveResultDoesNotPublishPolicyActivityOrThresholds() {
        when(dynamo.decideScalableCapacity(anyString(), anyString(), eq(REGION), any()))
                .thenReturn(new ScalableCapacityDecision(false, 0, 0));
        helper.execute(policy, target, alarm(0), 9000, REGION);
        verifyNoInteractions(service, metrics);
    }

    @ParameterizedTest
    @ValueSource(strings = {"custom", "mismatch", "step", "zero", "nan", "below-range", "above-range"})
    void unsupportedStoredConfigurationsStayOutsideTheNewPath(String kind) {
        switch (kind) {
            case "custom" -> policy.getTargetTrackingConfiguration().setCustomizedMetricSpecification(Map.of());
            case "mismatch" -> policy.getTargetTrackingConfiguration().getPredefinedMetricSpecification()
                    .setPredefinedMetricType("DynamoDBReadCapacityUtilization");
            case "step" -> policy.setPolicyType("StepScaling");
            case "zero" -> policy.getTargetTrackingConfiguration().setTargetValue(0.0);
            case "nan" -> policy.getTargetTrackingConfiguration().setTargetValue(Double.NaN);
            case "below-range" -> policy.getTargetTrackingConfiguration().setTargetValue(9.99);
            case "above-range" -> policy.getTargetTrackingConfiguration().setTargetValue(90.01);
            default -> throw new AssertionError(kind);
        }
        assertFalse(DynamoDbTargetTracking.supports(policy));
    }

    @Test
    void disabledScaleInStillExecutesOwnedConsumedHighAndReconcilesThreeAlarms() {
        installDisabledScaleInPolicy();
        helper.execute(policy, target, alarm(0), 9000, REGION);
        assertAll(() -> assertEquals(3, policy.getAlarms().size()),
                () -> assertEquals(215, capacity.get()),
                () -> assertEquals(9030.0, alarm(0).getThreshold()),
                () -> assertEquals(215.0, alarm(1).getThreshold()),
                () -> assertEquals(215.0, alarm(2).getThreshold()),
                () -> assertTrue(stored.values().stream().noneMatch(alarm ->
                        "ConsumedWriteCapacityUnits".equals(alarm.getMetricName())
                                && "LessThanThreshold".equals(alarm.getComparisonOperator()))));
        verify(service).savePolicy(any(ScalingPolicy.class), eq(REGION));
        verify(service).recordScalingActivity(any(ScalingPolicy.class), anyString(), anyString(), eq(REGION));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void eitherProvisionedAlarmInThreeAlarmLayoutOnlyReconcilesThresholds(int position) {
        installDisabledScaleInPolicy();
        MetricAlarm provisioned = alarm(position);
        capacity.set(17);
        helper.execute(policy, target, provisioned, 9000, REGION);
        assertAll(() -> assertEquals(3, policy.getAlarms().size()),
                () -> assertEquals("ProvisionedWriteCapacityUnits", provisioned.getMetricName()),
                () -> assertEquals(position == 1 ? "GreaterThanThreshold" : "LessThanThreshold",
                        provisioned.getComparisonOperator()),
                () -> assertEquals(17, capacity.get()),
                () -> assertEquals(714.0, alarm(0).getThreshold()),
                () -> assertEquals(17.0, alarm(1).getThreshold()),
                () -> assertEquals(17.0, alarm(2).getThreshold()));
        verifyNoInteractions(service);
    }

    @Test
    void retiredConsumedLowCannotExecuteAgainstThreeAlarmOwnership() {
        MetricAlarm retired = alarm(1);
        installDisabledScaleInPolicy();
        capacity.set(215);
        helper.execute(policy, target, retired, 0, REGION);
        assertEquals(3, policy.getAlarms().size());
        assertEquals(215, capacity.get());
        verifyNoInteractions(service, dynamo, metrics);
    }

    private void installDisabledScaleInPolicy() {
        policy.getTargetTrackingConfiguration().setDisableScaleIn(true);
        stored.clear();
        policy.setAlarms(helper.createAlarms(policy, null, REGION));
        clearInvocations(service, dynamo, metrics);
    }

    private MetricAlarm alarm(int kind) {
        return stored.get(policy.getAlarms().get(kind).getAlarmName());
    }

    private static ScalingPolicy policy() {
        ScalingPolicy policy = new ScalingPolicy();
        policy.setPolicyName("owned");
        policy.setPolicyArn("arn:aws:autoscaling:us-east-1:740000000001:scalingPolicy:owned");
        policy.setServiceNamespace("dynamodb");
        policy.setResourceId("table/owned");
        policy.setScalableDimension("dynamodb:table:WriteCapacityUnits");
        policy.setPolicyType("TargetTrackingScaling");
        TargetTrackingConfiguration config = new TargetTrackingConfiguration();
        config.setTargetValue(70.0);
        PredefinedMetricSpecification predefined = new PredefinedMetricSpecification();
        predefined.setPredefinedMetricType("DynamoDBWriteCapacityUtilization");
        config.setPredefinedMetricSpecification(predefined);
        policy.setTargetTrackingConfiguration(config);
        policy.setAlarms(new ArrayList<Alarm>());
        return policy;
    }
}

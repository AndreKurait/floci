package io.floci.aws.services.applicationautoscaling;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.applicationautoscaling.model.Alarm;
import io.floci.aws.services.applicationautoscaling.model.PredefinedMetricSpecification;
import io.floci.aws.services.applicationautoscaling.model.ScalableTarget;
import io.floci.aws.services.applicationautoscaling.model.ScalingPolicy;
import io.floci.aws.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.Dimension;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.dynamodb.DynamoDbService;
import io.floci.aws.services.dynamodb.DynamoDbService.ScalableCapacityDecision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Native DynamoDB target tracking, called only while the owning scalable-target lock is held.
 * This internal path does not assume a service-linked role or authorize an UpdateTable request.
 */
final class DynamoDbTargetTracking {
    private static final List<String> SUFFIXES =
            List.of("AlarmHigh", "AlarmLow", "ProvisionedCapacityHigh", "ProvisionedCapacityLow");

    private final ApplicationAutoScalingService service;
    private final DynamoDbService dynamo;
    private final CloudWatchMetricsService cloudWatch;
    private final Clock clock;

    DynamoDbTargetTracking(ApplicationAutoScalingService service, DynamoDbService dynamo,
                          CloudWatchMetricsService cloudWatch, Clock clock) {
        this.service = service;
        this.dynamo = dynamo;
        this.cloudWatch = cloudWatch;
        this.clock = clock;
    }

    static void validateAdmission(ScalingPolicy policy) {
        if (!"TargetTrackingScaling".equals(policy.getPolicyType())) {
            throw new AwsException("AccessDeniedException", "Account is not authorized", 400);
        }
        TargetTrackingConfiguration configuration = policy.getTargetTrackingConfiguration();
        if (configuration == null) {
            throw new AwsException("ValidationException", "TargetTrackingScaling policy configuration must be specified", 400);
        }
        if (configuration.getTargetValue() == null) {
            throw new AwsException("ValidationException", "1 validation error detected: Value null at "
                    + "'targetTrackingScalingPolicyConfiguration.targetValue' failed to satisfy constraint: "
                    + "Member must not be null", 400);
        }
        if (configuration.getCustomizedMetricSpecification() != null) {
            throw new AwsException("ValidationException", "Scalable dimension " + policy.getScalableDimension()
                    + " does not support CustomizedMetricSpecification.", 400);
        }
        if (configuration.getPredefinedMetricSpecification() == null) {
            throw new AwsException("ValidationException",
                    "PredefinedMetricSpecification or CustomizedMetricSpecification must be set for target tracking scaling.", 400);
        }
        String metric = configuration.getPredefinedMetricSpecification().getPredefinedMetricType();
        String expected = read(policy) ? "DynamoDBReadCapacityUtilization" : "DynamoDBWriteCapacityUtilization";
        if (!expected.equals(metric)) {
            throw new AwsException("ValidationException", "Scalable dimension " + policy.getScalableDimension()
                    + " only supports the following predefined metric types: " + expected, 400);
        }
        Double target = configuration.getTargetValue();
        if (!Double.isFinite(target) || target < 10 || target > 90) {
            throw new AwsException("ValidationException", "For predefined metric type " + metric
                    + ", target value must be between '10.0' and '90.0', but was '" + target + "'.", 400);
        }
    }

    static TargetTrackingConfiguration resolveConfiguration(TargetTrackingConfiguration supplied,
                                                            TargetTrackingConfiguration previous) {
        if (supplied == null) {
            return previous;
        }
        TargetTrackingConfiguration resolved = new TargetTrackingConfiguration();
        resolved.setTargetValue(supplied.getTargetValue());
        resolved.setCustomizedMetricSpecification(supplied.getCustomizedMetricSpecification());
        resolved.setScaleInCooldown(supplied.getScaleInCooldown());
        resolved.setScaleOutCooldown(supplied.getScaleOutCooldown());
        resolved.setDisableScaleIn(supplied.getDisableScaleIn());
        PredefinedMetricSpecification metric = supplied.getPredefinedMetricSpecification();
        if (metric == null && previous != null) {
            metric = previous.getPredefinedMetricSpecification();
        }
        if (metric != null) {
            PredefinedMetricSpecification retained = new PredefinedMetricSpecification();
            retained.setPredefinedMetricType(metric.getPredefinedMetricType());
            retained.setResourceLabel(metric.getResourceLabel());
            resolved.setPredefinedMetricSpecification(retained);
        }
        return resolved;
    }

    static boolean sameTargetTrackingConfiguration(TargetTrackingConfiguration left,
                                                    TargetTrackingConfiguration right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        PredefinedMetricSpecification leftMetric = left.getPredefinedMetricSpecification();
        PredefinedMetricSpecification rightMetric = right.getPredefinedMetricSpecification();
        boolean sameMetric = leftMetric == rightMetric || leftMetric != null && rightMetric != null
                && Objects.equals(leftMetric.getPredefinedMetricType(), rightMetric.getPredefinedMetricType())
                && Objects.equals(leftMetric.getResourceLabel(), rightMetric.getResourceLabel());
        return sameMetric
                && Objects.equals(left.getTargetValue(), right.getTargetValue())
                && Objects.equals(left.getCustomizedMetricSpecification(), right.getCustomizedMetricSpecification())
                && Objects.equals(left.getDisableScaleIn(), right.getDisableScaleIn())
                && Objects.equals(left.getScaleInCooldown(), right.getScaleInCooldown())
                && Objects.equals(left.getScaleOutCooldown(), right.getScaleOutCooldown());
    }

    static boolean supports(ScalingPolicy policy) {
        TargetTrackingConfiguration config = policy.getTargetTrackingConfiguration();
        if (!DynamoDbRegistrationTarget.supports(policy.getServiceNamespace(), policy.getScalableDimension())
                || !"TargetTrackingScaling".equals(policy.getPolicyType()) || config == null
                || config.getCustomizedMetricSpecification() != null || config.getPredefinedMetricSpecification() == null
                || config.getTargetValue() == null || !Double.isFinite(config.getTargetValue())
                || config.getTargetValue() < 10 || config.getTargetValue() > 90) {
            return false;
        }
        String expected = read(policy) ? "DynamoDBReadCapacityUtilization" : "DynamoDBWriteCapacityUtilization";
        return expected.equals(config.getPredefinedMetricSpecification().getPredefinedMetricType());
    }

    List<Alarm> createAlarms(ScalingPolicy policy, ScalingPolicy previous, String region) {
        ScalableCapacityDecision current = snapshot(policy, region);
        if (!current.active()) {
            return previous == null ? List.of() : previous.getAlarms();
        }
        List<Alarm> result = new ArrayList<>();
        try {
            for (int kind : alarmKinds(policy)) {
                String resource = policy.getResourceId();
                String prefix = "TargetTracking-";
                String identifier = UUID.randomUUID().toString();
                int longestSuffix = SUFFIXES.stream().mapToInt(String::length).max().orElseThrow();
                int resourceLimit = 255 - prefix.length() - longestSuffix - identifier.length() - 2;
                String name = prefix + resource.substring(0, Math.min(resourceLimit, resource.length()))
                        + "-" + SUFFIXES.get(kind) + "-" + identifier;
                MetricAlarm alarm = configuration(policy, kind, current.capacity(), name);
                // Track a new name before a persistence call whose failure may follow its write.
                Alarm identity = new Alarm(name, null);
                result.add(identity);
                cloudWatch.putMetricAlarm(alarm, region);
                identity.setAlarmArn(alarm.getAlarmArn());
            }
        } catch (RuntimeException failure) {
            for (Alarm created : result) {
                try {
                    cloudWatch.deleteAlarms(List.of(created.getAlarmName()), region);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        return result;
    }

    void reconcile(ScalingPolicy policy, String region) {
        ScalableCapacityDecision current = snapshot(policy, region);
        if (!current.active()) {
            return;
        }
        if (policy.getAlarms().size() != alarmKinds(policy).size()) {
            ScalingPolicy restored = copyPolicy(policy);
            restored.setAlarms(createAlarms(restored, null, region));
            try {
                service.savePolicy(restored, region);
            } catch (RuntimeException failure) {
                deleteNewAlarms(restored, policy, region, failure);
                throw failure;
            }
            for (Alarm old : policy.getAlarms()) {
                cloudWatch.deleteAlarms(List.of(old.getAlarmName()), region);
            }
            return;
        }
        List<Integer> kinds = alarmKinds(policy);
        for (int index = 0; index < policy.getAlarms().size(); index++) {
            int kind = kinds.get(index);
            Alarm identity = policy.getAlarms().get(index);
            MetricAlarm desired = configuration(policy, kind, current.capacity(), identity.getAlarmName());
            List<MetricAlarm> existing = cloudWatch.describeAlarms(List.of(identity.getAlarmName()), null, region);
            if (existing.size() == 1) {
                MetricAlarm stored = existing.getFirst();
                if (!identity.getAlarmArn().equals(stored.getAlarmArn())) {
                    continue;
                }
                if (sameConfiguration(stored, desired, true)) {
                    continue;
                }
            }
            cloudWatch.putMetricAlarm(desired, region);
        }
    }

    void execute(ScalingPolicy policy, ScalableTarget target, MetricAlarm alarm, double metricValue, String region) {
        int kind = ownedKind(policy, alarm);
        if (kind < 0) {
            return;
        }
        if (kind >= 2) {
            reconcile(policy, region);
            return;
        }
        if (!Double.isFinite(metricValue) || metricValue < 0) {
            return;
        }
        long now = clock.instant().getEpochSecond();
        ScalableTarget capturedTarget = new ScalableTarget(target);
        ScalableCapacityDecision result = dynamo.decideScalableCapacity(policy.getResourceId(),
                policy.getScalableDimension(), region,
                current -> decide(policy, capturedTarget, kind, metricValue, current, now));
        if (!result.active()) {
            return;
        }
        if (result.capacity() != result.previousCapacity()) {
            ScalingPolicy applied = copyPolicy(policy);
            boolean scaleOut = result.capacity() > result.previousCapacity();
            if (scaleOut) {
                applied.setLastScaleOutTime(now);
                applied.setLastScaleOutCapacity(Math.toIntExact(result.capacity()));
            } else {
                applied.setLastScaleInTime(now);
            }
            applied.setLastAppliedCapacity(Math.toIntExact(result.capacity()));
            // The capacity write has already succeeded. Later metadata failures do not undo it.
            service.savePolicy(applied, region);
            service.recordScalingActivity(applied, "Setting desired capacity to " + result.capacity(),
                    "monitor alarm " + alarm.getAlarmName() + " in state ALARM triggered policy "
                            + policy.getPolicyName(), region);
        }
        reconcile(policy, region);
    }

    private static long decide(ScalingPolicy policy, ScalableTarget target, int kind,
                               double metricValue, long current, long now) {
        TargetTrackingConfiguration config = policy.getTargetTrackingConfiguration();
        double threshold = threshold(policy, kind, current);
        if (kind == 0 ? metricValue <= threshold : metricValue >= threshold) {
            return current;
        }
        BigDecimal calculated = BigDecimal.valueOf(metricValue).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(config.getTargetValue()).multiply(BigDecimal.valueOf(60)), 0,
                        RoundingMode.CEILING);
        long desired = calculated.max(BigDecimal.valueOf(target.getMinCapacity()))
                .min(BigDecimal.valueOf(target.getMaxCapacity())).longValueExact();
        if (desired == current) {
            return current;
        }
        boolean scaleOut = desired > current;
        if (scaleOut) {
            if (target.getSuspendedState().isDynamicScalingOutSuspended()) {
                return current;
            }
            Integer lastApplied = policy.getLastAppliedCapacity();
            Integer lastOut = policy.getLastScaleOutCapacity();
            boolean drifted = lastApplied != null && lastApplied.longValue() != current;
            boolean larger = lastOut == null || drifted || desired > lastOut;
            if (!larger && withinCooldown(config.getScaleOutCooldown(), policy.getLastScaleOutTime(), now)) {
                return current;
            }
        } else if (Boolean.TRUE.equals(config.getDisableScaleIn())
                || target.getSuspendedState().isDynamicScalingInSuspended()
                || withinCooldown(config.getScaleInCooldown(), policy.getLastScaleInTime(), now)) {
            return current;
        }
        return desired;
    }

    private static boolean withinCooldown(Integer configured, double previous, long now) {
        int seconds = configured == null ? 0 : configured;
        return previous > 0 && now - previous < seconds;
    }

    private ScalableCapacityDecision snapshot(ScalingPolicy policy, String region) {
        return dynamo.decideScalableCapacity(policy.getResourceId(), policy.getScalableDimension(),
                region, capacity -> capacity);
    }

    void deleteNewAlarms(ScalingPolicy candidate, ScalingPolicy previous, String region, RuntimeException failure) {
        List<String> retained = previous == null ? List.of()
                : previous.getAlarms().stream().map(Alarm::getAlarmName).toList();
        for (Alarm alarm : candidate.getAlarms()) {
            if (!retained.contains(alarm.getAlarmName())) {
                try {
                    cloudWatch.deleteAlarms(List.of(alarm.getAlarmName()), region);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    private static List<Integer> alarmKinds(ScalingPolicy policy) {
        return Boolean.TRUE.equals(policy.getTargetTrackingConfiguration().getDisableScaleIn())
                ? List.of(0, 2, 3) : List.of(0, 1, 2, 3);
    }

    private static int ownedKind(ScalingPolicy policy, MetricAlarm alarm) {
        if (policy.getAlarms().size() != alarmKinds(policy).size()) {
            return -1;
        }
        List<Integer> kinds = alarmKinds(policy);
        for (int index = 0; index < policy.getAlarms().size(); index++) {
            int kind = kinds.get(index);
            Alarm identity = policy.getAlarms().get(index);
            if (Objects.equals(identity.getAlarmName(), alarm.getAlarmName())
                    && Objects.equals(identity.getAlarmArn(), alarm.getAlarmArn())
                    && sameConfiguration(alarm, configuration(policy, kind, 1, identity.getAlarmName()), false)) {
                return kind;
            }
        }
        return -1;
    }

    private static MetricAlarm configuration(ScalingPolicy policy, int kind, long capacity, String name) {
        boolean consumed = kind < 2;
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(name);
        alarm.setAlarmDescription("DO NOT EDIT OR DELETE. For TargetTrackingScaling policy " + policy.getPolicyArn() + ".");
        alarm.setActionsEnabled(true);
        alarm.setNamespace("AWS/DynamoDB");
        alarm.setMetricName((consumed ? "Consumed" : "Provisioned") + (read(policy) ? "Read" : "Write") + "CapacityUnits");
        alarm.setDimensions(dimensions(policy));
        alarm.setStatistic(consumed ? "Sum" : "Average");
        alarm.setPeriod(consumed ? 60 : 300);
        alarm.setEvaluationPeriods(consumed ? kind == 0 ? 2 : 15 : 3);
        alarm.setComparisonOperator(kind % 2 == 0 ? "GreaterThanThreshold" : "LessThanThreshold");
        alarm.setThreshold(threshold(policy, kind, capacity));
        alarm.setAlarmActions(List.of(policy.getPolicyArn()));
        return alarm;
    }

    private static double threshold(ScalingPolicy policy, int kind, long capacity) {
        if (kind >= 2) {
            return capacity;
        }
        double target = policy.getTargetTrackingConfiguration().getTargetValue();
        return capacity * 60.0 * (kind == 0 ? target : Math.max(5, target - 20)) / 100;
    }

    private static List<Dimension> dimensions(ScalingPolicy policy) {
        String[] parts = policy.getResourceId().split("/");
        return parts.length == 4
                ? List.of(new Dimension("TableName", parts[1]), new Dimension("GlobalSecondaryIndexName", parts[3]))
                : List.of(new Dimension("TableName", parts[1]));
    }

    private static boolean read(ScalingPolicy policy) {
        return policy.getScalableDimension().endsWith("ReadCapacityUnits");
    }

    private static boolean sameConfiguration(MetricAlarm left, MetricAlarm right, boolean includeThreshold) {
        return Objects.equals(left.getNamespace(), right.getNamespace())
                && Objects.equals(left.getMetricName(), right.getMetricName())
                && Objects.equals(left.getDimensions(), right.getDimensions())
                && Objects.equals(left.getStatistic(), right.getStatistic())
                && Objects.equals(left.getPeriod(), right.getPeriod())
                && left.getEvaluationPeriods() == right.getEvaluationPeriods()
                && Objects.equals(left.getComparisonOperator(), right.getComparisonOperator())
                && (!includeThreshold || left.getThreshold() == right.getThreshold())
                && Objects.equals(left.getAlarmActions(), right.getAlarmActions())
                && left.isActionsEnabled() == right.isActionsEnabled()
                && left.getMetrics().isEmpty()
                && left.getDatapointsToAlarm() == null
                && Objects.equals(left.getTreatMissingData(), right.getTreatMissingData())
                && Objects.equals(left.getUnit(), right.getUnit());
    }

    /** Only these execution metadata fields change; nested public configurations are read without mutation. */
    static ScalingPolicy copyPolicy(ScalingPolicy source) {
        ScalingPolicy copy = new ScalingPolicy();
        copy.setPolicyArn(source.getPolicyArn());
        copy.setPolicyName(source.getPolicyName());
        copy.setPolicyType(source.getPolicyType());
        copy.setServiceNamespace(source.getServiceNamespace());
        copy.setResourceId(source.getResourceId());
        copy.setScalableDimension(source.getScalableDimension());
        copy.setCreationTime(source.getCreationTime());
        copy.setAlarms(source.getAlarms());
        copy.setTargetTrackingConfiguration(source.getTargetTrackingConfiguration());
        copy.setStepScalingConfiguration(source.getStepScalingConfiguration());
        copy.setLastScaleOutTime(source.getLastScaleOutTime());
        copy.setLastScaleInTime(source.getLastScaleInTime());
        copy.setLastAppliedCapacity(source.getLastAppliedCapacity());
        copy.setLastScaleOutCapacity(source.getLastScaleOutCapacity());
        return copy;
    }
}

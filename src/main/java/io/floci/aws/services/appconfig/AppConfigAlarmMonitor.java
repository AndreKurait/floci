package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceRoleCallAuthorizer;
import io.floci.aws.services.appconfig.model.Monitor;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.AlarmFilter;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.DescribedAlarms;
import io.floci.aws.services.cloudwatch.metrics.model.Alarm;
import io.floci.aws.services.cloudwatch.metrics.model.CompositeAlarm;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads an environment's alarms using its configured monitoring role. */
@ApplicationScoped
public class AppConfigAlarmMonitor {
    private final CloudWatchMetricsService cloudWatch;
    private final ServiceRoleCallAuthorizer authorizer;
    private final RegionResolver regions;

    @Inject
    public AppConfigAlarmMonitor(CloudWatchMetricsService cloudWatch, ServiceRoleCallAuthorizer authorizer,
                                 RegionResolver regions) {
        this.cloudWatch = cloudWatch;
        this.authorizer = authorizer;
        this.regions = regions;
    }

    /** Returns the first rollback cause, after validating access to every configured monitor. */
    String rollbackCause(List<Monitor> monitors) {
        if (monitors == null) {
            return null;
        }
        String cause = null;
        for (Monitor monitor : monitors) {
            Alarm alarm = read(monitor);
            if (cause == null && alarm.isActionsEnabled()
                    && ("ALARM".equals(alarm.getStateValue()) || "INSUFFICIENT_DATA".equals(alarm.getStateValue()))) {
                cause = alarm.getAlarmArn() + " is " + alarm.getStateValue();
            }
        }
        return cause;
    }

    private Alarm read(Monitor monitor) {
        String arn = monitor.alarmArn();
        if (!AwsArnUtils.isArnFor(arn, "cloudwatch")) {
            throw unavailable(arn, "a CloudWatch alarm ARN is required");
        }
        AwsArnUtils.Arn id = AwsArnUtils.parse(arn);
        if (!id.accountId().equals(regions.getAccountId()) || !id.region().equals(regions.getRegion())
                || !id.partition().equals(regions.getPartition())
                || !id.resource().startsWith("alarm:") || id.resource().length() <= "alarm:".length()) {
            throw unavailable(arn, "the alarm must belong to the deployment account, region and partition");
        }
        DescribedAlarms described = cloudWatch.describeAlarms(new AlarmFilter(
                List.of(id.resource().substring("alarm:".length())), null,
                List.of("MetricAlarm", "CompositeAlarm"), null, null, null, null), id.region());
        List<Alarm> alarms = new ArrayList<>(described.metricAlarms());
        alarms.addAll(described.compositeAlarms());
        Alarm alarm = alarms.stream().filter(value -> arn.equals(value.getAlarmArn())).findFirst().orElse(null);
        Map<String, List<String>> context = new HashMap<>();
        if (alarm != null && alarm.getTags() != null) {
            alarm.getTags().forEach((key, value) -> context.put("aws:ResourceTag/" + key, List.of(value)));
        }
        Decision decision = alarm instanceof CompositeAlarm
                ? authorizer.authorizeUnscopedWithoutSourceContext(
                        "appconfig", monitor.alarmRoleArn(), "cloudwatch:DescribeAlarms",
                        id.accountId(), id.region())
                : authorizer.authorizeWithoutSourceContext(
                        "appconfig", monitor.alarmRoleArn(), "cloudwatch:DescribeAlarms", arn,
                        id.accountId(), id.partition(), context);
        if (decision != Decision.ALLOW) {
            throw unavailable(arn, "the monitoring role cannot describe this alarm");
        }
        if (alarm == null) {
            throw unavailable(arn, "the alarm does not exist");
        }
        return alarm;
    }

    private static AwsException unavailable(String arn, String reason) {
        return new AwsException("BadRequestException", "Cannot monitor " + arn + ": " + reason, 400);
    }
}

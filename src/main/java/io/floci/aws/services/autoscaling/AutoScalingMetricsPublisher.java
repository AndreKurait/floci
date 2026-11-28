package io.floci.aws.services.autoscaling;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
import io.floci.aws.services.autoscaling.model.AsgInstance;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.Dimension;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Publishes enabled basic group gauges once per observed minute, without backfilling missed minutes. */
@ApplicationScoped
public class AutoScalingMetricsPublisher extends SweepScheduler {
    private static final Logger LOG = Logger.getLogger(AutoScalingMetricsPublisher.class);
    static final String NAMESPACE = "AWS/AutoScaling";

    private final Work work;

    @Inject
    public AutoScalingMetricsPublisher(AutoScalingService service, CloudWatchMetricsService metrics,
                                      EmulatorConfig config) {
        this(service, metrics, config.services().autoscaling().enabled()
                && config.services().autoscaling().cloudwatchMetricsEnabled()
                && config.services().cloudwatchmetrics().enabled());
    }

    AutoScalingMetricsPublisher(AutoScalingService service, CloudWatchMetricsService metrics, boolean enabled) {
        this(service, metrics, new Work(), enabled);
    }

    private AutoScalingMetricsPublisher(AutoScalingService service, CloudWatchMetricsService metrics,
                                       Work work, boolean enabled) {
        super(new SweepWorker("autoscaling-group-metrics", enabled, 60, false,
                now -> publish(service, metrics, work, now)));
        this.work = work;
    }

    AutoScalingMetricsPublisher() {
        this.work = null;
    }

    private static void publish(AutoScalingService service, CloudWatchMetricsService metrics,
                                Work work, Instant now) {
        long minute = now.truncatedTo(ChronoUnit.MINUTES).getEpochSecond();
        work.samples.entrySet().removeIf(entry -> entry.getValue().minute != minute);
        for (String account : service.autoScalingGroupAccountIds()) {
            try {
                RequestScopes.runAs(account, () -> {
                    for (AutoScalingGroup group : service.describeAutoScalingGroups(null, null)) {
                        try {
                            RequestScopes.runAs(account, group.getRegion(), () ->
                                    service.reconcileIfPresent(group, () ->
                                            publishGroup(metrics, work, account, group, minute)));
                        } catch (RuntimeException failure) {
                            LOG.warnv(failure, "Group metric publication failed for {0} in account {1}",
                                    group.getAutoScalingGroupName(), account);
                        }
                    }
                });
            } catch (RuntimeException failure) {
                LOG.warnv(failure, "Group metric enumeration failed for account {0}", account);
            }
        }
    }

    // The existing group lock remains held through local metric storage writes. Disable, delete and
    // replacement therefore cannot finish before an older captured group publishes another sample.
    private static void publishGroup(CloudWatchMetricsService metrics, Work work, String account,
                                     AutoScalingGroup group, long minute) {
        if (group.getEnabledMetrics().isEmpty()) {
            return;
        }
        String incarnation = group.getMetricsIncarnation();
        if (incarnation == null) {
            // Legacy groups have no UUID. This stable fallback survives restarts; every new group gets
            // a UUID, including a same-name replacement with the identical public creation timestamp.
            incarnation = "legacy:" + group.getAutoScalingGroupArn() + ":" + group.getCreatedTime();
        }
        GroupKey key = new GroupKey(account, group.getRegion(), incarnation);
        Sample sample = work.samples.computeIfAbsent(key, ignored -> new Sample(minute, datums(group, minute)));
        for (Map.Entry<String, MetricDatum> entry : sample.datums.entrySet()) {
            String name = entry.getKey();
            if (group.getEnabledMetrics().contains(name) && !sample.delivered.contains(name)) {
                // A write that succeeded before throwing can be retried without adding another contribution.
                metrics.publishMetricForAccount(account, NAMESPACE, entry.getValue(), group.getRegion(),
                        "asg:" + key.incarnation + ":" + minute);
                sample.delivered.add(name);
            }
        }
    }

    private static Map<String, MetricDatum> datums(AutoScalingGroup group, long minute) {
        int inService = 0;
        int pending = 0;
        int standby = 0;
        int terminating = 0;
        for (AsgInstance instance : group.getInstances()) {
            switch (instance.getLifecycleState()) {
                case "InService" -> inService++;
                case "Pending", "Pending:Wait", "Pending:Proceed" -> pending++;
                case "Standby" -> standby++;
                case "Terminating", "Terminating:Wait", "Terminating:Proceed" -> terminating++;
                case null, default -> {
                    // Retained, warm-pool and transitional states do not belong to these basic gauges.
                }
            }
        }
        List<Dimension> dimensions = List.of(new Dimension("AutoScalingGroupName", group.getAutoScalingGroupName()));
        Map<String, MetricDatum> datums = new LinkedHashMap<>();
        add(datums, dimensions, minute, "GroupMinSize", group.getMinSize());
        add(datums, dimensions, minute, "GroupMaxSize", group.getMaxSize());
        add(datums, dimensions, minute, "GroupDesiredCapacity", group.getDesiredCapacity());
        add(datums, dimensions, minute, "GroupInServiceInstances", inService);
        add(datums, dimensions, minute, "GroupPendingInstances", pending);
        add(datums, dimensions, minute, "GroupStandbyInstances", standby);
        add(datums, dimensions, minute, "GroupTerminatingInstances", terminating);
        // The documented total is InService + Pending + Terminating; Standby has its own gauge.
        add(datums, dimensions, minute, "GroupTotalInstances", inService + pending + terminating);
        return datums;
    }

    private static void add(Map<String, MetricDatum> datums, List<Dimension> dimensions,
                            long minute, String name, int value) {
        datums.put(name, MetricDatum.of(name, "None", value, dimensions, minute));
    }

    @Override
    public void clear() {
        work.samples.clear();
    }

    private record GroupKey(String account, String region, String incarnation) {}

    private static final class Work {
        private final Map<GroupKey, Sample> samples = new HashMap<>();
    }

    private static final class Sample {
        private final long minute;
        private final Map<String, MetricDatum> datums;
        private final Set<String> delivered = new HashSet<>();

        private Sample(long minute, Map<String, MetricDatum> datums) {
            this.minute = minute;
            this.datums = datums;
        }
    }
}

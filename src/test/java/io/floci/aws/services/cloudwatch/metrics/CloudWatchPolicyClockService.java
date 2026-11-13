package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.core.storage.WriteProfile;
import io.floci.aws.services.cloudwatch.metrics.model.Alarm;
import io.floci.aws.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import io.floci.aws.testing.PolicyWorkloadTimeline;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;

/** Same owned metric, alarm and history stores; only the existing constructor clock differs. */
@Alternative
@Singleton
public class CloudWatchPolicyClockService extends CloudWatchMetricsService {
    @Inject
    public CloudWatchPolicyClockService(StorageFactory factory, RegionResolver regions,
                                       AlarmSnsNotifier notifier, PolicyWorkloadTimeline timeline) {
        super(factory.create("cloudwatchmetrics", "cwmetrics.json",
                        new TypeReference<Map<String, MetricDatum>>() {}, WriteProfile.APPEND_HEAVY),
                factory.create("cloudwatchmetrics", "cwalarms.json",
                        new TypeReference<Map<String, Alarm>>() {}),
                factory.create("cloudwatchmetrics", "cwalarmhistory.json",
                        new TypeReference<Map<String, AlarmHistoryItem>>() {}, WriteProfile.APPEND_HEAVY),
                regions, notifier, timeline.clock());
    }
}

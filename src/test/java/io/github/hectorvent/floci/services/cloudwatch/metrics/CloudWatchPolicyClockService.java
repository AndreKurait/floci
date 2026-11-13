package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.testing.PolicyWorkloadTimeline;
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

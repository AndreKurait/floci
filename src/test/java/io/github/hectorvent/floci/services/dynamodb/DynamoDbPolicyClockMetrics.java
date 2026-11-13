package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.testing.PolicyWorkloadTimeline;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** The real publisher records real service outcomes and registers its actual scheduled callback. */
@Alternative
@Singleton
public class DynamoDbPolicyClockMetrics extends DynamoDbCapacityMetrics {
    @Inject
    public DynamoDbPolicyClockMetrics(DynamoDbService dynamo, CloudWatchMetricsService metrics,
                                     EmulatorConfig config, PolicyWorkloadTimeline timeline) {
        super(dynamo, metrics, timeline.clock(), timeline.scheduler("publisher"),
                config.services().dynamodb().enabled() && config.services().cloudwatchmetrics().enabled()
                        && "native".equalsIgnoreCase(config.services().dynamodb().backend().trim()));
    }
}

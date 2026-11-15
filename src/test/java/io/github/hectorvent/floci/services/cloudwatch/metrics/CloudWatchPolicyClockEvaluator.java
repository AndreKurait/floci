package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.PolicyWorkloadTimeline;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Inherits owner traversal, evaluation, dispatch and lifecycle without overriding any method. */
@Alternative
@Singleton
public class CloudWatchPolicyClockEvaluator extends AlarmEvaluator {
    @Inject
    public CloudWatchPolicyClockEvaluator(CloudWatchMetricsService metrics,
                                         @Any Instance<AlarmActionHandler> handlers,
                                         PolicyWorkloadTimeline timeline) {
        super(metrics, handlers, timeline.clock(), timeline.scheduler("evaluator"));
    }
}

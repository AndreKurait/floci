package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.SweepScheduler;
import io.github.hectorvent.floci.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Refreshes managed thresholds after external provisioned-capacity changes. */
@ApplicationScoped
public class DynamoDbPolicyReconciler extends SweepScheduler {
    @Inject
    public DynamoDbPolicyReconciler(ApplicationAutoScalingService service, EmulatorConfig config) {
        super(new SweepWorker("dynamodb-policy-reconciliation",
                config.services().applicationautoscaling().enabled() && config.services().dynamodb().enabled(),
                10, false, service::reconcileDynamoDbPolicies));
    }
}

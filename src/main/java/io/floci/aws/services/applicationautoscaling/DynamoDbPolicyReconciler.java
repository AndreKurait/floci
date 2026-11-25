package io.floci.aws.services.applicationautoscaling;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
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

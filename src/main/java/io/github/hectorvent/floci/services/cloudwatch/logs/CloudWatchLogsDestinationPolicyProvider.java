package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** Adds the recipient's policy to the ordinary cross-account IAM request decision. */
@ApplicationScoped
public class CloudWatchLogsDestinationPolicyProvider implements ResourcePolicyProvider {
    private final CloudWatchLogsCrossAccountService destinations;

    @Inject
    public CloudWatchLogsDestinationPolicyProvider(CloudWatchLogsCrossAccountService destinations) {
        this.destinations = destinations;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!CloudWatchLogsCrossAccountService.isCrossAccountDestinationArn(resourceArn)) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        String policy = null;
        try {
            policy = destinations.resolveDestination(resourceArn, arn.region()).getAccessPolicy();
        } catch (AwsException failure) {
            if (!"ResourceNotFoundException".equals(failure.getErrorCode())) {
                throw failure;
            }
        }
        return List.of(new ResourcePolicy(policy, arn.accountId()));
    }
}

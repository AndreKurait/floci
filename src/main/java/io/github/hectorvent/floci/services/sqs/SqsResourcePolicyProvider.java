package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** Supplies the destination queue's policy and owner to IAM and service-principal authorization. */
@ApplicationScoped
public class SqsResourcePolicyProvider implements ResourcePolicyProvider {
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9]{12}");
    private static final Pattern QUEUE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,80}(\\.fifo)?");

    private final SqsService sqsService;

    public SqsResourcePolicyProvider(SqsService sqsService) {
        this.sqsService = sqsService;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!AwsArnUtils.isArnFor(resourceArn, "sqs")) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        if (arn.region().isBlank() || !ACCOUNT_ID.matcher(arn.accountId()).matches()
                || !QUEUE_NAME.matcher(arn.resource()).matches()) {
            return List.of();
        }
        return List.of(new ResourcePolicy(sqsService.findQueuePolicy(resourceArn).orElse(null), arn.accountId()));
    }
}

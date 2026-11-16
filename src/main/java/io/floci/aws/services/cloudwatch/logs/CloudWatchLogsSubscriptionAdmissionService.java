package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceCallAuthorizer;
import io.floci.aws.services.cloudwatch.logs.model.LogDestination;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.lambda.LambdaService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Shared destination admission for the native API and CloudFormation-owned filters. */
@ApplicationScoped
public class CloudWatchLogsSubscriptionAdmissionService {
    private final CloudWatchLogsCrossAccountService destinations;
    private final CloudWatchLogsDestinationAccess access;
    private final CloudWatchLogsDestinationTargetService targets;
    private final LambdaService lambda;
    private final ServiceCallAuthorizer serviceCalls;
    private final RegionResolver regions;

    @Inject
    public CloudWatchLogsSubscriptionAdmissionService(CloudWatchLogsCrossAccountService destinations,
                                                      CloudWatchLogsDestinationAccess access,
                                                      CloudWatchLogsDestinationTargetService targets,
                                                      LambdaService lambda, ServiceCallAuthorizer serviceCalls,
                                                      RegionResolver regions) {
        this.destinations = destinations;
        this.access = access;
        this.targets = targets;
        this.lambda = lambda;
        this.serviceCalls = serviceCalls;
        this.regions = regions;
    }

    public void validate(String destinationArn, String logGroup, String region, String account) {
        AwsArnUtils.Arn arn = parse(destinationArn);
        if (!region.equals(arn.region()) || !regions.partitionForRegion(region).equals(arn.partition())
                || !arn.accountId().matches("[0-9]{12}")) {
            throw invalid("The subscription destination must be in the log group's Region and partition.");
        }
        String sourceArn = sourceArn(logGroup, region, account);
        if (CloudWatchLogsCrossAccountService.isCrossAccountDestinationArn(destinationArn)) {
            LogDestination destination = destinations.resolveDestination(destinationArn, region);
            access.requireAccountAccess(destination, account, region);
            targets.validate(destination, account, sourceArn);
            return;
        }
        if (!"lambda".equals(arn.service()) || !account.equals(arn.accountId())
                || !arn.resource().matches("function:[A-Za-z0-9_-]{1,64}(?::[A-Za-z0-9_$-]+)?")) {
            throw invalid("This admission path supports same-account Lambda functions and logical Logs destinations.");
        }
        if (!lambda.functionExists(region, destinationArn)) {
            throw invalid("The Lambda destination does not exist.");
        }
        if (serviceCalls.authorize("logs", "lambda:InvokeFunction", destinationArn, sourceArn, account)
                != Decision.ALLOW) {
            throw invalid("CloudWatch Logs is not authorized to invoke the Lambda destination.");
        }
    }

    public void deliverLogical(String destinationArn, String logGroup, String region, String account,
                               byte[] payload, String partitionKey) {
        LogDestination destination = destinations.resolveDestination(destinationArn, region);
        access.requireAccountAccess(destination, account, region);
        targets.deliver(destination, account, sourceArn(logGroup, region, account), payload, partitionKey);
    }

    private String sourceArn(String group, String region, String account) {
        return regions.buildArn("logs", region, account, "log-group:" + group + ":*");
    }

    private static AwsArnUtils.Arn parse(String value) {
        try {
            return AwsArnUtils.parse(value);
        } catch (IllegalArgumentException failure) {
            throw invalid("destinationArn must be a valid ARN.");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}

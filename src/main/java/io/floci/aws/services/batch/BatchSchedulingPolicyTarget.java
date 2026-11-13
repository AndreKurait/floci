package io.floci.aws.services.batch;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.Set;
import java.util.regex.Pattern;

/** Shared exact identity for scheduling-policy storage and request authorization. */
public final class BatchSchedulingPolicyTarget {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final String PREFIX = "scheduling-policy/";
    private static final Set<String> OPERATIONS = Set.of(
            "batch:CreateSchedulingPolicy", "batch:DeleteSchedulingPolicy",
            "batch:DescribeSchedulingPolicies", "batch:ListSchedulingPolicies",
            "batch:UpdateSchedulingPolicy");

    private BatchSchedulingPolicyTarget() {}

    public static boolean operation(String operation) {
        return OPERATIONS.contains(operation == null ? "" : operation);
    }

    public static String name(String value) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw invalid("name must contain 1-128 ASCII letters, digits, hyphens or underscores.");
        }
        return value;
    }

    public static String arn(String partition, String region, String account, String name) {
        return new AwsArnUtils.Arn(partition, "batch", region, account, PREFIX + name(name)).toString();
    }

    /** Includes malformed policy selectors so they cannot fall through to an older Batch resource. */
    public static boolean isPolicySelector(String value) {
        if (!AwsArnUtils.isArn(value)) {
            return false;
        }
        return AwsArnUtils.parse(value).resource().startsWith("scheduling-policy");
    }

    /** The same decoded path parameter injected into the matched shared tag controller. */
    public static String taggedArn(ContainerRequestContext request) {
        return request == null || request.getUriInfo() == null || request.getUriInfo().getPathParameters() == null
                ? null : request.getUriInfo().getPathParameters().getFirst("arn");
    }

    public static String selectedName(String value, String partition, String region, String account) {
        try {
            AwsArnUtils.Arn selected = AwsArnUtils.parse(value);
            if (!"batch".equals(selected.service()) || !partition.equals(selected.partition())
                    || !region.equals(selected.region()) || !account.equals(selected.accountId())
                    || !selected.resource().startsWith(PREFIX)) {
                throw invalid("arn must identify a scheduling policy in the request account and Region.");
            }
            String name = name(selected.resource().substring(PREFIX.length()));
            if (!arn(partition, region, account, name).equals(value)) {
                throw invalid("arn must be an exact scheduling policy ARN.");
            }
            return name;
        } catch (IllegalArgumentException failure) {
            throw invalid("arn must be an exact scheduling policy ARN.");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("ClientException", message, 400);
    }
}

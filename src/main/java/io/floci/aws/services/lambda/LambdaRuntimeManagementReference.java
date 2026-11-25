package io.floci.aws.services.lambda;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;

import java.util.regex.Pattern;

/** The runtime-management API's NamespacedFunctionName and qualifier shapes. */
public record LambdaRuntimeManagementReference(String name, String qualifier, String functionArn) {

    private static final Pattern QUALIFIER = Pattern.compile("\\$(LATEST(\\.PUBLISHED)?)|[a-zA-Z0-9-_$]+");

    public static LambdaRuntimeManagementReference resolve(
            String reference, String qualifier, String region, String account) {
        if (AwsArnUtils.looksLikeArn(reference) && !AwsArnUtils.isArn(reference)) {
            throw invalid("Invalid ARN: " + reference);
        }
        LambdaArnUtils.ResolvedFunctionRef parsed = LambdaArnUtils.resolveNamespacedFunctionName(reference);
        if (parsed.name().length() > 64 && parsed.account() == null && parsed.region() == null
                && !AwsArnUtils.looksLikeArn(reference)) {
            throw invalid("FunctionName must have length less than or equal to 64");
        }
        if (qualifier != null && (qualifier.isEmpty() || qualifier.length() > 128
                || !QUALIFIER.matcher(qualifier).matches())) {
            throw invalid("Invalid qualifier: " + qualifier);
        }
        if (parsed.qualifier() != null && qualifier != null && !parsed.qualifier().equals(qualifier)) {
            throw invalid("The derived qualifier from the function name does not match the specified qualifier.");
        }
        if ((parsed.account() != null && !account.equals(parsed.account()))
                || (parsed.region() != null && !region.equals(parsed.region()))
                || (AwsArnUtils.looksLikeArn(reference)
                && !AwsRegions.partitionFor(region).equals(AwsArnUtils.parse(reference).partition()))) {
            throw missing(reference);
        }
        String selected = parsed.qualifier() != null ? parsed.qualifier() : qualifier;
        return new LambdaRuntimeManagementReference(parsed.name(), selected,
                AwsArnUtils.Arn.of("lambda", region, account, "function:" + parsed.name()).toString());
    }

    /** Qualified policies match the selector the request names, including an alias. */
    public String authorizationArn() {
        return qualifier == null ? functionArn : functionArn + ":" + qualifier;
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    static AwsException missing(String reference) {
        return new AwsException("ResourceNotFoundException", "Function not found: " + reference, 404);
    }
}

package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;

/** One canonical group identity for transformer execution and authorization. */
public record LogTransformerTarget(String name, String arn) {
    public static boolean supports(String action) {
        return "logs:PutTransformer".equals(action) || "logs:GetTransformer".equals(action)
                || "logs:DeleteTransformer".equals(action);
    }

    public static LogTransformerTarget resolve(String identifier, String region, String account, String partition) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 2048) {
            throw ParseJsonTransformer.invalid("logGroupIdentifier must be a log group name or ARN.");
        }
        String name = identifier;
        if (AwsArnUtils.looksLikeArn(identifier)) {
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(identifier);
            } catch (IllegalArgumentException ignored) {
                // A malformed ARN must not be treated as a literal log group name.
                throw ParseJsonTransformer.invalid("Invalid log group ARN.");
            }
            if (!parsed.resource().startsWith("log-group:")) {
                throw ParseJsonTransformer.invalid("Invalid log group ARN.");
            }
            name = parsed.resource().substring("log-group:".length());
            String expected = new AwsArnUtils.Arn(partition, "logs", region, account, "log-group:" + name).toString();
            if (!identifier.equals(expected)) {
                throw new AwsException("ResourceNotFoundException", "The specified log group does not exist.", 400);
            }
        }
        if (name.length() > 512 || !name.matches("[._/#A-Za-z0-9-]+")) {
            throw ParseJsonTransformer.invalid("Invalid log group name.");
        }
        return new LogTransformerTarget(name,
                new AwsArnUtils.Arn(partition, "logs", region, account, "log-group:" + name).toString());
    }
}

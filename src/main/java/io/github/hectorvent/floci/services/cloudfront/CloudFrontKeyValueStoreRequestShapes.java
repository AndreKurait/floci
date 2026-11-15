package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestConstraintCheck;

import java.util.List;

/**
 * Checks a {@code cloudfront-keyvaluestore} request against the constraints vendored in
 * {@code aws/cloudfront-keyvaluestore-request-shapes.json} by
 * {@code tools/aws/regen_cloudfront_keyvaluestore_shapes.py}. The request is the members the path, the
 * {@code If-Match} header, the query and the body carry, under the names the model gives them. A violation is a
 * {@code ValidationException}.
 */
final class CloudFrontKeyValueStoreRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/cloudfront-keyvaluestore-request-shapes.json",
            "CloudFront KeyValueStore request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return invalid(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String measure = "integer".equals(type) || "long".equals(type) ? "value" : "length";
                    return min != null && size < min
                            ? invalid(path, "Member must have " + measure + " greater than or equal to " + min)
                            : invalid(path, "Member must have " + measure + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return invalid(path, "must satisfy regular expression '" + pattern + "'");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return invalid(path, "Member must satisfy enum value set: " + values);
                }

                @Override
                public AwsException required(String path) {
                    return invalid(path, "Member must not be null");
                }
            });

    private CloudFrontKeyValueStoreRequestShapes() {
    }

    private static AwsException invalid(String path, String rule) {
        int dot = path.indexOf('.');
        String member = (dot < 0 ? "" : path.substring(dot + 1)).replace("[]", "");
        return new AwsException("ValidationException",
                "Invalid parameter at '" + member + "' failed to satisfy constraint: '" + rule + "'", 400);
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }
}

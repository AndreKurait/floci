package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestConstraintCheck;

import java.util.List;

/** The model's constraints apply before a patch-baseline operation changes stored state. */
final class SsmPatchRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/ssm-patch-request-shapes.json", "SSM patch-baseline request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return new AwsException("SerializationException", path + " must be a " + type + ".", 400);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return invalid(path + " is outside the modeled bounds.");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return invalid(path + " does not match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return invalid(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return invalid(path + " is required.");
                }
            });

    private SsmPatchRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }

    static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}

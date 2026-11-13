package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestConstraintCheck;

import java.util.List;

final class SsmResourceDataSyncRequests {
    private static final RequestConstraintCheck MODEL = RequestConstraintCheck.load(
            "aws/ssm-resource-data-sync-request-shapes.json", "SSM ResourceDataSync request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return new AwsException("SerializationException", path + " must be a " + type + ".", 400);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return invalid(path + " does not satisfy the " + type + " bounds " + min + ".." + max + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return invalid(path + " must satisfy " + pattern + ".");
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

    private SsmResourceDataSyncRequests() {
    }

    static void check(String operation, JsonNode request) {
        MODEL.check(operation, request);
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}

package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;

/** Numeric validation shared by the JSON and Query metric publication protocols. */
final class MetricNumberValidation {
    private MetricNumberValidation() {
    }

    static double parse(JsonNode value, String path) {
        require(value == null || value.isMissingNode() || value.isNull(), path);
        if (!value.isNumber()) {
            throw invalid(path);
        }
        return finite(value.doubleValue(), path);
    }

    static double parse(String value, String path) {
        require(value == null, path);
        try {
            return finite(Double.parseDouble(value), path);
        } catch (NumberFormatException ignored) {
            throw invalid(path);
        }
    }

    private static void require(boolean missing, String path) {
        if (missing) {
            throw CloudWatchMetricsException.missingParameter(path);
        }
    }

    private static double finite(double value, String path) {
        if (!Double.isFinite(value)) {
            throw invalid(path);
        }
        return value;
    }

    private static AwsException invalid(String path) {
        return CloudWatchMetricsException.invalidParameterValue(path + " must be a finite number.");
    }
}

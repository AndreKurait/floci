package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;

import java.math.BigInteger;
import java.util.Map;
import java.util.regex.Pattern;

/** Validation shared by the two native PATCH operations, before either replaces stored state. */
final class AppConfigUpdateRequest {
    private static final Pattern ID = Pattern.compile("[a-z0-9]{4,7}");
    private static final Pattern STRATEGY_ID = Pattern.compile("(?:[a-z0-9]{4,7}|AppConfig\\.[A-Za-z0-9]{9,40})");

    private AppConfigUpdateRequest() {
    }

    static Map<String, Object> read(ObjectMapper mapper, String body) {
        try {
            JsonNode document = mapper.readTree(body);
            if (document == null || !document.isObject()) {
                throw invalid("The request must be a JSON object");
            }
            return mapper.convertValue(document, new TypeReference<>() {});
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid("The request must be a JSON object");
        }
    }

    static void environment(String applicationId, String environmentId, Map<String, Object> request) {
        identifier("ApplicationId", applicationId, ID);
        identifier("EnvironmentId", environmentId, ID);
        requireRequest(request);
        text(request, "Name", 1, 64);
        text(request, "Description", 0, 1024);
    }

    static void strategy(String strategyId, Map<String, Object> request) {
        identifier("DeploymentStrategyId", strategyId, STRATEGY_ID);
        requireRequest(request);
        text(request, "Description", 0, 1024);
        minutes(request, "DeploymentDurationInMinutes");
        minutes(request, "FinalBakeTimeInMinutes");
        Object growth = request.get("GrowthFactor");
        if (growth != null && (!(growth instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 1 || number.doubleValue() > 100)) {
            throw invalid("GrowthFactor must be a number from 1 to 100");
        }
        Object type = request.get("GrowthType");
        if (type != null && !"LINEAR".equals(type) && !"EXPONENTIAL".equals(type)) {
            throw invalid("GrowthType must be LINEAR or EXPONENTIAL");
        }
    }

    private static void requireRequest(Map<String, Object> request) {
        if (request == null) {
            throw invalid("The request must be a JSON object");
        }
    }

    private static void identifier(String member, String value, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw invalid(member + " is not a valid identifier");
        }
    }

    private static void text(Map<String, Object> request, String member, int minimum, int maximum) {
        Object value = request.get(member);
        if (value != null && (!(value instanceof String text)
                || text.codePointCount(0, text.length()) < minimum
                || text.codePointCount(0, text.length()) > maximum)) {
            throw invalid(member + " must be a string of " + minimum + " to " + maximum + " characters");
        }
    }

    private static void minutes(Map<String, Object> request, String member) {
        Object value = request.get(member);
        if (value != null && (!(value instanceof Integer || value instanceof Long || value instanceof BigInteger)
                || ((Number) value).doubleValue() < 0 || ((Number) value).doubleValue() > 1440)) {
            throw invalid(member + " must be an integer from 0 to 1440");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}

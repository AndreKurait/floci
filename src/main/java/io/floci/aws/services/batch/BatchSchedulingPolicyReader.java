package io.floci.aws.services.batch;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.batch.model.StoredSchedulingPolicy.Fairshare;
import io.floci.aws.services.batch.model.StoredSchedulingPolicy.Quota;
import io.floci.aws.services.batch.model.StoredSchedulingPolicy.Share;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict parsing opt-in for the new policy APIs; existing Batch readers are unchanged. */
@ApplicationScoped
public class BatchSchedulingPolicyReader {
    private static final Pattern SHARE = Pattern.compile("[A-Za-z0-9]{1,255}\\*?");
    private final ObjectReader reader;

    @Inject
    public BatchSchedulingPolicyReader(ObjectMapper mapper) {
        reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public JsonNode object(String body) {
        if (body == null || body.isBlank()) {
            throw invalid("A JSON object request body is required.");
        }
        try {
            JsonNode value = reader.readValue(body);
            if (value == null || !value.isObject()) {
                throw invalid("The request body must be a JSON object.");
            }
            return value;
        } catch (JsonProcessingException failure) {
            throw invalid("Malformed JSON request body.");
        }
    }

    public static String text(JsonNode request, String member, boolean required) {
        JsonNode value = request.get(member);
        if (value == null || value.isNull()) {
            if (required) {
                throw invalid(member + " is required.");
            }
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(member + " must be a string.");
        }
        return value.textValue();
    }

    public static int integer(JsonNode request, String member, int fallback, int minimum, int maximum) {
        JsonNode value = request.get(member);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw invalid(member + " must be an integer between " + minimum + " and " + maximum + ".");
        }
        return value.intValue();
    }

    public static Fairshare fairshare(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw invalid("fairsharePolicy must be an object.");
        }
        int decay = integer(value, "shareDecaySeconds", 0, 0, 604800);
        int reservation = integer(value, "computeReservation", 0, 0, 99);
        List<Share> shares = new ArrayList<>();
        JsonNode distribution = value.get("shareDistribution");
        if (distribution != null && !distribution.isNull()) {
            if (!distribution.isArray() || distribution.size() > 500) {
                throw invalid("shareDistribution must be an array with at most 500 entries.");
            }
            for (JsonNode entry : distribution) {
                if (!entry.isObject()) {
                    throw invalid("Each shareDistribution entry must be an object.");
                }
                String identifier = text(entry, "shareIdentifier", true);
                if (!SHARE.matcher(identifier).matches() || identifier.length() > 255) {
                    throw invalid("shareIdentifier must be 1-255 ASCII alphanumerics with an optional terminal *.");
                }
                for (Share previous : shares) {
                    if (overlap(previous.shareIdentifier(), identifier)) {
                        throw invalid("shareIdentifiers must be unique and their prefixes must not overlap.");
                    }
                }
                float weight = 1.0F;
                JsonNode selected = entry.get("weightFactor");
                if (selected != null && !selected.isNull()) {
                    if (!selected.isNumber()) {
                        throw invalid("weightFactor must be a number.");
                    }
                    weight = selected.floatValue();
                    if (!Float.isFinite(weight) || weight < 0.0001F || weight > 999.9999F) {
                        throw invalid("weightFactor must be between 0.0001 and 999.9999.");
                    }
                }
                shares.add(new Share(identifier, weight));
            }
        }
        return new Fairshare(decay, reservation, shares);
    }

    private static boolean overlap(String first, String second) {
        return first.equals(second)
                || first.endsWith("*") && second.startsWith(first.substring(0, first.length() - 1))
                || second.endsWith("*") && first.startsWith(second.substring(0, second.length() - 1));
    }

    public static Quota quota(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject() || !"FIFO".equals(text(value, "idleResourceAssignmentStrategy", true))) {
            throw invalid("quotaSharePolicy requires idleResourceAssignmentStrategy FIFO.");
        }
        return new Quota("FIFO");
    }

    public static Map<String, String> tags(JsonNode request, boolean required) {
        JsonNode value = request.get("tags");
        if (value == null || value.isNull()) {
            if (required) {
                throw invalid("tags must be a string map.");
            }
            return Map.of();
        }
        if (!value.isObject()) {
            throw invalid("tags must be a string map.");
        }
        Map<String, String> result = new LinkedHashMap<>();
        value.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) {
                throw invalid("Tag values must be strings.");
            }
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags(result);
    }

    public static Map<String, String> tags(Map<String, String> tags) {
        if (tags == null || tags.size() > 50) {
            throw invalid("A policy supports at most 50 tags.");
        }
        tags.forEach((key, value) -> {
            tagKey(key);
            if (value == null || value.codePointCount(0, value.length()) > 256
                    || value.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw invalid("Tag values must contain 0-256 characters and cannot start with aws:.");
            }
        });
        return Map.copyOf(tags);
    }

    public static List<String> tagKeys(List<String> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > 50) {
            throw invalid("tagKeys must contain 1-50 keys.");
        }
        keys.forEach(BatchSchedulingPolicyReader::tagKey);
        return List.copyOf(keys);
    }

    private static void tagKey(String key) {
        if (key == null || key.isEmpty() || key.codePointCount(0, key.length()) > 128
                || key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
            throw invalid("Tag keys must contain 1-128 characters and cannot start with aws:.");
        }
    }

    public static AwsException invalid(String message) {
        return new AwsException("ClientException", message, 400);
    }
}

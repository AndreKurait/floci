package io.github.hectorvent.floci.services.cloudwatch.insights;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.core.common.AwsException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The supported public rule grammar: JSON, Count, local log-group names and StartsWith filters.
 * Other AWS grammars remain explicitly unsupported instead of producing empty reports.
 */
public record InsightRuleDefinition(List<String> groups, List<String> keys, List<Filter> filters) {
    public static final String SCHEMA = "CloudWatchLogRule/1";
    private static final ObjectReader JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readerFor(JsonNode.class);
    private static final Pattern PATH = Pattern.compile(
            "\\$(?:\\.[A-Za-z][A-Za-z0-9]*(?:\\[[0-9]+\\])*)+");
    private static final Pattern COMPONENT = Pattern.compile("\\.([A-Za-z][A-Za-z0-9]*)|\\[([0-9]+)\\]");

    public record Filter(String path, List<String> prefixes) {}

    public InsightRuleDefinition {
        groups = List.copyOf(groups);
        keys = List.copyOf(keys);
        filters = List.copyOf(filters);
    }

    public static InsightRuleDefinition parse(String definition) {
        if (definition == null) {
            throw new AwsException("MissingParameter", "RuleDefinition is required.", 400);
        }
        if (definition.isEmpty() || definition.length() > 8192
                || definition.chars().anyMatch(character -> character > 127)) {
            throw invalid("RuleDefinition must contain 1 to 8192 ASCII characters.");
        }
        JsonNode root;
        try {
            root = JSON.readValue(definition);
        } catch (JsonProcessingException error) {
            throw invalid("RuleDefinition must be a JSON object.");
        }
        if (root == null || !root.isObject()) {
            throw invalid("RuleDefinition must be a JSON object.");
        }
        JsonNode schema = root.path("Schema");
        if (!schema.isObject() || schema.size() != 2
                || !"CloudWatchLogRule".equals(schema.path("Name").asText())
                || !schema.path("Version").isIntegralNumber() || !schema.path("Version").canConvertToInt()
                || schema.path("Version").intValue() != 1) {
            throw invalid("Schema must name CloudWatchLogRule version 1.");
        }
        fields(root, Set.of("Schema", "LogGroupNames", "LogFormat", "Contribution", "AggregateOn"));
        if (!"JSON".equals(root.path("LogFormat").asText())
                || !"Count".equals(root.path("AggregateOn").asText())) {
            throw unsupported("Only JSON Count rules are supported.");
        }
        List<String> groups = strings(root.path("LogGroupNames"), "LogGroupNames", 1, Integer.MAX_VALUE);
        for (String group : groups) {
            int wildcard = group.indexOf('*');
            if (group.isEmpty() || (wildcard >= 0 && wildcard != group.length() - 1)
                    || group.indexOf('*', wildcard + 1) >= 0) {
                throw invalid("LogGroupNames supports exact names or one trailing wildcard.");
            }
        }
        JsonNode contribution = root.path("Contribution");
        if (!contribution.isObject()) {
            throw invalid("Contribution must be an object.");
        }
        fields(contribution, Set.of("Keys", "Filters"));
        List<String> keys = strings(contribution.path("Keys"), "Keys", 1, 4);
        keys.forEach(InsightRuleDefinition::path);
        List<Filter> filters = new ArrayList<>();
        JsonNode filterNodes = contribution.get("Filters");
        if (filterNodes != null) {
            if (!filterNodes.isArray() || filterNodes.size() > 4) {
                throw invalid("Filters must be an array with at most four entries.");
            }
            for (JsonNode filter : filterNodes) {
                if (!filter.isObject()) {
                    throw invalid("Each filter must be an object.");
                }
                fields(filter, Set.of("Match", "StartsWith"));
                String match = filter.path("Match").isTextual() ? filter.path("Match").textValue() : null;
                path(match);
                filters.add(new Filter(match, strings(filter.path("StartsWith"), "StartsWith", 1, 10)));
            }
        }
        return new InsightRuleDefinition(groups, keys, filters);
    }

    public List<String> contribution(String group, String message) {
        if (message == null || groups.stream().noneMatch(pattern -> pattern.endsWith("*")
                ? group.startsWith(pattern.substring(0, pattern.length() - 1)) : group.equals(pattern))) {
            return List.of();
        }
        JsonNode event;
        try {
            event = JSON.readValue(message);
        } catch (JsonProcessingException ignored) {
            // A log message outside the supported JSON grammar does not contribute.
            return List.of();
        }
        if (event == null || !event.isObject()) {
            return List.of();
        }
        for (Filter filter : filters) {
            String value = value(event, filter.path());
            if (value == null || filter.prefixes().stream().noneMatch(value::startsWith)) {
                return List.of();
            }
        }
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            String value = value(event, key);
            if (value == null) {
                return List.of();
            }
            values.add(truncatedKey(value));
        }
        return List.copyOf(values);
    }

    private static String value(JsonNode event, String path) {
        JsonNode value = event;
        Matcher components = COMPONENT.matcher(path);
        while (components.find()) {
            if (value == null) {
                return null;
            }
            if (components.group(1) != null) {
                value = value.isObject() ? value.get(components.group(1)) : null;
            } else {
                try {
                    value = value.isArray() ? value.get(Integer.parseInt(components.group(2))) : null;
                } catch (NumberFormatException ignored) {
                    // An index beyond Java's range cannot name an element of a stored JSON array.
                    return null;
                }
            }
        }
        if (value != null && value.isNumber()
                && value.decimalValue().abs().compareTo(BigDecimal.valueOf(1_000_000_000L)) > 0) {
            return null;
        }
        return value != null && (value.isTextual() || value.isNumber()) ? value.asText() : null;
    }

    private static String truncatedKey(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 1024) {
            return value;
        }
        int length = 1024;
        while ((bytes[length] & 0xc0) == 0x80) {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private static void fields(JsonNode value, Set<String> supported) {
        value.fieldNames().forEachRemaining(field -> {
            if (!supported.contains(field)) {
                throw unsupported("Rule field " + field + " is not supported.");
            }
        });
    }

    private static List<String> strings(JsonNode node, String field, int minimum, int maximum) {
        if (!node.isArray() || node.size() < minimum || node.size() > maximum) {
            throw invalid(field + " must be an array with " + minimum + " to " + maximum + " entries.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw invalid(field + " entries must be strings.");
            }
            values.add(item.textValue());
        }
        return List.copyOf(values);
    }

    private static void path(String value) {
        if (value == null || !PATH.matcher(value).matches()) {
            throw unsupported("Only JSON dot paths with optional array indices are supported.");
        }
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValue", message, 400);
    }

    static AwsException unsupported(String message) {
        return new AwsException("UnsupportedOperation", message, 400);
    }
}

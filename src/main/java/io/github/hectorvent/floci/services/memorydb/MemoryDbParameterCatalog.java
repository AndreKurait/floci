package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The versioned standalone metadata catalog; values do not configure a running engine. */
@ApplicationScoped
public class MemoryDbParameterCatalog {
    private final String version;
    private final Set<String> families;
    private final Map<String, Definition> definitions;
    private final Map<String, DefaultGroup> defaults;

    @Inject
    public MemoryDbParameterCatalog(ObjectMapper mapper) {
        try (InputStream input = MemoryDbParameterCatalog.class.getResourceAsStream("/memorydb/parameter-catalog-v1.json")) {
            if (input == null) {
                throw new IllegalStateException("MemoryDB parameter catalog is missing");
            }
            JsonNode source = mapper.readTree(input);
            version = source.required("version").asText();
            families = Set.copyOf(strings(source.required("families")));
            Map<String, Definition> parameters = new TreeMap<>();
            for (JsonNode parameter : source.required("parameters")) {
                Definition definition = definition(parameter);
                if (parameters.put(definition.name(), definition) != null) {
                    throw new IllegalStateException("Duplicate MemoryDB parameter");
                }
                definition.validate(definition.defaultValue());
            }
            definitions = Collections.unmodifiableMap(parameters);
            Map<String, DefaultGroup> groups = new TreeMap<>();
            for (JsonNode group : source.required("defaultGroups")) {
                DefaultGroup definition = new DefaultGroup(group.required("name").asText(),
                        group.required("family").asText(), stringMap(group.required("overrides")));
                if (!families.contains(definition.family()) || groups.put(definition.name(), definition) != null) {
                    throw new IllegalStateException("Invalid MemoryDB default group");
                }
                definition.overrides().forEach((name, value) -> parameter(definition.family(), name).validate(value));
            }
            defaults = Collections.unmodifiableMap(groups);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read MemoryDB parameter catalog", failure);
        }
    }

    public String version() {
        return version;
    }

    public boolean hasFamily(String family) {
        return families.contains(family);
    }

    public Map<String, DefaultGroup> defaults() {
        return defaults;
    }

    public Definition parameter(String family, String name) {
        Definition result = definitions.get(name);
        if (result == null || !result.minimumEngineVersions().containsKey(family)) {
            throw invalid("Unknown parameter " + name + " for family " + family + ".");
        }
        return result;
    }

    public List<Map<String, String>> parameters(String family, Map<String, String> overrides) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Definition definition : definitions.values()) {
            if (!definition.minimumEngineVersions().containsKey(family)) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Name", definition.name());
            row.put("Value", overrides.getOrDefault(definition.name(), definition.defaultValue()));
            row.put("Description", definition.description());
            row.put("DataType", definition.domain().kind().equals("integer") ? "integer" : "string");
            String allowed = definition.domain().allowedValues();
            if (allowed != null) {
                row.put("AllowedValues", allowed);
            }
            row.put("MinimumEngineVersion", definition.minimumEngineVersions().get(family));
            result.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(result);
    }

    private static Definition definition(JsonNode source) {
        JsonNode domain = source.required("domain");
        List<Range> ranges = new ArrayList<>();
        for (JsonNode range : domain.path("ranges")) {
            ranges.add(new Range(range.get(0).isNull() ? null : new BigInteger(range.get(0).asText()),
                    range.get(1).isNull() ? null : new BigInteger(range.get(1).asText())));
        }
        return new Definition(source.required("name").asText(), source.required("defaultValue").asText(),
                source.required("description").asText(), source.required("modifiable").booleanValue(),
                stringMap(source.required("minimumEngineVersions")),
                new Domain(domain.required("kind").asText(), List.copyOf(ranges), strings(domain.path("values"))));
    }

    private static List<String> strings(JsonNode source) {
        List<String> values = new ArrayList<>();
        source.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static Map<String, String> stringMap(JsonNode source) {
        Map<String, String> result = new TreeMap<>();
        source.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(result);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    public record DefaultGroup(String name, String family, Map<String, String> overrides) {}

    public record Definition(String name, String defaultValue, String description, boolean modifiable,
                             Map<String, String> minimumEngineVersions, Domain domain) {
        public void validate(String value) {
            if (!domain.accepts(value)) {
                throw invalid("Invalid value for parameter " + name + ".");
            }
        }
    }

    public record Range(BigInteger lower, BigInteger upper) {
        boolean includes(BigInteger number) {
            return (lower == null || number.compareTo(lower) >= 0)
                    && (upper == null || number.compareTo(upper) <= 0);
        }

        String render() {
            return lower != null && lower.equals(upper) ? lower.toString()
                    : (lower == null ? "" : lower.toString()) + "-" + (upper == null ? "" : upper.toString());
        }
    }

    public record Domain(String kind, List<Range> ranges, List<String> values) {
        boolean accepts(String value) {
            if (value == null) {
                return false;
            }
            return switch (kind) {
                case "integer" -> integer(value);
                case "tokens" -> values.contains(value);
                case "text" -> true;
                default -> throw new IllegalStateException("Unknown MemoryDB parameter domain");
            };
        }

        private boolean integer(String value) {
            try {
                BigInteger number = new BigInteger(value);
                return ranges.stream().anyMatch(range -> range.includes(number));
            } catch (NumberFormatException failure) {
                return false;
            }
        }

        String allowedValues() {
            return switch (kind) {
                case "integer" -> String.join(",", ranges.stream().map(Range::render).toList());
                case "tokens" -> String.join(",", values);
                case "text" -> null;
                default -> throw new IllegalStateException("Unknown MemoryDB parameter domain");
            };
        }
    }
}

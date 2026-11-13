package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudwatch.logs.model.LogTransformer;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** Data-only, bounded JSON parsing shared by preview and ingestion. */
final class ParseJsonTransformer {
    static final int MAX_EVENT_BYTES = 512 * 1024;
    private static final int MAX_EXTRACTED_FIELDS = 200;
    private static final Pattern DESTINATION = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*){0,2}");
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(64).maxStringLength(MAX_EVENT_BYTES).build())
            .build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ParseJsonTransformer() {
    }

    static LogTransformer definition(JsonNode configuration) {
        if (configuration == null || !configuration.isArray() || configuration.size() != 1) {
            throw invalid("Only a transformer containing exactly one parseJSON processor is supported.");
        }
        JsonNode processor = configuration.get(0);
        if (!processor.isObject() || processor.size() != 1 || !processor.has("parseJSON")
                || !processor.get("parseJSON").isObject()) {
            throw invalid("Only the parseJSON processor is supported.");
        }
        JsonNode parse = processor.get("parseJSON");
        parse.fieldNames().forEachRemaining(name -> {
            if (!Set.of("source", "destination").contains(name)) {
                throw invalid("Unsupported parseJSON member: " + name);
            }
        });
        String source = optionalString(parse, "source");
        if (source != null && !"@message".equals(source)) {
            throw invalid("The first parseJSON processor must use @message as its source.");
        }
        String destination = optionalString(parse, "destination");
        if (destination != null && (destination.length() > 128 || !DESTINATION.matcher(destination).matches())) {
            throw invalid("Supported destinations have at most three dotted identifier components and 128 characters.");
        }
        return new LogTransformer(source, destination, 0, 0);
    }

    static Outcome transform(String message, LogTransformer transformer) {
        if (message == null || message.getBytes(StandardCharsets.UTF_8).length >= MAX_EVENT_BYTES) {
            return new Outcome(null, "parseJSON input must be smaller than 512 KiB.");
        }
        try {
            JsonNode parsed = MAPPER.readTree(message);
            if (parsed == null || !parsed.isObject()) {
                return new Outcome(null, "parseJSON requires a JSON object.");
            }
            if (extractedFields(parsed, MAX_EXTRACTED_FIELDS) > MAX_EXTRACTED_FIELDS) {
                return new Outcome(null, "parseJSON supports at most 200 extracted JSON object members.");
            }
            JsonNode transformed = parsed;
            if (transformer.destination() != null) {
                ObjectNode root = MAPPER.createObjectNode();
                ObjectNode parent = root;
                String[] components = transformer.destination().split("\\.");
                for (int i = 0; i < components.length - 1; i++) {
                    parent = parent.putObject(components[i]);
                }
                parent.set(components[components.length - 1], parsed);
                transformed = root;
            }
            return new Outcome(MAPPER.writeValueAsString(transformed), null);
        } catch (JsonProcessingException ignored) {
            // Ingestion retains the original event and exposes this bounded error to queries.
            return new Outcome(null, "parseJSON could not parse a JSON object within the supported limits.");
        }
    }

    private static int extractedFields(JsonNode node, int remaining) {
        int count = node.isObject() ? node.size() : 0;
        if (count > remaining) {
            return count;
        }
        for (JsonNode child : node) {
            count += extractedFields(child, remaining - count);
            if (count > remaining) {
                return count;
            }
        }
        return count;
    }

    private static String optionalString(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw invalid(name + " must be a nonempty string when specified.");
        }
        return value.textValue();
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    record Outcome(String message, String error) {
    }
}

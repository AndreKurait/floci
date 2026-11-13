package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.VendoredResources;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The SDK's types and wire names for the admitted EC2 and SSM operations. */
final class SdkComputeModel {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final JsonNode SERVICES = VendoredResources.read("aws/stepfunctions-compute-shapes.json",
            "Step Functions compute SDK models", stream -> MAPPER.readTree(stream).path("services"));
    private final JsonNode service;
    private final JsonNode operation;

    SdkComputeModel(String name, String action) {
        service = SERVICES.path(name);
        operation = service.path("operations").path(action);
        if (operation.isMissingNode()) {
            throw new IllegalArgumentException("Unsupported compute SDK operation");
        }
    }

    static boolean supports(String service, String action) {
        return SERVICES.path(service).path("operations").has(action);
    }

    String target(String action) {
        return service.path("targetPrefix").asText() + "." + action;
    }

    String exceptionName(String prefix, String code) {
        return prefix + "." + service.path("exceptions").path(code).asText(prefix + "Exception");
    }

    byte[] query(String action, JsonNode input) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", action);
        parameters.put("Version", service.path("apiVersion").asText());
        flatten(input, shape(operation.path("input").asText()), "", parameters);
        String encoded = parameters.entrySet().stream()
                .map(entry -> SigV4Canonicalization.uriEncode(entry.getKey()) + "="
                        + SigV4Canonicalization.uriEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    JsonNode jsonRequest(JsonNode input) {
        return json(input, shape(operation.path("input").asText()), true);
    }

    JsonNode jsonResponse(JsonNode input) {
        return json(input, shape(operation.path("output").asText()), false);
    }

    JsonNode queryResponse(String xml) throws Exception {
        return xml(XmlParser.parseDocument(xml).getDocumentElement(), shape(operation.path("output").asText()));
    }

    private JsonNode shape(String name) {
        JsonNode shape = service.path("shapes").path(name);
        if (shape.isMissingNode()) {
            throw new IllegalArgumentException("Missing SDK shape " + name);
        }
        return shape;
    }

    private void flatten(JsonNode input, JsonNode schema, String prefix, Map<String, String> parameters) {
        String type = schema.path("type").asText();
        if ("structure".equals(type)) {
            require(input.isObject(), "Expected an object at " + prefix);
            for (JsonNode required : schema.path("required")) {
                require(input.hasNonNull(required.asText()), "Missing required member " + required.asText());
            }
            for (Map.Entry<String, JsonNode> entry : input.properties()) {
                JsonNode member = schema.path("members").path(entry.getKey());
                require(!member.isMissingNode(), "Unknown SDK member " + entry.getKey());
                String wire = member.path("queryName").asText(
                        AslExecutor.capitalizeFirst(member.path("locationName").asText(entry.getKey())));
                flatten(entry.getValue(), shape(member.path("shape").asText()),
                        prefix.isEmpty() ? wire : prefix + "." + wire, parameters);
            }
        } else if ("list".equals(type)) {
            require(input.isArray(), "Expected an array at " + prefix);
            for (int index = 0; index < input.size(); index++) {
                flatten(input.get(index), shape(schema.path("member").path("shape").asText()),
                        prefix + "." + (index + 1), parameters);
            }
        } else {
            validateScalar(input, type);
            parameters.put(prefix, input.asText());
        }
    }

    private JsonNode json(JsonNode input, JsonNode schema, boolean request) {
        String type = schema.path("type").asText();
        return switch (type) {
            case "structure" -> {
                require(input.isObject(), "Expected an SDK object");
                ObjectNode result = MAPPER.createObjectNode();
                if (request) {
                    for (JsonNode required : schema.path("required")) {
                        String name = AslExecutor.capitalizeFirst(required.asText());
                        require(input.hasNonNull(name), "Missing required member " + name);
                    }
                }
                for (Map.Entry<String, JsonNode> entry : input.properties()) {
                    boolean found = false;
                    for (Map.Entry<String, JsonNode> member : schema.path("members").properties()) {
                        String sdkName = AslExecutor.capitalizeFirst(member.getKey());
                        String wireName = member.getValue().path("locationName").asText(member.getKey());
                        if ((request ? sdkName : wireName).equals(entry.getKey())) {
                            result.set(request ? wireName : sdkName,
                                    json(entry.getValue(), shape(member.getValue().path("shape").asText()), request));
                            found = true;
                            break;
                        }
                    }
                    if (request) {
                        require(found, "Unknown SDK member " + entry.getKey());
                    }
                }
                yield result;
            }
            case "list" -> {
                require(input.isArray(), "Expected an SDK array");
                ArrayNode result = MAPPER.createArrayNode();
                for (JsonNode value : input) {
                    result.add(json(value, shape(schema.path("member").path("shape").asText()), request));
                }
                yield result;
            }
            case "map" -> {
                require(input.isObject(), "Expected an SDK dictionary");
                ObjectNode result = MAPPER.createObjectNode();
                for (Map.Entry<String, JsonNode> entry : input.properties()) {
                    result.set(entry.getKey(), json(entry.getValue(), shape(schema.path("value").path("shape").asText()),
                            request));
                }
                yield result;
            }
            case "timestamp" -> {
                if (request) {
                    Instant instant = Instant.parse(input.asText());
                    yield MAPPER.getNodeFactory().numberNode(BigDecimal.valueOf(instant.getEpochSecond())
                            .add(BigDecimal.valueOf(instant.getNano(), 9)));
                }
                BigDecimal seconds = input.decimalValue();
                long whole = seconds.longValue();
                yield MAPPER.getNodeFactory().textNode(TIMESTAMP.format(Instant.ofEpochSecond(whole,
                        seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValue())));
            }
            default -> {
                validateScalar(input, type);
                yield input.deepCopy();
            }
        };
    }

    private JsonNode xml(Element element, JsonNode schema) {
        String type = schema.path("type").asText();
        if ("structure".equals(type)) {
            ObjectNode result = MAPPER.createObjectNode();
            for (Map.Entry<String, JsonNode> member : schema.path("members").properties()) {
                String wire = member.getValue().path("locationName").asText(member.getKey());
                for (Element child : children(element, wire)) {
                    result.set(member.getKey(), xml(child, shape(member.getValue().path("shape").asText())));
                }
            }
            return result;
        }
        if ("list".equals(type)) {
            ArrayNode result = MAPPER.createArrayNode();
            JsonNode member = schema.path("member");
            for (Element child : children(element, member.path("locationName").asText("item"))) {
                result.add(xml(child, shape(member.path("shape").asText())));
            }
            return result;
        }
        String value = element.getTextContent();
        return switch (type) {
            case "integer", "long" -> MAPPER.getNodeFactory().numberNode(new BigInteger(value));
            case "float", "double" -> MAPPER.getNodeFactory().numberNode(new BigDecimal(value));
            case "boolean" -> {
                require(value.equals("true") || value.equals("false"), "Invalid SDK boolean response");
                yield MAPPER.getNodeFactory().booleanNode(Boolean.parseBoolean(value));
            }
            case "timestamp" -> MAPPER.getNodeFactory().textNode(TIMESTAMP.format(Instant.parse(value)));
            default -> MAPPER.getNodeFactory().textNode(value);
        };
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static void validateScalar(JsonNode input, String type) {
        boolean valid = switch (type) {
            case "boolean" -> input.isBoolean();
            case "integer", "long" -> input.isIntegralNumber();
            case "double", "float" -> input.isNumber();
            default -> input.isTextual();
        };
        require(valid, "Invalid SDK " + type + " value");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AwsException("InvalidParameterValue", message, 400);
        }
    }
}

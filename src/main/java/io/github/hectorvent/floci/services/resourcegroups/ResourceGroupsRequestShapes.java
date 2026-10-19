package io.github.hectorvent.floci.services.resourcegroups;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.VendoredResources;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class ResourceGroupsRequestShapes {
    private static final JsonNode MODEL = VendoredResources.read("aws/resource-groups-request-shapes.json",
            "Resource Groups request shapes", input -> new ObjectMapper().readTree(input));
    private static final Map<String, Pattern> PATTERNS = new HashMap<>();

    static {
        for (Map.Entry<String, JsonNode> shape : MODEL.path("shapes").properties()) {
            if (shape.getValue().has("pattern")) {
                PATTERNS.put(shape.getKey(), Pattern.compile(shape.getValue().get("pattern").asText()));
            }
        }
    }

    private ResourceGroupsRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        checkValue(MODEL.path("operations").path(operation).asText(), request, operation);
    }

    private static void checkValue(String name, JsonNode value, String path) {
        JsonNode shape = MODEL.path("shapes").path(name);
        String type = shape.path("type").asText();
        boolean validType = value != null && switch (type) {
            case "structure", "map" -> value.isObject();
            case "list" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer", "long" -> value.isIntegralNumber() && value.canConvertToLong();
            default -> false;
        };
        if (!validType) {
            throw bad(path + " must be a " + type + ".");
        }
        long size = switch (type) {
            case "string" -> value.textValue().codePointCount(0, value.textValue().length());
            case "integer", "long" -> value.longValue();
            default -> value.size();
        };
        if (shape.has("min") && size < shape.get("min").asLong()
                || shape.has("max") && size > shape.get("max").asLong()) {
            throw bad(path + " is outside the modeled bounds.");
        }
        if (PATTERNS.containsKey(name) && !PATTERNS.get(name).matcher(value.asText()).matches()) {
            throw bad(path + " does not match the modeled pattern.");
        }
        if (shape.has("enum")) {
            boolean known = false;
            for (JsonNode choice : shape.get("enum")) {
                known |= choice.equals(value);
            }
            if (!known) {
                throw bad(path + " is not a modeled value.");
            }
        }
        switch (type) {
            case "structure" -> {
                for (JsonNode required : shape.path("required")) {
                    if (!value.hasNonNull(required.asText())) {
                        throw bad(path + "." + required.asText() + " is required.");
                    }
                }
                for (Map.Entry<String, JsonNode> field : value.properties()) {
                    JsonNode member = shape.path("members").get(field.getKey());
                    if (member != null && !field.getValue().isNull()) {
                        checkValue(member.asText(), field.getValue(), path + "." + field.getKey());
                    }
                }
            }
            case "list" -> {
                for (JsonNode item : value) {
                    checkValue(shape.get("member").asText(), item, path + "[]");
                }
            }
            case "map" -> {
                for (Map.Entry<String, JsonNode> field : value.properties()) {
                    checkValue(shape.get("key").asText(), TextNode.valueOf(field.getKey()), path + ".key");
                    checkValue(shape.get("value").asText(), field.getValue(), path + "." + field.getKey());
                }
            }
            default -> {
            }
        }
    }

    private static AwsException bad(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}

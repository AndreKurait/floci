package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbParameterGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Typed JSON1.1 parsing for the six group operations and their scoped tag operations. */
@ApplicationScoped
public class MemoryDbParameterGroupHandler {
    private final MemoryDbParameterGroupService service;
    private final ObjectMapper mapper;

    @Inject
    public MemoryDbParameterGroupHandler(MemoryDbParameterGroupService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        if (request == null || !request.isObject()) {
            throw invalid("The request must be a JSON object.");
        }
        ObjectNode result = switch (action) {
            case "CreateParameterGroup" -> group(service.create(
                    text(request, "ParameterGroupName", true), text(request, "Family", true),
                    text(request, "Description", false), tags(request, false), region));
            case "DeleteParameterGroup" -> group(service.delete(text(request, "ParameterGroupName", true), region));
            case "DescribeParameterGroups" -> describe(request, region);
            case "DescribeParameters" -> parameters(request, region);
            case "UpdateParameterGroup" -> group(service.update(text(request, "ParameterGroupName", true),
                    updates(request), region));
            case "ResetParameterGroup" -> group(service.reset(text(request, "ParameterGroupName", true),
                    bool(request, "AllParameters"), strings(request, "ParameterNames", false), region));
            case "ListTags" -> tagList(service.listTags(text(request, "ResourceArn", true), region));
            case "TagResource" -> tagList(service.tag(text(request, "ResourceArn", true), tags(request, true), region));
            case "UntagResource" -> tagList(service.untag(text(request, "ResourceArn", true),
                    strings(request, "TagKeys", true), region));
            default -> throw new IllegalArgumentException("Unsupported parameter group operation");
        };
        return Response.ok(result).build();
    }

    private ObjectNode describe(JsonNode request, String region) {
        MemoryDbParameterGroupService.Page<MemoryDbParameterGroup> page = service.describe(
                text(request, "ParameterGroupName", false), integer(request, "MaxResults"),
                text(request, "NextToken", false), region);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode values = result.putArray("ParameterGroups");
        page.values().forEach(group -> values.add(metadata(group)));
        token(result, page.nextToken());
        return result;
    }

    private ObjectNode parameters(JsonNode request, String region) {
        MemoryDbParameterGroupService.Page<Map<String, String>> page = service.parameters(
                text(request, "ParameterGroupName", true), integer(request, "MaxResults"),
                text(request, "NextToken", false), region);
        ObjectNode result = mapper.createObjectNode();
        result.set("Parameters", mapper.valueToTree(page.values()));
        token(result, page.nextToken());
        return result;
    }

    private ObjectNode group(MemoryDbParameterGroup group) {
        ObjectNode result = mapper.createObjectNode();
        result.set("ParameterGroup", metadata(group));
        return result;
    }

    private ObjectNode metadata(MemoryDbParameterGroup group) {
        ObjectNode result = mapper.createObjectNode();
        result.put("Name", group.name());
        result.put("Family", group.family());
        result.put("Description", group.description());
        result.put("ARN", group.arn());
        return result;
    }

    private ObjectNode tagList(Map<String, String> tags) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode values = result.putArray("TagList");
        new TreeMap<>(tags).forEach((key, value) -> values.addObject().put("Key", key).put("Value", value));
        return result;
    }

    private static void token(ObjectNode result, String token) {
        if (token != null) {
            result.put("NextToken", token);
        }
    }

    private static List<MemoryDbParameterGroupService.ParameterValue> updates(JsonNode request) {
        JsonNode values = array(request, "ParameterNameValues", true);
        List<MemoryDbParameterGroupService.ParameterValue> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject()) {
                throw invalid("Each ParameterNameValues entry must be an object.");
            }
            result.add(new MemoryDbParameterGroupService.ParameterValue(
                    text(value, "ParameterName", true), text(value, "ParameterValue", true)));
        }
        return result;
    }

    private static Map<String, String> tags(JsonNode request, boolean required) {
        JsonNode values = array(request, "Tags", required);
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        if (values.size() > 200) {
            throw invalid("Tags must contain at most 200 entries.");
        }
        for (JsonNode value : values) {
            if (!value.isObject()) {
                throw invalid("Each Tags entry must be an object.");
            }
            result.put(text(value, "Key", true), text(value, "Value", false));
        }
        return result;
    }

    private static List<String> strings(JsonNode request, String field, boolean required) {
        JsonNode values = array(request, field, required);
        if (values == null) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalid(field + " must contain strings.");
            }
            result.add(value.textValue());
        }
        return result;
    }

    private static JsonNode array(JsonNode request, String field, boolean required) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw invalid(field + " is required.");
            }
            return null;
        }
        if (!value.isArray()) {
            throw invalid(field + " must be an array.");
        }
        return value;
    }

    private static String text(JsonNode request, String field, boolean required) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw invalid(field + " is required.");
            }
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string.");
        }
        return value.textValue();
    }

    private static Integer integer(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be an integer.");
        }
        return value.intValue();
    }

    private static Boolean bool(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw invalid(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }
}

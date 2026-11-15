package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request validation for the pure, request-local subset of TestState. */
final class TestStateDefinition {
    private static final Set<String> PURE_TYPES = Set.of("Pass", "Choice", "Succeed", "Fail");
    private static final Set<String> COMMON_FIELDS = Set.of("Type", "Comment", "QueryLanguage");
    private static final Map<String, Set<String>> TYPE_FIELDS = Map.of(
            "Pass", Set.of("InputPath", "OutputPath", "Parameters", "Result", "ResultPath", "Next", "End"),
            "Choice", Set.of("InputPath", "OutputPath", "Choices", "Default"),
            "Succeed", Set.of("InputPath", "OutputPath"),
            "Fail", Set.of("Error", "Cause", "ErrorPath", "CausePath"));

    private TestStateDefinition() {
    }

    record Prepared(String definitionText, JsonNode definition, JsonNode state, String stateName,
                    String roleArn, JsonNode input, String rawInput, boolean debug) {
    }

    static Prepared prepare(JsonNode request, ObjectMapper mapper) {
        if (request == null || !request.isObject()) {
            throw invalidRequest("The request must be a JSON object.");
        }
        String definitionText = text(request, "definition", true, 1, 1048576);
        String inputText = text(request, "input", false, 0, 262144);
        String roleArn = text(request, "roleArn", true, 1, 256);
        String stateName = text(request, "stateName", false, 1, 80);
        String level = text(request, "inspectionLevel", false, 1, 5);
        if (level != null && !"INFO".equals(level) && !"DEBUG".equals(level)) {
            throw invalidRequest("Pure TestState requests support INFO or DEBUG inspectionLevel.");
        }
        JsonNode reveal = request.get("revealSecrets");
        if (reveal != null && !reveal.isNull() && !reveal.isBoolean()) {
            throw invalidRequest("revealSecrets must be a boolean.");
        }
        for (String field : List.of("mock", "variables", "context", "stateConfiguration")) {
            if (request.hasNonNull(field)) {
                throw unsupported("TestState does not yet support " + field + ".");
            }
        }
        JsonNode definition = json(mapper, definitionText, "InvalidDefinition", "definition");
        if (!definition.isObject()) {
            throw invalidDefinition("definition must be a JSON object.");
        }
        JsonNode state = stateName == null ? definition : definition.path("States").get(stateName);
        if (state == null || !state.isObject()) {
            throw invalidDefinition("stateName must name a top-level state in a complete state machine.");
        }
        String rawInput = inputText == null ? "{}" : inputText;
        JsonNode input = json(mapper, rawInput, "InvalidExecutionInput", "input");
        return new Prepared(definitionText, definition, state, stateName, roleArn, input, rawInput,
                "DEBUG".equals(level));
    }

    static void validateSupported(Prepared prepared) {
        JsonNode state = prepared.state();
        JsonNode typeNode = state.get("Type");
        if (typeNode == null || !typeNode.isTextual()) {
            throw invalidDefinition("The state must declare a string Type.");
        }
        String type = typeNode.asText();
        if (!PURE_TYPES.contains(type)) {
            if (Set.of("Task", "Wait", "Map", "Parallel").contains(type)) {
                throw unsupported("TestState does not yet support " + type + " states.");
            }
            throw invalidDefinition("Unknown state Type: " + type);
        }
        if ("JSONata".equals(state.path("QueryLanguage").asText())
                || prepared.stateName() != null
                && "JSONata".equals(prepared.definition().path("QueryLanguage").asText())) {
            throw unsupported("TestState does not yet support JSONata.");
        }
        if (state.has("Assign")) {
            throw unsupported("TestState does not yet support workflow variables or Assign.");
        }
        Iterator<String> fields = state.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!COMMON_FIELDS.contains(field) && !TYPE_FIELDS.get(type).contains(field)) {
                throw invalidDefinition("Field '" + field + "' is not supported by a " + type + " state.");
            }
        }
        if (state.has("Comment") && !state.path("Comment").isTextual()) {
            throw invalidDefinition("Comment must be a string.");
        }
        for (String field : List.of("InputPath", "OutputPath", "ResultPath")) {
            if (state.hasNonNull(field)) {
                JsonNode path = state.get(field);
                if (!path.isTextual() || !ChoiceOperators.isReferencePath(path.asText())) {
                    throw invalidDefinition(field + " must be a JSONPath string or null.");
                }
                rejectExternalReferences(path.asText());
            }
        }
        for (String field : List.of("Next", "Default")) {
            if (state.has(field)) {
                requireStateName(state.get(field), field);
            }
        }
        if (state.has("End") && (!state.path("End").isBoolean() || !state.path("End").booleanValue()
                || state.has("Next"))) {
            throw invalidDefinition("End must be true and cannot be combined with Next.");
        }
        if (state.has("Parameters")) {
            if (!state.path("Parameters").isObject()) {
                throw invalidDefinition("Parameters must be an object.");
            }
            validateParameters(state.get("Parameters"));
        }
        for (String field : List.of("Error", "Cause")) {
            if (state.has(field) && !state.path(field).isTextual()) {
                throw invalidDefinition(field + " must be a string.");
            }
        }
        for (String field : List.of("ErrorPath", "CausePath")) {
            if (state.path(field).isTextual()) {
                rejectExternalReferences(state.path(field).asText());
            }
        }
        if ("Choice".equals(type)) {
            for (JsonNode choice : state.path("Choices")) {
                validateChoiceReferences(choice, true);
            }
        }
    }

    private static void validateParameters(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (field.getKey().endsWith(".$")) {
                    JsonNode value = field.getValue();
                    if (!value.isTextual() || !(ChoiceOperators.isReferencePath(value.asText())
                            || value.asText().startsWith("States.") && value.asText().endsWith(")"))) {
                        throw invalidDefinition("A Parameters .$ member must contain a path or intrinsic expression.");
                    }
                    if (node.has(field.getKey().substring(0, field.getKey().length() - 2))) {
                        throw invalidDefinition("Parameters cannot contain literal and .$ forms of the same member.");
                    }
                    rejectExternalReferences(value.asText());
                } else {
                    validateParameters(field.getValue());
                }
            });
        } else if (node.isArray()) {
            node.forEach(TestStateDefinition::validateParameters);
        }
    }

    private static void validateChoiceReferences(JsonNode rule, boolean topLevel) {
        if (!rule.isObject()) {
            return;
        }
        if (rule.has("Assign")) {
            throw unsupported("TestState does not yet support workflow variables or Assign.");
        }
        if (topLevel && rule.has("Next")) {
            requireStateName(rule.get("Next"), "Choices.Next");
        }
        rule.fields().forEachRemaining(field -> {
            if (("Variable".equals(field.getKey()) || field.getKey().endsWith("Path"))
                    && field.getValue().isTextual()) {
                rejectExternalReferences(field.getValue().asText());
            } else if ("And".equals(field.getKey()) || "Or".equals(field.getKey())) {
                field.getValue().forEach(child -> validateChoiceReferences(child, false));
            } else if ("Not".equals(field.getKey())) {
                validateChoiceReferences(field.getValue(), false);
            }
        });
    }

    /** Quoted intrinsic arguments and quoted path member names are literal data. */
    private static void rejectExternalReferences(String expression) {
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '$' && index + 1 < expression.length()) {
                char next = expression.charAt(index + 1);
                if (next == '$' || Character.isUnicodeIdentifierStart(next)) {
                    throw unsupported("TestState does not yet support context or workflow variable references.");
                }
            }
        }
    }

    private static void requireStateName(JsonNode node, String field) {
        if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > 80) {
            throw invalidDefinition(field + " must be a non-empty state name of at most 80 characters.");
        }
    }

    private static String text(JsonNode request, String field, boolean required, int minimum, int maximum) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw invalidRequest(field + " is required for a non-mocked TestState request.");
            }
            return null;
        }
        if (!value.isTextual()) {
            throw invalidRequest(field + " must be a string.");
        }
        String text = value.asText();
        if (text.length() < minimum || text.length() > maximum) {
            throw invalidRequest(field + " length must be between " + minimum + " and " + maximum + ".");
        }
        return text;
    }

    private static JsonNode json(ObjectMapper mapper, String text, String error, String field) {
        try {
            JsonNode result = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (result == null || result.isMissingNode()) {
                throw new AwsException(error, field + " must contain one JSON value.", 400);
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new AwsException(error, field + " must contain one valid JSON value.", 400);
        }
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException invalidDefinition(String message) {
        return new AwsException("InvalidDefinition", message, 400);
    }

    private static AwsException unsupported(String message) {
        return new AwsException("UnsupportedOperation", message, 400);
    }
}

package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Translates the supported explicit REST API authentication controls into inline OpenAPI. */
final class SamRestApiAuth {

    private static final Set<String> METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace",
            "x-amazon-apigateway-any-method");
    private static final Set<String> SUPPORTED = Set.of("DefaultAuthorizer", "ApiKeyRequired");

    private final ObjectMapper mapper;

    SamRestApiAuth(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void apply(JsonNode auth, JsonNode body) {
        if (auth.isMissingNode() || auth.isNull()) {
            return;
        }
        if (!auth.isObject()) {
            throw invalid("Auth must be an object.");
        }
        if (auth.isEmpty()) {
            return;
        }
        auth.fieldNames().forEachRemaining(name -> {
            if (!SUPPORTED.contains(name)) {
                throw invalid("Unsupported Auth property: " + name + ".");
            }
        });
        boolean iam = auth.has("DefaultAuthorizer");
        if (iam && (!auth.path("DefaultAuthorizer").isTextual()
                || !"AWS_IAM".equals(auth.path("DefaultAuthorizer").asText()))) {
            throw invalid("DefaultAuthorizer currently supports AWS_IAM only.");
        }
        if (auth.has("ApiKeyRequired") && !auth.path("ApiKeyRequired").isBoolean()) {
            throw invalid("ApiKeyRequired must be a boolean.");
        }
        boolean keyRequired = auth.path("ApiKeyRequired").asBoolean(false);
        if (!(body instanceof ObjectNode document) || !body.path("paths").isObject()) {
            throw invalid("Auth requires an inline DefinitionBody with a paths object.");
        }
        ObjectNode definitions;
        if ("2.0".equals(body.path("swagger").asText())) {
            definitions = object(document, "securityDefinitions");
        } else if (body.path("openapi").asText().startsWith("3.0.")) {
            definitions = object(object(document, "components"), "securitySchemes");
        } else {
            throw invalid("Auth currently supports Swagger 2.0 and OpenAPI 3.0 only.");
        }
        if (iam) {
            addScheme(definitions, "AWS_IAM", true);
        }
        if (keyRequired) {
            addScheme(definitions, "api_key", false);
        }
        Iterator<Map.Entry<String, JsonNode>> paths = document.path("paths").fields();
        while (paths.hasNext()) {
            JsonNode path = paths.next().getValue();
            if (!path.isObject() || path.has("$ref")) {
                throw invalid("Auth requires inline path objects.");
            }
            Iterator<Map.Entry<String, JsonNode>> methods = path.fields();
            while (methods.hasNext()) {
                Map.Entry<String, JsonNode> method = methods.next();
                if (!METHODS.contains(method.getKey())) {
                    continue;
                }
                if (!(method.getValue() instanceof ObjectNode operation)) {
                    throw invalid("Auth requires inline operation objects.");
                }
                JsonNode existing = operation.has("security")
                        ? operation.get("security") : document.path("security");
                ArrayNode security = security(existing, definitions);
                if (iam && !hasScheme(security, definitions, true)) {
                    security.add(mapper.createObjectNode().set("AWS_IAM", mapper.createArrayNode()));
                }
                if (keyRequired && !hasScheme(security, definitions, false)) {
                    security.add(mapper.createObjectNode().set("api_key", mapper.createArrayNode()));
                }
                operation.set("security", security);
            }
        }
    }

    private ArrayNode security(JsonNode value, ObjectNode definitions) {
        if (value.isMissingNode()) {
            return mapper.createArrayNode();
        }
        if (!value.isArray()) {
            throw invalid("Operation security must be an array.");
        }
        for (JsonNode requirement : value) {
            if (!requirement.isObject()) {
                throw invalid("Security requirements must be objects.");
            }
            requirement.fields().forEachRemaining(entry -> {
                JsonNode scopes = entry.getValue();
                JsonNode scheme = definitions.path(entry.getKey());
                if (!scopes.isArray() || !scopes.isEmpty()
                        || (!isScheme(scheme, true) && !isScheme(scheme, false))) {
                    throw invalid("Auth cannot safely translate the existing security requirement.");
                }
            });
        }
        return value.deepCopy();
    }

    private boolean hasScheme(ArrayNode security, ObjectNode definitions, boolean iam) {
        for (JsonNode requirement : security) {
            Iterator<String> names = requirement.fieldNames();
            while (names.hasNext()) {
                if (isScheme(definitions.path(names.next()), iam)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addScheme(ObjectNode definitions, String name, boolean iam) {
        if (definitions.has(name)) {
            if (!isScheme(definitions.get(name), iam)) {
                throw invalid("Auth conflicts with an existing security scheme.");
            }
            return;
        }
        ObjectNode scheme = mapper.createObjectNode();
        scheme.put("type", "apiKey");
        scheme.put("name", iam ? "Authorization" : "x-api-key");
        scheme.put("in", "header");
        if (iam) {
            scheme.put("x-amazon-apigateway-authtype", "awsSigv4");
        }
        definitions.set(name, scheme);
    }

    private boolean isScheme(JsonNode scheme, boolean iam) {
        return "apiKey".equals(scheme.path("type").asText())
                && "header".equals(scheme.path("in").asText())
                && (iam ? "Authorization" : "x-api-key").equalsIgnoreCase(scheme.path("name").asText())
                && !scheme.has("x-amazon-apigateway-authorizer")
                && (iam ? "awsSigv4".equalsIgnoreCase(scheme.path("x-amazon-apigateway-authtype").asText())
                        : !scheme.has("x-amazon-apigateway-authtype"));
    }

    private ObjectNode object(ObjectNode parent, String name) {
        if (!parent.has(name)) {
            parent.set(name, mapper.createObjectNode());
        }
        if (!(parent.get(name) instanceof ObjectNode result)) {
            throw invalid(name + " must be an object.");
        }
        return result;
    }

    private AwsException invalid(String reason) {
        return new AwsException("ValidationError", "SAM AWS::Serverless::Api " + reason, 400);
    }
}

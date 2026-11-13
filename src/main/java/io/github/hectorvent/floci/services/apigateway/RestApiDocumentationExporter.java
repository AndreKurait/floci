package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.apigateway.model.DocumentationVersion;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Complete snapshot extension plus documentation on explicitly identified exported entities. */
final class RestApiDocumentationExporter {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> INFO_FIELDS = Set.of("description", "termsOfService", "contact", "license");
    private static final Set<String> METHOD_FIELDS = Set.of("description", "summary", "tags", "externalDocs", "deprecated");
    private static final Set<String> DESCRIPTION = Set.of("description");

    private RestApiDocumentationExporter() {
    }

    static void apply(ObjectNode document, DocumentationVersion version, boolean swagger) {
        ObjectNode extension = document.putObject("x-amazon-apigateway-documentation");
        extension.put("version", version.version());
        extension.put("createdDate", Instant.ofEpochSecond(version.createdDate()).toString());
        ArrayNode parts = extension.putArray("documentationParts");
        for (DocumentationVersion.Part part : version.parts()) {
            JsonNode properties;
            try {
                properties = JSON.readTree(part.properties());
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Cannot read stored documentation properties", failure);
            }
            if (properties == null || !properties.isObject()) {
                throw new IllegalStateException("Stored documentation properties must be an object");
            }
            ObjectNode exported = parts.addObject();
            exported.set("location", JSON.valueToTree(part.location()));
            exported.set("properties", properties.deepCopy());
            project(document, part.location(), properties, swagger);
        }
    }

    private static void project(ObjectNode document, Map<String, String> location, JsonNode properties,
                                boolean swagger) {
        String type = location.get("type");
        if ("API".equals(type)) {
            copy(document, properties, Set.of("externalDocs", "tags"));
            copy(document.path("info"), properties.path("info"), INFO_FIELDS);
        } else if ("MODEL".equals(type)) {
            JsonNode schemas = swagger ? document.path("definitions") : document.path("components").path("schemas");
            copy(schemas.path(location.getOrDefault("name", "")), properties,
                    Set.of("description", "title", "example", "externalDocs"));
        } else if ("AUTHORIZER".equals(type)) {
            JsonNode schemes = swagger ? document.path("securityDefinitions")
                    : document.path("components").path("securitySchemes");
            copy(schemes.path(location.getOrDefault("name", "")), properties, DESCRIPTION);
        } else {
            String path = location.get("path");
            if (path == null || path.contains("*")) {
                return;
            }
            JsonNode resource = document.path("paths").path(path);
            if ("RESOURCE".equals(type)) {
                copy(resource, properties, swagger ? Set.of() : Set.of("summary", "description"));
                return;
            }
            String method = location.get("method");
            if (method == null || "*".equals(method)) {
                return;
            }
            JsonNode operation = resource.path("ANY".equals(method) ? "x-amazon-apigateway-any-method"
                    : method.toLowerCase(Locale.ROOT));
            if ("METHOD".equals(type)) {
                copy(operation, properties, METHOD_FIELDS);
            } else if ("RESPONSE".equals(type)) {
                String status = location.get("statusCode");
                if (status != null && !"*".equals(status)) {
                    copy(operation.path("responses").path(status), properties, DESCRIPTION);
                }
            }
        }
    }

    private static boolean descriptiveKind(String field, JsonNode value) {
        return switch (field) {
            case "tags" -> value.isArray();
            case "contact", "license", "externalDocs" -> value.isObject();
            case "deprecated" -> value.isBoolean();
            case "example" -> true;
            default -> value.isTextual();
        };
    }

    private static void copy(JsonNode target, JsonNode properties, Set<String> fields) {
        if (!(target instanceof ObjectNode object) || !properties.isObject()) {
            return;
        }
        properties.properties().forEach(entry -> {
            if (fields.contains(entry.getKey()) && descriptiveKind(entry.getKey(), entry.getValue())
                    || entry.getKey().startsWith("x-")
                    && !entry.getKey().startsWith("x-amazon-apigateway-")) {
                object.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
    }
}

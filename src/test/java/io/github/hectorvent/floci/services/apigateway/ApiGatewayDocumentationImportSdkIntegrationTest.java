package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.DocumentationPartLocation;
import software.amazon.awssdk.services.apigateway.model.GetDocumentationPartsResponse;
import software.amazon.awssdk.services.apigateway.model.ImportDocumentationPartsResponse;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayDocumentationImportSdkIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test
    void defaultAndExplicitMergePreserveOtherAttributesAndApiDefinitions() throws Exception {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            try {
                String apiName = client.getRestApi(r -> r.restApiId(api)).name();
                String root = client.getResources(r -> r.restApiId(api)).items().getFirst().id();
                String resource = client.createResource(r -> r.restApiId(api).parentId(root).pathPart("items")).id();
                client.putMethod(r -> r.restApiId(api).resourceId(resource).httpMethod("GET").authorizationType("NONE"));
                Map<String, String> location = Map.of("type", "METHOD", "path", "/items", "method", "GET");
                String initial = "{\"description\":\"Old\",\"summary\":\"Keep \\\"quotes\\\"\\nline\","
                        + "\"tags\":[\"owned\"],\"enabled\":true,\"count\":2,\"nested\":{\"old\":1},"
                        + "\"precise\":1.234567890123456789,\"nullable\":null}";
                String id = client.createDocumentationPart(r -> r.restApiId(api)
                        .location(DocumentationPartLocation.builder().type("METHOD").path("/items").method("GET").build())
                        .properties(initial)).id();
                String sibling = client.createDocumentationPart(r -> r.restApiId(api)
                        .location(DocumentationPartLocation.builder().type("API").build()).properties("{\"summary\":\"Sibling\"}")).id();
                for (String mode : new String[] {null, "merge"}) {
                    ObjectNode replacement = JSON.createObjectNode().put("description", "Updated " + mode);
                    replacement.set("nested", JSON.createObjectNode().put("replacement", 2));
                    replacement.put("incomingPrecise", new BigDecimal("9.876543210987654321"));
                    String body = document(List.of(part(location, replacement)));
                    ImportDocumentationPartsResponse imported = client.importDocumentationParts(r -> r.restApiId(api)
                            .mode(mode).failOnWarnings(true).body(SdkBytes.fromUtf8String(body)));
                    assertEquals(List.of(id), imported.ids());
                    assertTrue(imported.warnings().isEmpty());
                    JsonNode actual = properties(client, api, id);
                    JsonNode expected = JSON.readTree(initial);
                    ((ObjectNode) expected).setAll(replacement);
                    assertEquals(expected, actual);
                    assertEquals("Sibling", properties(client, api, sibling).path("summary").asText());
                    assertEquals("NONE", client.getMethod(r -> r.restApiId(api).resourceId(resource).httpMethod("GET"))
                            .authorizationType());
                    assertEquals(apiName, client.getRestApi(r -> r.restApiId(api)).name());
                }
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void overwriteReplacesCollectionAndPropertiesThenImportedPartsSupportCrudAndPagination() throws Exception {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            try {
                String keep = client.createDocumentationPart(r -> r.restApiId(api)
                        .location(DocumentationPartLocation.builder().type("API").build())
                        .properties("{\"summary\":\"Removed attribute\"}")).id();
                String remove = client.createDocumentationPart(r -> r.restApiId(api)
                        .location(DocumentationPartLocation.builder().type("RESOURCE").path("/old").build()).properties("{}")).id();
                String body = document(List.of(part(Map.of("type", "API"), Map.of("description", "Replacement")),
                        part(Map.of("type", "METHOD", "path", "/items", "method", "GET"), Map.of("tags", List.of("one", "two")))));
                ImportDocumentationPartsResponse imported = client.importDocumentationParts(r -> r.restApiId(api)
                        .mode("overwrite").body(SdkBytes.fromUtf8String(body)));
                assertEquals(keep, imported.ids().getFirst());
                assertEquals(JSON.readTree("{\"description\":\"Replacement\"}"), properties(client, api, keep));
                assertThrows(NotFoundException.class, () -> client.getDocumentationPart(r -> r.restApiId(api).documentationPartId(remove)));
                Set<String> listed = new HashSet<>();
                String position = null;
                do {
                    String token = position;
                    GetDocumentationPartsResponse page = client.getDocumentationParts(r -> r.restApiId(api).limit(1).position(token));
                    assertEquals(1, page.items().size());
                    assertTrue(listed.add(page.items().getFirst().id()));
                    position = page.position();
                } while (position != null);
                assertEquals(new HashSet<>(imported.ids()), listed);
                client.updateDocumentationPart(r -> r.restApiId(api).documentationPartId(keep)
                        .patchOperations(PatchOperation.builder().op("replace").path("/properties").value("{\"summary\":\"Patched\"}").build()));
                assertEquals("Patched", properties(client, api, keep).path("summary").asText());
                client.deleteDocumentationPart(r -> r.restApiId(api).documentationPartId(keep));
                assertThrows(NotFoundException.class, () -> client.getDocumentationPart(r -> r.restApiId(api).documentationPartId(keep)));
                ImportDocumentationPartsResponse cleared = client.importDocumentationParts(r -> r.restApiId(api)
                        .mode("overwrite").body(SdkBytes.fromUtf8String(document(List.of()))));
                assertTrue(cleared.ids().isEmpty());
                assertTrue(client.getDocumentationParts(r -> r.restApiId(api)).items().isEmpty());
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void invalidLaterPartAndDuplicateCanonicalLocationLeaveEntireCollectionUnchanged() throws Exception {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            try {
                String id = client.createDocumentationPart(r -> r.restApiId(api)
                        .location(DocumentationPartLocation.builder().type("API").build()).properties("{\"summary\":\"Retain\"}")).id();
                Map<String, Object> first = part(Map.of("type", "API"), Map.of("description", "Must not commit"));
                for (Map<String, Object> invalid : List.of(part(Map.of("type", "UNKNOWN"), Map.of()),
                        part(Map.of("type", "METHOD"), "not an object"), part(Map.of("type", "API"), Map.of()))) {
                    String body = document(List.of(first, invalid));
                    assertThrows(BadRequestException.class, () -> client.importDocumentationParts(r -> r.restApiId(api)
                            .mode("overwrite").body(SdkBytes.fromUtf8String(body))));
                    assertEquals(List.of(id), client.getDocumentationParts(r -> r.restApiId(api)).items().stream().map(p -> p.id()).toList());
                    assertEquals(JSON.readTree("{\"summary\":\"Retain\"}"), properties(client, api, id));
                }
                String canonicalDuplicates = document(List.of(part(Map.of("type", "METHOD"), Map.of()),
                        part(Map.of("type", "METHOD", "path", "/", "method", "*"), Map.of())));
                assertThrows(BadRequestException.class, () -> client.importDocumentationParts(r -> r.restApiId(api)
                        .body(SdkBytes.fromUtf8String(canonicalDuplicates))));
                assertEquals(1, client.getDocumentationParts(r -> r.restApiId(api)).items().size());
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void warningRefusalDoesNotOverwriteAndAllowedWarningsAreReturned() throws Exception {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            try {
                String id = client.createDocumentationPart(r -> r.restApiId(api)
                        .location(DocumentationPartLocation.builder().type("API").build()).properties("{\"summary\":\"Retain\"}")).id();
                ObjectNode root = (ObjectNode) JSON.readTree(document(List.of(part(Map.of("type", "API"), Map.of("description", "Imported")))));
                root.set("paths", JSON.readTree("""
                        {"/":{"get":{"responses":{"200":{"description":"response","content":{
                          "application/json":{"schema":{"$ref":"#/components/schemas/MissingModel"}}
                        }}}}}}
                        """));
                String warningBody = root.toString();
                assertThrows(BadRequestException.class, () -> client.importDocumentationParts(r -> r.restApiId(api)
                        .mode("overwrite").failOnWarnings(true).body(SdkBytes.fromUtf8String(warningBody))));
                assertEquals(JSON.readTree("{\"summary\":\"Retain\"}"), properties(client, api, id));
                ImportDocumentationPartsResponse result = client.importDocumentationParts(r -> r.restApiId(api)
                        .mode("overwrite").failOnWarnings(false).body(SdkBytes.fromUtf8String(warningBody)));
                assertFalse(result.warnings().isEmpty());
                assertEquals(List.of(id), result.ids());
                assertEquals(JSON.readTree("{\"description\":\"Imported\"}"), properties(client, api, id));
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void nativeImportChecksParentRegionAndBodyAndAcceptsSwaggerTwo() throws Exception {
        try (ApiGatewayClient client = client(); ApiGatewayClient west = AwsTestClients.create(
                ApiGatewayClient.builder(), "test", Region.US_WEST_2)) {
            String api = createApi(client);
            try {
                String valid = document(List.of(part(Map.of("type", "RESPONSE"), Map.of("description", "Defaults"))));
                assertThrows(NotFoundException.class, () -> west.importDocumentationParts(r -> r.restApiId(api).body(SdkBytes.fromUtf8String(valid))));
                assertThrows(NotFoundException.class, () -> client.importDocumentationParts(r -> r.restApiId("missing").body(SdkBytes.fromUtf8String(valid))));
                for (String invalid : List.of("{}", "[]", valid + " {}", "openapi: 3.0.0", "{broken")) {
                    assertThrows(BadRequestException.class, () -> client.importDocumentationParts(r -> r.restApiId(api).body(SdkBytes.fromUtf8String(invalid))));
                }
                assertThrows(BadRequestException.class, () -> client.importDocumentationParts(r -> r.restApiId(api).mode("invalid").body(SdkBytes.fromUtf8String(valid))));
                ObjectNode swagger = (ObjectNode) JSON.readTree(valid);
                swagger.remove("openapi");
                swagger.put("swagger", "2.0");
                String id = client.importDocumentationParts(r -> r.restApiId(api).body(SdkBytes.fromUtf8String(swagger.toString()))).ids().getFirst();
                DocumentationPartLocation location = client.getDocumentationPart(r -> r.restApiId(api).documentationPartId(id)).location();
                assertEquals("/", location.path());
                assertEquals("*", location.method());
                assertEquals("*", location.statusCode());
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    private ApiGatewayClient client() {
        return AwsTestClients.create(ApiGatewayClient.builder(), "test", Region.US_EAST_1, Apache5HttpClient.builder());
    }

    private static String createApi(ApiGatewayClient client) {
        return client.createRestApi(r -> r.name("documentation-import-" + UUID.randomUUID())).id();
    }

    static String document(List<Map<String, Object>> parts) {
        ObjectNode document = JSON.createObjectNode().put("openapi", "3.0.1");
        document.putObject("info").put("title", "Documentation only; must not rename the API").put("version", "1");
        document.putObject("paths");
        document.putObject("x-amazon-apigateway-documentation").put("version", "snapshot-1")
                .set("documentationParts", JSON.valueToTree(parts));
        return document.toString();
    }

    static Map<String, Object> part(Map<String, String> location, Object properties) {
        return Map.of("location", location, "properties", properties);
    }

    private static JsonNode properties(ApiGatewayClient client, String api, String part) throws Exception {
        return JSON.readTree(client.getDocumentationPart(r -> r.restApiId(api).documentationPartId(part)).properties());
    }
}

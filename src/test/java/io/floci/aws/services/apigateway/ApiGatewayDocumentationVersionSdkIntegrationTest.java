package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.CreateDocumentationVersionResponse;
import software.amazon.awssdk.services.apigateway.model.DocumentationPartLocation;
import software.amazon.awssdk.services.apigateway.model.GetDocumentationVersionsResponse;
import software.amazon.awssdk.services.apigateway.model.GetStageResponse;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayDocumentationVersionSdkIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String OLD_API = "{\"description\":\"Old API\",\"info\":{\"description\":\"Old API\"},"
            + "\"nested\":{\"quoted\":\"line\\n\\\"quoted\\\"\",\"count\":2},"
            + "\"precise\":1.234567890123456789,\"enabled\":true,\"nullable\":null}";
    private static final String OLD_METHOD = "{\"description\":\"Old method\",\"summary\":\"Published\"}";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void nativeMetadataCrudPreservesCreatedDateAndLiveDocumentation(String region) throws Exception {
        try (ApiGatewayClient client = client("test", region)) {
            String api = createApi(client);
            try {
                String part = apiPart(client, api, OLD_API);
                CreateDocumentationVersionResponse created = client.createDocumentationVersion(r -> r.restApiId(api)
                        .documentationVersion("v1").description("First description"));
                assertEquals(201, created.sdkHttpResponse().statusCode());
                assertEquals("v1", created.version());
                assertEquals("First description", created.description());
                assertNotNull(created.createdDate());
                assertEquals(created.createdDate(), client.getDocumentationVersion(r -> r.restApiId(api)
                        .documentationVersion("v1")).createdDate());
                assertEquals("First description", client.getDocumentationVersions(r -> r.restApiId(api))
                        .items().getFirst().description());
                client.updateDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1")
                        .patchOperations(replace("/description", "Second description")));
                assertEquals("Second description", client.getDocumentationVersion(r -> r.restApiId(api)
                        .documentationVersion("v1")).description());
                assertEquals(created.createdDate(), client.getDocumentationVersion(r -> r.restApiId(api)
                        .documentationVersion("v1")).createdDate());
                assertEquals(JSON.readTree(OLD_API), properties(client, api, part));
                assertEquals(202, client.deleteDocumentationVersion(r -> r.restApiId(api)
                        .documentationVersion("v1")).sdkHttpResponse().statusCode());
                assertThrows(NotFoundException.class, () -> client.getDocumentationVersion(r -> r.restApiId(api)
                        .documentationVersion("v1")));
                assertTrue(client.getDocumentationVersions(r -> r.restApiId(api)).items().isEmpty());
                assertEquals(JSON.readTree(OLD_API), properties(client, api, part));
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"swagger", "oas30"})
    void pinnedExportReusesExactSnapshotBytesForSiblingImport(String dialect) throws Exception {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            String sibling = createApi(client);
            try {
                String apiPart = apiPart(client, api, OLD_API);
                String methodPart = client.createDocumentationPart(r -> r.restApiId(api)
                        .location(DocumentationPartLocation.builder().type("METHOD").path("/").method("GET").build())
                        .properties(OLD_METHOD)).id();
                client.createDocumentationVersion(r -> r.restApiId(api).documentationVersion("published")
                        .description("Frozen documentation"));
                client.updateDocumentationPart(r -> r.restApiId(api).documentationPartId(apiPart)
                        .patchOperations(replace("/properties", "{\"description\":\"Live API\"}")));
                client.updateDocumentationPart(r -> r.restApiId(api).documentationPartId(methodPart)
                        .patchOperations(replace("/properties", "{\"description\":\"Live method\"}")));
                String deployment = deployment(client, api);
                client.createStage(r -> r.restApiId(api).stageName("published").deploymentId(deployment)
                        .documentationVersion("published"));
                byte[] exported = client.getExport(r -> r.restApiId(api).stageName("published")
                        .exportType(dialect).accepts("application/json")
                        .parameters(Map.of("extensions", "documentation"))).body().asByteArray();
                JsonNode document = JSON.readTree(exported);
                assertEquals("swagger".equals(dialect) ? "2.0" : "3.0.1",
                        document.path("swagger".equals(dialect) ? "swagger" : "openapi").asText());
                JsonNode extension = document.path("x-amazon-apigateway-documentation");
                assertEquals("published", extension.path("version").asText());
                assertEquals(expectedParts(), partSet(extension.path("documentationParts")));
                assertEquals("Old API", document.path("info").path("description").asText());
                assertEquals("Old method", document.path("paths").path("/").path("get").path("description").asText());
                client.importDocumentationParts(r -> r.restApiId(sibling).mode("overwrite").failOnWarnings(true)
                        .body(SdkBytes.fromByteArray(exported)));
                Set<JsonNode> imported = new HashSet<>();
                client.getDocumentationParts(r -> r.restApiId(sibling)).items().forEach(part -> {
                    JsonNode location = JSON.valueToTree(Map.of("type", part.location().typeAsString()));
                    if ("METHOD".equals(part.location().typeAsString())) {
                        location = JSON.valueToTree(Map.of("type", "METHOD", "path", part.location().path(),
                                "method", part.location().method()));
                    }
                    try {
                        imported.add(part(location, JSON.readTree(part.properties())));
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });
                assertEquals(expectedParts(), imported);
                assertEquals("Live API", properties(client, api, apiPart).path("description").asText());
                assertEquals("Live method", properties(client, api, methodPart).path("description").asText());
                client.deleteStage(r -> r.restApiId(api).stageName("published"));
                assertThrows(NotFoundException.class, () -> client.getStage(r -> r.restApiId(api).stageName("published")));
                client.deleteDocumentationVersion(r -> r.restApiId(api).documentationVersion("published"));
                assertEquals(2, client.getDocumentationParts(r -> r.restApiId(api)).items().size());
            } finally {
                client.deleteRestApi(r -> r.restApiId(sibling));
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void createVersionAssociatesExistingStageAndKeepsStageSettings() {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            try {
                apiPart(client, api, OLD_API);
                String deployment = deployment(client, api);
                client.createStage(r -> r.restApiId(api).stageName("live").deploymentId(deployment)
                        .description("Retain stage").variables(Map.of("key", "value")).tracingEnabled(true));
                client.createDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1").stageName("live"));
                GetStageResponse stage = client.getStage(r -> r.restApiId(api).stageName("live"));
                assertEquals("v1", stage.documentationVersion());
                assertEquals(deployment, stage.deploymentId());
                assertEquals("Retain stage", stage.description());
                assertEquals(Map.of("key", "value"), stage.variables());
                assertTrue(stage.tracingEnabled());
                assertEquals("v1", client.getStages(r -> r.restApiId(api)).item().getFirst().documentationVersion());
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void createAndReplaceStagePinSelectIndependentSnapshotsAndDefaultExportIncludesDocumentation() throws Exception {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            try {
                String part = apiPart(client, api, OLD_API);
                client.createDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1"));
                client.updateDocumentationPart(r -> r.restApiId(api).documentationPartId(part)
                        .patchOperations(replace("/properties", "{\"description\":\"Second snapshot\",\"info\":{\"description\":\"Second snapshot\"}}")));
                client.createDocumentationVersion(r -> r.restApiId(api).documentationVersion("v2"));
                String deployment = deployment(client, api);
                assertEquals("v1", client.createStage(r -> r.restApiId(api).stageName("live").deploymentId(deployment)
                        .documentationVersion("v1").variables(Map.of("preserved", "yes"))).documentationVersion());
                JsonNode first = export(client, api, "live");
                assertEquals("Old API", first.path("info").path("description").asText());
                assertEquals("v1", first.path("x-amazon-apigateway-documentation").path("version").asText());
                assertEquals("v2", client.updateStage(r -> r.restApiId(api).stageName("live")
                        .patchOperations(replace("/documentationVersion", "v2"))).documentationVersion());
                assertEquals(Map.of("preserved", "yes"), client.getStage(r -> r.restApiId(api).stageName("live")).variables());
                assertEquals("Second snapshot", export(client, api, "live").path("info").path("description").asText());
                client.deleteStage(r -> r.restApiId(api).stageName("live"));
                assertNull(client.createStage(r -> r.restApiId(api).stageName("live").deploymentId(deployment))
                        .documentationVersion());
                assertEquals(Set.of("v1", "v2"), new HashSet<>(client.getDocumentationVersions(r -> r.restApiId(api))
                        .items().stream().map(version -> version.version()).toList()));
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void snapshotSurvivesOverwriteAndDeletionOfLiveParts() throws Exception {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            try {
                apiPart(client, api, OLD_API);
                client.createDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1"));
                client.importDocumentationParts(r -> r.restApiId(api).mode("overwrite")
                        .body(SdkBytes.fromUtf8String(ApiGatewayDocumentationImportSdkIntegrationTest.document(List.of()))));
                assertTrue(client.getDocumentationParts(r -> r.restApiId(api)).items().isEmpty());
                String deployment = deployment(client, api);
                client.createStage(r -> r.restApiId(api).stageName("live").deploymentId(deployment).documentationVersion("v1"));
                JsonNode exported = export(client, api, "live");
                assertEquals("Old API", exported.path("info").path("description").asText());
                assertEquals(1, exported.path("x-amazon-apigateway-documentation").path("documentationParts").size());
                assertEquals(JSON.readTree(OLD_API), exported.path("x-amazon-apigateway-documentation")
                        .path("documentationParts").get(0).path("properties"));
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void populatedListPaginatesWithoutLeakingStagesOrSiblingVersions() {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            String sibling = createApi(client);
            try {
                for (String version : List.of("v3", "v1", "v2")) {
                    client.createDocumentationVersion(r -> r.restApiId(api).documentationVersion(version)
                            .description("Description " + version));
                }
                client.createDocumentationVersion(r -> r.restApiId(sibling).documentationVersion("sibling"));
                Set<String> versions = new HashSet<>();
                Set<String> tokens = new HashSet<>();
                String position = null;
                do {
                    String token = position;
                    GetDocumentationVersionsResponse page = client.getDocumentationVersions(r -> r.restApiId(api)
                            .limit(1).position(token));
                    assertEquals(1, page.items().size());
                    String version = page.items().getFirst().version();
                    assertTrue(versions.add(version));
                    assertEquals("Description " + version, page.items().getFirst().description());
                    position = page.position();
                    if (position != null) {
                        assertFalse(position.isBlank());
                        assertTrue(tokens.add(position));
                    }
                } while (position != null);
                assertEquals(Set.of("v1", "v2", "v3"), versions);
                assertTrue(client.getStages(r -> r.restApiId(api)).item().isEmpty());
                assertEquals(List.of("sibling"), client.getDocumentationVersions(r -> r.restApiId(sibling))
                        .items().stream().map(version -> version.version()).toList());
            } finally {
                client.deleteRestApi(r -> r.restApiId(sibling));
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void emptyListRequiresAnExistingParent() {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            try {
                GetDocumentationVersionsResponse empty = client.getDocumentationVersions(r -> r.restApiId(api));
                assertTrue(empty.items().isEmpty());
                assertNull(empty.position());
                assertThrows(NotFoundException.class, () -> client.getDocumentationVersions(r -> r.restApiId("missing")));
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void versionReadsAndDeletesRequireTheOwnedParentAndRegion() {
        try (ApiGatewayClient client = client();
             ApiGatewayClient west = client("test", "us-west-2");
             ApiGatewayClient other = client("271828182845", "us-east-1")) {
            String api = createApi(client);
            String sibling = createApi(client);
            try {
                client.createDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1"));
                for (ApiGatewayClient outsider : List.of(west, other)) {
                    assertThrows(NotFoundException.class, () -> outsider.getDocumentationVersion(r -> r.restApiId(api)
                            .documentationVersion("v1")));
                    assertThrows(NotFoundException.class, () -> outsider.deleteDocumentationVersion(r -> r.restApiId(api)
                            .documentationVersion("v1")));
                    assertThrows(NotFoundException.class, () -> outsider.getDocumentationVersions(r -> r.restApiId(api)));
                }
                assertThrows(NotFoundException.class, () -> client.getDocumentationVersion(r -> r.restApiId(sibling)
                        .documentationVersion("v1")));
                assertEquals("v1", client.getDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1")).version());
            } finally {
                client.deleteRestApi(r -> r.restApiId(sibling));
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @Test
    void absentPinIsRejectedBeforeCreatingOrChangingAStage() {
        try (ApiGatewayClient client = client()) {
            String api = createApi(client);
            try {
                String deployment = deployment(client, api);
                assertThrows(NotFoundException.class, () -> client.createStage(r -> r.restApiId(api)
                        .stageName("absent").deploymentId(deployment).documentationVersion("missing")));
                assertThrows(NotFoundException.class, () -> client.getStage(r -> r.restApiId(api).stageName("absent")));
                client.createStage(r -> r.restApiId(api).stageName("live").deploymentId(deployment).description("Original"));
                assertThrows(NotFoundException.class, () -> client.updateStage(r -> r.restApiId(api).stageName("live")
                        .patchOperations(replace("/description", "Must not commit"), replace("/documentationVersion", "missing"))));
                assertEquals("Original", client.getStage(r -> r.restApiId(api).stageName("live")).description());
                assertNull(client.getStage(r -> r.restApiId(api).stageName("live")).documentationVersion());
            } finally {
                client.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    static ApiGatewayClient client() {
        return client("test", "us-east-1");
    }

    private static ApiGatewayClient client(String account, String region) {
        return AwsTestClients.create(ApiGatewayClient.builder(), account, Region.of(region), Apache5HttpClient.builder());
    }

    static String createApi(ApiGatewayClient client) {
        return client.createRestApi(r -> r.name("documentation-version-" + UUID.randomUUID())).id();
    }

    private static String apiPart(ApiGatewayClient client, String api, String properties) {
        return client.createDocumentationPart(r -> r.restApiId(api)
                .location(DocumentationPartLocation.builder().type("API").build()).properties(properties)).id();
    }

    private static String deployment(ApiGatewayClient client, String api) {
        String root = client.getResources(r -> r.restApiId(api)).items().stream()
                .filter(resource -> "/".equals(resource.path())).findFirst().orElseThrow().id();
        client.putMethod(r -> r.restApiId(api).resourceId(root).httpMethod("GET").authorizationType("NONE"));
        client.putIntegration(r -> r.restApiId(api).resourceId(root).httpMethod("GET").type("MOCK")
                .requestTemplates(Map.of("application/json", "{\"statusCode\":200}")));
        client.putMethodResponse(r -> r.restApiId(api).resourceId(root).httpMethod("GET").statusCode("200"));
        client.putIntegrationResponse(r -> r.restApiId(api).resourceId(root).httpMethod("GET").statusCode("200"));
        return client.createDeployment(r -> r.restApiId(api).description("Owned definition snapshot")).id();
    }

    private static PatchOperation replace(String path, String value) {
        return PatchOperation.builder().op("replace").path(path).value(value).build();
    }

    private static JsonNode properties(ApiGatewayClient client, String api, String part) throws Exception {
        return JSON.readTree(client.getDocumentationPart(r -> r.restApiId(api).documentationPartId(part)).properties());
    }

    private static JsonNode export(ApiGatewayClient client, String api, String stage) throws Exception {
        return JSON.readTree(client.getExport(r -> r.restApiId(api).stageName(stage).exportType("oas30")
                .accepts("application/json")).body().asByteArray());
    }

    private static Set<JsonNode> expectedParts() throws Exception {
        return Set.of(part(JSON.valueToTree(Map.of("type", "API")), JSON.readTree(OLD_API)),
                part(JSON.valueToTree(Map.of("type", "METHOD", "path", "/", "method", "GET")),
                        JSON.readTree(OLD_METHOD)));
    }

    private static JsonNode part(JsonNode location, JsonNode properties) {
        return JSON.valueToTree(Map.of("location", location, "properties", properties));
    }

    private static Set<JsonNode> partSet(JsonNode parts) {
        Set<JsonNode> values = new HashSet<>();
        parts.forEach(part -> assertTrue(values.add(part)));
        return values;
    }
}

package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.apigateway.model.DocumentationPart;
import io.floci.aws.services.apigateway.model.DocumentationVersion;
import io.floci.aws.services.apigateway.model.RestApi;
import io.floci.aws.testing.InMemoryStorageFactory;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayDocumentationVersionContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "131719232937";
    private static final String OTHER = "271828182845";
    private static final String REGION = "us-east-1";
    private final RequestContext request = new RequestContext();
    private final InMemoryStorage<String, RestApi> parentRows = new InMemoryStorage<>();
    private final InMemoryStorage<String, DocumentationPart> partRows = new InMemoryStorage<>();
    private final InMemoryStorage<String, JsonNode> stageRows = new InMemoryStorage<>();
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ApiGatewayService service;

    ApiGatewayDocumentationVersionContractTest() {
        request.setAccountId(ACCOUNT);
        request.setRegion(REGION);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.defaultRegion()).thenReturn(REGION);
        @SuppressWarnings("unchecked")
        Instance<RequestContext> context = mock(Instance.class);
        when(context.isResolvable()).thenReturn(true);
        when(context.get()).thenReturn(request);
        InMemoryStorageFactory factory = new InMemoryStorageFactory(ACCOUNT)
                .withStore("apigateway-apis.json", new AccountAwareStorageBackend<>(parentRows, context, ACCOUNT))
                .withStore("apigateway-documentation-parts.json", new AccountAwareStorageBackend<>(partRows, context, ACCOUNT))
                .withStore(ApiGatewayStageDocumentationStore.FILE, new AccountAwareStorageBackend<>(stageRows, context, ACCOUNT));
        service = new ApiGatewayService(factory, config, mock(TlsCertificateManager.class), new RegionResolver(REGION, ACCOUNT));
        for (String account : List.of(ACCOUNT, OTHER)) {
            for (String region : List.of(REGION, "us-west-2")) {
                for (String api : List.of("old-api", "sibling")) {
                    RestApi parent = new RestApi();
                    parent.setId(api);
                    parent.setTags(Map.of("team", api));
                    parentRows.put(account + "/" + region + "::" + api, parent);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"documentationVersion\":4}", "{\"documentationVersion\":\"v1\",\"stageName\":3}",
            "{\"documentationVersion\":\"v1\",\"description\":false}"})
    void modeledCreateTypesFailBeforeAnyStageOrVersionWrite(String invalid) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(invalid, Map.class);
        assertEquals(400, assertThrows(AwsException.class,
                () -> service.createDocumentationVersion(REGION, "old-api", body)).getHttpStatus());
        assertTrue(stageRows.keys().isEmpty());
    }

    @Test
    void versionCursorBindsCallerRegionApiAndExactLimitAndSurvivesDeletedAnchor() {
        for (String version : List.of("c", "a", "b")) {
            service.createDocumentationVersion(REGION, "old-api", Map.of("documentationVersion", version));
        }
        ApiGatewayService.DocumentationVersionPage page = service.getDocumentationVersions(REGION, "old-api", 1, null);
        assertEquals(List.of("a"), page.items().stream().map(DocumentationVersion::version).toList());
        String token = page.position();
        assertEquals(400, assertThrows(AwsException.class,
                () -> service.getDocumentationVersions(REGION, "old-api", 2, token)).getHttpStatus());
        assertEquals(400, assertThrows(AwsException.class,
                () -> service.getDocumentationVersions(REGION, "sibling", 1, token)).getHttpStatus());
        assertEquals(400, assertThrows(AwsException.class,
                () -> service.getDocumentationVersions("us-west-2", "old-api", 1, token)).getHttpStatus());
        request.setAccountId(OTHER);
        assertEquals(400, assertThrows(AwsException.class,
                () -> service.getDocumentationVersions(REGION, "old-api", 1, token)).getHttpStatus());
        assertTrue(service.getDocumentationVersions(REGION, "old-api", null, null).items().isEmpty());
        request.setAccountId(ACCOUNT);
        for (String invalid : List.of("", token + "x", "bad.payload")) {
            assertEquals(400, assertThrows(AwsException.class,
                    () -> service.getDocumentationVersions(REGION, "old-api", 1, invalid)).getHttpStatus());
        }
        service.deleteDocumentationVersion(REGION, "old-api", "a");
        assertEquals(List.of("b"), service.getDocumentationVersions(REGION, "old-api", 1, token).items().stream()
                .map(DocumentationVersion::version).toList());
        for (int limit : List.of(0, -1, 501)) {
            assertEquals(400, assertThrows(AwsException.class,
                    () -> service.getDocumentationVersions(REGION, "old-api", limit, null)).getHttpStatus());
        }
        assertNull(service.getDocumentationVersions(REGION, "old-api", 500, null).position());
    }

    @Test
    void defaultLimitIsTwentyFiveAndExplicitDefaultCannotReuseOmittedLimitToken() {
        for (int index = 0; index < 26; index++) {
            service.createDocumentationVersion(REGION, "old-api", Map.of("documentationVersion", "v" + index));
        }
        ApiGatewayService.DocumentationVersionPage first = service.getDocumentationVersions(REGION, "old-api", null, null);
        assertEquals(25, first.items().size());
        assertEquals(1, service.getDocumentationVersions(REGION, "old-api", null, first.position()).items().size());
        assertEquals(400, assertThrows(AwsException.class,
                () -> service.getDocumentationVersions(REGION, "old-api", 25, first.position())).getHttpStatus());
    }

    @Test
    void snapshotReadsScopedPrecedenceAndDefaultOnlyLegacyWithoutMutatingSourceRows() {
        DocumentationPart legacy = part("part", "{\"description\":\"legacy\"}");
        DocumentationPart scoped = part("part", "{\"description\":\"scoped\"}");
        DocumentationPart foreign = part("other", "{\"description\":\"foreign\"}");
        partRows.put(REGION + "::old-api::part", legacy);
        partRows.put(ACCOUNT + "/" + REGION + "::old-api::part", scoped);
        partRows.put(OTHER + "/" + REGION + "::old-api::other", foreign);
        Map<String, JsonNode> before = partState();
        DocumentationVersion own = service.createDocumentationVersion(REGION, "old-api", Map.of("documentationVersion", "v1"));
        assertEquals(List.of("{\"description\":\"scoped\"}"), own.parts().stream().map(DocumentationVersion.Part::properties).toList());
        request.setAccountId(OTHER);
        DocumentationVersion other = service.createDocumentationVersion(REGION, "old-api", Map.of("documentationVersion", "v1"));
        assertEquals(List.of("{\"description\":\"foreign\"}"), other.parts().stream().map(DocumentationVersion.Part::properties).toList());
        assertEquals(before, partState());
        request.setAccountId(ACCOUNT);
        assertEquals(own, service.getDocumentationVersion(REGION, "old-api", "v1"));
        partRows.delete(ACCOUNT + "/" + REGION + "::old-api::part");
        DocumentationVersion defaultLegacy = service.createDocumentationVersion(REGION, "old-api", Map.of("documentationVersion", "v2"));
        assertEquals("{\"description\":\"legacy\"}", defaultLegacy.parts().getFirst().properties());
        assertTrue(partRows.keys().contains(REGION + "::old-api::part"));
    }

    @ParameterizedTest
    @CsvSource({"/restapis/old-api/documentation/versions,old-api", "/restapis/%6Fld-api/documentation/versions/,old-api",
            "/restapis/sibling/%2E%2E/old-api/documentation/versions,old-api",
            "/restapis/old-api%2Fextra/documentation/versions,missing",
            "/restapis/old-api%252Fextra/documentation/versions,missing"})
    void canonicalIamTagLookupMatchesTheRoutedParentWithoutWrites(String path, String expected) {
        Map<String, JsonNode> before = parentState();
        ContainerRequestContext context = mock(ContainerRequestContext.class, RETURNS_DEEP_STUBS);
        when(context.getMethod()).thenReturn("GET");
        when(context.getUriInfo().getBaseUri()).thenReturn(URI.create("http://localhost/"));
        when(context.getUriInfo().getRequestUri()).thenReturn(URI.create("http://localhost" + path));
        Map<String, List<String>> result = new ApiGatewayDocumentationIamConditions(service, request, config)
                .resolve("apigateway:GET", context);
        assertEquals("missing".equals(expected) ? Map.of() : Map.of("aws:ResourceTag/team", List.of(expected)), result);
        assertNull(new ApiGatewayDocumentationIamConditions(service, request, config).resolve("apigateway:POST", context));
        assertEquals(before, parentState());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void projectionKeepsCompleteExtensionAndPreservesOperationalFields(boolean swagger) throws Exception {
        ObjectNode document = (ObjectNode) JSON.readTree("""
                {"info":{"title":"Deployed title","version":"deployed"},"paths":{"/pets":{"get":{
                "description":"deployed","responses":{"200":{"description":"original"}},
                "x-amazon-apigateway-integration":{"type":"mock"}}}},
                "definitions":{"Pet":{"type":"object"}},"components":{"schemas":{"Pet":{"type":"object"}}}}
                """);
        List<DocumentationVersion.Part> parts = List.of(
                new DocumentationVersion.Part(Map.of("type", "API"),
                        "{\"foo\":\"extension only\",\"x-extra\":true,\"info\":{\"description\":\"API docs\",\"version\":\"ignored\"}}"),
                new DocumentationVersion.Part(Map.of("type", "METHOD", "path", "/pets", "method", "GET"),
                        "{\"summary\":\"Literal\",\"description\":{\"invalid\":true},\"x-amazon-apigateway-integration\":{\"type\":\"unsafe\"}}"),
                new DocumentationVersion.Part(Map.of("type", "METHOD", "path", "/*", "method", "*"),
                        "{\"summary\":\"Wildcard extension only\"}"),
                new DocumentationVersion.Part(Map.of("type", "MODEL", "name", "Pet"), "{\"description\":\"Model docs\"}"),
                new DocumentationVersion.Part(Map.of("type", "RESPONSE", "path", "/pets", "method", "GET", "statusCode", "200"),
                        "{\"description\":\"Response docs\"}"));
        RestApiDocumentationExporter.apply(document, new DocumentationVersion("v1", null, 123, ACCOUNT, REGION, "old-api", parts), swagger);
        assertEquals(5, document.path("x-amazon-apigateway-documentation").path("documentationParts").size());
        assertEquals("deployed", document.path("info").path("version").asText());
        assertEquals("Deployed title", document.path("info").path("title").asText());
        assertEquals("API docs", document.path("info").path("description").asText());
        assertFalse(document.has("foo"));
        assertTrue(document.path("x-extra").booleanValue());
        JsonNode method = document.path("paths").path("/pets").path("get");
        assertEquals("Literal", method.path("summary").asText());
        assertEquals("deployed", method.path("description").asText());
        assertEquals("mock", method.path("x-amazon-apigateway-integration").path("type").asText());
        assertEquals("Response docs", method.path("responses").path("200").path("description").asText());
        assertEquals("Model docs", (swagger ? document.path("definitions") : document.path("components").path("schemas"))
                .path("Pet").path("description").asText());
    }

    private static DocumentationPart part(String id, String properties) {
        DocumentationPart part = new DocumentationPart();
        part.setId(id);
        part.setLocation(Map.of("type", "API"));
        part.setProperties(properties);
        return part;
    }

    private Map<String, JsonNode> parentState() {
        Map<String, JsonNode> result = new TreeMap<>();
        for (String key : parentRows.keys()) {
            result.put(key, JSON.valueToTree(parentRows.get(key).orElseThrow()));
        }
        return result;
    }

    private Map<String, JsonNode> partState() {
        Map<String, JsonNode> result = new TreeMap<>();
        for (String key : partRows.keys()) {
            result.put(key, JSON.valueToTree(partRows.get(key).orElseThrow()));
        }
        return result;
    }
}

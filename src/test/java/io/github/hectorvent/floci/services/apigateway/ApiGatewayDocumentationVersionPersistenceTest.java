package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.DocumentationPart;
import io.github.hectorvent.floci.services.apigateway.model.DocumentationVersion;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ApiGatewayDocumentationVersionPersistenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "131719232937";
    private static final String REGION = "us-east-1";
    private static final String API = "old-api";
    private static final String KEY = REGION + "::" + API + "::old-stage";
    private static final String FILE = ApiGatewayStageDocumentationStore.FILE;
    private static final TypeReference<Map<String, JsonNode>> NODES = new TypeReference<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"persistent,false", "persistent,true", "wal,false", "wal,true", "hybrid,false", "hybrid,true"})
    void oldTypedStageReopensAsJsonRowsWithoutReadWritesThenPublishesAndReopens(String mode, boolean legacy) throws Exception {
        EmulatorConfig config = config();
        String original = oldStageJson();
        seedOldTypedStores(config, mode, legacy);
        DocumentationVersion captured;
        StorageFactory second = factory(config, mode);
        try {
            ApiGatewayService service = service(second, config);
            AccountAwareStorageBackend<JsonNode> rows = second.create("apigateway", FILE, NODES);
            Map<String, String> bytes = files();
            Set<String> keys = Set.copyOf(raw(rows).keys());
            Stage stage = service.getStage(REGION, API, "old-stage");
            assertEquals(original, JSON.writeValueAsString(stage));
            stage.getTags().put("owner", "detached mutation");
            assertEquals(original, JSON.writeValueAsString(service.getStage(REGION, API, "old-stage")));
            assertTrue(service.getDocumentationVersions(REGION, API, null, null).items().isEmpty());
            assertEquals(404, assertThrows(AwsException.class,
                    () -> service.getDocumentationVersion(REGION, API, "missing")).getHttpStatus());
            assertEquals(Map.of("aws:ResourceTag/team", List.of("owned")), conditions(service, config, ACCOUNT, REGION));
            assertTrue(conditions(service, config, "271828182845", REGION).isEmpty());
            assertTrue(conditions(service, config, ACCOUNT, "us-west-2").isEmpty());
            assertEquals(keys, raw(rows).keys());
            assertEquals(bytes, files());
            captured = service.createDocumentationVersion(REGION, API,
                    Map.of("documentationVersion", "v1", "description", "Original", "stageName", "old-stage"));
            assertEquals("v1", service.getStage(REGION, API, "old-stage").getDocumentationVersion());
            assertEquals(1, service.getResources().stream()
                    .filter(resource -> "apigateway:restapis/stages".equals(resource.resourceType())).count());
            assertTrue(service.getResources().stream().noneMatch(resource -> resource.arn().contains("documentation-versions")));
            service.updateDocumentationPart(REGION, API, "old-part", List.of(Map.of("op", "replace", "path", "/properties",
                    "value", "{\"info\":{\"description\":\"Live changed\"}}")));
            service.updateDocumentationVersion(REGION, API, "v1",
                    List.of(Map.of("op", "replace", "path", "/description", "value", "Edited metadata")));
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = factory(config, mode);
        try {
            ApiGatewayService service = service(third, config);
            DocumentationVersion reopened = service.getDocumentationVersion(REGION, API, "v1");
            assertEquals(captured.parts(), reopened.parts());
            assertEquals(captured.createdDate(), reopened.createdDate());
            assertEquals("Edited metadata", reopened.description());
            assertEquals("v1", service.getStage(REGION, API, "old-stage").getDocumentationVersion());
            assertEquals("{\"info\":{\"description\":\"Live changed\"}}",
                    service.getDocumentationPart(REGION, API, "old-part").getProperties());
            assertEquals(List.of("v1"), service.getDocumentationVersions(REGION, API, null, null).items()
                    .stream().map(DocumentationVersion::version).toList());
        } finally {
            third.shutdownAll();
        }
    }

    @ParameterizedTest
    @CsvSource({"persistent,false", "persistent,true", "wal,false", "wal,true", "hybrid,false", "hybrid,true"})
    void injectedRefusalAtAtomicBoundaryKeepsOldStateAndOnlyPermitsUnchangedLegacyMigration(String mode,
                                                                                           boolean legacy) throws Exception {
        EmulatorConfig config = config();
        seedOldTypedStores(config, mode, legacy);
        AtomicBoolean refuse = new AtomicBoolean(true);
        StorageFactory factory = faultFactory(config, mode, refuse);
        try {
            ApiGatewayService service = service(factory, config);
            AccountAwareStorageBackend<JsonNode> rows = factory.create("apigateway", FILE, NODES);
            JsonNode original = raw(rows).get(legacy ? KEY : ACCOUNT + "/" + KEY).orElseThrow().deepCopy();
            Map<String, String> before = files();
            assertThrows(UncheckedIOException.class, () -> service.createDocumentationVersion(REGION, API,
                    Map.of("documentationVersion", "v1", "stageName", "old-stage")));
            assertEquals(Set.of(ACCOUNT + "/" + KEY), raw(rows).keys());
            assertEquals(original, raw(rows).get(ACCOUNT + "/" + KEY).orElseThrow());
            assertNull(service.getStage(REGION, API, "old-stage").getDocumentationVersion());
            assertTrue(service.getDocumentationVersions(REGION, API, null, null).items().isEmpty());
            if (!legacy) {
                assertEquals(before, files());
            }
            // The injected refusal is before the real backend batch. Hybrid durability is still asynchronous.
            refuse.set(false);
        } finally {
            factory.shutdownAll();
        }
        StorageFactory reopened = factory(config, mode);
        try {
            ApiGatewayService service = service(reopened, config);
            assertEquals(oldStageJson(), JSON.writeValueAsString(service.getStage(REGION, API, "old-stage")));
            assertTrue(service.getDocumentationVersions(REGION, API, null, null).items().isEmpty());
            service.createDocumentationVersion(REGION, API, Map.of("documentationVersion", "v1", "stageName", "old-stage"));
            assertEquals("v1", service.getStage(REGION, API, "old-stage").getDocumentationVersion());
        } finally {
            reopened.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "wal"})
    void actualUnwritableSnapshotOrWalRejectsBothVersionAndPin(String mode) throws Exception {
        EmulatorConfig config = config();
        seedOldTypedStores(config, mode, false);
        StorageFactory factory = factory(config, mode);
        Path path = directory.resolve("wal".equals(mode) ? FILE.replace(".json", ".wal") : FILE);
        Path retained = directory.resolve("retained-owned-store");
        try {
            ApiGatewayService service = service(factory, config);
            Files.move(path, retained);
            Files.createDirectory(path);
            Files.writeString(path.resolve("blocker"), "owned fixture");
            try {
                assertThrows(UncheckedIOException.class, () -> service.createDocumentationVersion(REGION, API,
                        Map.of("documentationVersion", "v1", "stageName", "old-stage")));
                assertEquals(oldStageJson(), JSON.writeValueAsString(service.getStage(REGION, API, "old-stage")));
                assertTrue(service.getDocumentationVersions(REGION, API, null, null).items().isEmpty());
            } finally {
                Files.delete(path.resolve("blocker"));
                Files.delete(path);
                Files.move(retained, path);
            }
            service.createDocumentationVersion(REGION, API, Map.of("documentationVersion", "v1", "stageName", "old-stage"));
            assertEquals("v1", service.getStage(REGION, API, "old-stage").getDocumentationVersion());
        } finally {
            factory.shutdownAll();
        }
        StorageFactory reopened = factory(config, mode);
        try {
            assertEquals("v1", service(reopened, config).getStage(REGION, API, "old-stage").getDocumentationVersion());
        } finally {
            reopened.shutdownAll();
        }
    }

    private void seedOldTypedStores(EmulatorConfig config, String mode, boolean legacy) throws Exception {
        StorageFactory factory = factory(config, mode);
        try {
            // No ApiGatewayService exists in this factory: this is the historical Stage-typed creator.
            AccountAwareStorageBackend<Stage> stages = factory.create("apigateway", FILE, new TypeReference<>() {});
            Stage stage = JSON.readValue(oldStageJson(), Stage.class);
            assertFalse(JSON.writeValueAsString(stage).contains("documentationVersion"));
            assertEquals(oldStageJson(), JSON.writeValueAsString(stage));
            store(stages, KEY, stage, legacy);
            AccountAwareStorageBackend<RestApi> apis = factory.create("apigateway", "apigateway-apis.json", new TypeReference<>() {});
            RestApi api = new RestApi();
            api.setId(API);
            api.setName("Historical API");
            api.setRootResourceId("root");
            api.setTags(Map.of("team", "owned"));
            store(apis, REGION + "::" + API, api, legacy);
            AccountAwareStorageBackend<DocumentationPart> parts = factory.create("apigateway",
                    "apigateway-documentation-parts.json", new TypeReference<>() {});
            DocumentationPart part = new DocumentationPart();
            part.setId("old-part");
            part.setLocation(Map.of("type", "API"));
            part.setProperties("{\"info\":{\"description\":\"Captured old\"}}");
            store(parts, REGION + "::" + API + "::old-part", part, legacy);
        } finally {
            factory.shutdownAll();
        }
    }

    private static <T> void store(AccountAwareStorageBackend<T> store, String key, T value, boolean legacy) throws Exception {
        if (legacy) {
            raw(store).put(key, value);
        } else {
            store.putForAccount(ACCOUNT, key, value);
        }
    }

    private static <T> StorageBackend<String, T> raw(AccountAwareStorageBackend<T> store) throws Exception {
        Field field = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        StorageBackend<String, T> backend = (StorageBackend<String, T>) field.get(store);
        return backend;
    }

    private static Map<String, List<String>> conditions(ApiGatewayService service, EmulatorConfig config,
                                                        String account, String region) {
        RequestContext request = new RequestContext();
        request.setAccountId(account);
        request.setRegion(region);
        ContainerRequestContext context = mock(ContainerRequestContext.class, RETURNS_DEEP_STUBS);
        when(context.getMethod()).thenReturn("GET");
        when(context.getUriInfo().getBaseUri()).thenReturn(URI.create("http://localhost/"));
        when(context.getUriInfo().getRequestUri()).thenReturn(URI.create(
                "http://localhost/restapis/old-api/documentation/versions"));
        return new ApiGatewayDocumentationIamConditions(service, request, config).resolve("apigateway:GET", context);
    }

    private String oldStageJson() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/apigateway-documentation-old-stage.json")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    private Map<String, String> files() throws IOException {
        Map<String, String> bytes = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                bytes.put(directory.relativize(path).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return bytes;
    }

    private EmulatorConfig config() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.defaultRegion()).thenReturn(REGION);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(Long.MAX_VALUE);
        return config;
    }

    private ServiceConfigAccess access(String mode) {
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(Long.MAX_VALUE);
        return access;
    }

    private StorageFactory factory(EmulatorConfig config, String mode) {
        return new StorageFactory(config, access(mode));
    }

    private StorageFactory faultFactory(EmulatorConfig config, String mode, AtomicBoolean refuse) {
        return new StorageFactory(config, access(mode)) {
            @Override
            public synchronized <V> AccountAwareStorageBackend<V> create(String service, String file,
                                                                         TypeReference<Map<String, V>> type) {
                AccountAwareStorageBackend<V> backend = super.create(service, file, type);
                if (!FILE.equals(file)) {
                    return backend;
                }
                AccountAwareStorageBackend<V> guarded = spy(backend);
                doAnswer(invocation -> {
                    if (refuse.get()) {
                        throw new UncheckedIOException(new IOException("owned pre-batch refusal"));
                    }
                    return invocation.callRealMethod();
                }).when(guarded).applyAtomically(anyMap(), anySet());
                return guarded;
            }
        };
    }

    private ApiGatewayService service(StorageFactory factory, EmulatorConfig config) {
        return new ApiGatewayService(factory, config, mock(TlsCertificateManager.class), new RegionResolver(REGION, ACCOUNT));
    }
}

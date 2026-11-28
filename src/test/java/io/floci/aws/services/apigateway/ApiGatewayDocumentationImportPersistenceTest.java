package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.DocumentationPart;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.floci.aws.services.apigateway.ApiGatewayDocumentationImportSdkIntegrationTest.document;
import static io.floci.aws.services.apigateway.ApiGatewayDocumentationImportSdkIntegrationTest.part;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayDocumentationImportPersistenceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "131719232937";
    private static final String PARTS_FILE = "apigateway-documentation-parts.json";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void mixedOverwriteAndLaterCrudSurviveThreeStorageFactories(String mode) throws Exception {
        EmulatorConfig config = config();
        String api;
        String kept;
        String removed;
        String added;
        StorageFactory first = factory(config, mode);
        try {
            ApiGatewayService service = service(first, config);
            api = service.createRestApi(REGION, Map.of("name", "persisted-documentation")).getId();
            kept = service.createDocumentationPart(REGION, api, Map.of("location", Map.of("type", "API"),
                    "properties", "{\"summary\":\"Old\"}")).getId();
            removed = service.createDocumentationPart(REGION, api, Map.of("location", Map.of("type", "MODEL", "name", "Old"),
                    "properties", "{}")).getId();
            ApiGatewayService.DocumentationPartImportResult result = service.importDocumentationParts(REGION, api, "overwrite",
                    document(List.of(part(Map.of("type", "API"), Map.of("description", "Persisted", "tags", List.of("one", "two"))),
                            part(Map.of("type", "METHOD"), Map.of("nested", Map.of("typed", true, "number", 2))))), false);
            assertEquals(kept, result.ids().getFirst());
            added = result.ids().get(1);
            String rejected = document(List.of(part(Map.of("type", "API"), Map.of("description", "Rejected")),
                    part(Map.of("type", "INVALID"), Map.of())));
            assertThrows(AwsException.class, () -> service.importDocumentationParts(REGION, api, "overwrite", rejected, false));
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = factory(config, mode);
        try {
            ApiGatewayService service = service(second, config);
            assertEquals("Persisted", new ObjectMapper().readTree(service.getDocumentationPart(REGION, api, kept)
                    .getProperties()).path("description").asText());
            assertEquals(new ObjectMapper().readTree("{\"nested\":{\"typed\":true,\"number\":2}}"),
                    new ObjectMapper().readTree(service.getDocumentationPart(REGION, api, added).getProperties()));
            assertEquals(Map.of("type", "METHOD", "path", "/", "method", "*"),
                    service.getDocumentationPart(REGION, api, added).getLocation());
            assertEquals(404, assertThrows(AwsException.class, () -> service.getDocumentationPart(REGION, api, removed)).getHttpStatus());
            service.updateDocumentationPart(REGION, api, kept, List.of(Map.of("op", "replace", "path", "/properties", "value", "{\"summary\":\"Patched\"}")));
            service.deleteDocumentationPart(REGION, api, added);
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = factory(config, mode);
        try {
            ApiGatewayService service = service(third, config);
            assertEquals("{\"summary\":\"Patched\"}", service.getDocumentationPart(REGION, api, kept).getProperties());
            assertEquals(404, assertThrows(AwsException.class, () -> service.getDocumentationPart(REGION, api, added)).getHttpStatus());
            assertEquals(List.of(kept), service.getDocumentationParts(REGION, api, null, null, null, null, null, null)
                    .items().stream().map(DocumentationPart::getId).toList());
            service.deleteRestApi(REGION, api);
        } finally {
            third.shutdownAll();
        }
    }

    @Test
    void failedPersistentCommitLeavesOldValuesAndIdsIntact() throws Exception {
        EmulatorConfig config = config();
        StorageFactory factory = factory(config, "persistent");
        Path path = directory.resolve(PARTS_FILE);
        Path retained = directory.resolve("retained.json");
        try {
            ApiGatewayService service = service(factory, config);
            String api = service.createRestApi(REGION, Map.of("name", "failed-import")).getId();
            String id = service.createDocumentationPart(REGION, api, Map.of("location", Map.of("type", "API"), "properties", "{}"))
                    .getId();
            Files.move(path, retained);
            Files.createDirectory(path);
            Files.writeString(path.resolve("blocker"), "owned fixture");
            try {
                assertThrows(UncheckedIOException.class, () -> service.importDocumentationParts(REGION, api, "overwrite",
                        document(List.of(part(Map.of("type", "MODEL", "name", "Replacement"), Map.of("summary", "Rejected")))), false));
                assertEquals(List.of(id), service.getDocumentationParts(REGION, api, null, null, null, null, null, null)
                        .items().stream().map(DocumentationPart::getId).toList());
                assertEquals("{}", service.getDocumentationPart(REGION, api, id).getProperties());
            } finally {
                Files.delete(path.resolve("blocker"));
                Files.delete(path);
                Files.move(retained, path);
            }
        } finally {
            factory.shutdownAll();
        }
        StorageFactory reopened = factory(config, "persistent");
        try {
            ApiGatewayService service = service(reopened, config);
            String api = service.getRestApis(REGION).getFirst().getId();
            assertEquals("{}", service.getDocumentationParts(REGION, api, null, null, null, null, null, null)
                    .items().getFirst().getProperties());
        } finally {
            reopened.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "delete", "delete-api", "import"})
    void competingMutationWaitsUntilValidatedImportCommits(String operation) throws Exception {
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blockNext = new AtomicBoolean(true);
        InMemoryStorage<String, DocumentationPart> backing = new InMemoryStorage<>() {
            @Override
            public void applyAtomically(Map<String, DocumentationPart> entries, Set<String> keys) {
                if (blockNext.getAndSet(false)) {
                    committing.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
                super.applyAtomically(entries, keys);
            }
        };
        ApiGatewayService service = service(new InMemoryStorageFactory(ACCOUNT).withStore(PARTS_FILE, backing), config());
        String api = service.createRestApi(REGION, Map.of("name", "concurrent-import")).getId();
        String id = service.createDocumentationPart(REGION, api, Map.of("location", Map.of("type", "API"), "properties", "{}"))
                .getId();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> importing = workers.submit(() -> service.importDocumentationParts(REGION, api, "overwrite",
                    document(List.of(part(Map.of("type", "API"), Map.of("description", "Imported")))), false));
            try {
                assertTrue(committing.await(10, TimeUnit.SECONDS));
                CountDownLatch mutationStarted = new CountDownLatch(1);
                Future<?> mutation = workers.submit(() -> {
                    mutationStarted.countDown();
                    switch (operation) {
                        case "create" -> service.createDocumentationPart(REGION, api, Map.of("location", Map.of("type", "MODEL", "name", "Later"), "properties", "{}"));
                        case "update" -> service.updateDocumentationPart(REGION, api, id, List.of(Map.of("op", "replace", "path", "/properties", "value", "{\"summary\":\"Later\"}")));
                        case "delete" -> service.deleteDocumentationPart(REGION, api, id);
                        case "delete-api" -> service.deleteRestApi(REGION, api);
                        default -> service.importDocumentationParts(REGION, api, "merge",
                                document(List.of(part(Map.of("type", "API"), Map.of("summary", "Later")))), false);
                    }
                });
                assertTrue(mutationStarted.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> mutation.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                importing.get(10, TimeUnit.SECONDS);
                mutation.get(10, TimeUnit.SECONDS);
                switch (operation) {
                    case "create" -> assertEquals(2, service.getDocumentationParts(REGION, api, null, null, null, null, null, null).items().size());
                    case "update" -> assertEquals("{\"summary\":\"Later\"}", service.getDocumentationPart(REGION, api, id).getProperties());
                    case "delete" -> assertEquals(404, assertThrows(AwsException.class, () -> service.getDocumentationPart(REGION, api, id)).getHttpStatus());
                    case "delete-api" -> {
                        assertEquals(404, assertThrows(AwsException.class, () -> service.getRestApi(REGION, api)).getHttpStatus());
                        assertTrue(backing.keys().isEmpty());
                    }
                    default -> assertEquals(new ObjectMapper().readTree("{\"description\":\"Imported\",\"summary\":\"Later\"}"),
                            new ObjectMapper().readTree(service.getDocumentationPart(REGION, api, id).getProperties()));
                }
            } finally {
                release.countDown();
            }
        }
    }

    private EmulatorConfig config() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.defaultRegion()).thenReturn(REGION);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(Long.MAX_VALUE);
        return config;
    }

    private StorageFactory factory(EmulatorConfig config, String mode) {
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(Long.MAX_VALUE);
        return new StorageFactory(config, access);
    }

    private ApiGatewayService service(StorageFactory factory, EmulatorConfig config) {
        return new ApiGatewayService(factory, config, mock(TlsCertificateManager.class), new RegionResolver(REGION, ACCOUNT));
    }
}

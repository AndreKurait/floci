package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.apigateway.model.DocumentationVersion;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayDocumentationVersionStateTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "131719232937";
    private static final String REGION = "us-east-1";
    private static final String FILE = ApiGatewayStageDocumentationStore.FILE;

    @Test
    void detachedStageReadsDoNotMutateRowsAndInternalSnapshotsNeverBecomeStages() throws Exception {
        InMemoryStorage<String, JsonNode> rows = new InMemoryStorage<>();
        ApiGatewayStageDocumentationStore store = new ApiGatewayStageDocumentationStore(
                new InMemoryStorageFactory(ACCOUNT).withStore(FILE, rows));
        String key = REGION + "::api::live";
        Stage original = new Stage();
        original.setStageName("live");
        original.setDeploymentId("deployment");
        original.getTags().put("owned", "original");
        store.put(key, original);
        JsonNode stored = rows.get(ACCOUNT + "/" + key).orElseThrow().deepCopy();
        Stage read = store.get(key).orElseThrow();
        read.getTags().put("owned", "changed alias");
        read.setDescription("changed alias");
        assertEquals(stored, rows.get(ACCOUNT + "/" + key).orElseThrow());
        assertEquals("original", store.get(key).orElseThrow().getTags().get("owned"));
        DocumentationVersion snapshot = new DocumentationVersion("v1", "description", 123, ACCOUNT, REGION, "api",
                List.of(new DocumentationVersion.Part(Map.of("type", "API"), "{}")));
        store.publish(snapshot, null, null);
        assertEquals(Set.of(key), store.keys());
        assertEquals(1, store.scan(ignored -> true).size());
        String internal = rows.keys().stream().filter(value -> !value.equals(ACCOUNT + "/" + key)).findFirst().orElseThrow();
        JsonNode versionRow = rows.get(internal).orElseThrow().deepCopy();
        rows.put(ACCOUNT + "/" + key, versionRow);
        assertTrue(store.get(key).isEmpty());
        assertTrue(store.keys().isEmpty());
        assertEquals("v1", store.version(REGION, "api", "v1").orElseThrow().version());
        assertFalse(JSON.writeValueAsString(original).contains("documentationVersion"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusedPublicationLeavesNoVersionOrPinAndLegacyOnlyMovesUnchangedRepresentation(boolean legacy) {
        AtomicBoolean refuse = new AtomicBoolean();
        InMemoryStorage<String, JsonNode> rows = new InMemoryStorage<>() {
            @Override
            public void applyAtomically(Map<String, JsonNode> entries, Set<String> keys) {
                if (refuse.get()) {
                    throw new UncheckedIOException(new IOException("owned refusal"));
                }
                super.applyAtomically(entries, keys);
            }
        };
        ApiGatewayService service = service(rows);
        String api = service.createRestApi(REGION, Map.of("name", "atomic-documentation")).getId();
        service.createDocumentationPart(REGION, api, Map.of("location", Map.of("type", "API"), "properties", "{}"));
        service.createStage(REGION, api, Map.of("stageName", "live", "deploymentId", "deployment", "description", "Old"));
        String key = REGION + "::" + api + "::live";
        String scoped = ACCOUNT + "/" + key;
        JsonNode original = rows.get(scoped).orElseThrow().deepCopy();
        if (legacy) {
            rows.put(key, original.deepCopy());
            rows.delete(scoped);
        }
        Set<String> beforeKeys = Set.copyOf(rows.keys());
        assertNull(service.getStage(REGION, api, "live").getDocumentationVersion());
        assertEquals(beforeKeys, rows.keys());
        refuse.set(true);
        assertThrows(UncheckedIOException.class, () -> service.createDocumentationVersion(
                REGION, api, Map.of("documentationVersion", "v1", "stageName", "live")));
        assertEquals(Set.of(scoped), rows.keys());
        assertEquals(original, rows.get(scoped).orElseThrow());
        assertNull(service.getStage(REGION, api, "live").getDocumentationVersion());
        assertTrue(service.getDocumentationVersions(REGION, api, null, null).items().isEmpty());
        refuse.set(false);
        service.createDocumentationVersion(REGION, api, Map.of("documentationVersion", "v1", "stageName", "live"));
        assertEquals("v1", service.getStage(REGION, api, "live").getDocumentationVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"part-update", "stage-update", "stage-tag", "stage-untag", "deployment",
            "delete-stage", "delete-api", "parent-update", "parent-tag", "parent-untag"})
    void everyCompetingWriterWaitsForAtomicSnapshotPublication(String operation) throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean();
        InMemoryStorage<String, JsonNode> rows = new InMemoryStorage<>() {
            @Override
            public void applyAtomically(Map<String, JsonNode> entries, Set<String> keys) {
                if (block.getAndSet(false)) {
                    publishing.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                super.applyAtomically(entries, keys);
            }
        };
        ApiGatewayService service = service(rows);
        String api = service.createRestApi(REGION, Map.of("name", "concurrent-documentation")).getId();
        String part = service.createDocumentationPart(REGION, api,
                Map.of("location", Map.of("type", "API"), "properties", "{\"description\":\"Captured\"}")).getId();
        service.createStage(REGION, api, Map.of("stageName", "live", "deploymentId", "initial", "description", "Original"));
        block.set(true);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> publication = workers.submit(() -> service.createDocumentationVersion(
                    REGION, api, Map.of("documentationVersion", "v1", "stageName", "live")));
            try {
                assertTrue(publishing.await(10, TimeUnit.SECONDS));
                CountDownLatch started = new CountDownLatch(1);
                Future<?> mutation = workers.submit(() -> {
                    started.countDown();
                    switch (operation) {
                        case "part-update" -> service.updateDocumentationPart(REGION, api, part,
                                List.of(Map.of("op", "replace", "path", "/properties", "value", "{\"description\":\"Later\"}")));
                        case "stage-update" -> service.updateStage(REGION, api, "live",
                                List.of(Map.of("op", "replace", "path", "/description", "value", "Later")));
                        case "stage-tag" -> service.tagStage(REGION, api, "live", Map.of("later", "tag"));
                        case "stage-untag" -> service.untagStage(REGION, api, "live", List.of("later"));
                        case "deployment" -> service.createDeployment(REGION, api, Map.of("stageName", "live"));
                        case "delete-stage" -> service.deleteStage(REGION, api, "live");
                        case "delete-api" -> service.deleteRestApi(REGION, api);
                        case "parent-update" -> service.updateRestApi(REGION, api,
                                List.of(Map.of("op", "replace", "path", "/description", "value", "Later")));
                        case "parent-tag" -> service.tagResource(REGION, api, Map.of("later", "tag"));
                        default -> service.untagResource(REGION, api, List.of("later"));
                    }
                });
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> mutation.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                publication.get(10, TimeUnit.SECONDS);
                mutation.get(10, TimeUnit.SECONDS);
                if ("delete-api".equals(operation)) {
                    assertTrue(rows.keys().isEmpty());
                    assertEquals(404, assertThrows(AwsException.class,
                            () -> service.getDocumentationVersion(REGION, api, "v1")).getHttpStatus());
                } else {
                    assertEquals("{\"description\":\"Captured\"}", service.getDocumentationVersion(REGION, api, "v1")
                            .parts().getFirst().properties());
                    if (!"delete-stage".equals(operation)) {
                        assertEquals("v1", service.getStage(REGION, api, "live").getDocumentationVersion());
                    }
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rejectedPatchesKeepFullStageAndImmutableVersionThenUnpinnedDeleteWorks() {
        ApiGatewayService service = service(new InMemoryStorage<>());
        String api = service.createRestApi(REGION, Map.of("name", "patch-documentation")).getId();
        service.createDocumentationVersion(REGION, api, Map.of("documentationVersion", "v1", "description", "Original"));
        service.createStage(REGION, api, Map.of("stageName", "live", "deploymentId", "deployment", "documentationVersion", "v1"));
        JsonNode stage = JSON.valueToTree(service.getStage(REGION, api, "live"));
        DocumentationVersion version = service.getDocumentationVersion(REGION, api, "v1");
        for (String op : List.of("add", "remove", "copy", "move", "test")) {
            assertEquals(400, assertThrows(AwsException.class, () -> service.updateStage(REGION, api, "live",
                    List.of(Map.of("op", "replace", "path", "/description", "value", "Must not commit"),
                            Map.of("op", op, "path", "/documentationVersion", "value", "v1")))).getHttpStatus());
            assertEquals(stage, JSON.valueToTree(service.getStage(REGION, api, "live")));
            assertEquals(400, assertThrows(AwsException.class, () -> service.updateDocumentationVersion(REGION, api, "v1",
                    List.of(Map.of("op", op, "path", "/description", "value", "Must not commit")))).getHttpStatus());
            assertEquals(version, service.getDocumentationVersion(REGION, api, "v1"));
        }
        assertEquals(409, assertThrows(AwsException.class,
                () -> service.deleteDocumentationVersion(REGION, api, "v1")).getHttpStatus());
        service.deleteStage(REGION, api, "live");
        service.deleteDocumentationVersion(REGION, api, "v1");
        assertTrue(service.getDocumentationVersions(REGION, api, null, null).items().isEmpty());
    }

    private static ApiGatewayService service(InMemoryStorage<String, JsonNode> rows) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.defaultRegion()).thenReturn(REGION);
        return new ApiGatewayService(new InMemoryStorageFactory(ACCOUNT).withStore(FILE, rows), config,
                mock(TlsCertificateManager.class), new RegionResolver(REGION, ACCOUNT));
    }
}

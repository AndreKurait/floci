package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ecr.model.Image;
import io.github.hectorvent.floci.services.ecr.model.ImageMetadata;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

final class EcrLifecyclePreviewSupport implements AutoCloseable {

    static final String ACCOUNT = "000000000000";
    static final String REGION = "us-east-1";
    static final String NAME = "preview-unit";
    static final String KEY = REGION + "::" + ACCOUNT + "::" + NAME;
    static final String PHYSICAL = ACCOUNT + "/" + REGION + "/" + NAME;
    static final ObjectMapper JSON = new ObjectMapper();
    final EcrLifecyclePreviewRegistry registry;
    final EcrRegistryManager manager;
    final AccountAwareStorageBackend<Repository> repositories;
    final AccountAwareStorageBackend<ImageMetadata> metadata;
    final EcrService service;

    EcrLifecyclePreviewSupport(StorageBackend<String, Repository> rawRepositories,
                              StorageBackend<String, ImageMetadata> rawMetadata) throws Exception {
        registry = new EcrLifecyclePreviewRegistry();
        manager = mock(EcrRegistryManager.class);
        when(manager.tryEnsureStarted()).thenReturn(true);
        when(manager.httpClient()).thenReturn(new RegistryHttpClient(registry.endpoint()));
        when(manager.internalRepoName(anyString(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1) + "/" + call.getArgument(2));
        when(manager.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenAnswer(call -> registry.endpoint() + "/" + call.getArgument(0)
                        + "/" + call.getArgument(1) + "/" + call.getArgument(2));
        doAnswer(call -> {
            registry.delete(call.getArgument(0));
            return null;
        }).when(manager).deleteRepositoryStorageByInternalName(anyString());
        repositories = new AccountAwareStorageBackend<>(rawRepositories, null, ACCOUNT);
        metadata = new AccountAwareStorageBackend<>(rawMetadata, null, ACCOUNT);
        service = new EcrService(repositories, metadata, new InMemoryStorage<>(), manager,
                mock(EmulatorConfig.class), new RegionResolver(REGION, ACCOUNT));
    }

    Repository create() {
        return service.createRepository(NAME, null, null, null, null, null, Map.of("team", "blue", "empty", ""), REGION);
    }

    EcrLifecyclePreviews.View start(String policy) {
        return service.startLifecyclePolicyPreview(NAME, null, policy, REGION);
    }

    EcrLifecyclePreviews.View get() {
        return service.getLifecyclePolicyPreview(NAME, null, List.of(), null, null, null, REGION);
    }

    String manifest(String label) throws Exception {
        byte[] tar = new byte[1024];
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(tar);
        }
        byte[] layer = compressed.toByteArray();
        byte[] config = JSON.writeValueAsBytes(Map.of("architecture", "amd64", "os", "linux",
                "config", Map.of("Labels", Map.of("fixture", label)),
                "rootfs", Map.of("type", "layers", "diff_ids", List.of(EcrLifecyclePreviewRegistry.digest(tar)))));
        registry.seedBlob(PHYSICAL, config);
        registry.seedBlob(PHYSICAL, layer);
        return JSON.writeValueAsString(Map.of("schemaVersion", 2, "mediaType", EcrLifecyclePreviewRegistry.OCI,
                "config", Map.of("mediaType", "application/vnd.oci.image.config.v1+json", "size", config.length,
                        "digest", EcrLifecyclePreviewRegistry.digest(config)),
                "layers", List.of(Map.of("mediaType", "application/vnd.oci.image.layer.v1.tar+gzip", "size", layer.length,
                        "digest", EcrLifecyclePreviewRegistry.digest(layer)))));
    }

    Image push(String label, String tag) throws Exception {
        return service.putImage(NAME, manifest(label), EcrLifecyclePreviewRegistry.OCI, tag, null, null, REGION);
    }

    static String digest(String manifest) {
        return EcrLifecyclePreviewRegistry.digest(manifest.getBytes(StandardCharsets.UTF_8));
    }

    static String countPolicy() throws Exception {
        return EcrLifecyclePreviewIntegrationTest.policy("any", null, null, "imageCountMoreThan", 1);
    }

    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Bounded fixture interleaving did not arrive");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Override
    public void close() {
        registry.close();
        assertTrue(registry.failures.isEmpty(), registry.failures.toString());
    }

    static final class FaultStore<T> extends InMemoryStorage<String, T> {
        boolean failPut;
        boolean failDelete;

        @Override
        public synchronized void putAllAtomically(Map<String, T> entries) {
            if (failPut) {
                throw new IllegalStateException("Injected atomic put refusal");
            }
            super.putAllAtomically(entries);
        }

        @Override
        public synchronized void deleteAllAtomically(Set<String> keys) {
            if (failDelete) {
                throw new IllegalStateException("Injected atomic delete refusal");
            }
            super.deleteAllAtomically(keys);
        }
    }
}

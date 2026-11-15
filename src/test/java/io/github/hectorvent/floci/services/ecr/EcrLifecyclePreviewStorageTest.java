package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.ecr.model.ImageMetadata;
import io.github.hectorvent.floci.services.ecr.model.LifecyclePolicyPreview;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static io.github.hectorvent.floci.services.ecr.EcrLifecyclePreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EcrLifecyclePreviewStorageTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void completedImmutableSnapshotReloadsWithoutStartingOrReadingTheRegistry(String mode) throws Exception {
        StorageBackend<String, Repository> writer = store(mode);
        writer.load();
        LifecyclePolicyPreview expected;
        try (EcrLifecyclePreviewSupport fixture = new EcrLifecyclePreviewSupport(writer, new InMemoryStorage<>())) {
            fixture.create();
            fixture.push("old", "prod-v1");
            fixture.push("new", "prod-v2");
            fixture.start(countPolicy());
            expected = fixture.repositories.get(KEY).orElseThrow().getLifecyclePolicyPreview();
            writer.flush();
        } finally {
            shutdown(writer);
        }
        StorageBackend<String, Repository> reader = store(mode);
        reader.load();
        try (EcrLifecyclePreviewSupport fixture = new EcrLifecyclePreviewSupport(reader, new InMemoryStorage<>())) {
            when(fixture.manager.httpClient()).thenReturn(null);
            assertEquals(expected, fixture.repositories.get(KEY).orElseThrow().getLifecyclePolicyPreview());
            assertEquals(expected.policyText(), fixture.get().policy());
            assertEquals(expected.results(), fixture.get().results());
            assertEquals("COMPLETE", fixture.get().status());
            verify(fixture.manager, never()).tryEnsureStarted();
            assertTrue(fixture.registry.traffic.isEmpty());
        } finally {
            shutdown(reader);
        }
    }

    @Test
    void failedInventoryLeavesAValidDefaultLegacyRowAndItsRawKeysUntouched() throws Exception {
        InMemoryStorage<String, Repository> rows = new InMemoryStorage<>();
        InMemoryStorage<String, ImageMetadata> images = new InMemoryStorage<>();
        try (EcrLifecyclePreviewSupport fixture = new EcrLifecyclePreviewSupport(rows, images)) {
            Repository created = fixture.create();
            fixture.push("old-native-generation", "prod");
            Repository legacy = new Repository(created);
            legacy.setRepositoryGeneration(null);
            rows.put(KEY, legacy);
            rows.delete(ACCOUNT + "/" + KEY);
            Set<String> before = Set.copyOf(rows.keys());
            Map<String, ImageMetadata> imageRows = fixture.metadata.scanAllAccountsWithRawKeys();
            assertEquals("ServerException", assertThrows(AwsException.class,
                    () -> fixture.start(countPolicy())).getErrorCode());
            assertEquals(before, rows.keys());
            assertSame(legacy, rows.get(KEY).orElseThrow());
            assertNull(legacy.getRepositoryGeneration());
            assertNull(legacy.getLifecyclePolicyPreview());
            assertEquals(imageRows, fixture.metadata.scanAllAccountsWithRawKeys());
        }
    }

    @Test
    void authorizedLegacyMigrationCanPrecedeARefusedAtomicPreviewReplacement() throws Exception {
        FaultStore<Repository> rows = new FaultStore<>();
        try (EcrLifecyclePreviewSupport fixture = new EcrLifecyclePreviewSupport(rows, new InMemoryStorage<>())) {
            Repository legacy = new Repository(fixture.create());
            legacy.setRepositoryGeneration(null);
            legacy.setLifecyclePolicyText(countPolicy());
            rows.put(KEY, legacy);
            rows.delete(ACCOUNT + "/" + KEY);
            rows.failPut = true;
            assertThrows(IllegalStateException.class, () -> fixture.start(null));
            assertFalse(rows.keys().contains(KEY), "Successful authorized migration is a separate earlier write");
            assertSame(legacy, rows.get(ACCOUNT + "/" + KEY).orElseThrow());
            assertNull(legacy.getRepositoryGeneration());
            assertNull(legacy.getLifecyclePolicyPreview());
            assertEquals(countPolicy(), legacy.getLifecyclePolicyText());
        }
    }

    @Test
    void legacyReadOnlyTagLookupDoesNotInitializeOrMigrateTheRepository() throws Exception {
        InMemoryStorage<String, Repository> rows = new InMemoryStorage<>();
        try (EcrLifecyclePreviewSupport fixture = new EcrLifecyclePreviewSupport(rows, new InMemoryStorage<>())) {
            Repository legacy = new Repository(fixture.create());
            legacy.setRepositoryGeneration(null);
            rows.put(KEY, legacy);
            rows.delete(ACCOUNT + "/" + KEY);
            Set<String> before = Set.copyOf(rows.keys());
            assertEquals(Map.of("team", "blue", "empty", ""), fixture.service.lifecyclePreviewTags(NAME, null, REGION));
            assertEquals(before, rows.keys());
            assertSame(legacy, rows.get(KEY).orElseThrow());
            assertNull(legacy.getRepositoryGeneration());
            assertTrue(fixture.registry.traffic.isEmpty());
        }
    }

    private StorageBackend<String, Repository> store(String mode) {
        TypeReference<Map<String, Repository>> type = new TypeReference<>() {};
        return switch (mode) {
            case "persistent" -> new PersistentStorage<>(directory.resolve("repositories.json"), type);
            case "hybrid" -> new HybridStorage<>(directory.resolve("repositories.json"), type, 60_000);
            case "wal" -> new WalStorage<>(directory.resolve("repositories.json"), directory.resolve("repositories.wal"),
                    type, 60_000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void shutdown(StorageBackend<?, ?> store) {
        if (store instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        } else if (store instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        }
    }
}

package io.floci.aws.services.memorydb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.HybridStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.memorydb.model.MemoryDbParameterGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.memorydb.MemoryDbParameterGroupTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryDbParameterGroupPersistenceServiceTest {
    private static final TypeReference<Map<String, MemoryDbParameterGroup>> TYPE = new TypeReference<>() {};

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void reloadPreservesCatalogGenerationOverridesTagsAndDefaultOverlay(String mode, @TempDir Path directory) {
        StorageBackend<String, MemoryDbParameterGroup> original = backend(mode, directory);
        MemoryDbParameterGroup stored;
        String token;
        try {
            original.load();
            MemoryDbParameterGroupTestFixture fixture = new MemoryDbParameterGroupTestFixture(original);
            fixture.create("durable");
            stored = fixture.service.update("durable", update("timeout", "20"), REGION);
            MemoryDbParameterGroup defaults = fixture.current("default.memorydb-redis7.search");
            fixture.service.tag(defaults.arn(), Map.of("source", "overlay"), REGION);
            token = fixture.service.parameters("durable", 20, null, REGION).nextToken();
            original.flush();
        } finally {
            stop(original);
        }
        StorageBackend<String, MemoryDbParameterGroup> loaded = backend(mode, directory);
        try {
            loaded.load();
            MemoryDbParameterGroupTestFixture fixture = new MemoryDbParameterGroupTestFixture(loaded);
            assertEquals(stored, fixture.current("durable"));
            assertEquals("20", fixture.current("durable").overrides().get("timeout"));
            MemoryDbParameterGroup defaults = fixture.current("default.memorydb-redis7.search");
            assertEquals(Map.of("source", "overlay"), defaults.tags());
            assertEquals("yes", fixture.service.parameters(defaults.name(), null, null, REGION).values().stream()
                    .filter(row -> row.get("Name").equals("search-enabled")).findFirst().orElseThrow().get("Value"));
            error("InvalidParameterValueException", () -> fixture.service.parameters("durable", 20, token, REGION));
            fixture.service.delete("durable", REGION);
            MemoryDbParameterGroup next = fixture.create("durable");
            assertNotEquals(stored.generation(), next.generation());
            assertTrue(next.overrides().isEmpty());
            loaded.flush();
        } finally {
            stop(loaded);
        }
    }

    @Test
    void rejectedAtomicPublicationsPreserveLiveObjectAndDurableBytes(@TempDir Path directory) throws Exception {
        Path file = directory.resolve(FILE);
        RejectingStorage storage = new RejectingStorage(file);
        MemoryDbParameterGroupTestFixture fixture = new MemoryDbParameterGroupTestFixture(storage);
        MemoryDbParameterGroup before = fixture.create("retained");
        byte[] bytes = Files.readAllBytes(file);
        storage.reject = true;
        assertThrows(IllegalStateException.class,
                () -> fixture.service.update("retained", update("timeout", "20"), REGION));
        assertThrows(IllegalStateException.class,
                () -> fixture.service.tag(before.arn(), Map.of("team", "changed"), REGION));
        assertThrows(IllegalStateException.class, () -> fixture.service.delete("retained", REGION));
        assertThrows(IllegalStateException.class, () -> fixture.create("not-created"));
        assertSame(before, fixture.current("retained"));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(Set.of(ACCOUNT + "/" + REGION + ":retained"), storage.keys());
        storage.reject = false;
        fixture.service.update("retained", update("timeout", "20"), REGION);
        assertNotEquals(before, fixture.current("retained"));
        assertFalse(Arrays.equals(bytes, Files.readAllBytes(file)));
    }

    @Test
    void unknownStoredCatalogIsRefusedWithoutRewrite(@TempDir Path directory) throws Exception {
        Path file = directory.resolve(FILE);
        PersistentStorage<String, MemoryDbParameterGroup> original = new PersistentStorage<String, MemoryDbParameterGroup>(file, TYPE);
        MemoryDbParameterGroupTestFixture fixture = new MemoryDbParameterGroupTestFixture(original);
        MemoryDbParameterGroup known = fixture.create("unknown");
        MemoryDbParameterGroup unsupported = new MemoryDbParameterGroup(known.accountId(), known.region(), known.partition(), known.name(),
                known.arn(), known.family(), known.description(), known.generation(), known.revision(),
                "unavailable-catalog-v99", known.overrides(), known.tags());
        original.put(ACCOUNT + "/" + REGION + ":unknown", unsupported);
        byte[] before = Files.readAllBytes(file);
        PersistentStorage<String, MemoryDbParameterGroup> loaded = new PersistentStorage<String, MemoryDbParameterGroup>(file, TYPE);
        loaded.load();
        MemoryDbParameterGroupTestFixture reader = new MemoryDbParameterGroupTestFixture(loaded);
        error("InvalidParameterGroupStateFault", () -> reader.current("unknown"));
        error("InvalidParameterGroupStateFault",
                () -> reader.service.iamTags(known.arn(), known.partition(), REGION, ACCOUNT));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(unsupported, loaded.get(ACCOUNT + "/" + REGION + ":unknown").orElseThrow());
    }

    private static StorageBackend<String, MemoryDbParameterGroup> backend(String mode, Path directory) {
        return switch (mode) {
            case "persistent" -> new PersistentStorage<>(directory.resolve(FILE), TYPE);
            case "hybrid" -> new HybridStorage<>(directory.resolve(FILE), TYPE, 600_000);
            case "wal" -> new WalStorage<>(directory.resolve(FILE), directory.resolve("groups.wal"), TYPE, 600_000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void stop(StorageBackend<String, MemoryDbParameterGroup> storage) {
        if (storage instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        } else if (storage instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        }
    }

    private static final class RejectingStorage extends PersistentStorage<String, MemoryDbParameterGroup> {
        private boolean reject;

        private RejectingStorage(Path file) {
            super(file, TYPE);
        }

        @Override
        public synchronized void putAllAtomically(Map<String, MemoryDbParameterGroup> values) {
            if (reject) {
                throw new IllegalStateException("injected publication refusal");
            }
            super.putAllAtomically(values);
        }

        @Override
        public synchronized void deleteAllAtomically(Set<String> keys) {
            if (reject) {
                throw new IllegalStateException("injected deletion refusal");
            }
            super.deleteAllAtomically(keys);
        }
    }
}

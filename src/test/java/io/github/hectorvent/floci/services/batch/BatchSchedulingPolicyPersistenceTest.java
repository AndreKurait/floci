package io.github.hectorvent.floci.services.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.batch.model.StoredSchedulingPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.hectorvent.floci.services.batch.BatchSchedulingPolicyTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchSchedulingPolicyPersistenceTest {
    private static final TypeReference<Map<String, StoredSchedulingPolicy>> TYPE = new TypeReference<>() {};

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void reloadKeepsCompleteRowsAndRestartInvalidatesOldTokens(String mode, @TempDir Path directory) {
        StorageBackend<String, StoredSchedulingPolicy> original = backend(mode, directory);
        StoredSchedulingPolicy stored;
        String token;
        try {
            original.load();
            BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture(original);
            fixture.create("a");
            fixture.create("b");
            fixture.service.update(fixture.update("a", 40));
            fixture.service.tag(fixture.arn("a"), Map.of("new", "tag"));
            stored = fixture.stored("a");
            token = (String) fixture.service.list(json(Map.of("maxResults", 1))).get("nextToken");
            original.flush();
        } finally {
            stop(original);
        }
        StorageBackend<String, StoredSchedulingPolicy> loaded = backend(mode, directory);
        try {
            loaded.load();
            BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture(loaded);
            assertEquals(stored, fixture.stored("a"));
            assertEquals(stored.tags(), fixture.service.listTags(stored.arn()));
            assertThrows(AwsException.class, () -> fixture.service.list(json(Map.of("maxResults", 1, "nextToken", token))));
            fixture.service.delete(fixture.select("a"));
            StoredSchedulingPolicy next = fixture.create("a");
            assertNotEquals(stored.generation(), next.generation());
            assertEquals(0, next.revision());
            loaded.flush();
        } finally {
            stop(loaded);
        }
    }

    @Test
    void prepublicationBackendRefusalKeepsFullLiveRecordAndDurableBytes(@TempDir Path directory) throws Exception {
        Path file = directory.resolve(FILE);
        RejectingStorage storage = new RejectingStorage(file);
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture(storage);
        StoredSchedulingPolicy before = fixture.create("held");
        byte[] bytes = Files.readAllBytes(file);
        storage.reject = true;
        assertThrows(IllegalStateException.class, () -> fixture.service.update(fixture.update("held", 90)));
        assertThrows(IllegalStateException.class, () -> fixture.service.tag(before.arn(), Map.of("team", "red")));
        assertThrows(IllegalStateException.class, () -> fixture.service.untag(before.arn(), List.of("team")));
        assertThrows(IllegalStateException.class, () -> fixture.service.delete(fixture.select("held")));
        assertThrows(IllegalStateException.class, () -> fixture.create("absent"));
        assertSame(before, fixture.stored("held"));
        assertEquals(Set.of(fixture.key("held")), storage.keys());
        assertArrayEquals(bytes, Files.readAllBytes(file));
        storage.reject = false;
        fixture.service.update(fixture.update("held", 90));
        assertEquals(90, fixture.stored("held").fairshare().shareDecaySeconds());
    }

    private static StorageBackend<String, StoredSchedulingPolicy> backend(String mode, Path directory) {
        return switch (mode) {
            case "persistent" -> new PersistentStorage<>(directory.resolve(FILE), TYPE);
            case "hybrid" -> new HybridStorage<>(directory.resolve(FILE), TYPE, 600_000);
            case "wal" -> new WalStorage<>(directory.resolve(FILE), directory.resolve("policies.wal"), TYPE, 600_000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void stop(StorageBackend<String, StoredSchedulingPolicy> storage) {
        if (storage instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        } else if (storage instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        }
    }

    private static final class RejectingStorage extends PersistentStorage<String, StoredSchedulingPolicy> {
        private boolean reject;

        private RejectingStorage(Path file) {
            super(file, TYPE);
        }

        @Override
        public synchronized void putAllAtomically(Map<String, StoredSchedulingPolicy> values) {
            if (reject) {
                throw new IllegalStateException("injected refusal before backend publication");
            }
            super.putAllAtomically(values);
        }

        @Override
        public synchronized void deleteAllAtomically(Set<String> keys) {
            if (reject) {
                throw new IllegalStateException("injected refusal before backend deletion");
            }
            super.deleteAllAtomically(keys);
        }
    }
}

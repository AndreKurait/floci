package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionalPersistenceTest {

    enum Mode {
        MEMORY, PERSISTENT, HYBRID, WAL
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void persistsCurrentMutationAfterAnEarlierFlush(Mode mode, @TempDir Path dir) {
        StorageBackend<String, Map<String, String>> backend = create(mode, dir);
        try {
            Map<String, String> value = new HashMap<>(Map.of("state", "pending"));
            backend.put("instance", value);
            backend.flush();
            value.put("state", "running");
            assertTrue(backend.persistIfCurrent("instance", value));
            backend.flush();
            if (mode != Mode.MEMORY) {
                if (backend instanceof WalStorage<String, Map<String, String>> wal) {
                    wal.shutdown();
                }
                backend.load();
            }
            assertEquals("running", backend.get("instance").orElseThrow().get("state"));
        } finally {
            close(backend);
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void doesNotReinsertDeletedOrClearedRecords(Mode mode, @TempDir Path dir) {
        StorageBackend<String, Map<String, String>> backend = create(mode, dir);
        try {
            Map<String, String> value = new HashMap<>(Map.of("state", "running"));
            backend.put("deleted", value);
            backend.delete("deleted");
            assertFalse(backend.persistIfCurrent("deleted", value));
            backend.put("cleared", value);
            backend.clear();
            assertFalse(backend.persistIfCurrent("cleared", value));
            backend.flush();
            if (mode != Mode.MEMORY) {
                if (backend instanceof WalStorage<String, Map<String, String>> wal) {
                    wal.shutdown();
                }
                backend.load();
            }
            assertTrue(backend.isEmpty());
        } finally {
            close(backend);
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void doesNotPersistAnEqualButReplacedObject(Mode mode, @TempDir Path dir) {
        StorageBackend<String, Map<String, String>> backend = create(mode, dir);
        try {
            Map<String, String> previous = new HashMap<>(Map.of("state", "pending"));
            Map<String, String> replacement = new HashMap<>(previous);
            backend.put("instance", previous);
            backend.put("instance", replacement);
            assertFalse(backend.persistIfCurrent("instance", previous));
            previous.put("state", "running");
            backend.flush();
            if (mode != Mode.MEMORY) {
                if (backend instanceof WalStorage<String, Map<String, String>> wal) {
                    wal.shutdown();
                }
                backend.load();
            }
            assertEquals("pending", backend.get("instance").orElseThrow().get("state"));
            assertFalse(backend.persistIfCurrent("missing", null));
        } finally {
            close(backend);
        }
    }

    @Test
    void accountWrapperChecksThePrefixedStoredIdentity(@TempDir Path dir) {
        StorageBackend<String, Map<String, String>> delegate = create(Mode.PERSISTENT, dir);
        AccountAwareStorageBackend<Map<String, String>> backend =
                new AccountAwareStorageBackend<>(delegate, null, "111111111111");
        Map<String, String> value = new HashMap<>(Map.of("state", "pending"));
        backend.put("instance", value);
        delegate.put("222222222222/instance", new HashMap<>(value));
        value.put("state", "running");
        assertTrue(backend.persistIfCurrent("instance", value));
        delegate.load();
        assertEquals("running", delegate.get("111111111111/instance").orElseThrow().get("state"));
        assertEquals("pending", delegate.get("222222222222/instance").orElseThrow().get("state"));
        assertFalse(backend.persistIfCurrent("instance", value));
    }

    @Test
    void explicitAccountPersistenceRetainsTheForeignOwner(@TempDir Path dir) {
        StorageBackend<String, Map<String, String>> delegate = create(Mode.PERSISTENT, dir);
        AccountAwareStorageBackend<Map<String, String>> backend =
                new AccountAwareStorageBackend<>(delegate, null, "111111111111");
        Map<String, String> value = new HashMap<>(Map.of("state", "pending"));
        backend.putForAccount("222222222222", "instance", value);
        value.put("state", "running");
        assertTrue(backend.persistIfCurrentForAccount("222222222222", "instance", value));
        assertFalse(backend.persistIfCurrent("instance", value));
        delegate.load();
        assertEquals("running", delegate.get("222222222222/instance").orElseThrow().get("state"));
        assertTrue(delegate.get("111111111111/instance").isEmpty());
    }

    private StorageBackend<String, Map<String, String>> create(Mode mode, Path dir) {
        TypeReference<Map<String, Map<String, String>>> type = new TypeReference<>() {};
        StorageBackend<String, Map<String, String>> backend = switch (mode) {
            case MEMORY -> new InMemoryStorage<>();
            case PERSISTENT -> new PersistentStorage<>(dir.resolve("state.json"), type);
            case HYBRID -> new HybridStorage<>(dir.resolve("state.json"), type, 60_000);
            case WAL -> new WalStorage<>(dir.resolve("state.json"), dir.resolve("state.wal"), type, 60_000);
        };
        backend.load();
        return backend;
    }

    private void close(StorageBackend<String, Map<String, String>> backend) {
        if (backend instanceof HybridStorage<String, Map<String, String>> hybrid) {
            hybrid.shutdown();
        } else if (backend instanceof WalStorage<String, Map<String, String>> wal) {
            wal.shutdown();
        }
    }
}

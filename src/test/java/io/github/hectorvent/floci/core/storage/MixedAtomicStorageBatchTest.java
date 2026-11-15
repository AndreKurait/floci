package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixedAtomicStorageBatchTest {
    private static final TypeReference<Map<String, Integer>> TYPE = new TypeReference<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void oneAccountReplacesAndDeletesWithoutChangingAnotherAccount(String mode) {
        StorageBackend<String, Integer> backend = backend(mode);
        backend.load();
        Map<String, Integer> expected = Map.of("111111111111/a", 8, "111111111111/new", 9,
                "222222222222/a", 3, "222222222222/remove", 4, "333333333333/unique", 5);
        try {
            AccountAwareStorageBackend<Integer> accounts = new AccountAwareStorageBackend<>(backend, null, "111111111111");
            accounts.putAllAtomically(Map.of("a", 1, "remove", 2));
            accounts.putAllAtomicallyForAccount("222222222222", Map.of("a", 3, "remove", 4));
            assertTrue(accounts.findAnyAccount("missing").isEmpty());
            accounts.applyAtomically(Map.of("a", 8, "new", 9, "remove", 99), Set.of("remove"));
            accounts.applyAtomicallyForAccount("333333333333", Map.of("unique", 5), Set.of("absent"));
            assertEquals("333333333333", accounts.findAnyAccountEntry("unique").orElseThrow().account());
            assertEquals(expected.keySet(), backend.keys());
            expected.forEach((key, value) -> assertEquals(value, backend.get(key).orElseThrow()));
            backend.flush();
        } finally {
            close(backend);
        }
        if (!"memory".equals(mode)) {
            StorageBackend<String, Integer> reopened = backend(mode);
            reopened.load();
            try {
                assertEquals(expected.keySet(), reopened.keys());
                expected.forEach((key, value) -> assertEquals(value, reopened.get(key).orElseThrow()));
            } finally {
                close(reopened);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void scansSeeOnlyOneSideOfAReplacement(String mode) throws Exception {
        StorageBackend<String, Integer> backend = backend(mode);
        backend.load();
        backend.putAllAtomically(Map.of("old-a", 1, "old-b", 2));
        AtomicBoolean finished = new AtomicBoolean();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> reader = workers.submit(() -> {
                do {
                    assertEquals(3, backend.scan(key -> true).stream().mapToInt(Integer::intValue).sum());
                } while (!finished.get());
            });
            try {
                for (int index = 0; index < 30; index++) {
                    backend.applyAtomically(Map.of("new-a", 1, "new-b", 2), Set.of("old-a", "old-b"));
                    backend.applyAtomically(Map.of("old-a", 1, "old-b", 2), Set.of("new-a", "new-b"));
                }
            } finally {
                finished.set(true);
            }
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            close(backend);
        }
    }

    @Test
    void persistentWriteFailureKeepsBothMemoryAndLastSnapshot() throws Exception {
        Path path = directory.resolve("data.json");
        Path retained = directory.resolve("retained.json");
        PersistentStorage<String, Integer> backend = new PersistentStorage<>(path, TYPE);
        backend.putAllAtomically(Map.of("old", 1, "keep", 2));
        byte[] before = Files.readAllBytes(path);
        Files.move(path, retained);
        Files.createDirectory(path);
        Files.writeString(path.resolve("blocker"), "owned fixture");
        assertThrows(UncheckedIOException.class,
                () -> backend.applyAtomically(Map.of("new", 3, "keep", 4), Set.of("old")));
        assertEquals(Set.of("old", "keep"), backend.keys());
        assertEquals(2, backend.get("keep").orElseThrow());
        assertArrayEquals(before, Files.readAllBytes(retained));
        Files.delete(path.resolve("blocker"));
        Files.delete(path);
        Files.move(retained, path);
        backend.applyAtomically(Map.of("new", 3, "keep", 4), Set.of("old"));
        PersistentStorage<String, Integer> reopened = new PersistentStorage<>(path, TYPE);
        reopened.load();
        assertEquals(Set.of("new", "keep"), reopened.keys());
        assertEquals(4, reopened.get("keep").orElseThrow());
    }

    @Test
    void hybridMixedBatchRetainsAsynchronousDurability() throws Exception {
        HybridStorage<String, Integer> backend = new HybridStorage<>(directory.resolve("data.json"), TYPE, Long.MAX_VALUE);
        try {
            backend.putAllAtomically(Map.of("old", 1));
            backend.flush();
            byte[] before = Files.readAllBytes(directory.resolve("data.json"));
            backend.applyAtomically(Map.of("new", 2), Set.of("old"));
            assertArrayEquals(before, Files.readAllBytes(directory.resolve("data.json")));
            backend.flush();
            PersistentStorage<String, Integer> reopened = new PersistentStorage<>(directory.resolve("data.json"), TYPE);
            reopened.load();
            assertEquals(Set.of("new"), reopened.keys());
        } finally {
            backend.shutdown();
        }
    }

    @Test
    void accountWrapperRejectsNullKeysBeforeChangingEitherSide() {
        StorageBackend<String, Integer> backend = new InMemoryStorage<>();
        AccountAwareStorageBackend<Integer> accounts = new AccountAwareStorageBackend<>(backend, null, "111111111111");
        accounts.put("old", 1);
        Map<String, Integer> invalid = new HashMap<>();
        invalid.put(null, 2);
        assertThrows(NullPointerException.class, () -> accounts.applyAtomically(invalid, Set.of("old")));
        assertEquals(Set.of("111111111111/old"), backend.keys());
    }

    @Test
    void everyTornMixedFrameReplaysNeitherHalfAndAllowsLaterWrites() throws Exception {
        WalStorage<String, Integer> writer = new WalStorage<>(directory.resolve("data.json"),
                directory.resolve("data.wal"), TYPE, Long.MAX_VALUE);
        writer.load();
        try {
            writer.putAllAtomically(Map.of("old", 1, "keep", 2));
            writer.flush();
            byte[] snapshot = Files.readAllBytes(directory.resolve("data.json"));
            writer.applyAtomically(Map.of("new", 3, "keep", 4), Set.of("old"));
            byte[] complete = Files.readAllBytes(directory.resolve("data.wal"));
            assertEquals(WalStorage.OP_MIXED_BATCH, complete[0]);
            for (int length = 0; length <= complete.length; length++) {
                assertReplay(snapshot, Arrays.copyOf(complete, length), "prefix-" + length, length == complete.length);
            }
            byte[] malformedSecondFrame = complete.clone();
            int mapLength = ByteBuffer.wrap(complete).getInt(5);
            ByteBuffer.wrap(malformedSecondFrame).putInt(9 + mapLength, Integer.MAX_VALUE);
            assertReplay(snapshot, malformedSecondFrame, "malformed-deletions", false);
            byte[] invalidDeletionCbor = complete.clone();
            invalidDeletionCbor[13 + mapLength] = (byte) 0xff;
            assertReplay(snapshot, invalidDeletionCbor, "invalid-deletion-cbor", false);
        } finally {
            writer.shutdown();
        }
    }

    private void assertReplay(byte[] snapshot, byte[] log, String label, boolean complete) throws Exception {
        Path snapshotPath = directory.resolve(label + ".json");
        Path logPath = directory.resolve(label + ".wal");
        Files.write(snapshotPath, snapshot);
        Files.write(logPath, log);
        WalStorage<String, Integer> replay = new WalStorage<>(snapshotPath, logPath, TYPE, Long.MAX_VALUE);
        replay.load();
        try {
            assertEquals(complete ? Set.of("new", "keep") : Set.of("old", "keep"), replay.keys(), label);
            assertEquals(complete ? 4 : 2, replay.get("keep").orElseThrow(), label);
            replay.putAllAtomically(Map.of("later", 5));
            Map<String, Integer> recovered = WalStorage.readContents(snapshotPath, logPath, TYPE);
            assertEquals(5, recovered.get("later"), label);
            assertEquals(complete ? 4 : 2, recovered.get("keep"), label);
            assertEquals(!complete, recovered.containsKey("old"), label);
        } finally {
            replay.shutdown();
        }
    }

    private StorageBackend<String, Integer> backend(String mode) {
        return switch (mode) {
            case "persistent" -> new PersistentStorage<>(directory.resolve("data.json"), TYPE);
            case "hybrid" -> new HybridStorage<>(directory.resolve("data.json"), TYPE, Long.MAX_VALUE);
            case "wal" -> new WalStorage<>(directory.resolve("data.json"), directory.resolve("data.wal"), TYPE, Long.MAX_VALUE);
            default -> new InMemoryStorage<>();
        };
    }

    private void close(StorageBackend<String, Integer> backend) {
        if (backend instanceof WalStorage<String, Integer> wal) {
            wal.shutdown();
        } else if (backend instanceof HybridStorage<String, Integer> hybrid) {
            hybrid.shutdown();
        }
    }
}

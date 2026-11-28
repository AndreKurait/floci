package io.floci.aws.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageDurableBatchTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void failedDurableBatchDoesNotPublishCandidateAndCanRetry(String mode) throws Exception {
        StorageFactory factory = factory(mode);
        AccountAwareStorageBackend<String> store = factory.create("ec2", "journal.json", new TypeReference<>() {});
        try {
            store.putAllDurablyForAccount("111111111111", Map.of("token", "before"));
            store.putAllDurablyForAccount("222222222222", Map.of("token", "foreign"));
            Path destination = directory.resolve(mode.equals("wal") ? "journal.wal" : "journal.json");
            Path saved = directory.resolve("saved");
            Files.move(destination, saved);
            Files.createDirectory(destination);
            Path obstacle = destination.resolve("obstacle");
            Files.writeString(obstacle, "not empty");
            try {
                assertThrows(UncheckedIOException.class,
                        () -> store.putAllDurablyForAccount("111111111111", Map.of("token", "after", "new", "candidate")));
                assertEquals("before", store.getForAccount("111111111111", "token").orElseThrow());
                assertTrue(store.getForAccount("111111111111", "new").isEmpty());
                assertEquals("foreign", store.getForAccount("222222222222", "token").orElseThrow());
            } finally {
                Files.delete(obstacle);
                Files.delete(destination);
                Files.move(saved, destination);
            }
            store.putAllDurablyForAccount("111111111111", Map.of("token", "after"));
            assertEquals("after", store.getForAccount("111111111111", "token").orElseThrow());
            StorageFactory immediatelyRestored = factory(mode);
            try {
                AccountAwareStorageBackend<String> immediate = immediatelyRestored.create(
                        "ec2", "journal.json", new TypeReference<>() {});
                assertEquals("after", immediate.getForAccount("111111111111", "token").orElseThrow());
                assertEquals("foreign", immediate.getForAccount("222222222222", "token").orElseThrow());
            } finally {
                immediatelyRestored.shutdownAll();
            }
        } finally {
            factory.shutdownAll();
        }
        StorageFactory restored = factory(mode);
        try {
            AccountAwareStorageBackend<String> reloaded = restored.create("ec2", "journal.json", new TypeReference<>() {});
            assertEquals("after", reloaded.getForAccount("111111111111", "token").orElseThrow());
        } finally {
            restored.shutdownAll();
        }
    }

    @Test
    void memoryBatchPreservesOtherEntries() {
        InMemoryStorage<String, String> store = new InMemoryStorage<>();
        store.put("existing", "before");
        store.putAllDurably(Map.of("first", "one", "second", "two"));
        assertEquals(Map.of("existing", "before", "first", "one", "second", "two"),
                Map.of("existing", store.get("existing").orElseThrow(),
                        "first", store.get("first").orElseThrow(), "second", store.get("second").orElseThrow()));
    }

    @Test
    void unsupportedBackendRejectsWithoutPartialWrites() {
        StorageBackend<String, String> store = mock(StorageBackend.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, () -> store.putAllDurably(Map.of("key", "value")));
        verify(store, never()).put(anyString(), anyString());
        verify(store, never()).flushOrThrow();
    }

    @Test
    void unsupportedRegionBatchDoesNotFallBackToNonAtomicWrites() {
        StorageBackend<String, String> delegate = mock(StorageBackend.class);
        RegionScopedStorage<String> store = new RegionScopedStorage<>(delegate, mock(RegionResolver.class));
        assertThrows(UnsupportedOperationException.class, () -> store.putAllDurably(Map.of("key", "value")));
        verifyNoInteractions(delegate);
    }

    @Test
    void requestAccountBatchUsesDurableDelegate() {
        StorageFactory factory = factory("memory");
        try {
            AccountAwareStorageBackend<String> store = factory.create("ec2", "journal.json", new TypeReference<>() {});
            store.putAllDurably(Map.of("token", "value"));
            assertEquals("value", store.getForAccount("111111111111", "token").orElseThrow());
            assertTrue(store.getForAccount("222222222222", "token").isEmpty());
        } finally {
            factory.shutdownAll();
        }
    }

    private StorageFactory factory(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("111111111111");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(3600000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("ec2")).thenReturn(mode);
        when(access.storageFlushInterval("ec2")).thenReturn(3600000L);
        return new StorageFactory(config, access);
    }
    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void checkedFlushReportsFailureAndRetainsRetryState(String mode) throws Exception {
        StorageFactory factory = factory(mode);
        AccountAwareStorageBackend<String> store = factory.create("ec2", "journal.json", new TypeReference<>() {});
        try {
            store.putAllAtomically(Map.of("token", "value"));
            Path destination = directory.resolve(mode.equals("wal") ? "journal-snapshot.json" : "journal.json");
            Files.deleteIfExists(destination);
            Files.createDirectory(destination);
            Path obstacle = destination.resolve("obstacle");
            Files.writeString(obstacle, "not empty");
            try {
                assertThrows(UncheckedIOException.class, store::flushOrThrow);
            } finally {
                Files.delete(obstacle);
                Files.delete(destination);
            }
            store.flushOrThrow();
            assertTrue(Files.isRegularFile(destination));
            assertTrue(Files.readString(destination).contains("value"));
        } finally {
            factory.shutdownAll();
        }
    }

}

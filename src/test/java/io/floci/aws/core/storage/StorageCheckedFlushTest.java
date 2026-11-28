package io.floci.aws.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageCheckedFlushTest {
    @TempDir
    Path directory;

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

    @Test
    void memoryModeAcknowledgesItsNonPersistentContract() {
        InMemoryStorage<String, String> store = new InMemoryStorage<>();
        store.put("key", "value");
        assertDoesNotThrow(store::flushOrThrow);
        assertEquals("value", store.get("key").orElseThrow());
    }

    @Test
    void unsupportedBackendCannotSilentlyAcknowledgePersistence() {
        StorageBackend<?, ?> store = mock(StorageBackend.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, store::flushOrThrow);
    }

    @Test
    void regionViewDelegatesCheckedPersistenceFailure() {
        StorageBackend<String, String> delegate = mock(StorageBackend.class);
        RegionScopedStorage<String> view = new RegionScopedStorage<>(delegate, mock(RegionResolver.class));
        UncheckedIOException failure = new UncheckedIOException(new IOException("disk unavailable"));
        doThrow(failure).when(delegate).flushOrThrow();
        assertSame(failure, assertThrows(UncheckedIOException.class, view::flushOrThrow));
        verify(delegate).flushOrThrow();
        verify(delegate, never()).flush();
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
}

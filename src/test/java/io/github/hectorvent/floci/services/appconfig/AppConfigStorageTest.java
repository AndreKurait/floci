package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.StorageFactories;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigStorageTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void legacyKeysAndScopedKeysCannotEscapeTheirRegion(String mode) {
        StorageFactory factory = factory(directory, mode);
        try {
            StorageBackend<String, String> legacy = factory.create(
                    "appconfig", "records.json", new TypeReference<>() {});
            legacy.put("existing", "legacy");
            AppConfigStorage<String> east = store(factory, "us-east-1");
            AppConfigStorage<String> west = store(factory, "us-west-2");
            assertEquals(Optional.of("legacy"), east.get("existing"));
            assertEquals(Optional.empty(), west.get("existing"));
            east.put("existing", "east");
            west.put("existing", "west");
            legacy.put("untouched", "older-record");

            assertEquals(Optional.empty(), legacy.get("existing"));
            assertEquals(Optional.of("east"), east.get("existing"));
            assertEquals(Optional.of("west"), west.get("existing"));
            assertEquals(Set.of("existing", "untouched"), east.keys());
            assertEquals(Set.of("existing"), west.keys());
            assertEquals(List.of("west"), west.scan(key -> key.equals("existing")));
            List<String> selected = west.scan(key -> true);
            selected.clear();
            assertEquals(List.of("west"), west.scan(key -> true));

            assertEquals(Optional.empty(), east.get("us-west-2/existing"));
            east.delete("us-west-2/existing");
            assertThrows(IllegalArgumentException.class, () -> east.put("us-west-2/existing", "wrong"));
            assertEquals(Optional.of("west"), west.get("existing"));
            east.clear();
            assertEquals(Set.of(), east.keys());
            assertEquals(Optional.of("west"), west.get("existing"));
        } finally {
            factory.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void backgroundKeysRetainOwnersAndDeduplicateDefaultRegionLegacyKeys(String mode) {
        StorageFactory factory = factory(directory, mode);
        try {
            AccountAwareStorageBackend<String> raw = (AccountAwareStorageBackend<String>) factory
                    .<String>create("appconfig", "records.json", new TypeReference<>() {});
            raw.putForAccount("111111111111", "existing", "legacy");
            raw.putForAccount("111111111111", "us-east-1/existing", "current");
            raw.putForAccount("111111111111", "us-west-2/existing", "west");
            raw.putForAccount("222222222222", "cn-north-1/existing", "china");
            raw.putForAccount("222222222222", "us-east-1/invalid/nested", "invalid");
            AppConfigStorage<String> store = store(factory, "us-east-1");
            assertEquals(Set.of(
                    new AppConfigStorage.ScopedKey("111111111111", "us-east-1", "existing"),
                    new AppConfigStorage.ScopedKey("111111111111", "us-west-2", "existing"),
                    new AppConfigStorage.ScopedKey("222222222222", "cn-north-1", "existing")),
                    store.scopedKeys(value -> true));
            assertEquals(Set.of(new AppConfigStorage.ScopedKey("111111111111", "us-west-2", "existing")),
                    store.scopedKeys(value -> value.equals("west")));
        } finally {
            factory.shutdownAll();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void preAccountRecordsCannotBeClaimedByAnotherAccountReadingFirst() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("existing", "default-account");
        RequestContext context = mock(RequestContext.class);
        when(context.getAccountId()).thenReturn("111111111111");
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        AccountAwareStorageBackend<String> accounts = new AccountAwareStorageBackend<>(
                raw, contexts, "000000000000");
        StorageFactory factory = new InMemoryStorageFactory("000000000000").withStore("records.json", accounts);
        AppConfigStorage<String> store = store(factory, "us-east-1");
        assertEquals(Optional.empty(), store.get("existing"));
        assertEquals(Optional.of("default-account"), raw.get("existing"));
        when(context.getAccountId()).thenReturn("000000000000");
        assertEquals(Optional.of("default-account"), store.get("existing"));
        assertEquals(Optional.empty(), raw.get("existing"));
        assertEquals(Optional.of("default-account"), raw.get("000000000000/existing"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void atomicSnapshotsPreserveRegionBoundariesAndShadowLegacyUntilDeletion(String mode) {
        StorageFactory factory = StorageFactories.open(directory, mode, "000000000000");
        try {
            StorageBackend<String, String> legacy = factory.create(
                    "appconfig", "records.json", new TypeReference<>() {});
            legacy.put("existing", "legacy");
            AppConfigStorage<String> east = store(factory, "us-east-1");
            AppConfigStorage<String> west = store(factory, "us-west-2");
            east.putAllAtomically(Map.of("existing", "east", "fresh", "new"));
            assertEquals(Optional.of("legacy"), legacy.get("existing"));
            assertEquals(Optional.of("east"), east.get("existing"));
            assertEquals(Optional.empty(), west.get("existing"));
            west.putAllAtomically(Map.of("existing", "west"));
            Map<String, String> invalid = new LinkedHashMap<>();
            invalid.put("existing", "must-not-store");
            invalid.put("us-west-2/existing", "wrong-region");
            assertThrows(IllegalArgumentException.class, () -> east.putAllAtomically(invalid));
            assertEquals(Optional.of("east"), east.get("existing"));
            assertEquals(Optional.of("west"), west.get("existing"));
            assertEquals(Set.of("existing", "fresh"), east.keys());
            east.delete("existing");
            assertEquals(Optional.empty(), east.get("existing"));
            assertEquals(Optional.empty(), legacy.get("existing"));
            assertEquals(Optional.of("west"), west.get("existing"));
            assertEquals(Optional.of("new"), east.get("fresh"));
        } finally {
            factory.shutdownAll();
        }
    }

    private static AppConfigStorage<String> store(StorageFactory factory, String region) {
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getDefaultRegion()).thenReturn("us-east-1");
        when(regions.getRegion()).thenReturn(region);
        return new AppConfigStorage<>(factory, regions, "appconfig", "records.json", new TypeReference<>() {});
    }

    static StorageFactory factory(Path directory, String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(60000L);
        return new StorageFactory(config, access);
    }
}

package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.model.RedshiftEvent;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftEventStorageTest {

    private static final String ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "222222222222";
    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void historySurvivesRestart(String mode) {
        String originalKey;
        try (Fixture first = fixture(mode, ACCOUNT)) {
            first.east().record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created before restart");
            originalKey = history(first.east()).getFirst().getKey();
        }

        try (Fixture restarted = fixture(mode, ACCOUNT)) {
            restarted.factory().loadAll();
            restarted.factory().loadAll();
            List<RedshiftEvent> history = history(restarted.east());
            assertEquals(1, history.size());
            assertEquals(originalKey, history.getFirst().getKey());
            assertEquals(NOW, history.getFirst().getDate());
            assertEquals("REDSHIFT-EVENT-2000", history.getFirst().getEventId());
            assertEquals("created before restart", history.getFirst().getMessage());

            restarted.east().record(RedshiftEventCatalog.CLUSTER_REBOOTED, "warehouse", "rebooted after restart");
            assertEquals(2, history(restarted.east()).size());
        }

        try (Fixture third = fixture(mode, ACCOUNT)) {
            assertEquals(2, history(third.east()).size());
            assertEquals(2, history(third.east()).stream().map(RedshiftEvent::getKey).distinct().count());
            assertEquals(List.of("REDSHIFT-EVENT-2000", "REDSHIFT-EVENT-3000"), history(third.east()).stream()
                    .map(RedshiftEvent::getEventId).sorted().toList());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void persistedHistoryStaysWithinItsAccountAndRegion(String mode) {
        try (Fixture owner = fixture(mode, ACCOUNT)) {
            owner.east().record(RedshiftEventCatalog.CLUSTER_CREATED, "east-cluster", "east owner");
            owner.west().record(RedshiftEventCatalog.CLUSTER_CREATED, "west-cluster", "west owner");
            assertEquals(List.of("east-cluster"), sourceIds(owner.east()));
            assertEquals(List.of("west-cluster"), sourceIds(owner.west()));
        }

        try (Fixture other = fixture(mode, OTHER_ACCOUNT)) {
            assertTrue(history(other.east()).isEmpty());
            assertTrue(history(other.west()).isEmpty());
            other.east().record(RedshiftEventCatalog.CLUSTER_CREATED, "other-cluster", "other owner");
        }

        try (Fixture restored = fixture(mode, ACCOUNT)) {
            assertEquals(List.of("east-cluster"), sourceIds(restored.east()));
            assertEquals(List.of("west-cluster"), sourceIds(restored.west()));
            restored.east().record(RedshiftEventCatalog.CLUSTER_REBOOTED, "east-cluster", "owner reboot");
            assertEquals(2, history(restored.east()).size());
            assertEquals(1, history(restored.west()).size());
        }

        try (Fixture otherRestored = fixture(mode, OTHER_ACCOUNT)) {
            assertEquals(List.of("other-cluster"), sourceIds(otherRestored.east()));
            assertTrue(history(otherRestored.west()).isEmpty());
        }
    }

    private Fixture fixture(String mode, String account) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(account);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("redshift")).thenReturn(mode);
        when(access.storageFlushInterval("redshift")).thenReturn(60_000L);
        StorageFactory factory = new StorageFactory(config, access);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RedshiftEventService east = new RedshiftEventService(factory, new RegionResolver(EAST, account), clock);
        RedshiftEventService west = new RedshiftEventService(factory, new RegionResolver(WEST, account), clock);
        return new Fixture(factory, east, west);
    }

    private static List<RedshiftEvent> history(RedshiftEventService service) {
        return service.describeEvents(null, null, null, null, null, null, null).items();
    }

    private static List<String> sourceIds(RedshiftEventService service) {
        return history(service).stream().map(RedshiftEvent::getSourceIdentifier).toList();
    }

    private record Fixture(StorageFactory factory, RedshiftEventService east,
                           RedshiftEventService west) implements AutoCloseable {
        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
}

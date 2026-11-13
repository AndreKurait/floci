package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.account.model.AccountRegionTransition;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AccountRegionPersistenceTest {
    private static final String OWNER = "111122223333";
    private static final String OTHER = "444455556666";
    private static final String TARGET = "ap-southeast-7";
    private static final TypeReference<Map<String, AccountRegionTransition>> TYPE = new TypeReference<>() {};
    private final ObjectMapper mapper = new ObjectMapper();
    private final AccountRegionServiceTest.MutableClock clock = new AccountRegionServiceTest.MutableClock();
    private final RegionResolver regions = mock(RegionResolver.class);
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void wholeIntentsRetainTheirAccountsPartitionsAndDeadlinesAcrossRestart(String mode) {
        when(regions.getPartition()).thenReturn("aws");
        StorageFactory first = factory(mode);
        Map<String, AccountRegionTransition> before;
        String token;
        try {
            AccountRegionService service = service(first);
            service.enableRegion(OWNER, target());
            service.enableRegion(OTHER, target());
            token = service.listRegions(OWNER, mapper.createObjectNode().put("MaxResults", 1)).nextToken();
            before = snapshot(first);
            assertEquals(Set.of(OWNER + "/aws/" + TARGET, OTHER + "/aws/" + TARGET), before.keySet());
            before.forEach((key, value) -> {
                assertTrue(key.startsWith(value.accountId() + "/"));
                assertEquals("aws", value.partition());
                assertEquals(TARGET, value.regionName());
                assertEquals("ENABLED", value.terminalStatus());
                assertEquals(clock.millis() + 500, value.deadlineMillis());
                assertNotNull(value.revision());
            });
        } finally {
            first.shutdownAll();
        }
        clock.advance(500);
        StorageFactory second = factory(mode);
        try {
            AccountRegionService restarted = service(second);
            assertEquals(before, snapshot(second));
            assertEquals("ENABLED", restarted.getRegionOptStatus(OWNER, target()).regionOptStatus());
            assertEquals("ENABLED", restarted.getRegionOptStatus(OTHER, target()).regionOptStatus());
            assertEquals(before, snapshot(second), "Settlement is a read, not a rewritten persisted record");
            AwsException stale = assertThrows(AwsException.class, () -> restarted.listRegions(OWNER,
                    mapper.createObjectNode().put("MaxResults", 1).put("NextToken", token)));
            assertEquals("ValidationException", stale.getErrorCode());
            when(regions.getPartition()).thenReturn("aws-cn");
            assertEquals(2, restarted.listRegions(OWNER, mapper.createObjectNode()).items().size());
            assertEquals(before, snapshot(second));
            when(regions.getPartition()).thenReturn("aws");
            restarted.disableRegion(OWNER, target());
        } finally {
            second.shutdownAll();
        }
        clock.advance(500);
        StorageFactory third = factory(mode);
        try {
            AccountRegionService restarted = service(third);
            assertEquals("DISABLED", restarted.getRegionOptStatus(OWNER, target()).regionOptStatus());
            assertEquals("ENABLED", restarted.getRegionOptStatus(OTHER, target()).regionOptStatus());
            assertEquals(before.get(OTHER + "/aws/" + TARGET), snapshot(third).get(OTHER + "/aws/" + TARGET));
            restarted.beforeReset();
            try {
                third.clearAll();
                restarted.clear();
            } finally {
                restarted.afterReset();
            }
            assertTrue(snapshot(third).isEmpty());
            assertEquals("DISABLED", restarted.getRegionOptStatus(OTHER, target()).regionOptStatus());
        } finally {
            third.shutdownAll();
        }
        StorageFactory fourth = factory(mode);
        try {
            assertEquals("DISABLED", service(fourth).getRegionOptStatus(OWNER, target()).regionOptStatus());
            assertTrue(snapshot(fourth).isEmpty());
        } finally {
            fourth.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "wal"})
    void aRealFailedSnapshotOrJournalWriteLeavesEveryPriorValueUnchanged(String mode) throws Exception {
        when(regions.getPartition()).thenReturn("aws");
        StorageFactory factory = factory(mode);
        Path destination = directory.resolve("account-region-transitions." + ("wal".equals(mode) ? "wal" : "json"));
        Path saved = directory.resolve("saved-" + destination.getFileName());
        try {
            AccountRegionService service = service(factory);
            service.enableRegion(OWNER, target());
            service.enableRegion(OTHER, target());
            clock.advance(500);
            Map<String, AccountRegionTransition> before = snapshot(factory);
            byte[] savedBytes = Files.readAllBytes(destination);
            Files.move(destination, saved);
            Files.createDirectory(destination);
            Files.writeString(destination.resolve("owned-blocker"), "prevent atomic replacement");
            try {
                assertThrows(UncheckedIOException.class, () -> service.disableRegion(OWNER, target()));
                assertEquals(before, snapshot(factory));
                assertEquals("ENABLED", service.getRegionOptStatus(OWNER, target()).regionOptStatus());
                assertArrayEquals(savedBytes, Files.readAllBytes(saved));
            } finally {
                Files.delete(destination.resolve("owned-blocker"));
                Files.delete(destination);
                Files.move(saved, destination);
            }
            service.disableRegion(OWNER, target());
            assertNotEquals(before.get(OWNER + "/aws/" + TARGET), snapshot(factory).get(OWNER + "/aws/" + TARGET));
            assertEquals(before.get(OTHER + "/aws/" + TARGET), snapshot(factory).get(OTHER + "/aws/" + TARGET));
        } finally {
            factory.shutdownAll();
        }
    }

    @Test
    void hybridTransitionKeepsItsExistingFlushBasedDurabilityContract() throws Exception {
        when(regions.getPartition()).thenReturn("aws");
        StorageFactory factory = factory("hybrid");
        try {
            AccountRegionService service = service(factory);
            service.enableRegion(OWNER, target());
            factory.flushAll();
            Path file = directory.resolve("account-region-transitions.json");
            byte[] first = Files.readAllBytes(file);
            clock.advance(500);
            service.disableRegion(OWNER, target());
            assertArrayEquals(first, Files.readAllBytes(file));
            assertEquals("DISABLING", service.getRegionOptStatus(OWNER, target()).regionOptStatus());
            factory.flushAll();
            assertFalse(Arrays.equals(first, Files.readAllBytes(file)));
        } finally {
            factory.shutdownAll();
        }
    }

    private AccountRegionService service(StorageFactory factory) {
        return new AccountRegionService(factory, new AccountService(factory, mock(OrganizationsService.class)),
                regions, clock);
    }

    private Map<String, AccountRegionTransition> snapshot(StorageFactory factory) {
        AccountAwareStorageBackend<AccountRegionTransition> store =
                factory.create("account", "account-region-transitions.json", TYPE);
        return Map.copyOf(store.scanAllAccountsWithRawKeys());
    }

    private ObjectNode target() {
        return mapper.createObjectNode().put("RegionName", TARGET);
    }

    private StorageFactory factory(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(OWNER);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        ServiceConfigAccess services = mock(ServiceConfigAccess.class);
        when(services.storageMode(anyString())).thenReturn(mode);
        when(services.storageFlushInterval(anyString())).thenReturn(60_000L);
        return new StorageFactory(config, services);
    }
}

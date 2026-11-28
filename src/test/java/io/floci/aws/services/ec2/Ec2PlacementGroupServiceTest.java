package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.ec2.model.Ec2PlacementGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class Ec2PlacementGroupServiceTest {

    private static final String OWNER = "111111111111";
    private static final String REGION = "us-east-1";
    private static final TypeReference<Map<String, Ec2PlacementGroup>> TYPE = new TypeReference<>() {};

    @TempDir
    Path directory;

    @Test
    void ownsNamesByAccountRegionAndStoredOwnerWithoutLegacyReads() {
        Fixture fixture = new Fixture();
        Ec2PlacementGroup first = fixture.create(REGION, "same", Map.of("owner", "first"));
        fixture.account.set("222222222222");
        Ec2PlacementGroup second = fixture.create(REGION, "same", Map.of("owner", "second"));
        Ec2PlacementGroup west = fixture.create("us-west-2", "same", Map.of());
        assertNotEquals(first.groupId(), second.groupId());
        assertNotEquals(second.groupId(), west.groupId());
        assertEquals(List.of(second), fixture.all(REGION));
        fixture.account.set(OWNER);
        assertEquals(List.of(first), fixture.all(REGION));
        fixture.raw.put(OWNER + "/" + REGION + "::foreign", second);
        fixture.raw.put(REGION + "::legacy", first);
        Set<String> keys = Set.copyOf(fixture.raw.keys());
        int writes = fixture.raw.writes.get();
        assertNull(fixture.service.authorizationTarget(REGION, "foreign"));
        assertEquals(keys, fixture.raw.keys());
        assertEquals(writes, fixture.raw.writes.get());
        assertEquals(List.of(first), fixture.all(REGION));
        fixture.request.fresh();
        assertNull(fixture.service.authorizationTarget(REGION, "legacy"));
        assertEquals(keys, fixture.raw.keys());
        assertEquals(writes, fixture.raw.writes.get());
        fixture.request.fresh();
        fixture.service.delete(REGION, "same", false);
        fixture.account.set("222222222222");
        assertEquals(List.of(second), fixture.all(REGION));
        assertEquals(List.of(west), fixture.all("us-west-2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void storedIdentityUsesTheRequestPartitionAndLiteralName(String region) {
        Fixture fixture = new Fixture();
        String name = "222222222222/literal";
        Ec2PlacementGroup group = fixture.create(region, name, Map.of());
        assertEquals("arn:" + AwsRegions.partitionFor(region) + ":ec2:" + region + ":" + OWNER
                + ":placement-group/" + name, group.arn());
        assertEquals(List.of(group), fixture.service.describe(region, List.of(name), List.of(), List.of(), false));
        assertTrue(fixture.raw.keys().contains(OWNER + "/" + region + "::" + name));
    }

    @Test
    void immutableTagsAndRepeatedFiltersPreserveSiblingSnapshots() {
        Fixture fixture = new Fixture();
        Map<String, String> source = new HashMap<>(Map.of("owner", "one", "empty", ""));
        Ec2PlacementGroup first = fixture.create(REGION, "one", source);
        source.put("owner", "changed");
        Ec2PlacementGroup sibling = fixture.create(REGION, "two", Map.of("owner", "two"));
        assertThrows(UnsupportedOperationException.class, () -> first.tags().put("owner", "changed"));
        assertEquals("one", fixture.all(REGION).getFirst().tags().get("owner"));
        assertEquals(List.of(first), fixture.service.describe(REGION, List.of(), List.of(),
                List.of(Map.entry("tag:owner", List.of("one", "missing")),
                        Map.entry("group-name", List.of("o*")), Map.entry("tag:empty", List.of(""))), false));
        assertTrue(fixture.service.describe(REGION, List.of(), List.of(),
                List.of(Map.entry("tag:owner", List.of("one")),
                        Map.entry("tag:owner", List.of("two"))), false).isEmpty());
        assertTrue(fixture.service.describe(REGION, List.of(), List.of(),
                List.of(Map.entry("tag:owner", List.of("ONE"))), false).isEmpty());
        assertCode("InvalidPlacementGroup.Duplicate",
                () -> fixture.create(REGION, "one", Map.of("owner", "changed")));
        fixture.service.delete(REGION, "one", false);
        assertEquals(List.of(sibling), fixture.all(REGION));
        Ec2PlacementGroup replacement = fixture.create(REGION, "one", Map.of());
        assertNotEquals(first.generation(), replacement.generation());
        assertNotEquals(first.groupId(), replacement.groupId());
        assertTrue(replacement.tags().isEmpty());
    }

    @Test
    void invalidAndUnsupportedRequestsNeverPublishMetadata() {
        Fixture fixture = new Fixture();
        assertCode("MissingParameter", () -> fixture.create(REGION, "", Map.of()));
        assertCode("MissingParameter", () -> fixture.service.create(REGION, "n", null, null, null, Map.of(), false));
        assertCode("UnsupportedOperation",
                () -> fixture.service.create(REGION, "n", "precision-time", null, null, Map.of(), false));
        assertCode("InvalidParameterValue",
                () -> fixture.service.create(REGION, "n", "partition", 8, null, Map.of(), false));
        assertCode("InvalidParameterValue",
                () -> fixture.service.create(REGION, "n", "partition", null, null, Map.of(), false));
        assertCode("UnsupportedOperation",
                () -> fixture.service.create(REGION, "n", "spread", null, "host", Map.of(), false));
        assertCode("InvalidParameterCombination",
                () -> fixture.service.create(REGION, "n", "cluster", 2, null, Map.of(), false));
        assertCode("InvalidParameterValue", () -> fixture.create(REGION, "n", Map.of("aws:reserved", "x")));
        assertCode("InvalidParameterValue", () -> fixture.create(REGION, "non-ascii-\u00e9", Map.of()));
        assertCode("InvalidParameterValue", () -> fixture.service.describe(REGION, List.of(), List.of(),
                List.of(Map.entry("unknown-filter", List.of("x"))), false));
        assertTrue(fixture.raw.keys().isEmpty());
        assertEquals(0, fixture.raw.writes.get());
    }

    @Test
    void dryRunsAndAuthorizationReadsCannotWrite() {
        Fixture fixture = new Fixture();
        assertCode("DryRunOperation",
                () -> fixture.service.create(REGION, "name", "cluster", null, null, Map.of(), true));
        assertEquals(0, fixture.raw.writes.get());
        Ec2PlacementGroup current = fixture.create(REGION, "name", Map.of("allow", "yes"));
        int writes = fixture.raw.writes.get();
        assertSame(current, fixture.service.authorizationTarget(REGION, "name"));
        assertEquals(writes, fixture.raw.writes.get());
        assertCode("DryRunOperation", () -> fixture.service.delete(REGION, "name", true));
        assertCode("DryRunOperation",
                () -> fixture.service.describe(REGION, List.of("name"), List.of(), List.of(), true));
        assertEquals(writes, fixture.raw.writes.get());
        assertEquals(List.of(current), fixture.all(REGION));
        fixture.service.delete(REGION, "name", false);
        assertTrue(fixture.all(REGION).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedGenerationOrAbsenceCannotDeleteAReplacement(boolean initiallyPresent) throws Exception {
        Fixture fixture = new Fixture();
        Ec2PlacementGroup original = initiallyPresent ? fixture.create(REGION, "name", Map.of("allow", "yes")) : null;
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
            Future<AwsException> staleDelete = workers.submit(() -> {
                assertSame(original, fixture.service.authorizationTarget(REGION, "name"));
                captured.countDown();
                assertTrue(proceed.await(10, TimeUnit.SECONDS));
                return assertThrows(AwsException.class, () -> fixture.service.delete(REGION, "name", false));
            });
            try {
                assertTrue(captured.await(10, TimeUnit.SECONDS));
                if (initiallyPresent) {
                    fixture.service.delete(REGION, "name", false);
                }
                Ec2PlacementGroup replacement = fixture.create(REGION, "name", Map.of("allow", "no"));
                int writes = fixture.raw.writes.get();
                proceed.countDown();
                assertEquals("InvalidPlacementGroup.Unknown", staleDelete.get(10, TimeUnit.SECONDS).getErrorCode());
                assertEquals(writes, fixture.raw.writes.get());
                assertEquals(List.of(replacement), fixture.all(REGION));
            } finally {
                proceed.countDown();
            }
        }
    }

    @Test
    void simultaneousCreatesPublishExactlyOneGeneration() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> outcomes = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                outcomes.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        return fixture.create(REGION, "same", Map.of());
                    } catch (AwsException failure) {
                        return failure;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Object first = outcomes.get(0).get(10, TimeUnit.SECONDS);
            Object second = outcomes.get(1).get(10, TimeUnit.SECONDS);
            assertTrue(first instanceof Ec2PlacementGroup ^ second instanceof Ec2PlacementGroup);
            AwsException failure = first instanceof AwsException exception ? exception : (AwsException) second;
            assertEquals("InvalidPlacementGroup.Duplicate", failure.getErrorCode());
            assertEquals(1, fixture.raw.writes.get());
            assertEquals(1, fixture.all(REGION).size());
        } finally {
            start.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "wal"})
    void failedCommitsPreserveExactSnapshotAndSuccessfulDeletionSurvivesReload(String mode) throws Exception {
        Path snapshot = directory.resolve("groups.json");
        Path journal = directory.resolve("groups.wal");
        StorageBackend<String, Ec2PlacementGroup> backend = store(mode, snapshot, journal);
        backend.load();
        try {
            Fixture fixture = new Fixture(backend);
            Ec2PlacementGroup group = fixture.create(REGION, "kept", Map.of("owner", "same"));
            Path blocked = "wal".equals(mode) ? journal : snapshot;
            Path saved = directory.resolve("saved");
            byte[] bytes = Files.readAllBytes(blocked);
            Files.move(blocked, saved);
            Files.createDirectory(blocked);
            Files.writeString(blocked.resolve("blocker"), "owned");
            try {
                assertThrows(UncheckedIOException.class, () -> fixture.create(REGION, "new", Map.of()));
                assertThrows(UncheckedIOException.class, () -> fixture.service.delete(REGION, "kept", false));
                assertEquals(List.of(group), fixture.all(REGION));
                assertArrayEquals(bytes, Files.readAllBytes(saved));
            } finally {
                Files.delete(blocked.resolve("blocker"));
                Files.delete(blocked);
                Files.move(saved, blocked);
            }
            fixture.service.delete(REGION, "kept", false);
            fixture.create(REGION, "sibling", Map.of("empty", ""));
        } finally {
            if (backend instanceof WalStorage<String, Ec2PlacementGroup> wal) {
                wal.shutdown();
            }
        }
        StorageBackend<String, Ec2PlacementGroup> reloaded = store(mode, snapshot, journal);
        reloaded.load();
        try {
            Fixture recovered = new Fixture(reloaded);
            assertEquals(List.of("sibling"), recovered.all(REGION).stream().map(Ec2PlacementGroup::groupName).toList());
            assertEquals(Map.of("empty", ""), recovered.all(REGION).getFirst().tags());
        } finally {
            if (reloaded instanceof WalStorage<String, Ec2PlacementGroup> wal) {
                wal.shutdown();
            }
        }
    }

    private static StorageBackend<String, Ec2PlacementGroup> store(String mode, Path snapshot, Path journal) {
        return "wal".equals(mode) ? new WalStorage<>(snapshot, journal, TYPE, Long.MAX_VALUE)
                : new PersistentStorage<>(snapshot, TYPE);
    }

    private static void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(AwsException.class, action::run).getErrorCode());
    }

    private static final class Fixture {
        private final RecordingMemory raw = new RecordingMemory();
        private final AtomicReference<String> account = new AtomicReference<>(OWNER);
        private final ThreadRequest request = new ThreadRequest();
        private final Ec2PlacementGroupService service;

        Fixture() {
            this(null);
        }

        Fixture(StorageBackend<String, Ec2PlacementGroup> backend) {
            RegionResolver resolver = mock(RegionResolver.class);
            when(resolver.getAccountId()).thenAnswer(invocation -> account.get());
            when(resolver.partitionForRegion(anyString())).thenAnswer(invocation ->
                    AwsRegions.partitionFor(invocation.getArgument(0)));
            service = new Ec2PlacementGroupService(new AccountAwareStorageBackend<>(
                    backend == null ? raw : backend, null, OWNER), resolver, request);
        }

        Ec2PlacementGroup create(String region, String name, Map<String, String> tags) {
            return service.create(region, name, "cluster", null, null, tags, false);
        }

        List<Ec2PlacementGroup> all(String region) {
            return service.describe(region, List.of(), List.of(), List.of(), false);
        }
    }

    private static final class ThreadRequest extends Ec2PlacementGroupRequest {
        private final ThreadLocal<Ec2PlacementGroupRequest> current =
                ThreadLocal.withInitial(Ec2PlacementGroupRequest::new);

        void fresh() {
            current.remove();
        }

        @Override
        public DeleteTarget deleteTarget() {
            return current.get().deleteTarget();
        }

        @Override
        DeleteTarget capture(Ec2PlacementGroupService.Scope scope, String name, Ec2PlacementGroup group) {
            return current.get().capture(scope, name, group);
        }
    }

    private static final class RecordingMemory extends InMemoryStorage<String, Ec2PlacementGroup> {
        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public void put(String key, Ec2PlacementGroup value) {
            super.put(key, value);
            writes.incrementAndGet();
        }

        @Override
        public void delete(String key) {
            super.delete(key);
            writes.incrementAndGet();
        }

        @Override
        public void clear() {
            super.clear();
            writes.incrementAndGet();
        }

        @Override
        public void putAllAtomically(Map<String, Ec2PlacementGroup> entries) {
            super.putAllAtomically(entries);
            writes.incrementAndGet();
        }

        @Override
        public void deleteAllAtomically(Set<String> keys) {
            super.deleteAllAtomically(keys);
            writes.incrementAndGet();
        }
    }
}

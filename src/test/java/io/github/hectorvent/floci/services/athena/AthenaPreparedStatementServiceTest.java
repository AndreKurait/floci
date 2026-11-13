package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.athena.model.CreateWorkGroupRequest;
import io.github.hectorvent.floci.services.athena.model.PreparedStatement;
import io.github.hectorvent.floci.services.athena.model.UpdateWorkGroupRequest;
import io.github.hectorvent.floci.services.athena.model.WorkGroup;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AthenaPreparedStatementServiceTest {
    private static final String REGION = "us-east-1";
    @TempDir
    Path directory;

    @Test
    void recordsAndMapsAreImmutableAndUpdatesReplaceOnlyTheNamedStatement() {
        Fixture f = new Fixture(new InMemoryStorage<>());
        f.createGroup("group");
        f.service.create(REGION, "group", "first", "select ?", "original");
        f.service.create(REGION, "group", "second", "select 2", null);
        PreparedStatement before = f.service.get(REGION, "group", "first");
        Map<String, PreparedStatement> oldMap = f.athena.preparedStatementWorkGroup(REGION, "group").getPreparedStatements();
        assertThrows(UnsupportedOperationException.class, () -> oldMap.clear());
        f.service.update(REGION, "group", "first", "select 3", null);
        PreparedStatement after = f.service.get(REGION, "group", "first");
        assertEquals("select ?", before.queryStatement());
        assertEquals("original", oldMap.get("first").description());
        assertEquals("select 3", after.queryStatement());
        assertEquals("original", after.description(), "An omitted description preserves the stored value");
        assertFalse(after.lastModifiedTime().isBefore(before.lastModifiedTime()));
        assertEquals("select 2", f.service.get(REGION, "group", "second").queryStatement());
        assertFalse(f.athena.getWorkGroup("group", REGION).containsKey("PreparedStatements"));
        assertThrows(AwsException.class, () -> f.service.create(REGION, "group", "first", "changed", null));
        f.service.delete(REGION, "group", "first");
        for (Runnable missing : List.<Runnable>of(() -> f.service.get(REGION, "group", "first"),
                () -> f.service.update(REGION, "group", "first", "select 1", null),
                () -> f.service.delete(REGION, "group", "first"))) {
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, missing::run).getErrorCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "1a", "bad-name", "bad name", "aa/other", "a.b", "éa"})
    void rejectsInvalidStatementNamesBeforeAnyWrite(String name) {
        Fixture f = new Fixture(new InMemoryStorage<>());
        f.createGroup("group");
        assertThrows(AwsException.class, () -> f.service.create(REGION, "group", name, "select 1", null));
        assertTrue(f.athena.preparedStatementWorkGroup(REGION, "group").getPreparedStatements().isEmpty());
    }

    @Test
    void validatesExactLengthsAndCountsUnicodeCodePoints() {
        Fixture f = new Fixture(new InMemoryStorage<>());
        String longName = "a".repeat(256);
        String query = "😀".repeat(262144);
        String description = "😀".repeat(1024);
        f.service.create(REGION, "primary", longName, query, description);
        assertEquals(query, f.service.get(REGION, "primary", longName).queryStatement());
        for (Runnable invalid : List.<Runnable>of(
                () -> f.service.create(REGION, "primary", longName + "a", "q", null),
                () -> f.service.update(REGION, "primary", longName, query + "a", null),
                () -> f.service.update(REGION, "primary", longName, "q", description + "a"),
                () -> f.service.update(REGION, "primary", longName, "", null),
                () -> f.service.update(REGION, "primary", longName, "q", ""),
                () -> f.service.get(REGION, "bad group", longName),
                () -> f.service.batchGet(REGION, "primary", List.of(longName, "!")))) {
            assertThrows(AwsException.class, invalid::run);
        }
        assertEquals(description, f.service.get(REGION, "primary", longName).description());
        assertTrue(f.service.batchGet(REGION, "primary", List.of()).statements().isEmpty());
        assertEquals(List.of("missing"), f.service.batchGet(REGION, "primary", List.of(longName, "missing")).missing());
    }

    @Test
    void batchAccepts256NamesAndRejects257WithoutChangingStoredStatements() {
        Fixture f = new Fixture(new InMemoryStorage<>());
        f.service.create(REGION, "primary", "saved", "original", "preserved");
        PreparedStatement saved = f.service.get(REGION, "primary", "saved");
        List<String> names = new ArrayList<>();
        names.add("saved");
        for (int i = 0; i < 255; i++) {
            names.add("missing" + i);
        }
        AthenaPreparedStatementService.BatchResult batch = f.service.batchGet(REGION, "primary", names);
        assertEquals(List.of(saved), batch.statements());
        assertEquals(names.subList(1, 256), batch.missing());
        names.add("overflow");
        AwsException failure = assertThrows(AwsException.class, () -> f.service.batchGet(REGION, "primary", names));
        assertEquals("InvalidRequestException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("256"));
        assertEquals(saved, f.service.get(REGION, "primary", "saved"));
        assertEquals(1, f.athena.preparedStatementWorkGroup(REGION, "primary").getPreparedStatements().size());
    }

    @Test
    void quotaIsPerWorkGroupAndDeletionFreesOneSlot() {
        Fixture f = new Fixture(new InMemoryStorage<>());
        f.createGroup("other");
        for (int i = 0; i < 1000; i++) {
            f.service.create(REGION, "primary", "statement" + i, "q", null);
        }
        assertThrows(AwsException.class, () -> f.service.create(REGION, "primary", "overflow", "q", null));
        f.service.create(REGION, "other", "overflow", "q", null);
        f.service.update(REGION, "primary", "statement0", "updated", null);
        f.service.delete(REGION, "primary", "statement1");
        f.service.create(REGION, "primary", "replacement", "q", null);
        assertEquals(1000, f.athena.preparedStatementWorkGroup(REGION, "primary").getPreparedStatements().size());
    }

    @Test
    void rejectedAtomicWritesLeaveLiveRecordsAndMetadataUntouched() {
        ControlledStore store = new ControlledStore();
        Fixture f = new Fixture(store);
        f.createGroup("group");
        f.service.create(REGION, "group", "saved", "original", "description");
        WorkGroup before = f.athena.preparedStatementWorkGroup(REGION, "group");
        store.fail = true;
        for (Runnable write : List.<Runnable>of(() -> f.service.create(REGION, "group", "newone", "q", null),
                () -> f.service.update(REGION, "group", "saved", "changed", null),
                () -> f.service.delete(REGION, "group", "saved"))) {
            assertThrows(IllegalStateException.class, write::run);
            assertSame(before, f.athena.preparedStatementWorkGroup(REGION, "group"));
            assertEquals("original", f.service.get(REGION, "group", "saved").queryStatement());
            assertEquals("description", f.service.get(REGION, "group", "saved").description());
            assertEquals(1, before.getPreparedStatements().size());
        }
        store.fail = false;
        f.service.update(REGION, "group", "saved", "successful", null);
        assertEquals("successful", f.service.get(REGION, "group", "saved").queryStatement());
    }

    @Test
    void concurrentCreatesHaveOneDuplicateWinnerAndDoNotLoseIndependentWrites() throws Exception {
        Fixture f = new Fixture(new InMemoryStorage<>());
        f.createGroup("group");
        AtomicInteger winners = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                int value = i;
                writes.add(executor.submit(() -> {
                    try {
                        f.service.create(REGION, "group", "duplicate", "value" + value, null);
                        winners.incrementAndGet();
                    } catch (AwsException expected) {
                        assertEquals("InvalidRequestException", expected.getErrorCode());
                    }
                    f.service.create(REGION, "group", "independent" + value, "q", null);
                    f.service.update(REGION, "group", "independent" + value, "updated" + value, null);
                }));
            }
            for (Future<?> write : writes) {
                write.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(1, winners.get());
        assertEquals(25, f.athena.preparedStatementWorkGroup(REGION, "group").getPreparedStatements().size());
        for (int i = 0; i < 24; i++) {
            assertEquals("updated" + i, f.service.get(REGION, "group", "independent" + i).queryStatement());
        }
    }

    @Test
    void workGroupUpdateWaitsForStatementCommitWithoutLosingEitherChange() throws Exception {
        ControlledStore store = new ControlledStore();
        Fixture f = new Fixture(store);
        f.createGroup("group");
        store.block = true;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> statement = executor.submit(() -> f.service.create(REGION, "group", "saved", "q", null));
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            CountDownLatch attempted = new CountDownLatch(1);
            Future<?> metadata = executor.submit(() -> {
                attempted.countDown();
                UpdateWorkGroupRequest request = new UpdateWorkGroupRequest();
                request.setWorkGroup("group");
                request.setDescription("updated metadata");
                f.athena.updateWorkGroup(request, REGION);
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertFalse(metadata.isDone());
            store.release.countDown();
            statement.get(5, TimeUnit.SECONDS);
            metadata.get(5, TimeUnit.SECONDS);
            assertEquals("updated metadata", f.athena.getWorkGroup("group", REGION).get("Description"));
            assertEquals("q", f.service.get(REGION, "group", "saved").queryStatement());
        } finally {
            store.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void deletionWaitsForInFlightCommitAndRecreationDoesNotInheritChildren() throws Exception {
        ControlledStore store = new ControlledStore();
        Fixture f = new Fixture(store);
        f.createGroup("group");
        store.block = true;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = executor.submit(() -> f.service.create(REGION, "group", "saved", "q", null));
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            CountDownLatch attempted = new CountDownLatch(1);
            Future<?> delete = executor.submit(() -> {
                attempted.countDown();
                f.deleteGroup("group");
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertFalse(delete.isDone());
            store.release.countDown();
            write.get(5, TimeUnit.SECONDS);
            delete.get(5, TimeUnit.SECONDS);
            assertThrows(AwsException.class, () -> f.service.get(REGION, "group", "saved"));
            assertThrows(AwsException.class, () -> f.service.create(REGION, "group", "later", "q", null));
            f.createGroup("group");
            assertTrue(f.athena.preparedStatementWorkGroup(REGION, "group").getPreparedStatements().isEmpty());
        } finally {
            store.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectedParentDeletionKeepsTheWorkGroupAndPreparedChildrenReachable() {
        ControlledStore store = new ControlledStore();
        Fixture f = new Fixture(store);
        f.createGroup("group");
        f.service.create(REGION, "group", "saved", "original", null);
        WorkGroup before = f.athena.preparedStatementWorkGroup(REGION, "group");
        store.failDelete = true;
        assertThrows(IllegalStateException.class, () -> f.deleteGroup("group"));
        assertSame(before, f.athena.preparedStatementWorkGroup(REGION, "group"));
        assertEquals("original", f.service.get(REGION, "group", "saved").queryStatement());
        store.failDelete = false;
        f.deleteGroup("group");
        assertThrows(AwsException.class, () -> f.service.get(REGION, "group", "saved"));
    }

    @Test
    void aWriteQueuedBehindDeletionRereadsTheMissingParentInsteadOfResurrectingIt() throws Exception {
        ControlledStore store = new ControlledStore();
        Fixture f = new Fixture(store);
        f.createGroup("group");
        f.service.create(REGION, "group", "saved", "original", null);
        store.blockDelete = true;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> f.deleteGroup("group"));
            assertTrue(store.deleteEntered.await(5, TimeUnit.SECONDS));
            CountDownLatch attempted = new CountDownLatch(1);
            Future<?> write = executor.submit(() -> {
                attempted.countDown();
                f.service.update(REGION, "group", "saved", "must not resurrect", null);
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertFalse(write.isDone());
            store.deleteRelease.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> write.get(5, TimeUnit.SECONDS));
            assertInstanceOf(AwsException.class, failure.getCause());
            assertThrows(AwsException.class, () -> f.athena.getWorkGroup("group", REGION));
            f.createGroup("group");
            assertTrue(f.athena.preparedStatementWorkGroup(REGION, "group").getPreparedStatements().isEmpty());
        } finally {
            store.deleteRelease.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void configuredStorageRoundTripsPreparedRecordsAndLegacyWorkGroups(String mode) throws Exception {
        StorageBackend<String, WorkGroup> backend = backend(mode);
        backend.load();
        WorkGroup legacy = new ObjectMapper().readValue("{\"Name\":\"legacy\",\"State\":\"ENABLED\"}", WorkGroup.class);
        WorkGroup legacyNull = new ObjectMapper().readValue(
                "{\"Name\":\"legacy-null\",\"PreparedStatements\":null}", WorkGroup.class);
        assertTrue(legacyNull.getPreparedStatements().isEmpty());
        backend.put("000000000000/" + REGION + ":legacy", legacy);
        Fixture f = new Fixture(backend);
        assertTrue(f.athena.preparedStatementWorkGroup(REGION, "legacy").getPreparedStatements().isEmpty());
        f.service.create(REGION, "legacy", "saved", "select ?", "retained");
        PreparedStatement saved = f.service.get(REGION, "legacy", "saved");
        assertTrue(saved.lastModifiedTime().isBefore(Instant.now().plusSeconds(1)));
        stop(backend);
        StorageBackend<String, WorkGroup> reopened = mode.equals("memory") ? backend : backend(mode);
        reopened.load();
        try {
            Fixture restored = new Fixture(reopened);
            assertEquals(saved, restored.service.get(REGION, "legacy", "saved"));
            restored.deleteGroup("legacy");
            restored.createGroup("legacy");
            assertTrue(restored.athena.preparedStatementWorkGroup(REGION, "legacy").getPreparedStatements().isEmpty());
        } finally {
            stop(reopened);
        }
    }

    private StorageBackend<String, WorkGroup> backend(String mode) {
        TypeReference<Map<String, WorkGroup>> type = new TypeReference<>() {};
        return switch (mode) {
            case "persistent" -> new PersistentStorage<>(directory.resolve("groups.json"), type);
            case "hybrid" -> new HybridStorage<>(directory.resolve("groups.json"), type, 60000);
            case "wal" -> new WalStorage<>(directory.resolve("groups.json"), directory.resolve("groups.wal"), type, 60000);
            default -> new InMemoryStorage<>();
        };
    }

    private static void stop(StorageBackend<String, WorkGroup> backend) {
        if (backend instanceof HybridStorage<String, WorkGroup> hybrid) {
            hybrid.shutdown();
        } else if (backend instanceof WalStorage<String, WorkGroup> wal) {
            wal.shutdown();
        } else {
            backend.flush();
        }
    }

    private static final class Fixture {
        private final AthenaService athena;
        private final AthenaPreparedStatementService service;
        private final StorageFactory factory;
        private final RegionResolver resolver;

        private Fixture(StorageBackend<String, WorkGroup> backend) {
            factory = mock(StorageFactory.class);
            doAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"))
                    .when(factory).create(anyString(), anyString(), any());
            doReturn(new AccountAwareStorageBackend<>(backend, null, "000000000000"))
                    .when(factory).create(eq("athena"), eq("workgroups.json"), any());
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().athena().mock()).thenReturn(false);
            resolver = mock(RegionResolver.class);
            when(resolver.getAccountId()).thenReturn("000000000000");
            athena = new AthenaService(factory, mock(FlociDuckClient.class), mock(GlueService.class),
                    mock(S3Service.class), config, mock(Vertx.class), mock(GlueViewDdlBuilder.class), resolver);
            service = new AthenaPreparedStatementService(athena);
        }

        private void createGroup(String name) {
            CreateWorkGroupRequest request = new CreateWorkGroupRequest();
            request.setName(name);
            athena.createWorkGroup(request, REGION);
        }

        private void deleteGroup(String name) {
            athena.deleteWorkGroup(name, REGION, true);
        }
    }

    private static final class ControlledStore extends InMemoryStorage<String, WorkGroup> {
        private volatile boolean failDelete;
        private volatile boolean blockDelete;
        private final CountDownLatch deleteEntered = new CountDownLatch(1);
        private final CountDownLatch deleteRelease = new CountDownLatch(1);
        private volatile boolean fail;
        private volatile boolean block;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void deleteAllAtomically(Set<String> keys) {
            if (failDelete) {
                throw new IllegalStateException("Injected deletion rejection before any write");
            }
            if (blockDelete) {
                deleteEntered.countDown();
                try {
                    if (!deleteRelease.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Deletion was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            super.deleteAllAtomically(keys);
        }

        @Override
        public void putAllAtomically(Map<String, WorkGroup> entries) {
            if (fail) {
                throw new IllegalStateException("Injected rejection before any write");
            }
            if (block) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Writer was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            super.putAllAtomically(entries);
        }
    }
}

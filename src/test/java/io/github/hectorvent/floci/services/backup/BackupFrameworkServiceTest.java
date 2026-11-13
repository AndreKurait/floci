package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.BackupFrameworkService.Catalog;
import io.github.hectorvent.floci.services.backup.BackupFrameworkService.Framework;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ParkingStore;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class BackupFrameworkServiceTest {

    private static final String ACCOUNT = "741677901019";
    private static final String REGION = "eu-west-2";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-15T01:02:03.456Z"), ZoneOffset.UTC);

    @Test
    void lateRetriesDoNotUndoUpdatesOrResurrectDeletedIncarnations() {
        BackupFrameworkService service = service(new InMemoryStorageFactory(ACCOUNT));
        ObjectNode request = request("Definition", 35);
        Framework created = service.create(REGION, request);
        assertEquals(CLOCK.millis(), created.createdMillis());
        assertEquals(created, service.create(REGION, request.deepCopy()));
        ObjectNode firstUpdate = MAPPER.createObjectNode().put("FrameworkDescription", "first")
                .put("IdempotencyToken", "first");
        service.update(REGION, "Definition", firstUpdate);
        service.update(REGION, "Definition", MAPPER.createObjectNode().put("FrameworkDescription", "latest")
                .put("IdempotencyToken", "latest"));
        service.update(REGION, "Definition", firstUpdate);
        assertEquals("latest", service.describe(REGION, "Definition").description());
        assertEquals(created.controls(), service.describe(REGION, "Definition").controls());
        assertEquals(created.createdMillis(), service.describe(REGION, "Definition").createdMillis());
        assertCode("InvalidParameterValueException", () -> service.update(REGION, "Definition",
                firstUpdate.deepCopy().put("FrameworkDescription", "changed token payload")));
        service.delete(REGION, "Definition");
        assertEquals(created.arn(), service.create(REGION, request).arn());
        assertTrue(service.list(REGION, null, null).items().isEmpty());
        assertCode("ResourceNotFoundException", () -> service.describe(REGION, "Definition"));
        Framework replacement = service.create(REGION, request.deepCopy().put("IdempotencyToken", "new-incarnation"));
        assertNotEquals(created.arn(), replacement.arn());
        assertCode("ResourceNotFoundException", () -> service.tag(REGION, created.arn(), Map.of("old", "refused")));
        assertEquals(Map.of("owner", "initial"), service.listTags(REGION, replacement.arn()));
    }

    @Test
    void duplicateAndInvalidChangesPreserveTheExistingDefinitionAndTags() {
        BackupFrameworkService service = service(new InMemoryStorageFactory(ACCOUNT));
        Framework first = service.create(REGION, request("First", 35));
        Framework second = service.create(REGION, request("Second", 60));
        assertCode("AlreadyExistsException", () -> service.create(REGION, request("Duplicate", 35)));
        assertCode("AlreadyExistsException", () -> service.update(REGION, "Second", request("Second", 35)));
        assertEquals(second, service.describe(REGION, "Second"));
        for (ObjectNode invalid : List.<ObjectNode>of(
                request("WrongName", 35).put("FrameworkName", "1wrong"),
                request("WrongName", 35).put("FrameworkDescription", " "),
                request("WrongName", 35).put("FrameworkControls", "not-a-list"),
                request("WrongName", 35).set("FrameworkControls", MAPPER.createArrayNode()),
                request("WrongName", 35).set("FrameworkTags", MAPPER.createObjectNode().put("number", 1)))) {
            assertCode("InvalidParameterValueException", () -> service.create(REGION, invalid));
        }
        assertEquals(List.of(first, second), service.list(REGION, null, null).items());
        service.tag(REGION, first.arn(), Map.of("owner", "updated", "other", "kept"));
        service.untag(REGION, first.arn(), List.of("owner", "absent"));
        assertEquals(Map.of("other", "kept"), service.listTags(REGION, first.arn()));
        assertThrows(UnsupportedOperationException.class, () -> service.listTags(REGION, first.arn()).put("bad", "value"));
        assertCode("ResourceNotFoundException", () -> service.listTags("us-west-2", first.arn()));
        assertCode("ResourceNotFoundException", () -> service.listTags(REGION, first.arn().replace(ACCOUNT, "999999999999")));
    }

    @Test
    void quotasAndPaginationUseActualDefinitionsWithinTheRequestRegion() {
        BackupFrameworkService service = service(new InMemoryStorageFactory(ACCOUNT));
        for (int i = 0; i < 15; i++) {
            service.create(REGION, request("Framework_%02d".formatted(i), i + 1));
        }
        assertCode("LimitExceededException", () -> service.create(REGION, request("Overflow", 60)));
        String token = service.list(REGION, 7, null).nextToken();
        assertEquals(7, service.list(REGION, 7, token).items().size());
        assertCode("InvalidParameterValueException", () -> service.list("us-west-2", 7, token));
        assertCode("InvalidParameterValueException", () -> service.list(REGION, 0, null));
        assertCode("InvalidParameterValueException", () -> service.list(REGION, 1001, null));
        assertCode("InvalidParameterValueException", () -> service.list(REGION, 1, "not a token"));
        assertTrue(service.list("us-west-2", null, null).items().isEmpty());
        service.create("us-west-2", request("Overflow", 60));
        assertEquals(15, service.list(REGION, null, null).items().size());
    }

    @Test
    void invalidControlNameParameterAndScopeAreRejectedBeforeWriting() {
        BackupFrameworkService service = service(new InMemoryStorageFactory(ACCOUNT));
        for (String control : List.of(
                "{\"ControlName\":\"UNRECOGNIZED\"}",
                "{\"ControlName\":\"BACKUP_RECOVERY_POINT_ENCRYPTED\",\"ControlInputParameters\":["
                        + "{\"ParameterName\":\"requiredRetentionDays\",\"ParameterValue\":\"35\"}]}",
                "{\"ControlName\":\"BACKUP_RECOVERY_POINT_MINIMUM_RETENTION_CHECK\",\"ControlInputParameters\":["
                        + "{\"ParameterName\":\"requiredRetentionDays\",\"ParameterValue\":\"-1\"}]}",
                "{\"ControlName\":\"BACKUP_RESOURCES_PROTECTED_BY_BACKUP_PLAN\",\"ControlScope\":"
                        + "{\"Tags\":{\"one\":\"value\",\"two\":\"value\"}}}")) {
            ObjectNode input = request("Invalid", 35);
            try {
                input.set("FrameworkControls", MAPPER.readTree("[" + control + "]"));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            assertCode("InvalidParameterValueException", () -> service.create(REGION, input));
        }
        assertTrue(service.list(REGION, null, null).items().isEmpty());
    }

    @Test
    void controlQuotaCountsEveryFrameworkAndAnUpdateCannotExceedIt() {
        BackupFrameworkService service = service(new InMemoryStorageFactory(ACCOUNT));
        List<String> otherControls = List.of(
                "BACKUP_RESOURCES_PROTECTED_BY_BACKUP_PLAN",
                "BACKUP_PLAN_MIN_FREQUENCY_AND_MIN_RETENTION_CHECK",
                "BACKUP_RECOVERY_POINT_MANUAL_DELETION_DISABLED",
                "BACKUP_RECOVERY_POINT_ENCRYPTED",
                "BACKUP_RESOURCES_PROTECTED_BY_CROSS_REGION",
                "BACKUP_RESOURCES_PROTECTED_BY_CROSS_ACCOUNT",
                "BACKUP_RESOURCES_PROTECTED_BY_BACKUP_VAULT_LOCK",
                "BACKUP_LAST_RECOVERY_POINT_CREATED",
                "RESTORE_TIME_FOR_RESOURCES_MEET_TARGET");
        for (int i = 1; i <= 5; i++) {
            ObjectNode request = request("Quota_" + i, i);
            otherControls.forEach(control -> request.withArray("FrameworkControls").addObject().put("ControlName", control));
            service.create(REGION, request);
        }
        assertCode("LimitExceededException", () -> service.create(REGION, request("OverControls", 100)));
        Framework original = service.describe(REGION, "Quota_1");
        ObjectNode update = MAPPER.createObjectNode().put("IdempotencyToken", "quota_update");
        update.set("FrameworkControls", MAPPER.valueToTree(original.controls()));
        update.withArray("FrameworkControls").addObject().put("ControlName", "RESOURCES_IN_LOGICALLY_AIR_GAPPED_VAULT");
        assertCode("LimitExceededException", () -> service.update(REGION, original.name(), update));
        assertEquals(original, service.describe(REGION, original.name()));
        assertEquals(50, service.list(REGION, null, null).items().stream().mapToInt(value -> value.controls().size()).sum());
    }

    @Test
    void failedAtomicCatalogWriteDoesNotPublishDefinitionTagsOrReplayReceipt() {
        AccountAwareStorageBackend<Catalog> store = spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
        BackupFrameworkService service = service(new InMemoryStorageFactory(ACCOUNT)
                .withStore("backup-framework-definitions.json", store));
        Framework old = service.create(REGION, request("Definition", 35));
        ObjectNode update = MAPPER.createObjectNode().put("FrameworkDescription", "changed").put("IdempotencyToken", "update");
        doThrow(new IllegalStateException("write refused")).when(store).put(eq(REGION), any());
        assertThrows(IllegalStateException.class, () -> service.update(REGION, "Definition", update));
        assertThrows(IllegalStateException.class, () -> service.tag(REGION, old.arn(), Map.of("new", "not published")));
        assertThrows(IllegalStateException.class, () -> service.delete(REGION, "Definition"));
        assertEquals(old, service.describe(REGION, "Definition"));
        doCallRealMethod().when(store).put(eq(REGION), any());
        service.update(REGION, "Definition", update);
        assertEquals("changed", service.describe(REGION, "Definition").description());
        assertEquals(old.tags(), service.describe(REGION, "Definition").tags());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentCreatesRetainBothDefinitionsOrRefuseTheSameName(boolean sameName) throws Exception {
        ParkingStore<Catalog> inner = new ParkingStore<>();
        AccountAwareStorageBackend<Catalog> store = new AccountAwareStorageBackend<>(inner, null, ACCOUNT);
        BackupFrameworkService service = service(new InMemoryStorageFactory(ACCOUNT)
                .withStore("backup-framework-definitions.json", store));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            inner.parkNextWrite();
            Future<Framework> first = workers.submit(() -> service.create(REGION, request("First", 35)));
            assertTrue(inner.awaitParked());
            AtomicReference<Thread> competing = new AtomicReference<>();
            Future<?> second = workers.submit(() -> {
                competing.set(Thread.currentThread());
                ObjectNode request = request(sameName ? "First" : "Second", 60)
                        .put("IdempotencyToken", "second-request");
                if (sameName) {
                    assertCode("AlreadyExistsException", () -> service.create(REGION, request));
                } else {
                    service.create(REGION, request);
                }
            });
            ThreadStates.awaitBlockedOrDone(competing, second);
            assertFalse(second.isDone(), "a competing create must wait for the account and Region catalog write");
            inner.release();
            Framework created = first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(created, service.describe(REGION, "First"));
            assertEquals(sameName ? 1 : 2, service.list(REGION, null, null).items().size());
            if (!sameName) {
                assertEquals("60", service.describe(REGION, "Second").controls().getFirst()
                        .parameters().getFirst().value());
            }
        } finally {
            inner.release();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void definitionsTagsAndReplayReceiptsSurviveReconstruction(String mode, @TempDir Path path) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(path.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("backup")).thenReturn(mode);
        when(access.storageFlushInterval("backup")).thenReturn(60000L);
        ObjectNode initial = request("Persisted", 35);
        ((ObjectNode) initial.path("FrameworkControls").get(0)).putObject("ControlScope");
        Framework created;
        StorageFactory storage = new StorageFactory(config, access);
        try {
            BackupFrameworkService service = service(storage);
            created = service.create(REGION, initial);
            service.tag(REGION, created.arn(), Map.of("after", "retained"));
            service.update(REGION, created.name(), MAPPER.createObjectNode().put("FrameworkDescription", "updated")
                    .put("IdempotencyToken", "later"));
        } finally {
            storage.shutdownAll();
        }
        StorageFactory reloaded = new StorageFactory(config, access);
        try {
            BackupFrameworkService service = service(reloaded);
            Framework restored = service.describe(REGION, created.name());
            assertEquals(created.arn(), restored.arn());
            assertEquals(created.createdMillis(), restored.createdMillis());
            assertEquals(created.controls(), restored.controls());
            assertEquals("retained", restored.tags().get("after"));
            assertEquals(created.arn(), service.create(REGION, initial).arn());
            assertEquals("updated", service.describe(REGION, created.name()).description());
            service.delete(REGION, created.name());
            assertCode("ResourceNotFoundException", () -> service.describe(REGION, created.name()));
        } finally {
            reloaded.shutdownAll();
        }
    }

    private static BackupFrameworkService service(StorageFactory storage) {
        return new BackupFrameworkService(storage, new RegionResolver(REGION, ACCOUNT), MAPPER, CLOCK);
    }

    private static ObjectNode request(String name, int retention) {
        ObjectNode result = MAPPER.createObjectNode().put("FrameworkName", name)
                .put("FrameworkDescription", "initial").put("IdempotencyToken", name);
        result.putObject("FrameworkTags").put("owner", "initial");
        result.putArray("FrameworkControls").addObject().put("ControlName", "BACKUP_RECOVERY_POINT_MINIMUM_RETENTION_CHECK")
                .putArray("ControlInputParameters").addObject().put("ParameterName", "requiredRetentionDays")
                .put("ParameterValue", Integer.toString(retention));
        return result;
    }

    private static void assertCode(String expected, Runnable operation) {
        assertEquals(expected, assertThrows(AwsException.class, operation::run).getErrorCode());
    }
}

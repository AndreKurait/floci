package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.applicationautoscaling.DynamoDbRegistrationTarget;
import io.floci.aws.services.applicationautoscaling.model.ScalableTarget;
import io.floci.aws.services.dynamodb.NativeDynamoDbTableService.CreateTableRequest;
import io.floci.aws.services.dynamodb.NativeDynamoDbTableService.GsiThroughputUpdate;
import io.floci.aws.services.dynamodb.NativeDynamoDbTableService.TableSettings;
import io.floci.aws.services.dynamodb.NativeDynamoDbTableService.Throughput;
import io.floci.aws.services.dynamodb.NativeDynamoDbTableService.UpdateTableRequest;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.GlobalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.ProvisionedThroughput;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import io.floci.aws.services.kinesis.KinesisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Timeout(90)
class DynamoDbRegistrationServiceTest {
    private static final String REGION = "us-east-1";
    private static final String NAME = "owned";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    @TempDir
    Path directory;

    enum Target {
        TABLE_READ(false, true), TABLE_WRITE(false, false),
        INDEX_READ(true, true), INDEX_WRITE(true, false);

        final boolean index;
        final boolean read;
        Target(boolean index, boolean read) {
            this.index = index;
            this.read = read;
        }
        String dimension() {
            return "dynamodb:" + (index ? "index:" : "table:") + (read ? "ReadCapacityUnits" : "WriteCapacityUnits");
        }
        String resource() {
            return "table/" + NAME + (index ? "/index/selected" : "");
        }
        GlobalSecondaryIndex index(TableDefinition table) {
            return table.findGsi("selected").orElseThrow();
        }
        ProvisionedThroughput throughput(TableDefinition table) {
            return index ? index(table).getProvisionedThroughput() : table.getProvisionedThroughput();
        }
        long capacity(TableDefinition table) {
            return read ? throughput(table).getReadCapacityUnits() : throughput(table).getWriteCapacityUnits();
        }
        long rawWarm(TableDefinition table) {
            if (index) {
                return read ? index(table).rawWarmReadUnitsPerSecond() : index(table).rawWarmWriteUnitsPerSecond();
            }
            return read ? table.rawWarmReadUnitsPerSecond() : table.rawWarmWriteUnitsPerSecond();
        }
        long warm(TableDefinition table) {
            if (index) {
                return read ? index(table).getWarmReadUnitsPerSecond(table.getBillingMode())
                        : index(table).getWarmWriteUnitsPerSecond(table.getBillingMode());
            }
            return read ? table.getWarmReadUnitsPerSecond() : table.getWarmWriteUnitsPerSecond();
        }
        void rawWarm(TableDefinition table, long value) {
            if (index) {
                if (read) {
                    index(table).setWarmReadUnitsPerSecond(value);
                } else {
                    index(table).setWarmWriteUnitsPerSecond(value);
                }
            } else if (read) {
                table.setWarmReadUnitsPerSecond(value);
            } else {
                table.setWarmWriteUnitsPerSecond(value);
            }
        }
    }

    static class TableStore extends InMemoryStorage<String, TableDefinition> {
        Consumer<TableDefinition> beforePut = ignored -> {};
        @Override
        public void put(String key, TableDefinition value) {
            beforePut.accept(value);
            super.put(key, value);
        }
    }

    static class TargetStore extends InMemoryStorage<String, ScalableTarget> {
        Runnable beforeCommit = () -> {};
        @Override
        public void putAllAtomically(Map<String, ScalableTarget> values) {
            beforeCommit.run();
            super.putAllAtomically(values);
        }
    }

    static class Fixture {
        final DynamoDbService service;
        final TableDefinition table;
        final StorageBackend<String, TableDefinition> tables;
        Fixture(StorageBackend<String, TableDefinition> tables) {
            this.tables = tables;
            service = new DynamoDbService(tables);
            GlobalSecondaryIndex selected = index("selected", 3, 3);
            GlobalSecondaryIndex sibling = index("sibling", 17, 19);
            table = service.createTable(NAME, List.of(new KeySchemaElement("pk", "HASH")),
                    List.of(new AttributeDefinition("pk", "S")),
                    3L, 3L, List.of(selected, sibling), REGION);
            table.setBillingMode("PROVISIONED");
            tables.put(REGION + "::" + NAME, table);
        }
        static GlobalSecondaryIndex index(String name, long read, long write) {
            GlobalSecondaryIndex index = new GlobalSecondaryIndex(name,
                    List.of(new KeySchemaElement("pk", "HASH")), null, "ALL", List.of());
            index.setProvisionedThroughput(new ProvisionedThroughput(read, write));
            return index;
        }
    }

    private static ScalableTarget candidate(Target target, int min, int max) {
        ScalableTarget candidate = new ScalableTarget();
        candidate.setServiceNamespace("dynamodb");
        candidate.setResourceId(target.resource());
        candidate.setScalableDimension(target.dimension());
        candidate.setScalableTargetArn("arn:aws:application-autoscaling:us-east-1:000000000000:scalable-target/owned");
        candidate.setRoleArn("arn:aws:iam::000000000000:role/owned");
        candidate.setCreationTime(123.25);
        candidate.setMinCapacity(min);
        candidate.setMaxCapacity(max);
        candidate.setTags(Map.of("original", "kept"));
        return candidate;
    }

    private static void register(Fixture fixture, Target target, int min, int max,
                                 StorageBackend<String, ScalableTarget> targets) {
        fixture.service.reconcileScalableTarget(target.resource(), target.dimension(), min, max, REGION,
                new DynamoDbRegistrationTarget(targets, "owned", candidate(target, min, max)));
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void targetCommitRefusalRestoresSelectedDecodedValuesAndKeepsLiveMetadata(Target target) {
        for (boolean existing : List.of(false, true)) {
            Fixture fixture = new Fixture(new TableStore());
            TargetStore targets = new TargetStore();
            if (existing) {
                targets.put("owned", candidate(target, 1, 5));
            }
            JsonNode previousTargets = JSON.valueToTree(targets.scan(key -> true));
            target.rawWarm(fixture.table, 0);
            JsonNode expected = JSON.valueToTree(fixture.table);
            expected.withObject("/tags").put("concurrent", "retained");
            RuntimeException refused = new IllegalStateException("target rejected before atomic replacement");
            ProvisionedThroughput originalThroughput = target.throughput(fixture.table);
            targets.beforeCommit = () -> {
                assertEquals(7, target.capacity(fixture.table));
                assertEquals(7, target.warm(fixture.table));
                fixture.service.tagResource(fixture.table.getTableArn(), Map.of("concurrent", "retained"), REGION);
                throw refused;
            };
            assertSame(refused, assertThrows(IllegalStateException.class,
                    () -> register(fixture, target, 7, 12, targets)));
            assertSame(fixture.table, fixture.tables.get(REGION + "::" + NAME).orElseThrow());
            assertSame(originalThroughput, target.throughput(fixture.table));
            assertEquals(0, target.rawWarm(fixture.table));
            assertEquals(expected, JSON.valueToTree(fixture.table));
            assertEquals(previousTargets, JSON.valueToTree(targets.scan(key -> true)));
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void observedTableFailureRestoresFieldsAndRetainsACompensationFailure(Target target) {
        TableStore tables = new TableStore();
        Fixture fixture = new Fixture(tables);
        target.rawWarm(fixture.table, 0);
        JsonNode before = JSON.valueToTree(fixture.table);
        TargetStore targets = new TargetStore();
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException first = new IllegalStateException("observable table write refusal");
        RuntimeException second = new IllegalStateException("observable compensation refusal");
        tables.beforePut = value -> {
            if (attempts.incrementAndGet() == 1) {
                assertEquals(7, target.capacity(value));
                throw first;
            }
            assertEquals(3, target.capacity(value));
            assertEquals(0, target.rawWarm(value));
            throw second;
        };
        assertSame(first, assertThrows(IllegalStateException.class, () -> register(fixture, target, 7, 12, targets)));
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
        assertEquals(2, attempts.get());
        assertEquals(before, JSON.valueToTree(fixture.table));
        assertTrue(targets.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void inBoundsAndInactiveResourcesPublishTargetsWithoutTableWrites(Target target) {
        TableStore tables = new TableStore();
        Fixture fixture = new Fixture(tables);
        TargetStore targets = new TargetStore();
        tables.beforePut = ignored -> fail("No table write is admitted");
        JsonNode before = JSON.valueToTree(fixture.table);
        register(fixture, target, 1, 5, targets);
        assertEquals(before, JSON.valueToTree(fixture.table));
        assertEquals(1, targets.get("owned").orElseThrow().getMinCapacity());
        for (String status : new String[] {"CREATING", "UPDATING", null, "UNKNOWN"}) {
            fixture.table.setTableStatus(status);
            JsonNode inactive = JSON.valueToTree(fixture.table);
            register(fixture, target, 7, 12, targets);
            assertEquals(inactive, JSON.valueToTree(fixture.table));
            assertEquals(7, targets.get("owned").orElseThrow().getMinCapacity());
            register(fixture, target, targets.get("owned").orElseThrow().getMinCapacity(),
                    targets.get("owned").orElseThrow().getMaxCapacity(), targets);
            assertEquals(inactive, JSON.valueToTree(fixture.table));
        }
        fixture.table.setTableStatus("ACTIVE");
        tables.beforePut = ignored -> {};
        register(fixture, target, 7, 12, targets);
        assertEquals(7, target.capacity(fixture.table));
    }

    @Test
    void missingIndexResourceShapeAndBoundsRefusalsPreserveCompleteStores() {
        Fixture fixture = new Fixture(new TableStore());
        TargetStore targets = new TargetStore();
        JsonNode before = JSON.valueToTree(fixture.table);
        for (Target target : Target.values()) {
            AwsException zero = assertThrows(AwsException.class, () -> register(fixture, target, 0, 7, targets));
            assertEquals("Minimum capacity cannot be less than 1", zero.getMessage());
            assertThrows(AwsException.class, () -> register(fixture, target, 9, 7, targets));
            String invalidResource = target.index ? "table/" + NAME : "table/" + NAME + "/index/selected";
            assertThrows(AwsException.class, () -> fixture.service.reconcileScalableTarget(
                    invalidResource, target.dimension(), 1, 5, REGION,
                    new DynamoDbRegistrationTarget(targets, "owned", candidate(target, 1, 5))));
        }
        for (String dimension : List.of("dynamodb:index:ReadCapacityUnits", "dynamodb:index:WriteCapacityUnits")) {
            AwsException missing = assertThrows(AwsException.class, () -> fixture.service.reconcileScalableTarget(
                    "table/" + NAME + "/index/missing", dimension, 1, 5, REGION,
                    new DynamoDbRegistrationTarget(targets, "owned", candidate(Target.INDEX_READ, 1, 5))));
            assertEquals("DynamoDB index does not exist: table/" + NAME + "/index/missing", missing.getMessage());
        }
        assertEquals(before, JSON.valueToTree(fixture.table));
        assertTrue(targets.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void nativeCapacityWritesHoldTheCanonicalLockThroughTheFinalPersist(Target target) {
        TableStore tables = new TableStore();
        Fixture fixture = new Fixture(tables);
        Object lock = fixture.service.tableControlLock(NAME, REGION);
        assertSame(lock, fixture.service.tableControlLock(fixture.table.getTableArn(), REGION));
        AtomicInteger writes = new AtomicInteger();
        tables.beforePut = value -> {
            assertTrue(Thread.holdsLock(lock));
            writes.incrementAndGet();
        };
        NativeDynamoDbTableService nativeTables = new NativeDynamoDbTableService(
                fixture.service, mock(DynamoDbStreamService.class), mock(KinesisService.class));
        Throughput throughput = new Throughput(target.read ? 31L : null, target.read ? null : 31L);
        TableSettings settings = new TableSettings(null, null, null, null, null, null, null, null, null);
        nativeTables.updateTable(new UpdateTableRequest(fixture.table.getTableArn(),
                target.index ? null : throughput, List.of(), List.of(), List.of(),
                target.index ? List.of(new GsiThroughputUpdate("selected", throughput, null)) : List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), settings), REGION);
        assertTrue(writes.get() >= 2, "Both the service and final native persistence were observed");
        assertEquals(31, target.capacity(fixture.table));
        assertEquals(31, target.warm(fixture.table));
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void persistentAndWalRestartsRetainRegisteredCapacityTargetsAndWarmPeaks(Target target) {
        for (boolean wal : List.of(false, true)) {
            Path path = directory.resolve(target.name() + "-" + wal);
            StorageBackend<String, TableDefinition> tables = tables(path, wal);
            StorageBackend<String, ScalableTarget> targets = targets(path, wal);
            try {
                Fixture fixture = new Fixture(tables);
                target.rawWarm(fixture.table, 0);
                register(fixture, target, 31, 31, targets);
                register(fixture, target, 7, 7, targets);
                assertEquals(7, target.capacity(fixture.table));
                assertEquals(31, target.warm(fixture.table));
                tables.flush();
                targets.flush();
            } finally {
                close(tables);
                close(targets);
            }
            StorageBackend<String, TableDefinition> restoredTables = tables(path, wal);
            StorageBackend<String, ScalableTarget> restoredTargets = targets(path, wal);
            try {
                TableDefinition restored = restoredTables.get(REGION + "::" + NAME).orElseThrow();
                assertEquals(7, target.capacity(restored));
                assertEquals(31, target.rawWarm(restored));
                assertEquals(31, target.warm(restored));
                assertEquals(JSON.valueToTree(candidate(target, 7, 7)),
                        JSON.valueToTree(restoredTargets.get("owned").orElseThrow()));
            } finally {
                close(restoredTables);
                close(restoredTargets);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void observableTargetRefusalRestoresRawWarmOnPersistentAndWalReload(Target target) {
        for (boolean wal : List.of(false, true)) {
            for (long raw : List.of(0L, 1L, 31L)) {
                Path path = directory.resolve("rollback-" + target.name() + "-" + wal + "-" + raw);
                StorageBackend<String, TableDefinition> tables = tables(path, wal);
                JsonNode before;
                try {
                    Fixture fixture = new Fixture(tables);
                    target.rawWarm(fixture.table, raw);
                    tables.put(REGION + "::" + NAME, fixture.table);
                    before = JSON.valueToTree(fixture.table);
                    TargetStore targets = new TargetStore();
                    targets.beforeCommit = () -> { throw new IllegalStateException("before target commit"); };
                    assertThrows(IllegalStateException.class, () -> register(fixture, target, 7, 12, targets));
                    assertTrue(targets.isEmpty());
                } finally {
                    close(tables);
                }
                StorageBackend<String, TableDefinition> restoredTables = tables(path, wal);
                try {
                    TableDefinition restored = restoredTables.get(REGION + "::" + NAME).orElseThrow();
                    assertEquals(before, JSON.valueToTree(restored));
                    assertEquals(raw, target.rawWarm(restored));
                    assertEquals(Math.max(3, raw), target.warm(restored));
                } finally {
                    close(restoredTables);
                }
            }
        }
    }

    @Test
    void missingLegacyPeakMembersDecodeAsZeroAndSerializeWithTheOriginalNames() throws Exception {
        Fixture fixture = new Fixture(new TableStore());
        ObjectNode encoded = JSON.valueToTree(fixture.table);
        encoded.remove(List.of("warmReadUnitsPerSecond", "warmWriteUnitsPerSecond"));
        for (JsonNode index : encoded.path("globalSecondaryIndexes")) {
            assertInstanceOf(ObjectNode.class, index).remove(List.of("warmReadUnitsPerSecond", "warmWriteUnitsPerSecond"));
        }
        TableDefinition decoded = JSON.treeToValue(encoded, TableDefinition.class);
        for (Target target : Target.values()) {
            assertEquals(0, target.rawWarm(decoded));
            assertEquals(3, target.warm(decoded));
        }
        JsonNode reencoded = JSON.valueToTree(decoded);
        assertEquals(0, reencoded.path("warmReadUnitsPerSecond").longValue());
        assertEquals(0, reencoded.path("warmWriteUnitsPerSecond").longValue());
        assertTrue(reencoded.has("warmReadUnitsPerSecond"));
        assertFalse(reencoded.has("rawWarmReadUnitsPerSecond"));
        for (JsonNode index : reencoded.path("globalSecondaryIndexes")) {
            assertTrue(index.has("warmReadUnitsPerSecond"));
            assertTrue(index.has("warmWriteUnitsPerSecond"));
            assertEquals(0, index.path("warmReadUnitsPerSecond").longValue());
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void registrationKeepsNativeUpdateAndDeleteOutsideItsPublicationInterval(Target target) throws Exception {
        for (boolean delete : List.of(false, true)) {
            Fixture fixture = new Fixture(new TableStore());
            TargetStore targets = new TargetStore();
            AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
            AtomicReference<Thread> background = new AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1);
            long owner = Thread.currentThread().threadId();
            targets.beforeCommit = () -> {
                assertTrue(Thread.holdsLock(fixture.service.tableControlLock(NAME, REGION)));
                Thread competitor = Thread.ofPlatform().start(() -> {
                    entered.countDown();
                    try {
                        if (delete) {
                            fixture.service.deleteTable(fixture.table.getTableArn(), REGION);
                        } else {
                            nativeUpdate(fixture, target, 31);
                        }
                    } catch (Throwable failure) {
                        backgroundFailure.set(failure);
                    }
                });
                background.set(competitor);
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    boolean blocked = false;
                    while (System.nanoTime() < deadline) {
                        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(competitor.threadId());
                        if (info != null && info.getThreadState() == Thread.State.BLOCKED
                                && info.getLockOwnerId() == owner) {
                            blocked = true;
                            break;
                        }
                        Thread.sleep(1);
                    }
                    assertTrue(blocked, "The actual competing writer must block on this registration's monitor");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            };
            try {
                register(fixture, target, 7, 12, targets);
            } finally {
                if (background.get() != null) {
                    background.get().join(TimeUnit.SECONDS.toMillis(5));
                }
            }
            assertNotNull(background.get());
            assertFalse(background.get().isAlive());
            assertNull(backgroundFailure.get());
            assertEquals(7, targets.get("owned").orElseThrow().getMinCapacity());
            if (delete) {
                assertTrue(fixture.tables.isEmpty());
            } else {
                assertEquals(31, target.capacity(fixture.table));
                assertEquals(31, target.warm(fixture.table));
            }
        }
    }

    @Test
    void nativeCreateKeepsBillingFinalizationInsideTheCanonicalLock() {
        TableStore tables = new TableStore();
        DynamoDbService service = new DynamoDbService(tables);
        Object lock = service.tableControlLock(NAME, REGION);
        AtomicInteger writes = new AtomicInteger();
        tables.beforePut = table -> {
            assertTrue(Thread.holdsLock(lock));
            writes.incrementAndGet();
        };
        NativeDynamoDbTableService nativeTables = new NativeDynamoDbTableService(
                service, mock(DynamoDbStreamService.class), mock(KinesisService.class));
        TableSettings settings = new TableSettings("PAY_PER_REQUEST", null, null, null, null, null, null, null, null);
        TableDefinition table = nativeTables.createTable(new CreateTableRequest(NAME,
                List.of(new KeySchemaElement("pk", "HASH")), List.of(new AttributeDefinition("pk", "S")),
                null, List.of(), List.of(), List.of(), Map.of(), settings), "ACTIVE", REGION);
        assertTrue(writes.get() >= 2);
        assertEquals("PAY_PER_REQUEST", table.getBillingMode());
        assertEquals(12000, table.getWarmReadUnitsPerSecond());
        assertEquals(4000, table.getWarmWriteUnitsPerSecond());
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void creationUsesRequestedCapacityAndLaterIndexCreationRetainsItsOwnPeak(Target target) {
        TableStore tables = new TableStore();
        DynamoDbService service = new DynamoDbService(tables);
        NativeDynamoDbTableService nativeTables = new NativeDynamoDbTableService(
                service, mock(DynamoDbStreamService.class), mock(KinesisService.class));
        TableSettings settings = new TableSettings("PROVISIONED", null, null, null, null, null, null, null, null);
        TableDefinition table = nativeTables.createTable(new CreateTableRequest(NAME,
                List.of(new KeySchemaElement("pk", "HASH")), List.of(new AttributeDefinition("pk", "S")),
                new Throughput(1L, 1L), List.of(Fixture.index("selected", 1, 1)), List.of(),
                List.of(), Map.of(), settings), "ACTIVE", REGION);
        assertEquals(1, target.capacity(table));
        assertEquals(1, target.rawWarm(table));
        assertEquals(1, target.warm(table));
        TableSettings unchanged = new TableSettings(null, null, null, null, null, null, null, null, null);
        nativeTables.updateTable(new UpdateTableRequest(NAME, null,
                List.of(new AttributeDefinition("pk", "S")), List.of(Fixture.index("later", 31, 37)), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), unchanged), REGION);
        GlobalSecondaryIndex added = table.findGsi("later").orElseThrow();
        assertEquals(31, added.rawWarmReadUnitsPerSecond());
        assertEquals(37, added.rawWarmWriteUnitsPerSecond());
        nativeTables.updateTable(new UpdateTableRequest(NAME, null, List.of(), List.of(), List.of(),
                List.of(new GsiThroughputUpdate("later", new Throughput(7L, 11L), null)), List.of(),
                List.of(), List.of(), List.of(), List.of(), unchanged), REGION);
        assertEquals(7, added.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(11, added.getProvisionedThroughput().getWriteCapacityUnits());
        assertEquals(31, added.rawWarmReadUnitsPerSecond());
        assertEquals(37, added.rawWarmWriteUnitsPerSecond());
        assertEquals(1, target.capacity(table));
        assertEquals(1, target.warm(table));
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void anAlreadyHigherPeakSurvivesCapacityDecreasesAndBillingRoundTrips(Target target) {
        Fixture fixture = new Fixture(new TableStore());
        target.rawWarm(fixture.table, 50000);
        nativeUpdate(fixture, target, 31);
        nativeUpdate(fixture, target, 7);
        assertEquals(50000, target.warm(fixture.table));
        NativeDynamoDbTableService nativeTables = new NativeDynamoDbTableService(
                fixture.service, mock(DynamoDbStreamService.class), mock(KinesisService.class));
        for (String billing : List.of("PAY_PER_REQUEST", "PROVISIONED")) {
            TableSettings settings = new TableSettings(billing, null, null, null, null, null, null, null, null);
            nativeTables.updateTable(new UpdateTableRequest(NAME,
                    "PROVISIONED".equals(billing) ? new Throughput(11L, 13L) : null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), settings), REGION);
            assertEquals(billing, fixture.table.getBillingMode());
            assertEquals(50000, target.warm(fixture.table));
            assertEquals(50000, target.rawWarm(fixture.table));
        }
    }

    private static void nativeUpdate(Fixture fixture, Target target, long value) {
        NativeDynamoDbTableService nativeTables = new NativeDynamoDbTableService(
                fixture.service, mock(DynamoDbStreamService.class), mock(KinesisService.class));
        Throughput throughput = new Throughput(target.read ? value : null, target.read ? null : value);
        TableSettings settings = new TableSettings(null, null, null, null, null, null, null, null, null);
        nativeTables.updateTable(new UpdateTableRequest(fixture.table.getTableArn(),
                target.index ? null : throughput, List.of(), List.of(), List.of(),
                target.index ? List.of(new GsiThroughputUpdate("selected", throughput, null)) : List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), settings), REGION);
    }

    private static StorageBackend<String, TableDefinition> tables(Path path, boolean wal) {
        TypeReference<Map<String, TableDefinition>> type = new TypeReference<>() {};
        StorageBackend<String, TableDefinition> store = wal
                ? new WalStorage<>(path.resolve("tables.json"), path.resolve("tables.wal"), type, Long.MAX_VALUE)
                : new PersistentStorage<>(path.resolve("tables.json"), type);
        store.load();
        return store;
    }

    private static StorageBackend<String, ScalableTarget> targets(Path path, boolean wal) {
        TypeReference<Map<String, ScalableTarget>> type = new TypeReference<>() {};
        StorageBackend<String, ScalableTarget> store = wal
                ? new WalStorage<>(path.resolve("targets.json"), path.resolve("targets.wal"), type, Long.MAX_VALUE)
                : new PersistentStorage<>(path.resolve("targets.json"), type);
        store.load();
        return store;
    }

    private static void close(StorageBackend<?, ?> store) {
        if (store instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        } else {
            store.flush();
        }
    }
}

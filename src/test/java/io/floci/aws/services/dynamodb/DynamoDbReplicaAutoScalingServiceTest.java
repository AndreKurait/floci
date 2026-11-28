package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.HybridStorage;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.dynamodb.DynamoDbReplicaAutoScalingService.Description;
import io.floci.aws.services.dynamodb.model.ReplicaAutoScalingState;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DynamoDbReplicaAutoScalingServiceTest {
    private static final String REGION = "us-east-1";
    private static final String TABLE = "autoscale-service";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, TableDefinition>> TABLE_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, ReplicaAutoScalingState>> STATE_TYPE = new TypeReference<>() { };
    private static final String POPULATED = """
            {"TableName":"autoscale-service","ProvisionedWriteCapacityAutoScalingUpdate":{
              "MinimumUnits":9007199254740993,"MaximumUnits":9007199254740999,"AutoScalingDisabled":false,
              "AutoScalingRoleArn":"arn:aws:iam::000000000000:role/configured-only",
              "ScalingPolicyUpdate":{"PolicyName":"configured-policy","TargetTrackingScalingPolicyConfiguration":{
                "TargetValue":65.5,"DisableScaleIn":false,"ScaleInCooldown":0,"ScaleOutCooldown":0}}},
              "GlobalSecondaryIndexUpdates":[{"IndexName":"by-category",
                "ProvisionedWriteCapacityAutoScalingUpdate":{"MinimumUnits":2,"MaximumUnits":40}}],
              "ReplicaUpdates":[{"RegionName":"us-west-2",
                "ReplicaProvisionedReadCapacityAutoScalingUpdate":{"MinimumUnits":3,"MaximumUnits":30},
                "ReplicaGlobalSecondaryIndexUpdates":[{"IndexName":"by-category",
                  "ProvisionedReadCapacityAutoScalingUpdate":{"MinimumUnits":4,"MaximumUnits":50}}]}]}
            """;

    enum Mode {
        MEMORY, PERSISTENT, HYBRID, WAL
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void populatedPartialUpdatesAndDeletionSurviveStorageLifecycle(Mode mode, @TempDir Path directory) throws Exception {
        StorageBackend<String, TableDefinition> tableStore = storage(mode, directory, "tables", TABLE_TYPE);
        StorageBackend<String, ReplicaAutoScalingState> stateStore = storage(mode, directory, "settings", STATE_TYPE);
        try {
            Fixture fixture = fixture(tableStore, stateStore, true);
            fixture.settings.update(parse(POPULATED), REGION);
            Description expected = fixture.settings.update(parse("""
                    {"TableName":"autoscale-service","ProvisionedWriteCapacityAutoScalingUpdate":{
                      "MaximumUnits":9007199254741001,"ScalingPolicyUpdate":{
                        "TargetTrackingScalingPolicyConfiguration":{"TargetValue":70.25}}}}
                    """), REGION);
            assertEquals(9007199254740993L, expected.replicas().getFirst().write().minimumUnits());
            assertEquals(0, expected.replicas().getFirst().write().policy().targetTracking().scaleInCooldown());
            assertFalse(expected.replicas().getFirst().write().disabled());
            assertEquals("configured-policy", expected.replicas().getFirst().write().policy().name());
            assertEquals(3L, expected.replicas().get(1).read().minimumUnits());
            assertEquals(4L, expected.replicas().get(1).indexes().getFirst().read().minimumUnits());
            tableStore.flush();
            stateStore.flush();
            if (mode != Mode.MEMORY) {
                close(tableStore);
                close(stateStore);
                tableStore = storage(mode, directory, "tables", TABLE_TYPE);
                stateStore = storage(mode, directory, "settings", STATE_TYPE);
                fixture = fixture(tableStore, stateStore, false);
            }
            assertEquals(expected, fixture.settings.describe(TABLE, REGION));
            fixture.tables.deleteTable(TABLE, REGION);
            assertTrue(stateStore.scan(key -> true).isEmpty());
            fixture.handler.handle("CreateTable", MAPPER.readTree(createRequest()), REGION);
            assertNull(fixture.settings.describe(TABLE, REGION).replicas().getFirst().write());
        } finally {
            close(tableStore);
            close(stateStore);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"MinimumUnits\":0}", "{\"MinimumUnits\":1.5}", "{\"MinimumUnits\":\"5\"}",
            "{\"MaximumUnits\":9223372036854775808}", "{\"AutoScalingDisabled\":\"false\"}",
            "{\"AutoScalingRoleArn\":\"\"}", "{\"ScalingPolicyUpdate\":{}}",
            "{\"ScalingPolicyUpdate\":{\"TargetTrackingScalingPolicyConfiguration\":{}}}",
            "{\"ScalingPolicyUpdate\":{\"TargetTrackingScalingPolicyConfiguration\":{\"TargetValue\":0}}}",
            "{\"ScalingPolicyUpdate\":{\"TargetTrackingScalingPolicyConfiguration\":{\"TargetValue\":1e109}}}",
            "{\"ScalingPolicyUpdate\":{\"TargetTrackingScalingPolicyConfiguration\":{\"TargetValue\":65,\"ScaleInCooldown\":0.5}}}"
    })
    void malformedTypedSettingsNeverChangePopulatedState(String invalid) throws Exception {
        Fixture fixture = fixture(new InMemoryStorage<>(), new InMemoryStorage<>(), true);
        Description original = fixture.settings.update(parse(POPULATED), REGION);
        assertThrows(AwsException.class, () -> fixture.settings.update(parse(
                "{\"TableName\":\"" + TABLE + "\",\"ProvisionedWriteCapacityAutoScalingUpdate\":" + invalid + "}"), REGION));
        assertEquals(original, fixture.settings.describe(TABLE, REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"ProvisionedWriteCapacityAutoScalingUpdate\":{\"MinimumUnits\":9007199254741000}",
            "\"ReplicaUpdates\":[{\"RegionName\":\"absent\"}]",
            "\"GlobalSecondaryIndexUpdates\":[{\"IndexName\":\"absent\"}]",
            "\"GlobalSecondaryIndexUpdates\":[{}]",
            "\"ReplicaUpdates\":[{\"RegionName\":\"us-west-2\"},{\"RegionName\":\"us-west-2\"}]",
            "\"GlobalSecondaryIndexUpdates\":[{\"IndexName\":\"by-category\"},{\"IndexName\":\"by-category\"}]",
            "\"ReplicaUpdates\":[]", "\"GlobalSecondaryIndexUpdates\":[]",
            "\"ReplicaUpdates\":[{\"RegionName\":\"us-west-2\",\"ReplicaProvisionedReadCapacityAutoScalingUpdate\":{\"MinimumUnits\":31}}]"
    })
    void semanticRefusalRollsBackAllEarlierUpdates(String invalidMember) throws Exception {
        Fixture fixture = fixture(new InMemoryStorage<>(), new InMemoryStorage<>(), true);
        Description original = fixture.settings.update(parse(POPULATED), REGION);
        assertThrows(AwsException.class, () -> fixture.settings.update(parse(
                "{\"TableName\":\"" + TABLE + "\",\"GlobalSecondaryIndexUpdates\":[{\"IndexName\":\"by-category\","
                + "\"ProvisionedWriteCapacityAutoScalingUpdate\":{\"MaximumUnits\":100}}]," + invalidMember + "}"), REGION));
        assertEquals(original, fixture.settings.describe(TABLE, REGION));
    }

    @Test
    void rejectedAtomicStoreWriteLeavesOriginalImmutableState() throws Exception {
        RefusingStorage store = new RefusingStorage();
        Fixture fixture = fixture(new InMemoryStorage<>(), store, true);
        Description original = fixture.settings.update(parse(POPULATED), REGION);
        store.refuse = true;
        assertThrows(IllegalStateException.class, () -> fixture.settings.update(parse("""
                {"TableName":"autoscale-service","ProvisionedWriteCapacityAutoScalingUpdate":{"AutoScalingDisabled":true}}
                """), REGION));
        assertEquals(original, fixture.settings.describe(TABLE, REGION));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void settingsDeletionFailureCannotInterruptExistingTableTeardown(boolean hasSettings) throws Exception {
        InMemoryStorage<String, TableDefinition> tableStore = new InMemoryStorage<>();
        InMemoryStorage<String, Map<String, JsonNode>> itemStore = new InMemoryStorage<>();
        RefusingDeleteStorage settingsStore = new RefusingDeleteStorage();
        DynamoDbStreamService streams = mock(DynamoDbStreamService.class);
        KinesisStreamingForwarder forwarder = mock(KinesisStreamingForwarder.class);
        DynamoDbService tables = new DynamoDbService(tableStore, itemStore,
                new RegionResolver(REGION, "000000000000"), streams, forwarder, settingsStore);
        NativeDynamoDbJsonHandler handler = new NativeDynamoDbJsonHandler(tables, streams, null, MAPPER);
        handler.handle("CreateTable", MAPPER.readTree(createRequest()), REGION);
        DynamoDbReplicaAutoScalingService settings = new DynamoDbReplicaAutoScalingService(tables);
        JsonNode item = MAPPER.readTree("{\"pk\":{\"S\":\"existing-item\"}}");
        tables.putItem(TABLE, item, REGION);
        assertEquals(item, tables.getItem(TABLE, item, REGION));
        assertFalse(itemStore.isEmpty(), "the existing item store must actually contain data");
        String arn = tables.describeTable(TABLE, REGION).getTableArn();
        if (hasSettings) {
            settings.update(parse("""
                    {"TableName":"autoscale-service","ProvisionedWriteCapacityAutoScalingUpdate":{"MinimumUnits":3}}
                    """), REGION);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> tables.deleteTable(TABLE, REGION));
            assertEquals("simulated settings deletion failure", failure.getMessage());
            assertEquals(1, settingsStore.deleteCalls);
            assertTrue(settingsStore.get(arn).isPresent(), "the failed atomic deletion retains its old record");
        } else {
            assertDoesNotThrow(() -> tables.deleteTable(TABLE, REGION));
            assertEquals(0, settingsStore.deleteCalls, "tables without settings must not write the settings file");
        }
        assertTrue(tables.findTable(TABLE, REGION).isEmpty());
        assertTrue(itemStore.isEmpty(), "original item-store teardown must finish despite the new file failure");
        verify(streams).deleteStream(arn);
        verify(forwarder).onTableDeleted("000000000000", REGION, TABLE);
        handler.handle("CreateTable", MAPPER.readTree(createRequest()), REGION);
        assertNull(tables.getItem(TABLE, item, REGION), "in-memory item state must not survive recreation");
        assertNull(settings.describe(TABLE, REGION).replicas().getFirst().write());
    }

    @Test
    void staleRecordCannotAttachToRecreatedTableEvenIfCleanupWasMissed() throws Exception {
        InMemoryStorage<String, TableDefinition> tableStore = new InMemoryStorage<>();
        InMemoryStorage<String, ReplicaAutoScalingState> stateStore = new InMemoryStorage<>();
        Fixture fixture = fixture(tableStore, stateStore, true);
        fixture.settings.update(parse(POPULATED), REGION);
        String originalId = fixture.tables.describeTable(TABLE, REGION).getTableId();
        tableStore.delete(REGION + "::" + TABLE);
        fixture.handler.handle("CreateTable", MAPPER.readTree(createRequest()), REGION);
        assertNotEquals(originalId, fixture.tables.describeTable(TABLE, REGION).getTableId());
        assertNull(fixture.settings.describe(TABLE, REGION).replicas().getFirst().write());
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "delete"})
    void nameAndArnMutationsShareTheTableLock(String secondOperation) throws Exception {
        PausingStorage store = new PausingStorage();
        Fixture fixture = fixture(new InMemoryStorage<>(), store, true);
        String arn = fixture.tables.describeTable(TABLE, REGION).getTableArn();
        fixture.settings.update(parse(POPULATED), REGION);
        store.pause = true;
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                firstThread.set(Thread.currentThread());
                return fixture.settings.update(parse("{\"TableName\":\"" + arn
                        + "\",\"ProvisionedWriteCapacityAutoScalingUpdate\":{\"MaximumUnits\":9007199254741010}}"), REGION);
            });
            try {
                assertTrue(store.atWrite.await(5, TimeUnit.SECONDS));
                Future<?> second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    if ("delete".equals(secondOperation)) {
                        fixture.tables.deleteTable(TABLE, REGION);
                    } else {
                        fixture.settings.update(parse("""
                                {"TableName":"autoscale-service","ProvisionedWriteCapacityAutoScalingUpdate":{
                                  "MinimumUnits":9007199254741005}}
                                """), REGION);
                    }
                    return null;
                });
                await().atMost(Duration.ofSeconds(5)).until(() -> {
                    if (second.isDone()) {
                        return true;
                    }
                    Thread waiting = secondThread.get();
                    ThreadInfo info = waiting == null ? null
                            : ManagementFactory.getThreadMXBean().getThreadInfo(waiting.threadId());
                    return info != null && info.getLockOwnerId() == firstThread.get().threadId();
                });
                store.resume.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                if ("delete".equals(secondOperation)) {
                    assertTrue(fixture.tables.findTable(TABLE, REGION).isEmpty());
                    assertTrue(store.scan(key -> true).isEmpty());
                } else {
                    Description result = fixture.settings.describe(TABLE, REGION);
                    assertEquals(9007199254741005L, result.replicas().getFirst().write().minimumUnits());
                    assertEquals(9007199254741010L, result.replicas().getFirst().write().maximumUnits());
                }
            } finally {
                store.resume.countDown();
            }
        }
    }

    private record Fixture(DynamoDbService tables, DynamoDbReplicaAutoScalingService settings,
                           NativeDynamoDbJsonHandler handler) {}

    private static Fixture fixture(StorageBackend<String, TableDefinition> tables,
                                   StorageBackend<String, ReplicaAutoScalingState> settings, boolean create)
            throws Exception {
        DynamoDbService service = new DynamoDbService(tables, new RegionResolver(REGION, "000000000000"), settings);
        NativeDynamoDbJsonHandler handler = new NativeDynamoDbJsonHandler(service, null, null, MAPPER);
        if (create) {
            handler.handle("CreateTable", MAPPER.readTree(createRequest()), REGION);
            handler.handle("UpdateTable", MAPPER.readTree("""
                    {"TableName":"autoscale-service","ReplicaUpdates":[{"Create":{"RegionName":"us-west-2"}}]}
                    """), REGION);
        }
        return new Fixture(service, new DynamoDbReplicaAutoScalingService(service), handler);
    }

    private static String createRequest() {
        return """
                {"TableName":"autoscale-service","BillingMode":"PAY_PER_REQUEST",
                 "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"},
                   {"AttributeName":"category","AttributeType":"S"}],
                 "GlobalSecondaryIndexes":[{"IndexName":"by-category",
                   "KeySchema":[{"AttributeName":"category","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}}]}
                """;
    }

    private static ReplicaAutoScalingRequest parse(String json) throws Exception {
        return ReplicaAutoScalingRequest.parse(MAPPER.readTree(json));
    }

    private static final class RefusingStorage extends InMemoryStorage<String, ReplicaAutoScalingState> {
        private boolean refuse;

        @Override
        public void putAllAtomically(Map<String, ReplicaAutoScalingState> entries) {
            if (refuse) {
                throw new IllegalStateException("simulated atomic persistence failure");
            }
            super.putAllAtomically(entries);
        }
    }

    private static final class RefusingDeleteStorage extends InMemoryStorage<String, ReplicaAutoScalingState> {
        private int deleteCalls;

        @Override
        public void deleteAllAtomically(Set<String> keys) {
            deleteCalls++;
            throw new IllegalStateException("simulated settings deletion failure");
        }
    }

    private static final class PausingStorage extends InMemoryStorage<String, ReplicaAutoScalingState> {
        private final CountDownLatch atWrite = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private volatile boolean pause;

        @Override
        public void putAllAtomically(Map<String, ReplicaAutoScalingState> entries) {
            if (pause) {
                atWrite.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to resume auto scaling mutation");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            super.putAllAtomically(entries);
        }
    }

    private static <T> StorageBackend<String, T> storage(Mode mode, Path directory, String name,
                                                        TypeReference<Map<String, T>> type) {
        StorageBackend<String, T> storage = switch (mode) {
            case MEMORY -> new InMemoryStorage<>();
            case PERSISTENT -> new PersistentStorage<>(directory.resolve(name + ".json"), type);
            case HYBRID -> new HybridStorage<>(directory.resolve(name + ".json"), type, 60_000);
            case WAL -> new WalStorage<>(directory.resolve(name + ".json"), directory.resolve(name + ".wal"), type, 60_000);
        };
        storage.load();
        return storage;
    }

    private static void close(StorageBackend<String, ?> storage) {
        if (storage instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        } else if (storage instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        } else {
            storage.flush();
        }
    }
}

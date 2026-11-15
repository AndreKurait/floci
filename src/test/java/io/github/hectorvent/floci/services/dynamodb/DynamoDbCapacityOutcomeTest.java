package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.MapEntryWriter;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.ProvisionedThroughput;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Timeout(15)
class DynamoDbCapacityOutcomeTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String TABLE = "owned";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final AccountAwareStorageBackend<TableDefinition> tables =
            AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final EntryStore itemStore = new EntryStore();
    private DynamoDbService service;
    private TableDefinition table;
    private DynamoDbCapacityMetrics publisher;

    @BeforeEach
    void createOwnedTableAndIndexInTheActualService() {
        service = new DynamoDbService(tables, new AccountAwareStorageBackend<>(itemStore, null, ACCOUNT),
                new RegionResolver(REGION, ACCOUNT));
        table = new TableDefinition(TABLE, List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("gk", "S")), REGION, ACCOUNT);
        GlobalSecondaryIndex index = new GlobalSecondaryIndex("focused", List.of(new KeySchemaElement("gk", "HASH")),
                null, "ALL", List.of());
        index.setProvisionedThroughput(new ProvisionedThroughput(3, 3));
        table.setGlobalSecondaryIndexes(List.of(index));
        tables.put(REGION + "::" + TABLE, table);
    }

    @AfterEach
    void releaseOnlyOwnedExecutors() {
        workers.shutdownNow();
        if (publisher != null) {
            publisher.stop();
        }
    }

    @Test
    void competingWritesChargeTheImagesActuallyObservedUnderTheSameItemLock() throws Exception {
        service.putItem(TABLE, item("same", 20), REGION);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<DynamoDbService.ItemWriteResult> first = workers.submit(() -> service.putItemWithCapacity(
                TABLE, item("same", 6000), null, null, null, REGION, "NONE", previous -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }));
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<DynamoDbService.ItemWriteResult> second = workers.submit(() -> {
                secondThread.set(Thread.currentThread());
                return service.putItemWithCapacity(TABLE, item("same", 20), null, null, null,
                        REGION, "NONE", null);
            });
            awaitBlockedOrDone(secondThread, second);
            assertFalse(second.isDone(), "The second operation has not observed a stale image before the lock");
            release.countDown();
            assertEquals(6, first.get(2, TimeUnit.SECONDS).cost().table());
            DynamoDbService.ItemWriteResult actual = second.get(2, TimeUnit.SECONDS);
            assertEquals(item("same", 6000), actual.oldItem());
            assertEquals(6, actual.cost().table());
            assertEquals(Map.of("focused", 6.0), actual.usage().indexWrites());
            assertEquals(ACCOUNT, actual.usage().accountId());
            assertEquals(table.getTableId(), actual.usage().tableId());
            assertThrows(UnsupportedOperationException.class, () -> actual.cost().gsi().put("other", 7.0));
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @CsvSource({"put,NONE", "put,ALL_OLD", "delete,NONE", "delete,ALL_OLD", "update,NONE", "update,ALL_OLD"})
    void conditionalRefusalsCaptureOldImageCostWithoutExposingAnUnrequestedItem(String operation, String returns) {
        ObjectNode existing = item("same", 2500);
        service.putItem(TABLE, existing, REGION);
        ConditionalCheckFailedException failure = assertThrows(ConditionalCheckFailedException.class, () -> {
            switch (operation) {
                case "put" -> service.putItemWithCapacity(TABLE, item("same", 20), "attribute_not_exists(pk)",
                        null, null, REGION, returns, null);
                case "delete" -> service.deleteItemWithCapacity(TABLE, key("same"), "attribute_not_exists(pk)",
                        null, null, REGION, returns, null);
                case "update" -> service.updateItemWithCapacity(TABLE, key("same"), null, "SET payload = :v", null,
                        JSON.readTree("{\":v\":{\"S\":\"small\"}}"), "NONE", "attribute_not_exists(pk)",
                        REGION, returns, null);
                default -> throw new AssertionError(operation);
            }
        });
        assertEquals("ALL_OLD".equals(returns) ? existing : null, failure.getItem());
        assertEquals(3, failure.capacityUsage().tableWrite());
        assertEquals(0, failure.capacityUsage().tableRead());
        assertTrue(failure.capacityUsage().indexWrites().isEmpty());
        assertEquals(existing, service.getItem(TABLE, key("same"), REGION));
        JsonNode ordinarySerialization = JSON.valueToTree(failure);
        assertFalse(ordinarySerialization.has("capacityUsage"));
        assertFalse(ordinarySerialization.toString().contains(table.getTableId()));
    }

    @Test
    void aFailedBatchRetainsOnlyOperationsThatReturnedBeforeTheStorageFailure() {
        itemStore.failSecond = true;
        Map<String, List<JsonNode>> requests = Map.of(TABLE, List.of(
                put(item("first", 20)), put(item("second", 20)), put(item("third", 20))));
        DynamoDbService.BatchWriteResult result = service.batchWriteItemWithCapacity(requests, REGION);
        assertSame(itemStore.failure, result.failure());
        assertEquals(1, result.usage().size());
        assertEquals(1, result.capacity().get(TABLE).table());
        assertEquals(1, result.usage().getFirst().tableWrite());
        assertNotNull(service.getItem(TABLE, key("first"), REGION));
        assertNotNull(service.getItem(TABLE, key("second"), REGION),
                "The failed operation's in-memory mutation is an existing non-transactional storage boundary");
        assertNull(service.getItem(TABLE, key("third"), REGION));
        Map<String, JsonNode> persisted = itemStore.scan(key -> true).getFirst();
        assertEquals(1, persisted.size());
        assertEquals("first", persisted.values().iterator().next().path("pk").path("S").asText());
    }

    @Test
    void theNativeHandlerPublishesCompletedBatchUsageBeforeReturningItsOriginalFailure() throws Exception {
        CloudWatchMetricsService metrics = metrics(new InMemoryStorage<>());
        NativeDynamoDbJsonHandler handler = handler(metrics);
        itemStore.failSecond = true;
        ObjectNode request = JSON.createObjectNode();
        request.putObject("RequestItems").putArray(TABLE).add(put(item("first", 20)))
                .add(put(item("second", 20))).add(put(item("third", 20)));
        assertSame(itemStore.failure, assertThrows(IllegalStateException.class,
                () -> handler.handle("BatchWriteItem", request, REGION)));
        assertEquals(1, writeSum(metrics));
    }

    @Test
    void metricStorageFailureCannotChangeAnAcceptedNativeWriteOrKeepItsItemLockHeld() throws Exception {
        InMemoryStorage<String, MetricDatum> store = new InMemoryStorage<>() {
            @Override
            public void put(String key, MetricDatum value) {
                try {
                    Future<?> independent = workers.submit(() -> service.putItem(TABLE, item("same", 20), REGION));
                    independent.get(2, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError("Metric publication still held the DynamoDB item lock", failure);
                }
                throw new IllegalStateException("metric storage failed after an accepted item operation");
            }
        };
        NativeDynamoDbJsonHandler handler = handler(metrics(store));
        ObjectNode request = JSON.createObjectNode().put("TableName", TABLE);
        request.set("Item", item("same", 20));
        assertEquals(200, handler.handle("PutItem", request, REGION).getStatus());
        assertEquals(item("same", 20), service.getItem(TABLE, key("same"), REGION));
    }

    @Test
    void aCapacitySnapshotWaitsForTheCanonicalTableControlLock() throws Exception {
        AtomicReference<Thread> reader = new AtomicReference<>();
        Future<List<DynamoDbService.CapacitySnapshot>> snapshot;
        synchronized (service.tableControlLock(TABLE, REGION)) {
            snapshot = workers.submit(() -> {
                reader.set(Thread.currentThread());
                return service.capacitySnapshots();
            });
            awaitBlockedOrDone(reader, snapshot);
            assertFalse(snapshot.isDone());
            table.getProvisionedThroughput().setReadCapacityUnits(17);
            table.findGsi("focused").orElseThrow().getProvisionedThroughput().setWriteCapacityUnits(19);
        }
        List<DynamoDbService.CapacitySnapshot> actual = snapshot.get(2, TimeUnit.SECONDS);
        assertEquals(17, actual.stream().filter(row -> row.indexName() == null).findFirst().orElseThrow().reads());
        assertEquals(19, actual.stream().filter(row -> "focused".equals(row.indexName())).findFirst().orElseThrow().writes());
    }

    @Test
    void actualOwnerReReadRejectsAnEarlierTableIncarnationAndRemovedIndex() {
        DynamoDbCapacityUsage usage = service.putItemWithCapacity(TABLE, item("same", 20), null, null, null,
                REGION, "NONE", null).usage();
        assertTrue(service.currentCapacityOwner(usage).current());
        table.setGlobalSecondaryIndexes(List.of());
        assertTrue(service.currentCapacityOwner(usage).indexes().isEmpty());
        service.deleteTable(TABLE, REGION);
        TableDefinition replacement = new TableDefinition(TABLE, List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")), REGION, ACCOUNT);
        tables.put(REGION + "::" + TABLE, replacement);
        assertNotEquals(usage.tableId(), replacement.getTableId());
        assertFalse(service.currentCapacityOwner(usage).current());
    }

    private CloudWatchMetricsService metrics(InMemoryStorage<String, MetricDatum> store) {
        return new CloudWatchMetricsService(new InMemoryStorageFactory(ACCOUNT).withStore("cwmetrics.json", store),
                new RegionResolver(REGION, ACCOUNT));
    }

    private NativeDynamoDbJsonHandler handler(CloudWatchMetricsService metrics) {
        publisher = new DynamoDbCapacityMetrics(service, metrics, Clock.systemUTC(),
                mock(ScheduledExecutorService.class), true);
        return new NativeDynamoDbJsonHandler(service, new NativeDynamoDbTableService(service, null, null),
                JSON, publisher);
    }

    private double writeSum(CloudWatchMetricsService metrics) {
        Instant now = Instant.now();
        return metrics.getMetricStatistics("AWS/DynamoDB", "ConsumedWriteCapacityUnits",
                List.of(new Dimension("TableName", TABLE)), now.minusSeconds(120), now.plusSeconds(60),
                60, List.of("Sum"), null, REGION).stream().mapToDouble(CloudWatchMetricsService.Datapoint::sum).sum();
    }

    private static ObjectNode key(String value) {
        ObjectNode item = JSON.createObjectNode();
        item.putObject("pk").put("S", value);
        return item;
    }

    private static ObjectNode item(String value, int bytes) {
        ObjectNode item = key(value);
        item.putObject("gk").put("S", "index");
        item.putObject("payload").put("S", "a".repeat(bytes));
        return item;
    }

    private static ObjectNode put(JsonNode item) {
        ObjectNode request = JSON.createObjectNode();
        request.putObject("PutRequest").set("Item", item);
        return request;
    }

    private static final class EntryStore extends InMemoryStorage<String, Map<String, JsonNode>>
            implements MapEntryWriter<JsonNode> {
        private final IllegalStateException failure = new IllegalStateException("second item storage refused");
        private boolean failSecond;

        @Override
        public void putEntry(String key, String entryKey, JsonNode value) {
            if (failSecond && "second".equals(value.path("pk").path("S").asText())) {
                throw failure;
            }
            Map<String, JsonNode> items = new HashMap<>(get(key).orElse(Map.of()));
            items.put(entryKey, value);
            put(key, items);
        }

        @Override
        public void removeEntry(String key, String entryKey) {
            Map<String, JsonNode> items = new HashMap<>(get(key).orElse(Map.of()));
            items.remove(entryKey);
            put(key, items);
        }
    }
}

package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(45)
class DynamoDbCapacityAttributionIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-ddb-capacity-attribution-wire-id");
    private static final String INDEX = "focused";
    private final String table = "capacity-attribution-" + UUID.randomUUID();
    private final WireReceipt wire = new WireReceipt();
    private final Map<String, Object> observations = new LinkedHashMap<>();
    private DynamoDbClient dynamo;
    private CloudWatchClient metrics;
    private Instant start;
    private String account;
    private final List<String> attemptedTables = new ArrayList<>();
    private final Map<String, TableDescription> initialTables = new LinkedHashMap<>();

    @BeforeEach
    void createOwnedProvisionedTableAndIndex() {
        account = Long.toString(ThreadLocalRandom.current().nextLong(740000000000L, 749999999999L));
        dynamo = AwsTestClients.create(DynamoDbClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(8)).apiCallAttemptTimeout(Duration.ofSeconds(6))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)));
        metrics = AwsTestClients.create(CloudWatchClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(8)).apiCallAttemptTimeout(Duration.ofSeconds(6))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)));
        observations.put("account", account);
        observations.put("reservedTable", table);
        assertEquals(List.of(), dynamo.listTables().tableNames());
        start = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 60 * 60 - 60);
        createProvisionedTable(table);
    }

    private void createProvisionedTable(String selected) {
        attemptedTables.add(selected);
        observations.put("reservedTables", List.copyOf(attemptedTables));
        dynamo.createTable(request -> request.tableName(selected)
                .attributeDefinitions(attribute -> attribute.attributeName("pk").attributeType("S"),
                        attribute -> attribute.attributeName("sk").attributeType("S"),
                        attribute -> attribute.attributeName("gk").attributeType("S"),
                        attribute -> attribute.attributeName("lk").attributeType("S"))
                .keySchema(key -> key.attributeName("pk").keyType("HASH"),
                        key -> key.attributeName("sk").keyType("RANGE"))
                .billingMode("PROVISIONED")
                .provisionedThroughput(throughput -> throughput.readCapacityUnits(3L).writeCapacityUnits(3L))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName(INDEX)
                        .keySchema(key -> key.attributeName("gk").keyType("HASH"))
                        .projection(projection -> projection.projectionType("ALL"))
                        .provisionedThroughput(throughput -> throughput.readCapacityUnits(3L).writeCapacityUnits(3L))
                        .build())
                .localSecondaryIndexes(index -> index.indexName("local")
                        .keySchema(key -> key.attributeName("pk").keyType("HASH"),
                                key -> key.attributeName("lk").keyType("RANGE"))
                        .projection(projection -> projection.projectionType("KEYS_ONLY"))));
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertEquals("ACTIVE", describe(selected).tableStatusAsString()));
        TableDescription initial = describe(selected);
        assertNotNull(initial.tableId());
        initialTables.put(selected, initial);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gsi-identical", "gsi-key-change", "gsi-key-removal", "missing-delete",
            "gsi-scan", "lsi-query", "lsi-scan", "lsi-query-fetch", "multi-batch-write", "multi-batch-get"})
    void tableIndexAndBatchAttributionRemainWithinTheRequestOwner(String operation) throws IOException {
        observations.put("operation", operation);
        Map<String, Totals> expected = new LinkedHashMap<>();
        switch (operation) {
            case "gsi-identical", "gsi-key-change", "gsi-key-removal" -> {
                seed("one", 20, true);
                if ("gsi-identical".equals(operation)) {
                    dynamo.putItem(request -> request.tableName(table).item(item("one", 20, true)));
                } else if ("gsi-key-change".equals(operation)) {
                    dynamo.updateItem(request -> request.tableName(table).key(key("one"))
                            .updateExpression("SET gk = :next")
                            .expressionAttributeValues(Map.of(":next", value("next"))));
                } else {
                    dynamo.updateItem(request -> request.tableName(table).key(key("one"))
                            .updateExpression("REMOVE gk"));
                }
                expected.put(table, new Totals(0, 2, 0, switch (operation) {
                    case "gsi-identical" -> 1;
                    case "gsi-key-change" -> 3;
                    default -> 2;
                }));
            }
            case "missing-delete" -> {
                dynamo.deleteItem(request -> request.tableName(table).key(key("absent")));
                expected.put(table, new Totals(0, 1, 0, 0));
            }
            case "gsi-scan" -> {
                seed("one", 20, true);
                assertEquals(0, dynamo.scan(request -> request.tableName(table).indexName(INDEX)
                        .filterExpression("pk = :absent")
                        .expressionAttributeValues(Map.of(":absent", value("absent")))).count());
                expected.put(table, new Totals(0, 1, 0.5, 1));
            }
            case "lsi-query", "lsi-scan", "lsi-query-fetch" -> {
                Map<String, AttributeValue> stored = item("one", 5000, true);
                stored.put("lk", value("order"));
                dynamo.putItem(request -> request.tableName(table).item(stored));
                if ("lsi-scan".equals(operation)) {
                    assertEquals(List.of(key("one")), dynamo.scan(request -> request.tableName(table)
                            .indexName("local").projectionExpression("pk, sk")).items());
                } else {
                    boolean fetch = "lsi-query-fetch".equals(operation);
                    assertEquals(List.of(fetch ? stored : key("one")),
                            dynamo.query(request -> request.tableName(table).indexName("local")
                                    .keyConditionExpression("pk = :owner")
                                    .expressionAttributeValues(Map.of(":owner", value("owned")))
                                    .select(fetch ? "ALL_ATTRIBUTES" : "SPECIFIC_ATTRIBUTES")
                                    .projectionExpression(fetch ? null : "pk, sk")).items());
                }
                expected.put(table, new Totals("lsi-query-fetch".equals(operation) ? 1.5 : 0.5, 6, 0, 5));
            }
            case "multi-batch-write", "multi-batch-get" -> {
                String second = table + "-other";
                createProvisionedTable(second);
                if ("multi-batch-write".equals(operation)) {
                    assertTrue(dynamo.batchWriteItem(request -> request.requestItems(Map.of(
                            table, List.of(write(item("one", 20, true))),
                            second, List.of(write(item("one", 5000, false))))))
                            .unprocessedItems().isEmpty());
                } else {
                    seed("one", 20, true);
                    dynamo.putItem(request -> request.tableName(second).item(item("one", 5000, false)));
                    KeysAndAttributes selected = KeysAndAttributes.builder().keys(key("one"))
                            .consistentRead(true).projectionExpression("pk, sk").build();
                    assertEquals(Map.of(table, List.of(key("one")), second, List.of(key("one"))),
                            dynamo.batchGetItem(request -> request.requestItems(Map.of(
                                    table, selected, second, selected))).responses());
                }
                boolean read = "multi-batch-get".equals(operation);
                expected.put(table, new Totals(read ? 1 : 0, 1, 0, 1));
                expected.put(second, new Totals(read ? 2 : 0, 5, 0, 0));
            }
            default -> throw new IllegalArgumentException(operation);
        }
        observations.put("measuredResponse", wire.lastResponse());
        observations.put("expected", expected);
        for (Map.Entry<String, TableDescription> initial : initialTables.entrySet()) {
            assertEquals(configuration(initial.getValue()), configuration(describe(initial.getKey())));
        }
        expected.forEach(this::assertConsumption);
        try (CloudWatchClient otherAccount = metricsClient(Long.toString(Long.parseLong(account) + 1), Region.US_EAST_1);
             CloudWatchClient otherRegion = metricsClient(account, Region.US_WEST_2)) {
            for (String selected : expected.keySet()) {
                for (String metric : List.of("ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits")) {
                    for (boolean index : List.of(false, true)) {
                        assertTrue(statistics(otherAccount, selected, metric, index).datapoints().isEmpty());
                        assertTrue(statistics(otherRegion, selected, metric, index).datapoints().isEmpty());
                    }
                }
            }
        }
        for (String metric : List.of("ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits")) {
            assertTrue(metrics.getMetricStatistics(request -> request.namespace("AWS/DynamoDB")
                    .metricName(metric).dimensions(
                            Dimension.builder().name("TableName").value(table).build(),
                            Dimension.builder().name("LocalSecondaryIndexName").value("local").build())
                    .startTime(start).endTime(Instant.now().plusSeconds(60)).period(60).statisticsWithStrings("Sum"))
                    .datapoints().isEmpty(), "LSI consumption shares its table series");
        }
    }

    private static WriteRequest write(Map<String, AttributeValue> item) {
        return WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build();
    }

    private CloudWatchClient metricsClient(String owner, Region region) {
        return AwsTestClients.create(CloudWatchClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(8)).apiCallAttemptTimeout(Duration.ofSeconds(6))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                owner, region, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)));
    }

    private void assertConsumption(String selected, Totals expected) {
        await().pollInSameThread().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Totals actual = new Totals(sum(selected, "ConsumedReadCapacityUnits", false),
                    sum(selected, "ConsumedWriteCapacityUnits", false), sum(selected, "ConsumedReadCapacityUnits", true),
                    sum(selected, "ConsumedWriteCapacityUnits", true));
            observations.put("observed-" + selected, actual);
            assertEquals(expected, actual, "Each positive total must come from real SDK item requests");
        });
        assertTrue(metrics.getMetricStatistics(request -> request.namespace("AWS/DynamoDB")
                .metricName("ConsumedWriteCapacityUnits").dimensions(Dimension.builder()
                        .name("GlobalSecondaryIndexName").value(INDEX).build())
                .startTime(start).endTime(Instant.now().plusSeconds(60)).period(60).statisticsWithStrings("Sum"))
                .datapoints().isEmpty(), "An index-name-only consumption series must not exist");
    }

    private double sum(String selected, String metric, boolean index) {
        return statistics(metrics, selected, metric, index).datapoints().stream()
                .mapToDouble(point -> point.sum()).sum();
    }

    private GetMetricStatisticsResponse statistics(CloudWatchClient client, String selected, String metric, boolean index) {
        List<Dimension> dimensions = new ArrayList<>();
        dimensions.add(Dimension.builder().name("TableName").value(selected).build());
        if (index) {
            dimensions.add(Dimension.builder().name("GlobalSecondaryIndexName").value(INDEX).build());
        }
        return client.getMetricStatistics(request -> request
                .namespace("AWS/DynamoDB").metricName(metric).dimensions(dimensions)
                .startTime(start).endTime(Instant.now().plusSeconds(60)).period(60).statisticsWithStrings("Sum"));
    }

    private void seed(String sort, int payloadBytes, boolean indexed) {
        dynamo.putItem(request -> request.tableName(table).item(item(sort, payloadBytes, indexed)));
    }

    private static Map<String, AttributeValue> key(String sort) {
        return Map.of("pk", value("owned"), "sk", value(sort));
    }

    private static Map<String, AttributeValue> item(String sort, int payloadBytes, boolean indexed) {
        Map<String, AttributeValue> item = new LinkedHashMap<>(key(sort));
        item.put("payload", value("a".repeat(payloadBytes)));
        if (indexed) {
            item.put("gk", value("group"));
        }
        return item;
    }

    private static AttributeValue value(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private TableDescription describe(String selected) {
        return dynamo.describeTable(request -> request.tableName(selected)).table();
    }

    private static TableDescription configuration(TableDescription table) {
        return table.toBuilder().itemCount(null).tableSizeBytes(null)
                .localSecondaryIndexes(table.localSecondaryIndexes().stream()
                        .map(index -> index.toBuilder().itemCount(null).indexSizeBytes(null).build()).toList())
                .globalSecondaryIndexes(table.globalSecondaryIndexes().stream()
                        .map(index -> index.toBuilder().itemCount(null).indexSizeBytes(null).build()).toList())
                .build();
    }

    @AfterEach
    void deleteOwnedTableAndSaveAllCapturedExchanges() throws IOException {
        List<Throwable> failures = new ArrayList<>();
        try {
            if (dynamo != null) {
                for (String selected : attemptedTables) {
                    cleanup(failures, () -> {
                        try {
                            TableDescription current = describe(selected);
                            if (initialTables.containsKey(selected)) {
                                assertEquals(initialTables.get(selected).tableId(), current.tableId());
                            }
                            dynamo.deleteTable(request -> request.tableName(selected));
                        } catch (ResourceNotFoundException absent) {
                            assertEquals(400, absent.statusCode());
                        }
                    });
                    cleanup(failures, () -> await().atMost(Duration.ofSeconds(3))
                            .pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                                    assertThrows(ResourceNotFoundException.class, () -> describe(selected))));
                }
            }
            if (dynamo != null) {
                cleanup(failures, () -> assertEquals(List.of(), dynamo.listTables().tableNames()));
            }
            observations.put("cleanupFailures", failures.stream().map(Throwable::toString).toList());
        } finally {
            try {
                wire.save();
            } finally {
                if (dynamo != null) {
                    dynamo.close();
                }
                if (metrics != null) {
                    metrics.close();
                }
            }
        }
        if (!failures.isEmpty()) {
            AssertionError failure = new AssertionError("Owned telemetry fixture cleanup failed");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static void cleanup(List<Throwable> failures, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private record Totals(double tableRead, double tableWrite, double indexRead, double indexWrite) {}

    private final class WireReceipt implements ExecutionInterceptor {
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertTrue(List.of("localhost", "127.0.0.1", "::1").contains(context.httpRequest().getUri().getHost()));
            assertEquals("http", context.httpRequest().getUri().getScheme());
            int id = calls.size();
            attributes.putAttribute(WIRE_ID, id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", id);
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("requestHeaders", context.httpRequest().headers());
            try (InputStream input = context.requestBody().isPresent()
                    ? context.requestBody().orElseThrow().contentStreamProvider().newStream() : InputStream.nullInputStream()) {
                row.put("requestBase64", encode(input));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            calls.add(row);
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(
                Context.ModifyHttpResponse context, ExecutionAttributes attributes) {
            Map<String, Object> row = calls.get(attributes.getAttribute(WIRE_ID));
            row.put("status", context.httpResponse().statusCode());
            row.put("responseHeaders", context.httpResponse().headers());
            try (InputStream input = context.responseBody().orElseGet(InputStream::nullInputStream)) {
                String encoded = encode(input);
                row.put("responseBase64", encoded);
                return Optional.of(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private String encode(InputStream stream) throws IOException {
            byte[] bytes = stream.readNBytes(524289);
            assertTrue(bytes.length <= 524288, "Wire capture byte limit");
            return Base64.getEncoder().encodeToString(bytes);
        }

        private JsonNode lastResponse() throws IOException {
            return MAPPER.readTree(Base64.getDecoder().decode((String) calls.getLast().get("responseBase64")));
        }

        private void save() throws IOException {
            Path directory = Path.of("target", "dynamodb-capacity-attribution-wire");
            Files.createDirectories(directory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(table + ".json").toFile(),
                    Map.of("table", table, "observations", observations, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "Every attempted exchange must retain its response or fail qualification");
        }
    }
}

package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Condition;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(45)
class DynamoDbLsiEvaluatedFetchIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-ddb-lsi-evaluated-fetch-wire-id");
    private final String table = "lsi-evaluated-fetch-" + UUID.randomUUID();
    private final WireReceipt wire = new WireReceipt();
    private final Map<String, Object> observations = new LinkedHashMap<>();
    private final List<Map<String, AttributeValue>> originalItems =
            List.of(item("first", "keep"), item("second", "drop"));
    private DynamoDbClient dynamo;
    private CloudWatchClient metrics;
    private TableDescription initial;
    private Instant start;
    private boolean createAttempted;

    static Stream<Arguments> cases() {
        return Stream.of("Query", "Scan").flatMap(operation -> Stream.of(false, true).flatMap(consistent ->
                Stream.of("all", "all-filter-keep", "all-filter-none", "legacy-filter-keep", "projection-filter-keep")
                        .map(mode -> Arguments.of(operation, consistent, mode))));
    }

    @BeforeEach
    void createOwnedTableWithTwoLargeItemsAndAnIncludeIndex() {
        String account = Long.toString(ThreadLocalRandom.current().nextLong(740000000000L, 749999999999L));
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
        assertEmptyInventory();
        start = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 60 * 60 - 60);
        createAttempted = true;
        dynamo.createTable(request -> request.tableName(table).billingMode("PROVISIONED")
                .attributeDefinitions(attribute -> attribute.attributeName("pk").attributeType("S"),
                        attribute -> attribute.attributeName("sk").attributeType("S"),
                        attribute -> attribute.attributeName("lk").attributeType("S"))
                .keySchema(key -> key.attributeName("pk").keyType("HASH"),
                        key -> key.attributeName("sk").keyType("RANGE"))
                .provisionedThroughput(throughput -> throughput.readCapacityUnits(3L).writeCapacityUnits(3L))
                .localSecondaryIndexes(index -> index.indexName("included")
                        .keySchema(key -> key.attributeName("pk").keyType("HASH"),
                                key -> key.attributeName("lk").keyType("RANGE"))
                        .projection(projection -> projection.projectionType("INCLUDE").nonKeyAttributes("flag"))));
        await().atMost(Duration.ofSeconds(3)).pollInSameThread().pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertEquals("ACTIVE", describe().tableStatusAsString()));
        initial = describe();
        assertNotNull(initial.tableId());
        observations.put("tableId", initial.tableId());
        originalItems.forEach(item -> dynamo.putItem(request -> request.tableName(table).item(item)));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void everyEvaluatedBaseItemIsChargedBeforeFiltering(String operation, boolean consistent, String mode)
            throws IOException {
        observations.put("operation", operation);
        observations.put("consistent", consistent);
        observations.put("mode", mode);
        boolean legacy = mode.startsWith("legacy");
        boolean filtered = mode.contains("filter");
        boolean projected = mode.startsWith("projection");
        String expectedFlag = mode.endsWith("none") ? "absent" : "keep";
        List<Map<String, AttributeValue>> returned;
        int count;
        int scanned;
        if ("Query".equals(operation)) {
            QueryRequest.Builder request = QueryRequest.builder().tableName(table).indexName("included")
                    .consistentRead(consistent).returnConsumedCapacity("INDEXES");
            if (legacy) {
                request.keyConditions(Map.of("pk", condition("owned"))).queryFilter(Map.of("flag", condition("keep")));
            } else {
                Map<String, AttributeValue> values = new LinkedHashMap<>(Map.of(":owner", value("owned")));
                request.keyConditionExpression("pk = :owner");
                if (filtered) {
                    values.put(":filter", value(expectedFlag));
                    request.filterExpression("#flag = :filter").expressionAttributeNames(Map.of("#flag", "flag"));
                }
                request.expressionAttributeValues(values);
            }
            if (projected) {
                request.projectionExpression("pk, payload");
            } else {
                request.select("ALL_ATTRIBUTES");
            }
            QueryResponse response = dynamo.query(request.build());
            returned = response.items();
            count = response.count();
            scanned = response.scannedCount();
            assertTrue(response.lastEvaluatedKey().isEmpty());
        } else {
            ScanRequest.Builder request = ScanRequest.builder().tableName(table).indexName("included")
                    .consistentRead(consistent).returnConsumedCapacity("INDEXES");
            if (legacy) {
                request.scanFilter(Map.of("flag", condition("keep")));
            } else if (filtered) {
                request.filterExpression("#flag = :filter").expressionAttributeNames(Map.of("#flag", "flag"))
                        .expressionAttributeValues(Map.of(":filter", value(expectedFlag)));
            }
            if (projected) {
                request.projectionExpression("pk, payload");
            } else {
                request.select("ALL_ATTRIBUTES");
            }
            ScanResponse response = dynamo.scan(request.build());
            returned = response.items();
            count = response.count();
            scanned = response.scannedCount();
            assertTrue(response.lastEvaluatedKey().isEmpty());
        }
        JsonNode measured = wire.lastResponse();
        observations.put("measuredResponse", measured);
        // Independent strong GetItem readbacks each cost 2 RCU for a known 5 KB item.
        List<Map<String, AttributeValue>> readback = originalItems.stream().map(item -> dynamo.getItem(request ->
                request.tableName(table).consistentRead(true).key(key(item.get("sk").s()))).item()).toList();
        TableDescription after = describe();
        int expectedCount = mode.endsWith("none") ? 0 : filtered ? 1 : 2;
        List<Map<String, AttributeValue>> expectedItems = originalItems.subList(0, expectedCount);
        if (projected) {
            expectedItems = expectedItems.stream()
                    .map(item -> Map.of("pk", item.get("pk"), "payload", item.get("payload"))).toList();
        }
        List<Map<String, AttributeValue>> selectedItems = expectedItems;
        double indexUnits = consistent ? 1 : 0.5;
        double fetchedUnits = consistent ? 4 : 2;
        double measuredUnits = indexUnits + fetchedUnits;
        double expectedReadSum = measuredUnits + 4;
        JsonNode expectedCapacity = MAPPER.valueToTree(Map.of("TableName", table, "CapacityUnits", measuredUnits,
                "Table", Map.of("CapacityUnits", fetchedUnits),
                "LocalSecondaryIndexes", Map.of("included", Map.of("CapacityUnits", indexUnits))));
        observations.put("expectedMeasuredCapacity", expectedCapacity);
        observations.put("expectedReadSumIncludingReadback", expectedReadSum);
        assertAll("Full evaluated fetch charge, actual items, native request metrics and unchanged configuration",
                () -> assertEquals(expectedCapacity, measured.path("ConsumedCapacity")),
                () -> assertEquals(2, scanned),
                () -> assertEquals(expectedCount, count),
                () -> assertEquals(selectedItems.size(), returned.size()),
                () -> assertEquals(new HashSet<>(selectedItems), new HashSet<>(returned)),
                () -> assertEquals(originalItems, readback),
                () -> assertEquals(configuration(initial), configuration(after)),
                () -> await().atMost(Duration.ofSeconds(2)).pollInSameThread().pollInterval(Duration.ofMillis(200))
                        .untilAsserted(() -> {
                            double actual = metrics.getMetricStatistics(request -> request.namespace("AWS/DynamoDB")
                                            .metricName("ConsumedReadCapacityUnits").dimensions(
                                                    Dimension.builder().name("TableName").value(table).build())
                                            .startTime(start).endTime(Instant.now().plusSeconds(60))
                                            .period(60).statisticsWithStrings("Sum"))
                                    .datapoints().stream().mapToDouble(point -> point.sum()).sum();
                            observations.put("observedReadSumIncludingReadback", actual);
                            assertEquals(expectedReadSum, actual);
                        }),
                () -> assertTrue(metrics.getMetricStatistics(request -> request.namespace("AWS/DynamoDB")
                                .metricName("ConsumedReadCapacityUnits").dimensions(
                                        Dimension.builder().name("TableName").value(table).build(),
                                        Dimension.builder().name("LocalSecondaryIndexName").value("included").build())
                                .startTime(start).endTime(Instant.now().plusSeconds(60))
                                .period(60).statisticsWithStrings("Sum"))
                        .datapoints().isEmpty(), "LSI cost belongs to the table's consumption series"));
    }

    private static Condition condition(String text) {
        return Condition.builder().comparisonOperator("EQ").attributeValueList(value(text)).build();
    }

    private static Map<String, AttributeValue> key(String sort) {
        return Map.of("pk", value("owned"), "sk", value(sort));
    }

    private static Map<String, AttributeValue> item(String sort, String flag) {
        Map<String, AttributeValue> item = new LinkedHashMap<>(key(sort));
        item.put("lk", value(sort));
        item.put("flag", value(flag));
        item.put("payload", value("x".repeat(5000)));
        return item;
    }

    private static AttributeValue value(String text) {
        return AttributeValue.builder().s(text).build();
    }

    private TableDescription describe() {
        return dynamo.describeTable(request -> request.tableName(table)).table();
    }

    private static TableDescription configuration(TableDescription table) {
        return table.toBuilder().itemCount(null).tableSizeBytes(null)
                .localSecondaryIndexes(table.localSecondaryIndexes().stream()
                        .map(index -> index.toBuilder().itemCount(null).indexSizeBytes(null).build()).toList()).build();
    }

    private void assertEmptyInventory() {
        ListTablesResponse response = dynamo.listTables();
        assertTrue(response.tableNames().isEmpty());
        assertTrue(response.lastEvaluatedTableName() == null || response.lastEvaluatedTableName().isEmpty());
    }

    @AfterEach
    void removeOwnedTableAndRetainCompleteCaptures() {
        List<Throwable> failures = new ArrayList<>();
        if (dynamo != null && createAttempted) {
            cleanup(failures, () -> {
                try {
                    TableDescription current = describe();
                    if (initial != null) {
                        assertEquals(initial.tableId(), current.tableId());
                    }
                    dynamo.deleteTable(request -> request.tableName(table));
                } catch (ResourceNotFoundException absent) {
                    assertEquals(400, absent.statusCode());
                }
            });
            cleanup(failures, () -> await().atMost(Duration.ofSeconds(3)).pollInSameThread()
                    .pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
                        ResourceNotFoundException absent = assertThrows(ResourceNotFoundException.class, this::describe);
                        assertEquals(400, absent.statusCode());
                    }));
        }
        if (dynamo != null) {
            cleanup(failures, this::assertEmptyInventory);
        }
        observations.put("cleanupFailures", failures.stream().map(Throwable::toString).toList());
        cleanup(failures, () -> {
            try {
                wire.save();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        if (dynamo != null) {
            cleanup(failures, dynamo::close);
        }
        if (metrics != null) {
            cleanup(failures, metrics::close);
        }
        if (!failures.isEmpty()) {
            AssertionError failure = new AssertionError("Owned LSI fixture cleanup or capture failed");
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
            Path directory = Path.of("target", "dynamodb-lsi-evaluated-fetch-wire");
            Files.createDirectories(directory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(table + ".json").toFile(),
                    Map.of("table", table, "observations", observations, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "Every attempted exchange must retain its response or fail qualification");
        }
    }
}

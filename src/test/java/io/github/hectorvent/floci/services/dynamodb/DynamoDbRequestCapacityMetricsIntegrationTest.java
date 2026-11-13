package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
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
class DynamoDbRequestCapacityMetricsIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-ddb-request-capacity-metrics-wire-id");
    private static final String INDEX = "focused";
    private final String table = "request-capacity-" + UUID.randomUUID();
    private final WireReceipt wire = new WireReceipt();
    private final Map<String, Object> observations = new LinkedHashMap<>();
    private DynamoDbClient dynamo;
    private CloudWatchClient metrics;
    private Instant start;
    private TableDescription initialTable;
    private boolean createAttempted;

    @BeforeEach
    void createOwnedProvisionedTableAndIndex() {
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
        assertEquals(List.of(), dynamo.listTables().tableNames());
        start = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 60 * 60 - 60);
        createAttempted = true;
        dynamo.createTable(request -> request.tableName(table)
                .attributeDefinitions(attribute -> attribute.attributeName("pk").attributeType("S"),
                        attribute -> attribute.attributeName("sk").attributeType("S"),
                        attribute -> attribute.attributeName("gk").attributeType("S"))
                .keySchema(key -> key.attributeName("pk").keyType("HASH"),
                        key -> key.attributeName("sk").keyType("RANGE"))
                .billingMode("PROVISIONED")
                .provisionedThroughput(throughput -> throughput.readCapacityUnits(3L).writeCapacityUnits(3L))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName(INDEX)
                        .keySchema(key -> key.attributeName("gk").keyType("HASH"))
                        .projection(projection -> projection.projectionType("ALL"))
                        .provisionedThroughput(throughput -> throughput.readCapacityUnits(3L).writeCapacityUnits(3L))
                        .build()));
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertEquals("ACTIVE", describe().tableStatusAsString()));
        initialTable = describe();
        assertNotNull(initialTable.tableId());
        observations.put("tableId", initialTable.tableId());
    }

    private static Stream<Arguments> ordinaryRequests() {
        return Stream.of("PutItem", "UpdateItem", "DeleteItem", "GetItem", "Query", "Scan",
                        "BatchWriteItem", "BatchGetItem")
                .flatMap(operation -> Stream.of("OMITTED", "NONE", "TOTAL", "INDEXES")
                        .map(mode -> Arguments.of(operation, mode)));
    }

    @ParameterizedTest
    @MethodSource("ordinaryRequests")
    void realRequestsPublishTheIndependentlyCalculatedTableAndIndexConsumption(String operation, String mode)
            throws IOException {
        observations.put("operation", operation);
        observations.put("returnConsumedCapacity", mode);
        String returned = "OMITTED".equals(mode) ? null : mode;
        Totals expected = switch (operation) {
            case "PutItem" -> {
                dynamo.putItem(request -> request.tableName(table).item(item("one", 20, true))
                        .returnConsumedCapacity(returned));
                yield new Totals(0, 1, 0, 1);
            }
            case "UpdateItem" -> {
                seed("one", 20, true);
                dynamo.updateItem(request -> request.tableName(table).key(key("one"))
                        .updateExpression("SET payload = :replacement")
                        .expressionAttributeValues(Map.of(":replacement", value("b".repeat(100))))
                        .returnConsumedCapacity(returned));
                yield new Totals(0, 2, 0, 2);
            }
            case "DeleteItem" -> {
                seed("one", 20, true);
                dynamo.deleteItem(request -> request.tableName(table).key(key("one"))
                        .returnConsumedCapacity(returned));
                yield new Totals(0, 2, 0, 2);
            }
            case "GetItem" -> {
                seed("one", 5000, true);
                assertEquals(Map.of("pk", value("owned")), dynamo.getItem(request -> request.tableName(table)
                        .key(key("one")).consistentRead(true).projectionExpression("pk")
                        .returnConsumedCapacity(returned)).item());
                yield new Totals(2, 5, 0, 5);
            }
            case "Query" -> {
                seed("one", 2300, true);
                seed("two", 2300, true);
                assertEquals(0, dynamo.query(request -> request.tableName(table).indexName(INDEX)
                        .keyConditionExpression("gk = :group").filterExpression("pk = :missing")
                        .expressionAttributeValues(Map.of(":group", value("group"), ":missing", value("absent")))
                        .returnConsumedCapacity(returned)).count());
                yield new Totals(0, 6, 1, 6);
            }
            case "Scan" -> {
                seed("one", 2300, true);
                seed("two", 2300, true);
                assertEquals(0, dynamo.scan(request -> request.tableName(table).filterExpression("pk = :missing")
                        .expressionAttributeValues(Map.of(":missing", value("absent")))
                        .returnConsumedCapacity(returned)).count());
                yield new Totals(1, 6, 0, 6);
            }
            case "BatchWriteItem" -> {
                List<WriteRequest> writes = List.of(
                        WriteRequest.builder().putRequest(PutRequest.builder().item(item("one", 20, true)).build()).build(),
                        WriteRequest.builder().putRequest(PutRequest.builder().item(item("two", 20, false)).build()).build());
                assertTrue(dynamo.batchWriteItem(request -> request.requestItems(Map.of(table, writes))
                        .returnConsumedCapacity(returned)).unprocessedItems().isEmpty());
                yield new Totals(0, 2, 0, 1);
            }
            case "BatchGetItem" -> {
                seed("one", 5000, true);
                seed("two", 5000, true);
                KeysAndAttributes keys = KeysAndAttributes.builder().keys(key("one"), key("two"), key("missing"))
                        .consistentRead(true).projectionExpression("pk, sk").build();
                assertEquals(2, dynamo.batchGetItem(request -> request.requestItems(Map.of(table, keys))
                        .returnConsumedCapacity(returned)).responses().get(table).size());
                yield new Totals(5, 10, 0, 10);
            }
            default -> throw new IllegalArgumentException(operation);
        };
        JsonNode requestResponse = wire.lastResponse();
        observations.put("measuredResponse", requestResponse);
        observations.put("expected", expected);
        assertEquals(!"OMITTED".equals(mode) && !"NONE".equals(mode), requestResponse.has("ConsumedCapacity"));
        assertConsumption(expected);
        assertEquals(configuration(initialTable), configuration(describe()),
                "Request metering must not change table configuration");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PutItem", "UpdateItem", "DeleteItem"})
    void failedConditionalWritesAreMeteredFromTheStoredItemWithoutChangingItsPayload(String operation)
            throws IOException {
        observations.put("operation", operation + "-conditional-refusal");
        Map<String, AttributeValue> stored = item("one", 2500, true);
        seed("one", 2500, true);
        ConditionalCheckFailedException refusal = assertThrows(ConditionalCheckFailedException.class, () -> {
            switch (operation) {
                case "PutItem" -> dynamo.putItem(request -> request.tableName(table).item(item("one", 1, true))
                        .conditionExpression("attribute_not_exists(pk)").returnConsumedCapacity("INDEXES"));
                case "UpdateItem" -> dynamo.updateItem(request -> request.tableName(table).key(key("one"))
                        .conditionExpression("attribute_not_exists(pk)").updateExpression("SET payload = :small")
                        .expressionAttributeValues(Map.of(":small", value("x"))).returnConsumedCapacity("INDEXES"));
                case "DeleteItem" -> dynamo.deleteItem(request -> request.tableName(table).key(key("one"))
                        .conditionExpression("attribute_not_exists(pk)").returnConsumedCapacity("INDEXES"));
                default -> throw new IllegalArgumentException(operation);
            }
        });
        JsonNode errorBody = wire.lastResponse();
        observations.put("refusal", errorBody);
        assertAll(() -> assertEquals(400, refusal.statusCode()),
                () -> assertEquals("ConditionalCheckFailedException", refusal.awsErrorDetails().errorCode()),
                () -> assertFalse(errorBody.has("ConsumedCapacity")),
                () -> assertFalse(errorBody.has("Item")));
        assertEquals(stored, dynamo.getItem(request -> request.tableName(table).key(key("one"))).item());
        Totals expected = new Totals(0.5, 6, 0, 3);
        observations.put("expected", expected);
        assertConsumption(expected);
        assertEquals(configuration(initialTable), configuration(describe()));
    }

    private void assertConsumption(Totals expected) {
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Totals actual = new Totals(sum("ConsumedReadCapacityUnits", false),
                    sum("ConsumedWriteCapacityUnits", false), sum("ConsumedReadCapacityUnits", true),
                    sum("ConsumedWriteCapacityUnits", true));
            observations.put("observed", actual);
            assertEquals(expected, actual, "Each positive total must come from real SDK item requests");
        });
        assertTrue(metrics.getMetricStatistics(request -> request.namespace("AWS/DynamoDB")
                .metricName("ConsumedWriteCapacityUnits").dimensions(Dimension.builder()
                        .name("GlobalSecondaryIndexName").value(INDEX).build())
                .startTime(start).endTime(Instant.now().plusSeconds(60)).period(60).statisticsWithStrings("Sum"))
                .datapoints().isEmpty(), "An index-name-only consumption series must not exist");
    }

    private double sum(String metric, boolean index) {
        List<Dimension> dimensions = new ArrayList<>();
        dimensions.add(Dimension.builder().name("TableName").value(table).build());
        if (index) {
            dimensions.add(Dimension.builder().name("GlobalSecondaryIndexName").value(INDEX).build());
        }
        GetMetricStatisticsResponse response = metrics.getMetricStatistics(request -> request
                .namespace("AWS/DynamoDB").metricName(metric).dimensions(dimensions)
                .startTime(start).endTime(Instant.now().plusSeconds(60)).period(60).statisticsWithStrings("Sum"));
        return response.datapoints().stream().mapToDouble(point -> point.sum()).sum();
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

    private TableDescription describe() {
        return dynamo.describeTable(request -> request.tableName(table)).table();
    }

    private static TableDescription configuration(TableDescription table) {
        return table.toBuilder().itemCount(null).tableSizeBytes(null)
                .globalSecondaryIndexes(table.globalSecondaryIndexes().stream()
                        .map(index -> index.toBuilder().itemCount(null).indexSizeBytes(null).build()).toList())
                .build();
    }

    @AfterEach
    void deleteOwnedTableAndSaveAllCapturedExchanges() throws IOException {
        List<Throwable> failures = new ArrayList<>();
        try {
            if (dynamo != null && createAttempted) {
                cleanup(failures, () -> {
                    try {
                        TableDescription current = describe();
                        if (initialTable != null) {
                            assertEquals(initialTable.tableId(), current.tableId());
                        }
                        dynamo.deleteTable(request -> request.tableName(table));
                    } catch (ResourceNotFoundException absent) {
                        assertEquals(400, absent.statusCode());
                    }
                });
                cleanup(failures, () -> await().atMost(Duration.ofSeconds(3))
                        .pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                                assertThrows(ResourceNotFoundException.class, this::describe)));
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
            Path directory = Path.of("target", "dynamodb-request-capacity-wire");
            Files.createDirectories(directory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(table + ".json").toFile(),
                    Map.of("table", table, "observations", observations, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "Every attempted exchange must retain its response or fail qualification");
        }
    }
}

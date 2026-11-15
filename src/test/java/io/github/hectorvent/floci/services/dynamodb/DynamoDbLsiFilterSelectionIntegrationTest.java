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
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Condition;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
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
class DynamoDbLsiFilterSelectionIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-ddb-lsi-filter-selection-wire-id");
    private final String table = "lsi-filter-selection-" + UUID.randomUUID();
    private final WireReceipt wire = new WireReceipt();
    private final Map<String, Object> observations = new LinkedHashMap<>();
    private final List<Map<String, AttributeValue>> originalItems =
            List.of(item("first", "keep"), item("second", "drop"));
    private DynamoDbClient dynamo;
    private TableDescription initial;
    private boolean createAttempted;

    static Stream<Arguments> cases() {
        return Stream.of(false, true).flatMap(consistent -> Stream.concat(
                Stream.concat(
                        Stream.of("all", "projection", "legacy-all", "projected", "count")
                                .map(mode -> Arguments.of("Query", "keys", consistent, mode, "flag", -1)),
                        Stream.of(Arguments.of("Query", "included", consistent, "projected", "hidden", -1),
                                Arguments.of("Query", "included", consistent, "projected", "flag", 1),
                                Arguments.of("Query", "included", consistent, "count", "flag", 1))),
                Stream.concat(
                        Stream.of("all", "projection", "legacy-all", "all-none", "projected", "count")
                                .map(mode -> Arguments.of("Scan", "keys", consistent, mode, "flag",
                                        List.of("all", "projection", "legacy-all").contains(mode) ? 1 : 0)),
                        Stream.of(Arguments.of("Scan", "included", consistent, "projected", "flag", 1),
                                Arguments.of("Scan", "included", consistent, "count", "flag", 1),
                                Arguments.of("Scan", "included", consistent, "projected", "hidden", 0)))));
    }

    @BeforeEach
    void createOwnedTableWithTwoLargeItemsAndTwoIndexProjections() {
        String account = Long.toString(ThreadLocalRandom.current().nextLong(740000000000L, 749999999999L));
        dynamo = AwsTestClients.create(DynamoDbClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(8)).apiCallAttemptTimeout(Duration.ofSeconds(6))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)));
        observations.put("account", account);
        observations.put("reservedTable", table);
        assertEmptyInventory();
        createAttempted = true;
        dynamo.createTable(request -> request.tableName(table).billingMode("PROVISIONED")
                .attributeDefinitions(attribute -> attribute.attributeName("pk").attributeType("S"),
                        attribute -> attribute.attributeName("sk").attributeType("S"),
                        attribute -> attribute.attributeName("lk").attributeType("S"))
                .keySchema(key -> key.attributeName("pk").keyType("HASH"),
                        key -> key.attributeName("sk").keyType("RANGE"))
                .provisionedThroughput(throughput -> throughput.readCapacityUnits(3L).writeCapacityUnits(3L))
                .localSecondaryIndexes(index -> index.indexName("keys")
                        .keySchema(key -> key.attributeName("pk").keyType("HASH"),
                                key -> key.attributeName("lk").keyType("RANGE"))
                        .projection(projection -> projection.projectionType("KEYS_ONLY")),
                        index -> index.indexName("included")
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
    void filtersUseTheSelectedIndexReadPath(String operation, String index, boolean consistent,
                                           String mode, String attribute, int expectedCount) throws IOException {
        observations.put("operation", operation);
        observations.put("index", index);
        observations.put("consistent", consistent);
        observations.put("mode", mode);
        observations.put("filterAttribute", attribute);
        observations.put("expectedCount", expectedCount);
        ReadResult result;
        if ("Query".equals(operation)) {
            result = query(index, consistent, mode, attribute);
        } else {
            result = scan(index, consistent, mode, attribute);
        }
        JsonNode measured = wire.lastResponse();
        observations.put("measuredResponse", measured);
        List<Map<String, AttributeValue>> readback = originalItems.stream().map(item -> dynamo.getItem(request ->
                request.tableName(table).consistentRead(true).key(key(item.get("sk").s()))).item()).toList();
        TableDescription after = describe();
        assertAll("Filter admission or complete returned items, and unchanged whole resource state",
                () -> assertReadResult(result, measured, index, mode, attribute, expectedCount),
                () -> assertEquals(originalItems, readback),
                () -> assertEquals(configuration(initial), configuration(after)));
    }

    private ReadResult query(String index, boolean consistent, String mode, String attribute) {
        QueryRequest.Builder request = QueryRequest.builder().tableName(table).indexName(index)
                .consistentRead(consistent).returnConsumedCapacity("INDEXES");
        if ("legacy-all".equals(mode)) {
            request.keyConditions(Map.of("pk", condition("owned")))
                    .queryFilter(Map.of(attribute, condition("keep")));
        } else {
            request.keyConditionExpression("pk = :owner").filterExpression("#filter = :filter")
                    .expressionAttributeNames(Map.of("#filter", attribute))
                    .expressionAttributeValues(Map.of(":owner", value("owned"), ":filter", value("keep")));
        }
        if ("projection".equals(mode)) {
            request.projectionExpression("pk, payload");
        } else {
            request.select(selection(mode));
        }
        try {
            QueryResponse response = dynamo.query(request.build());
            return new ReadResult(response.items(), response.count(), response.scannedCount(),
                    response.lastEvaluatedKey(), null);
        } catch (DynamoDbException failure) {
            return new ReadResult(List.of(), null, null, Map.of(), failure);
        }
    }

    private ReadResult scan(String index, boolean consistent, String mode, String attribute) {
        ScanRequest.Builder request = ScanRequest.builder().tableName(table).indexName(index)
                .consistentRead(consistent).returnConsumedCapacity("INDEXES");
        if ("legacy-all".equals(mode)) {
            request.scanFilter(Map.of(attribute, condition("keep")));
        } else {
            request.filterExpression("#filter = :filter").expressionAttributeNames(Map.of("#filter", attribute))
                    .expressionAttributeValues(Map.of(":filter", value("all-none".equals(mode) ? "absent" : "keep")));
        }
        if ("projection".equals(mode)) {
            request.projectionExpression("pk, payload");
        } else {
            request.select(selection(mode));
        }
        try {
            ScanResponse response = dynamo.scan(request.build());
            return new ReadResult(response.items(), response.count(), response.scannedCount(),
                    response.lastEvaluatedKey(), null);
        } catch (DynamoDbException failure) {
            return new ReadResult(List.of(), null, null, Map.of(), failure);
        }
    }

    private static String selection(String mode) {
        return switch (mode) {
            case "projected" -> "ALL_PROJECTED_ATTRIBUTES";
            case "count" -> "COUNT";
            default -> "ALL_ATTRIBUTES";
        };
    }

    private void assertReadResult(ReadResult result, JsonNode measured, String index,
                                  String mode, String attribute, int expectedCount) {
        if (expectedCount < 0) {
            assertNotNull(result.failure(), "A Query filter cannot read attributes absent from the index projection");
            String message = "One or more parameter values were invalid: Secondary index " + index
                    + " does not project one or more filter attributes: [" + attribute + "]";
            assertAll("Exact typed Query refusal",
                    () -> assertEquals(400, result.failure().statusCode()),
                    () -> assertEquals("ValidationException", result.failure().awsErrorDetails().errorCode()),
                    () -> assertEquals(message, result.failure().awsErrorDetails().errorMessage()),
                    () -> assertFalse(measured.has("Items") || measured.has("ConsumedCapacity")));
            return;
        }
        assertEquals(null, result.failure(), "The selected read path is accepted");
        List<Map<String, AttributeValue>> expected = expectedCount == 0 || "count".equals(mode)
                ? List.of() : List.of(originalItems.getFirst());
        if ("projection".equals(mode)) {
            expected = expected.stream().map(item -> Map.of("pk", item.get("pk"), "payload", item.get("payload")))
                    .toList();
        } else if ("projected".equals(mode)) {
            expected = expected.stream().map(item -> {
                Map<String, AttributeValue> projected = new LinkedHashMap<>(key(item.get("sk").s()));
                projected.put("lk", item.get("lk"));
                if ("included".equals(index)) {
                    projected.put("flag", item.get("flag"));
                }
                return projected;
            }).toList();
        }
        List<Map<String, AttributeValue>> expectedItems = expected;
        assertAll("Full result collection and evaluated count",
                () -> assertEquals(expectedCount, result.count()),
                () -> assertEquals(2, result.scanned()),
                () -> assertTrue(result.lastEvaluatedKey().isEmpty()),
                () -> assertEquals(expectedItems.size(), result.items().size()),
                () -> assertEquals(new HashSet<>(expectedItems), new HashSet<>(result.items())),
                () -> assertEquals(!"count".equals(mode), measured.has("Items")));
    }

    private record ReadResult(List<Map<String, AttributeValue>> items, Integer count, Integer scanned,
                              Map<String, AttributeValue> lastEvaluatedKey, DynamoDbException failure) {}

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
        item.put("hidden", value(flag));
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
            Path directory = Path.of("target", "dynamodb-lsi-filter-selection-wire");
            Files.createDirectories(directory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(table + ".json").toFile(),
                    Map.of("table", table, "observations", observations, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "Every attempted exchange must retain its response or fail qualification");
        }
    }
}

package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.cloudwatch.metrics.AlarmEvaluator;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PolicyWorkloadClockProfile;
import io.floci.aws.testing.PolicyWorkloadTimeline;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
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
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Positive prerequisite: native SDK traffic reaches the real scheduled evaluator under one explicit clock. */
@QuarkusTest
@TestProfile(PolicyWorkloadClockProfile.class)
@Timeout(45)
class DynamoDbPolicyClockIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-ddb-policy-clock-wire-id");
    private static final String INDEX = "focused";
    @Inject
    PolicyWorkloadTimeline timeline;
    @Inject
    BeanManager beans;
    private final String table = "policy-clock-" + UUID.randomUUID();
    private final String alarm = "policy-clock-" + UUID.randomUUID();
    private final WireReceipt wire = new WireReceipt();
    private final Map<String, Object> observations = new LinkedHashMap<>();
    private DynamoDbClient dynamo;
    private CloudWatchClient metrics;
    private TableDescription initialTable;
    private Instant minute;
    private String account;
    private String alarmArn;
    private boolean createAttempted;
    private boolean alarmAttempted;

    @BeforeEach
    void reserveOwnedScopeAndDiscoverTheRealTable() {
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
        observations.put("reservedAlarm", alarm);
        assertEquals(List.of(), dynamo.listTables().tableNames());
        assertAlarmInventoryEmpty();
        long seconds = timeline.clock().instant().getEpochSecond();
        timeline.advance(Duration.between(timeline.clock().instant(), Instant.ofEpochSecond((seconds / 300 + 1) * 300)));
        createAttempted = true;
        dynamo.createTable(request -> request.tableName(table)
                .attributeDefinitions(attribute -> attribute.attributeName("pk").attributeType("S"),
                        attribute -> attribute.attributeName("gk").attributeType("S"))
                .keySchema(key -> key.attributeName("pk").keyType("HASH"))
                .billingMode("PROVISIONED")
                .provisionedThroughput(throughput -> throughput.readCapacityUnits(3L).writeCapacityUnits(3L))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName(INDEX)
                        .keySchema(key -> key.attributeName("gk").keyType("HASH"))
                        .projection(projection -> projection.projectionType("ALL"))
                        .provisionedThroughput(throughput -> throughput.readCapacityUnits(3L).writeCapacityUnits(3L))
                        .build()));
        await().pollInSameThread().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertEquals("ACTIVE", describe().tableStatusAsString()));
        initialTable = describe();
        assertNotNull(initialTable.tableId());
        observations.put("tableId", initialTable.tableId());
        timeline.assertSchedules();
        for (Class<?> type : List.of(DynamoDbService.class, DynamoDbCapacityMetrics.class,
                CloudWatchMetricsService.class, AlarmEvaluator.class)) {
            assertEquals(1, beans.getBeans(type).size(), "Exactly one selected clock bean must exist");
        }
        timeline.run("publisher");
        dynamo.putItem(request -> request.tableName(table).item(item("a")));
        timeline.advance(Duration.ofMinutes(5));
        timeline.run("publisher");
        minute = timeline.clock().instant();
        observations.put("measuredMinute", minute.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"table-read", "table-write", "index-read", "index-write"})
    void nativeRequestsReachTheOwnedAlarmOnlyAfterTheirMeasuredMinuteCompletes(String path) throws IOException {
        boolean read = path.endsWith("read");
        boolean index = path.startsWith("index");
        String metric = read ? "ConsumedReadCapacityUnits" : "ConsumedWriteCapacityUnits";
        double expected = read ? index ? 1.0 : 2.0 : 5.0;
        List<Dimension> dimensions = dimensions(index);
        observations.put("path", path);
        observations.put("expectedSum", expected);
        alarmAttempted = true;
        metrics.putMetricAlarm(request -> request.alarmName(alarm).namespace("AWS/DynamoDB")
                .metricName(metric).dimensions(dimensions).statistic("Sum").period(60).evaluationPeriods(1)
                .comparisonOperator("GreaterThanThreshold").threshold(expected / 2).actionsEnabled(false));
        MetricAlarm initial = alarm();
        alarmArn = initial.alarmArn();
        assertTrue(alarmArn.contains(":" + account + ":alarm:"));
        assertEquals(dimensions.stream().collect(Collectors.toMap(Dimension::name, Dimension::value)),
                initial.dimensions().stream().collect(Collectors.toMap(Dimension::name, Dimension::value)));
        assertEquals(List.of(), initial.alarmActions());
        assertEquals("Sum", initial.statisticAsString());
        assertEquals(60, initial.period());
        assertEquals(1, initial.evaluationPeriods());
        timeline.advance(Duration.ofSeconds(10));
        if (!read) {
            dynamo.putItem(request -> request.tableName(table).item(item("b")));
        } else if (index) {
            assertEquals(List.of(item("a")), dynamo.query(request -> request.tableName(table).indexName(INDEX)
                    .keyConditionExpression("gk = :group")
                    .expressionAttributeValues(Map.of(":group", value("group")))).items());
        } else {
            assertEquals(item("a"), dynamo.getItem(request -> request.tableName(table)
                    .key(Map.of("pk", value("owned"))).consistentRead(true)).item());
        }
        assertMeasuredSum(metric, dimensions, expected);
        timeline.advance(Duration.ofSeconds(50).minusNanos(1));
        timeline.run("publisher");
        timeline.run("evaluator");
        observations.put("incompleteAt", timeline.clock().instant().toString());
        assertEquals("OK", alarm().stateValueAsString());
        timeline.advance(Duration.ofNanos(1));
        timeline.run("publisher");
        timeline.run("evaluator");
        observations.put("completeAt", timeline.clock().instant().toString());
        MetricAlarm evaluated = alarm();
        assertEquals(alarmArn, evaluated.alarmArn());
        assertEquals("ALARM", evaluated.stateValueAsString());
        assertEquals(timeline.clock().instant(), evaluated.stateUpdatedTimestamp());
        assertMeasuredSum(metric, dimensions, expected);
        assertEquals(configuration(initialTable), configuration(describe()));
        assertTrue(wire.calls.stream().noneMatch(row -> List.of("PutMetricData", "SetAlarmState")
                .contains(row.get("operation"))));
    }

    private void assertMeasuredSum(String metric, List<Dimension> dimensions, double expected) {
        GetMetricStatisticsResponse response = metrics.getMetricStatistics(request -> request.namespace("AWS/DynamoDB").metricName(metric)
                .dimensions(dimensions).startTime(minute).endTime(minute.plusSeconds(59))
                .period(60).statisticsWithStrings("Sum"));
        assertEquals(1, response.datapoints().size());
        assertEquals(minute, response.datapoints().getFirst().timestamp());
        assertEquals(expected, response.datapoints().getFirst().sum());
    }

    private MetricAlarm alarm() {
        DescribeAlarmsResponse response = metrics.describeAlarms(request -> request.alarmNames(alarm));
        assertNull(response.nextToken());
        assertEquals(List.of(), response.compositeAlarms());
        assertEquals(1, response.metricAlarms().size());
        return response.metricAlarms().getFirst();
    }

    private List<Dimension> dimensions(boolean index) {
        List<Dimension> dimensions = new ArrayList<>();
        dimensions.add(Dimension.builder().name("TableName").value(table).build());
        if (index) {
            dimensions.add(Dimension.builder().name("GlobalSecondaryIndexName").value(INDEX).build());
        }
        return dimensions;
    }

    private static Map<String, AttributeValue> item(String character) {
        return Map.of("pk", value("owned"), "gk", value("group"), "payload", value(character.repeat(5000)));
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

    private void assertAlarmInventoryEmpty() {
        DescribeAlarmsResponse response = metrics.describeAlarms(request -> request.alarmTypesWithStrings("MetricAlarm", "CompositeAlarm"));
        assertNull(response.nextToken());
        assertEquals(List.of(), response.metricAlarms());
        assertEquals(List.of(), response.compositeAlarms());
    }

    @AfterEach
    void independentlyRemoveOwnedAlarmAndTableThenSaveCapture() throws IOException {
        List<Throwable> failures = new ArrayList<>();
        try {
            if (metrics != null && alarmAttempted) {
                cleanup(failures, () -> {
                    DescribeAlarmsResponse current = metrics.describeAlarms(request -> request.alarmNames(alarm));
                    assertNull(current.nextToken());
                    if (!current.metricAlarms().isEmpty()) {
                        assertEquals(1, current.metricAlarms().size());
                        assertEquals(alarm, current.metricAlarms().getFirst().alarmName());
                        if (alarmArn != null) {
                            assertEquals(alarmArn, current.metricAlarms().getFirst().alarmArn());
                        }
                        metrics.deleteAlarms(request -> request.alarmNames(alarm));
                    }
                });
            }
            if (metrics != null) {
                cleanup(failures, this::assertAlarmInventoryEmpty);
            }
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
                cleanup(failures, () -> await().pollInSameThread().atMost(Duration.ofSeconds(3))
                        .pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                                assertEquals(400, assertThrows(ResourceNotFoundException.class, this::describe).statusCode())));
            }
            if (dynamo != null) {
                cleanup(failures, () -> {
                    ListTablesResponse response = dynamo.listTables();
                    assertNull(response.lastEvaluatedTableName());
                    assertEquals(List.of(), response.tableNames());
                });
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
            AssertionError failure = new AssertionError("Owned controlled-clock fixture cleanup failed");
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
            row.put("manualInstant", timeline.clock().instant().toString());
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
            Path directory = Path.of("target", "dynamodb-policy-clock-wire");
            Files.createDirectories(directory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(table + ".json").toFile(),
                    Map.of("table", table, "observations", observations, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "Every attempted exchange must retain its response or fail qualification");
        }
    }
}

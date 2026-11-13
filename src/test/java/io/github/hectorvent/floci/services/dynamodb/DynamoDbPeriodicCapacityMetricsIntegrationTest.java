package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
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
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(210)
class DynamoDbPeriodicCapacityMetricsIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-ddb-periodic-capacity-metrics-wire-id");
    private static final String INDEX = "focused";
    private final String table = "periodic-capacity-" + UUID.randomUUID();
    private final WireReceipt wire = new WireReceipt();
    private final Map<String, Object> observations = new LinkedHashMap<>();
    private DynamoDbClient dynamo;
    private CloudWatchClient metrics;
    private Instant start;
    private TableDescription initialTable;
    private boolean createAttempted;

    @BeforeEach
    void createOwnedProvisionedTableAndIndex(TestInfo testInfo) {
        boolean onDemand = testInfo.getTestMethod().orElseThrow().getName()
                .equals("freshOnDemandTableDoesNotPublishProvisionedSamples");
        observations.put("billingMode", onDemand ? "PAY_PER_REQUEST" : "PROVISIONED");
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
        GlobalSecondaryIndex.Builder index = GlobalSecondaryIndex.builder().indexName(INDEX)
                .keySchema(key -> key.attributeName("gk").keyType("HASH"))
                .projection(projection -> projection.projectionType("ALL"));
        CreateTableRequest.Builder request = CreateTableRequest.builder().tableName(table)
                .attributeDefinitions(attribute -> attribute.attributeName("pk").attributeType("S"),
                        attribute -> attribute.attributeName("sk").attributeType("S"),
                        attribute -> attribute.attributeName("gk").attributeType("S"))
                .keySchema(key -> key.attributeName("pk").keyType("HASH"),
                        key -> key.attributeName("sk").keyType("RANGE"))
                .billingMode(onDemand ? "PAY_PER_REQUEST" : "PROVISIONED");
        if (!onDemand) {
            request.provisionedThroughput(throughput -> throughput.readCapacityUnits(3L).writeCapacityUnits(7L));
            index.provisionedThroughput(throughput -> throughput.readCapacityUnits(5L).writeCapacityUnits(11L));
        }
        dynamo.createTable(request.globalSecondaryIndexes(index.build()).build());
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertEquals("ACTIVE", describe().tableStatusAsString()));
        initialTable = describe();
        assertNotNull(initialTable.tableId());
        observations.put("tableId", initialTable.tableId());
    }

    @Test
    void activeProvisionedTableAndIndexPublishTheirActualDistinctCapacity() {
        observations.put("operation", "provisioned-gauges");
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> assertAll(
                () -> gauge("ProvisionedReadCapacityUnits", false, 3),
                () -> gauge("ProvisionedWriteCapacityUnits", false, 7),
                () -> gauge("ProvisionedReadCapacityUnits", true, 5),
                () -> gauge("ProvisionedWriteCapacityUnits", true, 11)));
        assertEquals(configuration(initialTable), configuration(describe()));
    }

    @Test
    void aFullyObservedIdleMinutePublishesRealZeroConsumptionPoints() {
        observations.put("operation", "complete-idle-minute");
        // Give the local periodic observer twenty seconds to see this new table before the selected minute.
        Instant fullMinute = Instant.ofEpochSecond((Instant.now().getEpochSecond() + 79) / 60 * 60);
        Instant end = fullMinute.plusSeconds(60);
        observations.put("fullMinuteStart", fullMinute.toString());
        observations.put("fullMinuteEnd", end.toString());
        await().atMost(Duration.ofSeconds(165)).pollInterval(Duration.ofMillis(100))
                .until(() -> !Instant.now().isBefore(end.plusSeconds(20)));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> assertAll(
                () -> idle("ConsumedReadCapacityUnits", false, fullMinute, end),
                () -> idle("ConsumedWriteCapacityUnits", false, fullMinute, end),
                () -> idle("ConsumedReadCapacityUnits", true, fullMinute, end),
                () -> idle("ConsumedWriteCapacityUnits", true, fullMinute, end)));
        assertEquals(configuration(initialTable), configuration(describe()));
    }

    @Test
    void freshOnDemandTableDoesNotPublishProvisionedSamples() {
        observations.put("operation", "fresh-on-demand-no-provisioned");
        observations.put("observationStartedAt", Instant.now().toString());
        await().during(Duration.ofSeconds(20)).atMost(Duration.ofSeconds(25))
                .pollInterval(Duration.ofMillis(500)).untilAsserted(() -> assertAll(
                        () -> absentProvisioned("ProvisionedReadCapacityUnits", false),
                        () -> absentProvisioned("ProvisionedWriteCapacityUnits", false),
                        () -> absentProvisioned("ProvisionedReadCapacityUnits", true),
                        () -> absentProvisioned("ProvisionedWriteCapacityUnits", true)));
        observations.put("observationFinishedAt", Instant.now().toString());
        assertEquals(configuration(initialTable), configuration(describe()));
    }

    private void absentProvisioned(String metric, boolean index) {
        assertTrue(statistics(metric, index, start, Instant.now().plusSeconds(60),
                300, "Average").datapoints().isEmpty());
    }

    private void idle(String metric, boolean index, Instant from, Instant to) {
        GetMetricStatisticsResponse response = statistics(metric, index, from, to, 60, "Sum");
        assertEquals(1, response.datapoints().size(), "An idle period needs a stored zero point");
        assertEquals(from, response.datapoints().getFirst().timestamp());
        assertEquals(0.0, response.datapoints().getFirst().sum());
    }

    private void gauge(String metric, boolean index, double expected) {
        GetMetricStatisticsResponse response = statistics(metric, index, start, Instant.now().plusSeconds(60),
                300, "Average");
        assertFalse(response.datapoints().isEmpty(), "Provisioned capacity must have a real metric sample");
        assertTrue(response.datapoints().stream().allMatch(point -> Double.valueOf(expected).equals(point.average())));
    }

    private GetMetricStatisticsResponse statistics(String metric, boolean index, Instant from, Instant to,
                                                   int period, String statistic) {
        List<Dimension> dimensions = new ArrayList<>();
        dimensions.add(Dimension.builder().name("TableName").value(table).build());
        if (index) {
            dimensions.add(Dimension.builder().name("GlobalSecondaryIndexName").value(INDEX).build());
        }
        return metrics.getMetricStatistics(request -> request.namespace("AWS/DynamoDB").metricName(metric)
                .dimensions(dimensions).startTime(from).endTime(to).period(period).statisticsWithStrings(statistic));
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
            row.put("requestAt", Instant.now().toString());
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
            row.put("responseAt", Instant.now().toString());
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
            Path directory = Path.of("target", "dynamodb-periodic-capacity-wire");
            Files.createDirectories(directory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(table + ".json").toFile(),
                    Map.of("table", table, "observations", observations, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "Every attempted exchange must retain its response or fail qualification");
        }
    }
}

package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudwatch.metrics.AlarmEvaluator;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.PolicyWorkloadClockProfile;
import io.github.hectorvent.floci.testing.PolicyWorkloadTimeline;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.Alarm;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingActivitiesResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingPoliciesResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.PutScalingPolicyResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTarget;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalingActivity;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalingPolicy;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real SDK traffic, published consumption and owned alarms must adjust only the selected capacity. */
@QuarkusTest
@TestProfile(PolicyWorkloadClockProfile.class)
@Timeout(120)
class DynamoDbPolicyWorkloadIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-ddb-policy-workload-wire-id");
    private static final String INDEX = "focused";
    @Inject
    PolicyWorkloadTimeline timeline;
    @Inject
    BeanManager beans;
    private final String table = "policy-workload-" + UUID.randomUUID();
    private final String policyName = "policy-workload-" + UUID.randomUUID();
    private final WireReceipt wire = new WireReceipt();
    private final Map<String, Object> observations = new LinkedHashMap<>();
    private DynamoDbClient dynamo;
    private CloudWatchClient metrics;
    private ApplicationAutoScalingClient scaling;
    private TableDescription initialTable;
    private Instant minute;
    private String account;
    private String policyArn;
    private String resource;
    private String dimension;
    private boolean selectedIndex;
    private boolean selectedRead;
    private ScalingPolicy initialPolicy;
    private List<ScalableTarget> initialTargets;
    private ObjectNode initialConfiguration;
    private final Map<String, String> ownedAlarms = new LinkedHashMap<>();
    private boolean createAttempted;
    private boolean targetAttempted;
    private boolean policyAttempted;

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
        scaling = AwsTestClients.create(ApplicationAutoScalingClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(8)).apiCallAttemptTimeout(Duration.ofSeconds(6))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)));
        observations.put("account", account);
        observations.put("reservedTable", table);
        observations.put("reservedPolicy", policyName);
        ListTablesResponse tables = dynamo.listTables();
        assertNull(tables.lastEvaluatedTableName());
        assertEquals(List.of(), tables.tableNames());
        assertEquals(List.of(), targets());
        assertEquals(List.of(), policies());
        assertEquals(List.of(), activities());
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
        dynamo.putItem(request -> request.tableName(table).item(item()));
        timeline.advance(Duration.ofMinutes(5));
        timeline.run("publisher");
        minute = timeline.clock().instant();
        observations.put("measuredMinute", minute.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"table-read", "table-write", "index-read", "index-write"})
    void realConsumptionScalesOnlyItsRegisteredDimensionAfterCompletedHighAndLowWindows(String path) {
        selectedRead = path.endsWith("read");
        selectedIndex = path.startsWith("index");
        resource = "table/" + table + (selectedIndex ? "/index/" + INDEX : "");
        dimension = "dynamodb:" + (selectedIndex ? "index:" : "table:")
                + (selectedRead ? "ReadCapacityUnits" : "WriteCapacityUnits");
        String metric = selectedRead ? "ConsumedReadCapacityUnits" : "ConsumedWriteCapacityUnits";
        double measuredSum = selectedRead ? 128.0 : 9000.0;
        long scaledCapacity = selectedRead ? 4 : 215;
        observations.put("path", path);
        observations.put("resourceId", resource);
        observations.put("scalableDimension", dimension);
        observations.put("expectedHighSum", measuredSum);
        observations.put("expectedScaledCapacity", scaledCapacity);
        targetAttempted = true;
        scaling.registerScalableTarget(request -> request.serviceNamespace("dynamodb").resourceId(resource)
                .scalableDimension(dimension).minCapacity(1).maxCapacity(300));
        initialTargets = targets();
        assertEquals(1, initialTargets.size());
        assertEquals(resource, initialTargets.getFirst().resourceId());
        assertEquals(dimension, initialTargets.getFirst().scalableDimensionAsString());
        policyAttempted = true;
        PutScalingPolicyResponse created = scaling.putScalingPolicy(request -> request.serviceNamespace("dynamodb")
                .resourceId(resource).scalableDimension(dimension).policyName(policyName)
                .policyType("TargetTrackingScaling").targetTrackingScalingPolicyConfiguration(config -> config
                        .targetValue(70.0).predefinedMetricSpecification(spec -> spec.predefinedMetricType(
                                selectedRead ? "DynamoDBReadCapacityUtilization" : "DynamoDBWriteCapacityUtilization"))));
        policyArn = created.policyARN();
        assertNotNull(policyArn);
        assertTrue(policyArn.contains(":" + account + ":scalingPolicy:"));
        List<ScalingPolicy> currentPolicies = policies();
        assertEquals(1, currentPolicies.size());
        initialPolicy = currentPolicies.getFirst();
        assertEquals(policyArn, initialPolicy.policyARN());
        captureOwnedAlarms(initialPolicy);
        List<MetricAlarm> initialAlarms = alarms();
        TableDescription before = describe();
        initialConfiguration = stableConfiguration(wire.lastJsonResponse());
        assertEquals(initialTable.tableId(), before.tableId());
        List<Executable> assertions = new ArrayList<>();
        assertions.add(() -> assertManagedAlarms(initialAlarms, 3));
        assertions.add(() -> assertNull(initialPolicy.targetTrackingScalingPolicyConfiguration().scaleInCooldown()));
        assertions.add(() -> assertNull(initialPolicy.targetTrackingScalingPolicyConfiguration().scaleOutCooldown()));
        List<Map<String, Object>> periods = new ArrayList<>();
        observations.put("periods", periods);
        for (int high = 0; high < 2; high++) {
            Instant start = timeline.clock().instant();
            timeline.advance(Duration.ofSeconds(10));
            produceTraffic(high);
            GetMetricStatisticsResponse measured = measured(metric, start);
            assertions.add(() -> assertMetric(measured, start, measuredSum));
            timeline.advance(Duration.ofSeconds(50));
            timeline.run("publisher");
            timeline.run("evaluator");
            long expected = high == 0 ? 3 : scaledCapacity;
            collectPeriod(periods, assertions, "high", high + 1, start, expected, false);
        }
        for (int low = 1; low <= 15; low++) {
            Instant start = timeline.clock().instant();
            timeline.advance(Duration.ofMinutes(1));
            timeline.run("publisher");
            GetMetricStatisticsResponse measured = measured(metric, start);
            assertions.add(() -> assertMetric(measured, start, 0));
            timeline.run("evaluator");
            long expected = low == 15 ? 1 : scaledCapacity;
            collectPeriod(periods, assertions, "idle", low, start, expected, low == 15);
        }
        List<ScalingActivity> completed = activities();
        observations.put("finalActivityIds", completed.stream().map(ScalingActivity::activityId).toList());
        assertions.add(() -> assertEquals(2, completed.size(), "One actual scale-out and one actual scale-in"));
        assertions.add(() -> assertTrue(completed.stream().allMatch(activity ->
                "Successful".equals(activity.statusCodeAsString()) && resource.equals(activity.resourceId())
                        && dimension.equals(activity.scalableDimensionAsString()))));
        assertions.add(() -> assertEquals(List.of(initialPolicy), policies()));
        assertions.add(() -> assertEquals(initialTargets, targets()));
        assertions.add(() -> assertTrue(wire.calls.stream().noneMatch(row ->
                Set.of("PutMetricData", "SetAlarmState", "UpdateTable").contains(row.get("operation")))));
        assertAll("Real request consumption must drive the owned DynamoDB target-tracking policy", assertions);
    }

    private void produceTraffic(int minuteNumber) {
        if (!selectedRead) {
            for (int update = 0; update < 72; update++) {
                String revision = Integer.toString(minuteNumber * 72 + update);
                dynamo.updateItem(request -> request.tableName(table).key(Map.of("pk", value("owned")))
                        .updateExpression("SET revision = :revision")
                        .expressionAttributeValues(Map.of(":revision", value(revision))));
            }
        } else if (selectedIndex) {
            for (int read = 0; read < 8; read++) {
                QueryResponse response = dynamo.query(request -> request.tableName(table).indexName(INDEX)
                        .keyConditionExpression("gk = :group")
                        .expressionAttributeValues(Map.of(":group", value("group"))));
                assertEquals(List.of(item()), response.items());
                assertEquals(1, response.count());
                assertEquals(1, response.scannedCount());
                assertTrue(response.lastEvaluatedKey().isEmpty());
            }
        } else {
            for (int read = 0; read < 4; read++) {
                assertEquals(item(), dynamo.getItem(request -> request.tableName(table)
                        .key(Map.of("pk", value("owned"))).consistentRead(true)).item());
            }
        }
    }

    private void collectPeriod(List<Map<String, Object>> periods, List<Executable> assertions, String phase,
                               int ordinal, Instant start, long expectedCapacity, boolean lowComplete) {
        TableDescription current = describe();
        ObjectNode configuration = stableConfiguration(wire.lastJsonResponse());
        Map<String, Long> capacities = capacities(current);
        List<MetricAlarm> currentAlarms = alarms();
        List<ScalingActivity> currentActivities = activities();
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("phase", phase);
        observation.put("ordinal", ordinal);
        observation.put("start", start.toString());
        observation.put("completeAt", timeline.clock().instant().toString());
        observation.put("capacities", capacities);
        observation.put("activityIds", currentActivities.stream().map(ScalingActivity::activityId).toList());
        periods.add(observation);
        assertions.add(() -> assertEquals(expectedCapacity, capacities.get(dimension)));
        assertions.add(() -> assertTrue(capacities.entrySet().stream()
                .filter(entry -> !dimension.equals(entry.getKey())).allMatch(entry -> entry.getValue() == 3)));
        assertions.add(() -> assertEquals(initialConfiguration, configuration,
                "Full table metadata except selected capacity/warm fields and asynchronous size/count members"));
        assertions.add(() -> assertManagedAlarms(currentAlarms, expectedCapacity));
        if ("high".equals(phase) && ordinal == 1) {
            assertions.add(() -> assertEquals(List.of(), currentActivities));
        } else if (!lowComplete) {
            assertions.add(() -> assertEquals(1, currentActivities.size()));
        }
        if (lowComplete) {
            assertions.add(() -> assertEquals(2, currentActivities.size()));
        }
    }

    private GetMetricStatisticsResponse measured(String metric, Instant start) {
        return metrics.getMetricStatistics(request -> request.namespace("AWS/DynamoDB").metricName(metric)
                .dimensions(dimensions(selectedIndex)).startTime(start).endTime(start.plusSeconds(59))
                .period(60).statisticsWithStrings("Sum"));
    }

    private static void assertMetric(GetMetricStatisticsResponse response, Instant start, double sum) {
        assertEquals(1, response.datapoints().size());
        assertEquals(start, response.datapoints().getFirst().timestamp());
        assertEquals(sum, response.datapoints().getFirst().sum());
    }

    private void assertManagedAlarms(List<MetricAlarm> current, long capacity) {
        assertEquals(4, current.size());
        assertEquals(ownedAlarms, current.stream().collect(Collectors.toMap(MetricAlarm::alarmName, MetricAlarm::alarmArn)));
        String consumed = selectedRead ? "ConsumedReadCapacityUnits" : "ConsumedWriteCapacityUnits";
        String provisioned = selectedRead ? "ProvisionedReadCapacityUnits" : "ProvisionedWriteCapacityUnits";
        Map<String, MetricAlarm> kinds = current.stream().collect(Collectors.toMap(
                alarm -> alarm.metricName() + ":" + alarm.comparisonOperatorAsString(), alarm -> alarm));
        assertEquals(Set.of(consumed + ":GreaterThanThreshold", consumed + ":LessThanThreshold",
                provisioned + ":GreaterThanThreshold", provisioned + ":LessThanThreshold"), kinds.keySet());
        for (MetricAlarm alarm : current) {
            boolean usage = consumed.equals(alarm.metricName());
            boolean high = "GreaterThanThreshold".equals(alarm.comparisonOperatorAsString());
            assertEquals("AWS/DynamoDB", alarm.namespace());
            assertEquals(dimensions(selectedIndex).stream().collect(Collectors.toMap(Dimension::name, Dimension::value)),
                    alarm.dimensions().stream().collect(Collectors.toMap(Dimension::name, Dimension::value)));
            assertEquals(usage ? "Sum" : "Average", alarm.statisticAsString());
            assertEquals(usage ? 60 : 300, alarm.period());
            assertEquals(usage ? high ? 2 : 15 : 3, alarm.evaluationPeriods());
            assertEquals(usage ? capacity * 60.0 * (high ? 70 : 50) / 100 : (double) capacity, alarm.threshold());
            assertEquals(List.of(policyArn), alarm.alarmActions());
            assertTrue(alarm.actionsEnabled());
            assertTrue(alarm.alarmArn().contains(":" + account + ":alarm:"));
        }
    }

    private List<MetricAlarm> alarms() {
        DescribeAlarmsResponse response = metrics.describeAlarms(request -> request
                .alarmTypesWithStrings("MetricAlarm", "CompositeAlarm"));
        assertNull(response.nextToken());
        assertEquals(List.of(), response.compositeAlarms());
        return response.metricAlarms();
    }

    private List<ScalableTarget> targets() {
        DescribeScalableTargetsResponse response = scaling.describeScalableTargets(request -> request
                .serviceNamespace("dynamodb").maxResults(50));
        assertNull(response.nextToken(), "Fresh owned scope contains at most one target");
        return response.scalableTargets();
    }

    private List<ScalingPolicy> policies() {
        DescribeScalingPoliciesResponse response = scaling.describeScalingPolicies(request -> request
                .serviceNamespace("dynamodb").maxResults(10));
        assertNull(response.nextToken(), "Fresh owned scope contains at most one policy");
        return response.scalingPolicies();
    }

    private List<ScalingActivity> activities() {
        DescribeScalingActivitiesResponse response = scaling.describeScalingActivities(request -> request
                .serviceNamespace("dynamodb").maxResults(50));
        assertNull(response.nextToken(), "Two expected changes fit one bounded page");
        return response.scalingActivities();
    }

    private void captureOwnedAlarms(ScalingPolicy policy) {
        policy.alarms().forEach(alarm -> {
            assertNotNull(alarm.alarmName());
            assertNotNull(alarm.alarmARN());
            assertNull(ownedAlarms.putIfAbsent(alarm.alarmName(), alarm.alarmARN()));
        });
    }

    private static Map<String, Long> capacities(TableDescription current) {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("dynamodb:table:ReadCapacityUnits", current.provisionedThroughput().readCapacityUnits());
        values.put("dynamodb:table:WriteCapacityUnits", current.provisionedThroughput().writeCapacityUnits());
        values.put("dynamodb:index:ReadCapacityUnits", current.globalSecondaryIndexes().getFirst()
                .provisionedThroughput().readCapacityUnits());
        values.put("dynamodb:index:WriteCapacityUnits", current.globalSecondaryIndexes().getFirst()
                .provisionedThroughput().writeCapacityUnits());
        return values;
    }

    private ObjectNode stableConfiguration(ObjectNode raw) {
        ObjectNode copy = ((ObjectNode) raw.get("Table")).deepCopy();
        copy.remove(List.of("ItemCount", "TableSizeBytes"));
        for (JsonNode index : copy.path("GlobalSecondaryIndexes")) {
            ((ObjectNode) index).remove(List.of("ItemCount", "IndexSizeBytes"));
        }
        ObjectNode selected = selectedIndex ? (ObjectNode) copy.path("GlobalSecondaryIndexes").get(0) : copy;
        ((ObjectNode) selected.get("ProvisionedThroughput")).remove(List.of(
                selectedRead ? "ReadCapacityUnits" : "WriteCapacityUnits",
                "LastIncreaseDateTime", "LastDecreaseDateTime", "NumberOfDecreasesToday"));
        if (selected.get("WarmThroughput") instanceof ObjectNode warm) {
            warm.remove(selectedRead ? "ReadUnitsPerSecond" : "WriteUnitsPerSecond");
        }
        return copy;
    }

    private List<Dimension> dimensions(boolean index) {
        List<Dimension> dimensions = new ArrayList<>();
        dimensions.add(Dimension.builder().name("TableName").value(table).build());
        if (index) {
            dimensions.add(Dimension.builder().name("GlobalSecondaryIndexName").value(INDEX).build());
        }
        return dimensions;
    }

    private static Map<String, AttributeValue> item() {
        return Map.of("pk", value("owned"), "gk", value("group"), "payload", value("a".repeat(127900)));
    }

    private static AttributeValue value(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private TableDescription describe() {
        return dynamo.describeTable(request -> request.tableName(table)).table();
    }

    private void assertAlarmInventoryEmpty() {
        DescribeAlarmsResponse response = metrics.describeAlarms(request -> request.alarmTypesWithStrings("MetricAlarm", "CompositeAlarm"));
        assertNull(response.nextToken());
        assertEquals(List.of(), response.metricAlarms());
        assertEquals(List.of(), response.compositeAlarms());
    }

    @AfterEach
    void independentlyRemoveOwnedPolicyTargetAlarmsAndTableThenSaveCapture() throws IOException {
        List<Throwable> failures = new ArrayList<>();
        try {
            if (scaling != null && policyAttempted) {
                cleanup(failures, () -> {
                    List<ScalingPolicy> current = policies();
                    for (ScalingPolicy policy : current) {
                        assertEquals(policyName, policy.policyName());
                        assertEquals(resource, policy.resourceId());
                        assertEquals(dimension, policy.scalableDimensionAsString());
                        if (policyArn != null) {
                            assertEquals(policyArn, policy.policyARN());
                        }
                        for (Alarm alarm : policy.alarms()) {
                            String existing = ownedAlarms.putIfAbsent(alarm.alarmName(), alarm.alarmARN());
                            if (existing != null) {
                                assertEquals(existing, alarm.alarmARN());
                            }
                        }
                        scaling.deleteScalingPolicy(request -> request.serviceNamespace("dynamodb")
                                .resourceId(resource).scalableDimension(dimension).policyName(policyName));
                    }
                });
            }
            if (scaling != null && targetAttempted) {
                cleanup(failures, () -> {
                    List<ScalableTarget> current = targets();
                    for (ScalableTarget target : current) {
                        assertEquals(resource, target.resourceId());
                        assertEquals(dimension, target.scalableDimensionAsString());
                        if (initialTargets != null && !initialTargets.isEmpty()) {
                            assertEquals(initialTargets.getFirst().scalableTargetARN(), target.scalableTargetARN());
                        }
                        scaling.deregisterScalableTarget(request -> request.serviceNamespace("dynamodb")
                                .resourceId(resource).scalableDimension(dimension));
                    }
                });
            }
            if (metrics != null) {
                if (policyAttempted) {
                    cleanup(failures, () -> {
                        for (MetricAlarm alarm : alarms()) {
                            assertTrue(alarm.alarmArn().contains(":" + account + ":alarm:"));
                            assertTrue(alarm.alarmName().startsWith("TargetTracking-" + resource + "-"));
                            assertEquals(1, alarm.alarmActions().size());
                            String action = alarm.alarmActions().getFirst();
                            assertTrue(action.contains(":" + account + ":scalingPolicy:"));
                            assertTrue(action.endsWith(":resource/dynamodb/" + resource + ":policyName/" + policyName));
                            if (policyArn != null) {
                                assertEquals(policyArn, action);
                            }
                            String existing = ownedAlarms.putIfAbsent(alarm.alarmName(), alarm.alarmArn());
                            if (existing != null) {
                                assertEquals(existing, alarm.alarmArn());
                            }
                        }
                    });
                }
                for (Map.Entry<String, String> owned : ownedAlarms.entrySet()) {
                    cleanup(failures, () -> {
                        DescribeAlarmsResponse current = metrics.describeAlarms(request -> request.alarmNames(owned.getKey()));
                        assertNull(current.nextToken());
                        assertEquals(List.of(), current.compositeAlarms());
                        for (MetricAlarm alarm : current.metricAlarms()) {
                            assertEquals(owned.getKey(), alarm.alarmName());
                            assertEquals(owned.getValue(), alarm.alarmArn());
                            metrics.deleteAlarms(request -> request.alarmNames(owned.getKey()));
                        }
                    });
                }
                cleanup(failures, this::assertAlarmInventoryEmpty);
            }
            if (scaling != null) {
                cleanup(failures, () -> assertEquals(List.of(), policies()));
                cleanup(failures, () -> assertEquals(List.of(), targets()));
                cleanup(failures, () -> observations.put("retainedActivityIds",
                        activities().stream().map(ScalingActivity::activityId).toList()));
            }
            if (dynamo != null && createAttempted) {
                cleanup(failures, () -> {
                    try {
                        TableDescription current = describe();
                        if (initialTable != null && initialTable.tableId() != null) {
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
                if (scaling != null) {
                    scaling.close();
                }
            }
        }
        if (!failures.isEmpty()) {
            AssertionError failure = new AssertionError("Owned policy workload fixture cleanup failed");
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

        private ObjectNode lastJsonResponse() {
            try {
                return (ObjectNode) MAPPER.readTree(Base64.getDecoder().decode((String) calls.getLast().get("responseBase64")));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private void save() throws IOException {
            Path directory = Path.of("target", "dynamodb-policy-workload-wire");
            Files.createDirectories(directory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(table + ".json").toFile(),
                    Map.of("table", table, "observations", observations, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "Every attempted exchange must retain its response or fail qualification");
        }
    }
}

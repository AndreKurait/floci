package io.github.hectorvent.floci.services.dynamodb;

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
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.Alarm;
import software.amazon.awssdk.services.applicationautoscaling.model.ApplicationAutoScalingException;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingActivitiesResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingPoliciesResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.PutScalingPolicyRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.PutScalingPolicyResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTarget;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalingActivity;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalingPolicy;
import software.amazon.awssdk.services.applicationautoscaling.model.TargetTrackingScalingPolicyConfiguration;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
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


/** Bounded SDK admission observations use fixed capacity and do not produce a scaling workload. */
@QuarkusTest
@TestProfile(PolicyWorkloadClockProfile.class)
@Timeout(120)
class DynamoDbPolicyConditionalAdmissionIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-ddb-policy-conditional-admission-wire-id");
    private static final String INDEX = "focused";
    @Inject
    PolicyWorkloadTimeline timeline;
    @Inject
    BeanManager beans;
    private final String table = "policy-admission-" + UUID.randomUUID();
    private final String policyName = "policy-admission-" + UUID.randomUUID();
    private final WireReceipt wire = new WireReceipt();
    private final Map<String, Object> observations = new LinkedHashMap<>();
    private DynamoDbClient dynamo;
    private CloudWatchClient metrics;
    private ApplicationAutoScalingClient scaling;
    private TableDescription initialTable;
    private String account;
    private String policyArn;
    private String resource;
    private String dimension;
    private List<ScalableTarget> initialTargets;
    private final Map<String, String> ownedAlarms = new LinkedHashMap<>();
    private boolean createAttempted;
    private boolean targetAttempted;
    private boolean policyAttempted;
    private boolean cleaning;
    private int forwardCalls;
    private int cleanupCalls;

    @BeforeEach
    void reserveOwnedFixedCapacityTableAndTarget() {
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
        createAttempted = true;
        dynamo.createTable(request -> request.tableName(table)
                .attributeDefinitions(attribute -> attribute.attributeName("pk").attributeType("S"),
                        attribute -> attribute.attributeName("gk").attributeType("S"))
                .keySchema(key -> key.attributeName("pk").keyType("HASH"))
                .billingMode("PROVISIONED")
                .provisionedThroughput(throughput -> throughput.readCapacityUnits(1L).writeCapacityUnits(1L))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName(INDEX)
                        .keySchema(key -> key.attributeName("gk").keyType("HASH"))
                        .projection(projection -> projection.projectionType("ALL"))
                        .provisionedThroughput(throughput -> throughput.readCapacityUnits(1L).writeCapacityUnits(1L))
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
        resource = "table/" + table;
        dimension = "dynamodb:table:WriteCapacityUnits";
        observations.put("resourceId", resource);
        observations.put("scalableDimension", dimension);
        targetAttempted = true;
        scaling.registerScalableTarget(request -> request.serviceNamespace("dynamodb").resourceId(resource)
                .scalableDimension(dimension).minCapacity(1).maxCapacity(1));
        initialTargets = targets();
        assertEquals(1, initialTargets.size());
        assertEquals(resource, initialTargets.getFirst().resourceId());
        assertEquals(dimension, initialTargets.getFirst().scalableDimensionAsString());
        assertTrue(initialTargets.getFirst().scalableTargetARN().contains(":" + account + ":"));
    }

    @ParameterizedTest
    @EnumSource(AdmissionCase.class)
    void policyAdmissionPreservesRefusedStateAndDistinguishesNoOpFromChangedConfiguration(AdmissionCase test) {
        observations.put("case", test.name());
        policyAttempted = true;
        if (test.update) {
            PutScalingPolicyRequest seed = request("TargetTrackingScaling", 50.0);
            if (test.partial()) {
                seed = seed.toBuilder().targetTrackingScalingPolicyConfiguration(seed
                        .targetTrackingScalingPolicyConfiguration().toBuilder()
                        .scaleInCooldown(7).scaleOutCooldown(11).disableScaleIn(true).build()).build();
            }
            PutScalingPolicyResponse initial = scaling.putScalingPolicy(seed);
            assertEquals(200, initial.sdkHttpResponse().statusCode());
            policyArn = initial.policyARN();
            assertTrue(policyArn.contains(":" + account + ":scalingPolicy:"));
            List<ScalingPolicy> current = policies();
            assertEquals(1, current.size());
            rememberAlarms(current.getFirst());
        }
        Snapshot before = snapshot("before");
        int attemptIndex = wire.calls.size();
        Attempt attempt = put(test);
        observations.put("attemptIndex", attemptIndex);
        observations.put("attemptStatus", attempt.status());
        observations.put("attemptCode", attempt.code() == null ? "" : attempt.code());
        Snapshot after = snapshot("after");
        for (ScalingPolicy policy : after.policies()) {
            assertEquals(policyName, policy.policyName());
            assertEquals(resource, policy.resourceId());
            assertEquals(dimension, policy.scalableDimensionAsString());
            rememberAlarms(policy);
        }
        List<Executable> assertions = new ArrayList<>();
        assertions.add(() -> assertEquals(before.table(), after.table(), "Complete SDK table metadata is unchanged"));
        assertions.add(() -> assertEquals(before.targets(), after.targets()));
        assertions.add(() -> assertEquals(before.activities(), after.activities()));
        assertions.add(() -> assertEquals(List.of(), after.activities(), "Fixed-capacity admission is not scaling"));
        assertions.add(() -> assertFixedCapacity(after.table()));
        if (test.error != null) {
            assertions.add(() -> assertEquals(400, attempt.status()));
            assertions.add(() -> assertEquals(test.error, attempt.code()));
            assertions.add(() -> assertEquals(test.message(), attempt.message()));
            assertions.add(() -> assertEquals(before, after, "Every captured SDK resource definition is retained"));
        } else {
            assertions.add(() -> assertEquals(200, attempt.status()));
            assertions.add(() -> assertNull(attempt.code()));
            assertions.add(() -> assertEquals(1, after.policies().size()));
            assertions.add(() -> assertPolicy(after, test.expected));
            assertions.add(() -> assertManagedAlarms(after, test.expected));
            assertions.add(() -> assertEquals(test.expectedConfiguration(), after.policies().getFirst()
                    .targetTrackingScalingPolicyConfiguration(), "Complete resolved configuration"));
            if (test.partial()) {
                assertions.add(() -> assertEquals(3, before.alarms().size(), "Disabled scale-in omits consumed low"));
                assertions.add(() -> assertEquals(TargetTrackingScalingPolicyConfiguration.builder().targetValue(50.0)
                        .predefinedMetricSpecification(metric -> metric.predefinedMetricType("DynamoDBWriteCapacityUtilization"))
                        .scaleInCooldown(7).scaleOutCooldown(11).disableScaleIn(true).build(), before.policies()
                        .getFirst().targetTrackingScalingPolicyConfiguration(), "Explicit prior optional settings"));
                assertions.add(() -> assertTrue(before.alarms().stream().noneMatch(alarm ->
                        "ConsumedWriteCapacityUnits".equals(alarm.metricName())
                                && "LessThanThreshold".equals(alarm.comparisonOperatorAsString()))));
            }
            if (test.unchanged) {
                assertions.add(() -> assertEquals(before, after, "An unchanged update preserves complete definitions"));
                assertions.add(() -> assertEquals(alarmIdentities(before), alarmIdentities(after)));
            } else if (test.update) {
                assertions.add(() -> assertEquals(before.policies().getFirst().policyARN(),
                        after.policies().getFirst().policyARN()));
                assertions.add(() -> assertEquals(before.policies().getFirst().creationTime(),
                        after.policies().getFirst().creationTime()));
                assertions.add(() -> assertTrue(alarmIdentities(before).keySet().stream()
                        .noneMatch(alarmIdentities(after)::containsKey), "Changed configuration replaces prior alarm names"));
            }
        }
        assertions.add(() -> assertTrue(wire.calls.stream().noneMatch(row -> Set.of("PutItem", "UpdateItem",
                "PutMetricData", "SetAlarmState", "UpdateTable").contains(row.get("operation")))));
        assertAll("Qualified bounded policy admission contract: " + test.name(), assertions);
    }

    private PutScalingPolicyRequest request(String type, Double value) {
        PutScalingPolicyRequest.Builder request = PutScalingPolicyRequest.builder().serviceNamespace("dynamodb")
                .resourceId(resource).scalableDimension(dimension).policyName(policyName);
        if (type != null) {
            request.policyType(type);
        }
        if (value != null) {
            request.targetTrackingScalingPolicyConfiguration(configuration -> configuration.targetValue(value)
                    .predefinedMetricSpecification(metric -> metric.predefinedMetricType("DynamoDBWriteCapacityUtilization")));
        }
        if ("StepScaling".equals(type)) {
            request.stepScalingPolicyConfiguration(configuration -> configuration.adjustmentType("ChangeInCapacity")
                    .cooldown(0).metricAggregationType("Average")
                    .stepAdjustments(adjustment -> adjustment.metricIntervalLowerBound(0.0).scalingAdjustment(1)));
        }
        if ("PredictiveScaling".equals(type)) {
            request.predictiveScalingPolicyConfiguration(configuration -> configuration.mode("ForecastOnly")
                    .metricSpecifications(metric -> metric.targetValue(50.0)
                            .predefinedMetricPairSpecification(pair -> pair.predefinedMetricType("ECSServiceCPUUtilization"))));
        }
        return request.build();
    }

    private PutScalingPolicyRequest request(AdmissionCase test) {
        PutScalingPolicyRequest.Builder builder = request(test.type, test.value).toBuilder();
        TargetTrackingScalingPolicyConfiguration.Builder configuration =
                TargetTrackingScalingPolicyConfiguration.builder();
        if (test.value != null) {
            configuration.targetValue(test.value);
        }
        switch (test) {
            case CREATE_MISSING_CONFIG, UPDATE_MISSING_CONFIG -> {
                return builder.targetTrackingScalingPolicyConfiguration((TargetTrackingScalingPolicyConfiguration) null).build();
            }
            case CREATE_CUSTOMIZED, UPDATE_CUSTOMIZED -> configuration.customizedMetricSpecification(metric -> metric
                    .namespace("Floci/Parity").metricName("Capacity").statistic("Sum").unit("Count"));
            case CREATE_MISMATCH, UPDATE_MISMATCH -> configuration.predefinedMetricSpecification(metric -> metric
                    .predefinedMetricType("DynamoDBReadCapacityUtilization"));
            case CREATE_MISSING_TARGET, UPDATE_MISSING_TARGET,
                    PARTIAL_TARGET_AND_METRIC_CHANGE, PARTIAL_TARGET_AND_METRIC_UNCHANGED ->
                    configuration.predefinedMetricSpecification(metric -> metric.predefinedMetricType("DynamoDBWriteCapacityUtilization"));
            case PARTIAL_CLEAR_IN -> configuration.scaleInCooldown(0);
            case PARTIAL_CLEAR_OUT -> configuration.scaleOutCooldown(0);
            case PARTIAL_ENABLE_IN -> configuration.disableScaleIn(false);
            case PARTIAL_CLEAR_ALL -> configuration.scaleInCooldown(0).scaleOutCooldown(0).disableScaleIn(false);
            default -> { }
        }
        return builder.targetTrackingScalingPolicyConfiguration(configuration.build()).build();
    }

    private Attempt put(AdmissionCase test) {
        try {
            PutScalingPolicyResponse response = scaling.putScalingPolicy(request(test));
            if (policyArn == null) {
                policyArn = response.policyARN();
            }
            return new Attempt(response.sdkHttpResponse().statusCode(), null, null);
        } catch (ApplicationAutoScalingException failure) {
            return new Attempt(failure.statusCode(), failure.awsErrorDetails().errorCode(),
                    failure.awsErrorDetails().errorMessage());
        }
    }

    private Snapshot snapshot(String label) {
        int start = wire.calls.size();
        TableDescription tableState = describe();
        List<ScalableTarget> targetState = targets();
        List<ScalingPolicy> policyState = policies();
        List<ScalingActivity> activityState = activities();
        List<MetricAlarm> alarmState = alarms();
        observations.put(label + "Start", start);
        observations.put(label + "End", wire.calls.size());
        return new Snapshot(tableState, targetState, policyState, activityState, alarmState);
    }

    private void assertPolicy(Snapshot state, double value) {
        ScalingPolicy policy = state.policies().getFirst();
        assertEquals(policyName, policy.policyName());
        assertEquals(resource, policy.resourceId());
        assertEquals(dimension, policy.scalableDimensionAsString());
        assertEquals(policyArn, policy.policyARN());
        assertEquals("TargetTrackingScaling", policy.policyTypeAsString());
        assertEquals(value, policy.targetTrackingScalingPolicyConfiguration().targetValue());
        assertEquals("DynamoDBWriteCapacityUtilization", policy.targetTrackingScalingPolicyConfiguration()
                .predefinedMetricSpecification().predefinedMetricTypeAsString());
    }

    private void assertManagedAlarms(Snapshot state, double value) {
        assertEquals(4, state.alarms().size());
        Map<String, String> described = alarmIdentities(state);
        assertEquals(state.policies().getFirst().alarms().stream()
                .collect(Collectors.toMap(Alarm::alarmName, Alarm::alarmARN)), described);
        Map<String, MetricAlarm> kinds = state.alarms().stream().collect(Collectors.toMap(
                alarm -> alarm.metricName() + ":" + alarm.comparisonOperatorAsString(), alarm -> alarm));
        assertEquals(Set.of("ConsumedWriteCapacityUnits:GreaterThanThreshold", "ConsumedWriteCapacityUnits:LessThanThreshold",
                "ProvisionedWriteCapacityUnits:GreaterThanThreshold", "ProvisionedWriteCapacityUnits:LessThanThreshold"), kinds.keySet());
        for (MetricAlarm alarm : state.alarms()) {
            boolean usage = "ConsumedWriteCapacityUnits".equals(alarm.metricName());
            boolean high = "GreaterThanThreshold".equals(alarm.comparisonOperatorAsString());
            assertEquals("AWS/DynamoDB", alarm.namespace());
            assertEquals(Map.of("TableName", table), alarm.dimensions().stream()
                    .collect(Collectors.toMap(Dimension::name, Dimension::value)));
            assertEquals(usage ? "Sum" : "Average", alarm.statisticAsString());
            assertEquals(usage ? 60 : 300, alarm.period());
            assertEquals(usage ? high ? 2 : 15 : 3, alarm.evaluationPeriods());
            double expected = usage ? 60.0 * (high ? value : Math.max(5, value - 20)) / 100 : 1.0;
            assertEquals(expected, alarm.threshold(), 1e-12);
            assertEquals(List.of(policyArn), alarm.alarmActions());
            assertTrue(alarm.actionsEnabled());
            assertTrue(alarm.alarmArn().contains(":" + account + ":alarm:"));
        }
    }

    private static void assertFixedCapacity(TableDescription table) {
        assertEquals(1L, table.provisionedThroughput().readCapacityUnits());
        assertEquals(1L, table.provisionedThroughput().writeCapacityUnits());
        assertEquals(1, table.globalSecondaryIndexes().size());
        assertEquals(1L, table.globalSecondaryIndexes().getFirst().provisionedThroughput().readCapacityUnits());
        assertEquals(1L, table.globalSecondaryIndexes().getFirst().provisionedThroughput().writeCapacityUnits());
    }

    private static Map<String, String> alarmIdentities(Snapshot state) {
        return state.alarms().stream().collect(Collectors.toMap(MetricAlarm::alarmName, MetricAlarm::alarmArn));
    }

    private void rememberAlarms(ScalingPolicy policy) {
        for (Alarm alarm : policy.alarms()) {
            assertNotNull(alarm.alarmName());
            assertNotNull(alarm.alarmARN());
            String previous = ownedAlarms.putIfAbsent(alarm.alarmName(), alarm.alarmARN());
            if (previous != null) {
                assertEquals(previous, alarm.alarmARN());
            }
        }
    }

    private record Attempt(int status, String code, String message) {}
    private record Snapshot(TableDescription table, List<ScalableTarget> targets, List<ScalingPolicy> policies,
                            List<ScalingActivity> activities, List<MetricAlarm> alarms) {}

    enum AdmissionCase {
        CREATE_MISSING_CONFIG(false, "TargetTrackingScaling", null, "ValidationException", 0, false),
        UPDATE_MISSING_CONFIG(true, "TargetTrackingScaling", null, null, 50, true),
        CREATE_CUSTOMIZED(false, "TargetTrackingScaling", 50.0, "ValidationException", 0, false),
        UPDATE_CUSTOMIZED(true, "TargetTrackingScaling", 50.0, "ValidationException", 0, false),
        CREATE_MISSING_SPEC(false, "TargetTrackingScaling", 50.0, "ValidationException", 0, false),
        UPDATE_MISSING_SPEC(true, "TargetTrackingScaling", 50.0, null, 50, true),
        CREATE_MISMATCH(false, "TargetTrackingScaling", 50.0, "ValidationException", 0, false),
        UPDATE_MISMATCH(true, "TargetTrackingScaling", 50.0, "ValidationException", 0, false),
        CREATE_MISSING_TARGET(false, "TargetTrackingScaling", null, "ValidationException", 0, false),
        UPDATE_MISSING_TARGET(true, "TargetTrackingScaling", null, "ValidationException", 0, false),
        PARTIAL_TARGET_ONLY_CHANGE(true, "TargetTrackingScaling", 60.0, null, 60, false),
        PARTIAL_TARGET_AND_METRIC_CHANGE(true, "TargetTrackingScaling", 60.0, null, 60, false),
        PARTIAL_CLEAR_IN(true, "TargetTrackingScaling", 60.0, null, 60, false),
        PARTIAL_CLEAR_OUT(true, "TargetTrackingScaling", 60.0, null, 60, false),
        PARTIAL_ENABLE_IN(true, "TargetTrackingScaling", 60.0, null, 60, false),
        PARTIAL_TARGET_ONLY_UNCHANGED(true, "TargetTrackingScaling", 50.0, null, 50, false),
        PARTIAL_TARGET_AND_METRIC_UNCHANGED(true, "TargetTrackingScaling", 50.0, null, 50, false),
        PARTIAL_CLEAR_ALL(true, "TargetTrackingScaling", 60.0, null, 60, false);

        final boolean update;
        final String type;
        final Double value;
        final String error;
        final double expected;
        final boolean unchanged;

        AdmissionCase(boolean update, String type, Double value, String error, double expected, boolean unchanged) {
            this.update = update;
            this.type = type;
            this.value = value;
            this.error = error;
            this.expected = expected;
            this.unchanged = unchanged;
        }

        boolean partial() {
            return name().startsWith("PARTIAL_");
        }

        TargetTrackingScalingPolicyConfiguration expectedConfiguration() {
            TargetTrackingScalingPolicyConfiguration.Builder configuration =
                    TargetTrackingScalingPolicyConfiguration.builder().targetValue(expected)
                            .predefinedMetricSpecification(metric -> metric.predefinedMetricType("DynamoDBWriteCapacityUtilization"));
            switch (this) {
                case PARTIAL_CLEAR_IN -> configuration.scaleInCooldown(0);
                case PARTIAL_CLEAR_OUT -> configuration.scaleOutCooldown(0);
                case PARTIAL_ENABLE_IN -> configuration.disableScaleIn(false);
                case PARTIAL_CLEAR_ALL -> configuration.scaleInCooldown(0).scaleOutCooldown(0).disableScaleIn(false);
                default -> { }
            }
            return configuration.build();
        }

        String message() {
            return switch (this) {
                case CREATE_MISSING_CONFIG -> "TargetTrackingScaling policy configuration must be specified";
                case CREATE_CUSTOMIZED, UPDATE_CUSTOMIZED ->
                        "Scalable dimension dynamodb:table:WriteCapacityUnits does not support CustomizedMetricSpecification.";
                case CREATE_MISSING_SPEC ->
                        "PredefinedMetricSpecification or CustomizedMetricSpecification must be set for target tracking scaling.";
                case CREATE_MISMATCH, UPDATE_MISMATCH -> "Scalable dimension dynamodb:table:WriteCapacityUnits "
                        + "only supports the following predefined metric types: DynamoDBWriteCapacityUtilization";
                case CREATE_MISSING_TARGET, UPDATE_MISSING_TARGET -> "1 validation error detected: Value null at "
                        + "'targetTrackingScalingPolicyConfiguration.targetValue' failed to satisfy constraint: Member must not be null";
                default -> throw new IllegalStateException("No expected refusal for " + this);
            };
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
    void independentlyRemoveOwnedAdmissionResourcesThenSaveCapture() throws IOException {
        cleaning = true;
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
            assertTrue((cleaning ? cleanupCalls++ : forwardCalls++) < 150,
                    "Independent bounded forward and cleanup exchanges");
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
            Path directory = Path.of("target", "dynamodb-policy-conditional-admission-wire");
            Files.createDirectories(directory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(table + ".json").toFile(),
                    Map.of("table", table, "observations", observations, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "Every attempted exchange must retain its response or fail qualification");
        }
    }
}

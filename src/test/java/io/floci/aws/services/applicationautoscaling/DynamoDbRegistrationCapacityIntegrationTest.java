package io.floci.aws.services.applicationautoscaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
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
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.ObjectNotFoundException;
import software.amazon.awssdk.services.applicationautoscaling.model.RegisterScalableTargetRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.RegisterScalableTargetResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableDimension;
import software.amazon.awssdk.services.applicationautoscaling.model.ServiceNamespace;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexUpdate;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.UpdateGlobalSecondaryIndexAction;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Timeout(90)
class DynamoDbRegistrationCapacityIntegrationTest {
    private static final Logger LOG = Logger.getLogger(DynamoDbRegistrationCapacityIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INDEX = "selected-index";
    private static final String SIBLING = "untouched-index";
    private static final Region REGION = Region.US_EAST_1;
    private static final ExecutionAttribute<WireCall> WIRE = new ExecutionAttribute<>("ddb-registration-wire");

    private final List<WireCall> calls = new ArrayList<>();
    private final Set<Target> reservedTargets = new LinkedHashSet<>();
    private ApplicationAutoScalingClient scaling;
    private DynamoDbClient dynamo;
    private String owner;
    private String table;
    private String marker;
    private boolean reservedTable;
    private boolean cleanup;
    private int forwardCalls;
    private int cleanupCalls;

    @BeforeEach
    void openOwnedClients() {
        owner = Long.toString(ThreadLocalRandom.current().nextLong(740000000000L, 749999999999L));
        table = "ddb-registration-" + UUID.randomUUID();
        marker = table;
        scaling = AwsTestClients.create(ApplicationAutoScalingClient.builder().overrideConfiguration(config -> config
                .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(new WireReceipt("application-autoscaling"))),
                owner, REGION, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        dynamo = AwsTestClients.create(DynamoDbClient.builder().overrideConfiguration(config -> config
                .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(new WireReceipt("dynamodb"))),
                owner, REGION, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        assertEquals(Map.of(), targets());
        assertEquals(List.of(), tables());
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void newRegistrationRaisesOnlyTheSelectedCapacityToItsMinimum(Target target) {
        create(target, 3);
        exercise(target, 7, 12, 7, null);
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void newRegistrationLowersOnlyTheSelectedCapacityToItsMaximum(Target target) {
        create(target, 19);
        exercise(target, 7, 12, 12, null);
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void changedBoundsReconcileCapacityAndPreserveTargetIdentity(Target target) {
        create(target, 5);
        register(target, 2, 20);
        ObjectNode existing = onlyTarget(target);
        exercise(target, 7, 12, 7, existing);
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void omittedBoundsReconcileCapacityAboveTheRetainedMaximum(Target target) {
        create(target, 9);
        register(target, 7, 12);
        ObjectNode existing = onlyTarget(target);
        changeCapacity(target, 19);
        exercise(target, null, null, 12, existing);
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void omittedBoundsReconcileCapacityBelowTheRetainedMinimum(Target target) {
        create(target, 9);
        register(target, 7, 12);
        ObjectNode existing = onlyTarget(target);
        changeCapacity(target, 3);
        exercise(target, null, null, 7, existing);
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void registrationInsideBoundsLeavesTheWholeTableUnchanged(Target target) {
        create(target, 9);
        exercise(target, 7, 12, 9, null);
    }

    private void create(Target target, long capacity) {
        long read = target == Target.TABLE_READ ? capacity : 11;
        long write = target == Target.TABLE_WRITE ? capacity : 13;
        long indexRead = target == Target.INDEX_READ ? capacity : 17;
        long indexWrite = target == Target.INDEX_WRITE ? capacity : 19;
        CreateTableRequest request = CreateTableRequest.builder().tableName(table).billingMode("PROVISIONED")
                .attributeDefinitions(attribute("pk"), attribute("selected"), attribute("sibling"))
                .keySchema(key("pk")).provisionedThroughput(throughput(read, write))
                .globalSecondaryIndexes(index(INDEX, "selected", indexRead, indexWrite),
                        index(SIBLING, "sibling", 23, 29)).build();
        reservedTable = true;
        LOG.infov("DDB_REGISTRATION_TABLE_RESERVED marker={0} account={1} table={2}", marker, owner, table);
        assertEquals(200, dynamo.createTable(request).sdkHttpResponse().statusCode());
        assertEquals(capacity, selectedThroughput(activeTable(), target).path(target.field()).asLong());
    }

    private void exercise(Target target, Integer minimum, Integer maximum, long expectedCapacity,
                          ObjectNode previousTarget) {
        ObjectNode before = activeTable();
        Map<String, ObjectNode> targetsBefore = targets();
        String arn = register(target, minimum, maximum);
        ObjectNode after = activeTable();
        Map<String, ObjectNode> targetsAfter = targets();
        ObjectNode expected = before.deepCopy();
        selectedThroughput(expected, target).put(target.field(), Math.toIntExact(expectedCapacity));
        boolean unchanged = selectedThroughput(before, target).path(target.field()).asLong() == expectedCapacity;
        LOG.infov("DDB_REGISTRATION_CASE marker={0} dimension={1} minimum={2} maximum={3} expected={4}"
                        + " before={5} after={6} targetsBefore={7} targetsAfter={8}",
                marker, target.dimension(), minimum, maximum, expectedCapacity,
                before, after, targetsBefore, targetsAfter);
        assertAll("The selected capacity changes while complete table and target identities are preserved",
                () -> assertEquals(expectedCapacity, selectedThroughput(after, target).path(target.field()).asLong()),
                () -> assertEquals(unchanged ? expected : stableTable(expected, target),
                        unchanged ? after : stableTable(after, target)),
                () -> {
                    assertEquals(1, targetsAfter.size());
                    ObjectNode actual = targetsAfter.get(target.resource(table) + "|" + target.dimension());
                    assertNotNull(actual);
                    assertEquals(arn, actual.path("ScalableTargetARN").asText());
                    assertEquals(target.resource(table), actual.path("ResourceId").asText());
                    assertEquals(target.dimension(), actual.path("ScalableDimension").asText());
                    assertEquals("dynamodb", actual.path("ServiceNamespace").asText());
                    assertFalse(actual.path("RoleARN").asText().isEmpty());
                    assertTrue(actual.hasNonNull("CreationTime"));
                    if (previousTarget == null) {
                        assertEquals(Map.of(), targetsBefore);
                        assertEquals(minimum.intValue(), actual.path("MinCapacity").asInt());
                        assertEquals(maximum.intValue(), actual.path("MaxCapacity").asInt());
                    } else {
                        assertEquals(Map.of(target.resource(table) + "|" + target.dimension(), previousTarget),
                                targetsBefore);
                        ObjectNode expectedTarget = previousTarget.deepCopy();
                        if (minimum != null) {
                            expectedTarget.put("MinCapacity", minimum);
                        }
                        if (maximum != null) {
                            expectedTarget.put("MaxCapacity", maximum);
                        }
                        assertEquals(expectedTarget, actual);
                    }
                });
    }

    private String register(Target target, Integer minimum, Integer maximum) {
        reservedTargets.add(target);
        LOG.infov("DDB_REGISTRATION_TARGET_RESERVED marker={0} resource={1} dimension={2}",
                marker, target.resource(table), target.dimension());
        RegisterScalableTargetResponse response = scaling.registerScalableTarget(RegisterScalableTargetRequest.builder()
                .serviceNamespace(ServiceNamespace.DYNAMODB).resourceId(target.resource(table))
                .scalableDimension(ScalableDimension.fromValue(target.dimension()))
                .minCapacity(minimum).maxCapacity(maximum).build());
        assertEquals(200, response.sdkHttpResponse().statusCode());
        assertNotNull(response.scalableTargetARN());
        assertFalse(response.scalableTargetARN().isEmpty());
        return response.scalableTargetARN();
    }

    private void changeCapacity(Target target, long capacity) {
        ObjectNode current = selectedThroughput(activeTable(), target);
        long read = target.read() ? capacity : current.path("ReadCapacityUnits").asLong();
        long write = target.read() ? current.path("WriteCapacityUnits").asLong() : capacity;
        UpdateTableRequest.Builder request = UpdateTableRequest.builder().tableName(table);
        if (target.index()) {
            request.globalSecondaryIndexUpdates(GlobalSecondaryIndexUpdate.builder().update(
                    UpdateGlobalSecondaryIndexAction.builder().indexName(INDEX)
                            .provisionedThroughput(throughput(read, write)).build()).build());
        } else {
            request.provisionedThroughput(throughput(read, write));
        }
        assertEquals(200, dynamo.updateTable(request.build()).sdkHttpResponse().statusCode());
        assertEquals(capacity, selectedThroughput(activeTable(), target).path(target.field()).asLong());
    }

    private ObjectNode activeTable() {
        for (int poll = 0; poll < 40; poll++) {
            DescribeTableResponse response = dynamo.describeTable(request -> request.tableName(table));
            assertEquals(200, response.sdkHttpResponse().statusCode());
            JsonNode tableNode = object(calls.getLast().response).get("Table");
            assertNotNull(tableNode);
            assertTrue(tableNode.isObject());
            ObjectNode raw = tableNode.deepCopy();
            assertNotNull(response.table());
            assertEquals(table, response.table().tableName());
            if ("ACTIVE".equals(response.table().tableStatusAsString())
                    && response.table().globalSecondaryIndexes().stream()
                    .allMatch(index -> "ACTIVE".equals(index.indexStatusAsString()))) {
                assertEquals(2, response.table().globalSecondaryIndexes().size());
                return raw;
            }
            pause();
        }
        throw new AssertionError("Owned table/indexes did not become active within the fixture poll budget");
    }

    private ObjectNode onlyTarget(Target target) {
        Map<String, ObjectNode> all = targets();
        assertEquals(1, all.size());
        ObjectNode result = all.get(target.resource(table) + "|" + target.dimension());
        assertNotNull(result);
        return result.deepCopy();
    }

    private Map<String, ObjectNode> targets() {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        Set<String> tokens = new HashSet<>();
        String next = null;
        for (int page = 0; page < 20; page++) {
            DescribeScalableTargetsResponse response = scaling.describeScalableTargets(
                    DescribeScalableTargetsRequest.builder().serviceNamespace(ServiceNamespace.DYNAMODB)
                            .maxResults(50).nextToken(next).build());
            ObjectNode raw = object(calls.getLast().response);
            assertEquals(200, response.sdkHttpResponse().statusCode());
            assertTrue(raw.path("ScalableTargets").isArray());
            assertEquals(response.scalableTargets().size(), raw.path("ScalableTargets").size());
            for (JsonNode item : raw.path("ScalableTargets")) {
                assertTrue(item.isObject());
                String identity = item.path("ResourceId").asText() + "|" + item.path("ScalableDimension").asText();
                assertNull(result.put(identity, item.deepCopy()), "A full collection contains each target once");
            }
            next = response.nextToken();
            if (next == null) {
                return result;
            }
            assertFalse(next.isEmpty());
            assertTrue(tokens.add(next));
            assertFalse(response.scalableTargets().isEmpty());
        }
        throw new AssertionError("Owned target collection exceeded the fixture page budget");
    }

    private List<String> tables() {
        ListTablesResponse response = dynamo.listTables(request -> request.limit(100));
        assertEquals(200, response.sdkHttpResponse().statusCode());
        ObjectNode raw = object(calls.getLast().response);
        assertTrue(raw.path("TableNames").isArray());
        assertEquals(response.tableNames().size(), raw.path("TableNames").size());
        assertNull(response.lastEvaluatedTableName(), "The isolated fixture owns at most one table");
        return response.tableNames();
    }

    @AfterEach
    void deleteOnlyReservedResourcesAndRetainAllWirePairs() {
        cleanup = true;
        List<Throwable> failures = new ArrayList<>();
        for (Target target : reservedTargets) {
            try {
                try {
                    assertEquals(200, scaling.deregisterScalableTarget(request -> request
                            .serviceNamespace(ServiceNamespace.DYNAMODB).resourceId(target.resource(table))
                            .scalableDimension(ScalableDimension.fromValue(target.dimension())))
                            .sdkHttpResponse().statusCode());
                } catch (ObjectNotFoundException expected) {
                    assertEquals(400, expected.statusCode());
                    LOG.infov("DDB_REGISTRATION_TARGET_ALREADY_ABSENT marker={0} dimension={1}", marker, target.dimension());
                }
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        if (reservedTable) {
            try {
                try {
                    assertEquals(200, dynamo.deleteTable(request -> request.tableName(table)).sdkHttpResponse().statusCode());
                } catch (ResourceNotFoundException expected) {
                    assertEquals(400, expected.statusCode());
                    LOG.infov("DDB_REGISTRATION_TABLE_ALREADY_ABSENT marker={0}", marker);
                }
                boolean absent = false;
                for (int poll = 0; poll < 80; poll++) {
                    try {
                        DescribeTableResponse response = dynamo.describeTable(request -> request.tableName(table));
                        assertEquals(200, response.sdkHttpResponse().statusCode());
                        assertEquals("DELETING", response.table().tableStatusAsString());
                        pause();
                    } catch (ResourceNotFoundException expected) {
                        assertEquals(400, expected.statusCode());
                        absent = true;
                        break;
                    }
                }
                assertTrue(absent, "Accepted deletion must reach verified absence within the cleanup budget");
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        try {
            if (scaling != null && dynamo != null) {
                assertEquals(Map.of(), targets());
                assertEquals(List.of(), tables());
                LOG.infov("DDB_REGISTRATION_CLEAN marker={0} targets=[] tables=[]", marker);
            }
            for (WireCall call : calls) {
                assertNotNull(call.request);
                assertNotNull(call.response, "Every transmitted request must retain a response or fail qualification");
                assertTrue(call.status >= 100);
            }
        } catch (Throwable failure) {
            failures.add(failure);
        } finally {
            if (scaling != null) {
                scaling.close();
            }
            if (dynamo != null) {
                dynamo.close();
            }
        }
        assertAll("Every reserved target/table and raw pair remains accounted for",
                failures.stream().map(failure -> () -> { throw failure; }));
    }

    private static ObjectNode selectedThroughput(ObjectNode description, Target target) {
        if (!target.index()) {
            return (ObjectNode) description.get("ProvisionedThroughput");
        }
        for (JsonNode index : description.path("GlobalSecondaryIndexes")) {
            if (INDEX.equals(index.path("IndexName").asText())) {
                return (ObjectNode) index.get("ProvisionedThroughput");
            }
        }
        throw new AssertionError("Selected GSI is absent from the complete description");
    }

    private static ObjectNode stableTable(ObjectNode description, Target target) {
        ObjectNode copy = description.deepCopy();
        // These selected-resource counters/times may legitimately change with throughput.
        selectedThroughput(copy, target).remove(List.of(
                "LastIncreaseDateTime", "LastDecreaseDateTime", "NumberOfDecreasesToday"));
        ObjectNode selected = copy;
        if (target.index()) {
            for (JsonNode index : copy.path("GlobalSecondaryIndexes")) {
                if (INDEX.equals(index.path("IndexName").asText())) {
                    selected = (ObjectNode) index;
                    break;
                }
            }
        }
        if (selected.path("WarmThroughput") instanceof ObjectNode warm) {
            warm.remove(target.read() ? "ReadUnitsPerSecond" : "WriteUnitsPerSecond");
        }
        return copy;
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType("S").build();
    }

    private static KeySchemaElement key(String name) {
        return KeySchemaElement.builder().attributeName(name).keyType("HASH").build();
    }

    private static ProvisionedThroughput throughput(long read, long write) {
        return ProvisionedThroughput.builder().readCapacityUnits(read).writeCapacityUnits(write).build();
    }

    private static GlobalSecondaryIndex index(String name, String attribute, long read, long write) {
        return GlobalSecondaryIndex.builder().indexName(name).keySchema(key(attribute))
                .projection(Projection.builder().projectionType("ALL").build())
                .provisionedThroughput(throughput(read, write)).build();
    }

    private static ObjectNode object(byte[] bytes) {
        try {
            JsonNode result = JSON.readTree(bytes);
            assertTrue(result.isObject());
            return (ObjectNode) result;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for an owned resource", failure);
        }
    }

    enum Target {
        TABLE_READ, TABLE_WRITE, INDEX_READ, INDEX_WRITE;

        boolean index() { return this == INDEX_READ || this == INDEX_WRITE; }
        boolean read() { return this == TABLE_READ || this == INDEX_READ; }
        String field() { return read() ? "ReadCapacityUnits" : "WriteCapacityUnits"; }
        String dimension() { return "dynamodb:" + (index() ? "index:" : "table:") + field(); }
        String resource(String table) { return "table/" + table + (index() ? "/index/" + INDEX : ""); }
    }

    private static final class WireCall {
        private final int id;
        private byte[] request;
        private byte[] response;
        private int status;

        private WireCall(int id) {
            this.id = id;
        }
    }

    private final class WireReceipt implements ExecutionInterceptor {
        private final String service;

        private WireReceipt(String service) {
            this.service = service;
        }

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            assertTrue((cleanup ? cleanupCalls++ : forwardCalls++) < 150,
                    "Separate forward and cleanup call budgets");
            WireCall call = new WireCall(calls.size() + 1);
            calls.add(call);
            attributes.putAttribute(WIRE, call);
            try (InputStream stream = context.requestBody().orElseThrow().contentStreamProvider().newStream()) {
                call.request = stream.readNBytes(262145);
                LOG.infov("DDB_REGISTRATION_REQUEST marker={0} id={1} service={2} account={3} region={4}"
                                + " method={5} uri={6} headers={7} base64={8}",
                        marker, call.id, service, owner, REGION, context.httpRequest().method(),
                        context.httpRequest().getUri(), Map.of(
                                "content-type", context.httpRequest().firstMatchingHeader("content-type").orElse(""),
                                "x-amz-target", context.httpRequest().firstMatchingHeader("x-amz-target").orElse("")),
                        Base64.getEncoder().encodeToString(call.request));
                assertTrue(call.request.length <= 262144);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            WireCall call = attributes.getAttribute(WIRE);
            call.status = context.httpResponse().statusCode();
            if (context.responseBody().isEmpty()) {
                call.response = new byte[0];
                LOG.infov("DDB_REGISTRATION_RESPONSE marker={0} id={1} status={2} headers={3} base64=",
                        marker, call.id, call.status, context.httpResponse().headers());
                return Optional.empty();
            }
            try (InputStream stream = context.responseBody().orElseThrow()) {
                call.response = stream.readNBytes(262145);
                LOG.infov("DDB_REGISTRATION_RESPONSE marker={0} id={1} status={2} headers={3} base64={4}",
                        marker, call.id, call.status, context.httpResponse().headers(),
                        Base64.getEncoder().encodeToString(call.response));
                assertTrue(call.response.length <= 262144);
                return Optional.of(new ByteArrayInputStream(call.response));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}

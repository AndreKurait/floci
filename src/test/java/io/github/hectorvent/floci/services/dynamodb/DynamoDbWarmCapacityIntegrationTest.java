package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Timeout(90)
class DynamoDbWarmCapacityIntegrationTest {
    private static final Logger LOG = Logger.getLogger(DynamoDbWarmCapacityIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INDEX = "selected-index";
    private static final String SIBLING = "untouched-index";
    private static final Region REGION = Region.US_EAST_1;
    private static final ExecutionAttribute<WireCall> WIRE = new ExecutionAttribute<>("ddb-warm-capacity-wire");

    private final List<WireCall> calls = new ArrayList<>();
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
        table = "ddb-warm-capacity-" + UUID.randomUUID();
        marker = table;
        dynamo = AwsTestClients.create(DynamoDbClient.builder().overrideConfiguration(config -> config
                .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(new WireReceipt("dynamodb"))),
                owner, REGION, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        assertEquals(List.of(), tables());
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void provisionedIncreasesRaiseWarmFloorAndDecreasesRetainIt(Target target) {
        create(target, 3);
        ObjectNode before = activeTable();
        changeCapacity(target, 31);
        ObjectNode increased = activeTable();
        changeCapacity(target, 7);
        ObjectNode decreased = activeTable();
        long initialWarm = selectedWarm(before, target);
        long increasedWarm = selectedWarm(increased, target);
        long decreasedWarm = selectedWarm(decreased, target);
        ObjectNode expectedIncrease = before.deepCopy();
        selectedThroughput(expectedIncrease, target).put(target.field(), 31);
        ObjectNode expectedDecrease = increased.deepCopy();
        selectedThroughput(expectedDecrease, target).put(target.field(), 7);
        LOG.infov("DDB_WARM_CAPACITY_CASE marker={0} dimension={1} before={2} increased={3} decreased={4}",
                marker, target.dimension(), before, increased, decreased);
        assertAll("Provisioned warm floor and retained peak preserve every opposite and sibling member",
                () -> assertTrue(initialWarm >= 3, "Initial warm throughput is at least provisioned capacity"),
                () -> assertTrue(increasedWarm >= Math.max(initialWarm, 31),
                        "Warm throughput reaches the provisioned increase without losing an older peak"),
                () -> assertTrue(decreasedWarm >= Math.max(increasedWarm, 7),
                        "A provisioned decrease does not reduce the observed warm peak"),
                () -> assertEquals(stableTable(expectedIncrease, target), stableTable(increased, target)),
                () -> assertEquals(stableTable(expectedDecrease, target), stableTable(decreased, target)));
    }

    private static long selectedWarm(ObjectNode description, Target target) {
        JsonNode selected = description;
        if (target.index()) {
            selected = null;
            for (JsonNode index : description.path("GlobalSecondaryIndexes")) {
                if (INDEX.equals(index.path("IndexName").asText())) {
                    selected = index;
                    break;
                }
            }
        }
        assertNotNull(selected, "Selected resource is present in the whole Table");
        JsonNode warm = selected.path("WarmThroughput");
        assertTrue(warm.isObject());
        assertEquals("ACTIVE", warm.path("Status").asText());
        JsonNode units = warm.path(target.read() ? "ReadUnitsPerSecond" : "WriteUnitsPerSecond");
        assertTrue(units.isIntegralNumber());
        return units.longValue();
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
        LOG.infov("DDB_WARM_CAPACITY_TABLE_RESERVED marker={0} account={1} table={2}", marker, owner, table);
        assertEquals(200, dynamo.createTable(request).sdkHttpResponse().statusCode());
        assertEquals(capacity, selectedThroughput(activeTable(), target).path(target.field()).asLong());
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
        if (reservedTable) {
            try {
                try {
                    assertEquals(200, dynamo.deleteTable(request -> request.tableName(table)).sdkHttpResponse().statusCode());
                } catch (ResourceNotFoundException expected) {
                    assertEquals(400, expected.statusCode());
                    LOG.infov("DDB_WARM_CAPACITY_TABLE_ALREADY_ABSENT marker={0}", marker);
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
            if (dynamo != null) {
                assertEquals(List.of(), tables());
                LOG.infov("DDB_WARM_CAPACITY_CLEAN marker={0} tables=[]", marker);
            }
            for (WireCall call : calls) {
                assertNotNull(call.request);
                assertNotNull(call.response, "Every transmitted request must retain a response or fail qualification");
                assertTrue(call.status >= 100);
            }
        } catch (Throwable failure) {
            failures.add(failure);
        } finally {
            if (dynamo != null) {
                dynamo.close();
            }
        }
        assertAll("Every reserved table and raw pair remains accounted for",
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
                LOG.infov("DDB_WARM_CAPACITY_REQUEST marker={0} id={1} service={2} account={3} region={4}"
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
                LOG.infov("DDB_WARM_CAPACITY_RESPONSE marker={0} id={1} status={2} headers={3} base64=",
                        marker, call.id, call.status, context.httpResponse().headers());
                return Optional.empty();
            }
            try (InputStream stream = context.responseBody().orElseThrow()) {
                call.response = stream.readNBytes(262145);
                LOG.infov("DDB_WARM_CAPACITY_RESPONSE marker={0} id={1} status={2} headers={3} base64={4}",
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

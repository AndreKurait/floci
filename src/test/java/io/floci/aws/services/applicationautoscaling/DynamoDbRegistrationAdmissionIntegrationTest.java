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
import software.amazon.awssdk.services.applicationautoscaling.model.ApplicationAutoScalingException;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingPoliciesRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingPoliciesResponse;
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
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

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
class DynamoDbRegistrationAdmissionIntegrationTest {
    private static final Logger LOG = Logger.getLogger(DynamoDbRegistrationAdmissionIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INDEX = "selected-index";
    private static final String SIBLING = "untouched-index";
    private static final Region REGION = Region.US_EAST_1;
    private static final ExecutionAttribute<WireCall> WIRE = new ExecutionAttribute<>("ddb-registration-admission-wire");

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
        table = "ddb-admission-" + UUID.randomUUID();
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
    void registrationRefusesAMissingTableWithoutCreatingATarget(Target target) {
        assertMissingTable();
        exerciseRefusal(target, null, "DynamoDB " + (target.index() ? "index" : "table")
                + " does not exist: " + target.resource(table));
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void registrationRefusesOnDemandCapacityAndPreservesTheWholeTable(Target target) {
        reservedTable = true;
        LOG.infov("DDB_REGISTRATION_ADMISSION_TABLE_RESERVED marker={0} account={1} table={2}", marker, owner, table);
        assertEquals(200, dynamo.createTable(CreateTableRequest.builder().tableName(table)
                .billingMode("PAY_PER_REQUEST")
                .attributeDefinitions(attribute("pk"), attribute("selected"), attribute("sibling"))
                .keySchema(key("pk")).globalSecondaryIndexes(
                        GlobalSecondaryIndex.builder().indexName(INDEX).keySchema(key("selected"))
                                .projection(Projection.builder().projectionType("ALL").build()).build(),
                        GlobalSecondaryIndex.builder().indexName(SIBLING).keySchema(key("sibling"))
                                .projection(Projection.builder().projectionType("ALL").build()).build())
                .build()).sdkHttpResponse().statusCode());
        exerciseRefusal(target, activeTable(),
                "Validation failed for scalable target. Reason: PAY_PER_REQUEST table mode is not scalable.");
    }

    private void exerciseRefusal(Target target, ObjectNode before, String message) {
        Map<String, ObjectNode> targetsBefore = targets();
        Map<String, ObjectNode> policiesBefore = policies();
        reservedTargets.add(target);
        LOG.infov("DDB_REGISTRATION_ADMISSION_TARGET_RESERVED marker={0} resource={1} dimension={2}",
                marker, target.resource(table), target.dimension());
        RegistrationAttempt attempt = register(target);
        Map<String, ObjectNode> targetsAfter = targets();
        Map<String, ObjectNode> policiesAfter = policies();
        ObjectNode after = before == null ? null : activeTable();
        if (before == null) {
            assertMissingTable();
        }
        LOG.infov("DDB_REGISTRATION_ADMISSION_CASE marker={0} dimension={1} status={2} code={3}"
                        + " before={4} after={5} targetsBefore={6} targetsAfter={7}"
                        + " policiesBefore={8} policiesAfter={9}",
                marker, target.dimension(), attempt.status(), attempt.code(), before, after,
                targetsBefore, targetsAfter, policiesBefore, policiesAfter);
        assertAll("Admission refuses the request without changing either complete resource collection",
                () -> assertEquals(400, attempt.status()),
                () -> assertEquals("ValidationException", attempt.code()),
                () -> assertEquals(message, attempt.message()),
                () -> assertEquals(before, after),
                () -> assertEquals(Map.of(), targetsBefore),
                () -> assertEquals(targetsBefore, targetsAfter),
                () -> assertEquals(Map.of(), policiesBefore),
                () -> assertEquals(policiesBefore, policiesAfter));
    }

    private RegistrationAttempt register(Target target) {
        try {
            RegisterScalableTargetResponse response = scaling.registerScalableTarget(
                    RegisterScalableTargetRequest.builder().serviceNamespace(ServiceNamespace.DYNAMODB)
                            .resourceId(target.resource(table))
                            .scalableDimension(ScalableDimension.fromValue(target.dimension()))
                            .minCapacity(1).maxCapacity(5).build());
            return new RegistrationAttempt(response.sdkHttpResponse().statusCode(), null, null);
        } catch (ApplicationAutoScalingException failure) {
            return new RegistrationAttempt(failure.statusCode(), failure.awsErrorDetails().errorCode(),
                    failure.awsErrorDetails().errorMessage());
        }
    }

    private void assertMissingTable() {
        ResourceNotFoundException failure = assertThrows(ResourceNotFoundException.class,
                () -> dynamo.describeTable(request -> request.tableName(table)));
        assertEquals(400, failure.statusCode());
    }

    private Map<String, ObjectNode> policies() {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        Set<String> tokens = new HashSet<>();
        String next = null;
        for (int page = 0; page < 20; page++) {
            DescribeScalingPoliciesResponse response = scaling.describeScalingPolicies(
                    DescribeScalingPoliciesRequest.builder().serviceNamespace(ServiceNamespace.DYNAMODB)
                            .maxResults(10).nextToken(next).build());
            ObjectNode raw = object(calls.getLast().response);
            assertEquals(200, response.sdkHttpResponse().statusCode());
            assertTrue(raw.path("ScalingPolicies").isArray());
            assertEquals(response.scalingPolicies().size(), raw.path("ScalingPolicies").size());
            for (JsonNode item : raw.path("ScalingPolicies")) {
                assertTrue(item.isObject());
                String identity = item.path("ResourceId").asText() + "|"
                        + item.path("ScalableDimension").asText() + "|" + item.path("PolicyName").asText();
                assertNull(result.put(identity, item.deepCopy()));
            }
            next = response.nextToken();
            if (next == null) {
                return result;
            }
            assertFalse(next.isEmpty());
            assertTrue(tokens.add(next));
            assertFalse(response.scalingPolicies().isEmpty());
        }
        throw new AssertionError("Owned policy collection exceeded the fixture page budget");
    }

    private record RegistrationAttempt(int status, String code, String message) {}

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
                    LOG.infov("DDB_REGISTRATION_ADMISSION_TARGET_ALREADY_ABSENT marker={0} dimension={1}", marker, target.dimension());
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
                    LOG.infov("DDB_REGISTRATION_ADMISSION_TABLE_ALREADY_ABSENT marker={0}", marker);
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
                assertEquals(Map.of(), policies());
                assertEquals(List.of(), tables());
                LOG.infov("DDB_REGISTRATION_ADMISSION_CLEAN marker={0} targets=[] tables=[]", marker);
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

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType("S").build();
    }

    private static KeySchemaElement key(String name) {
        return KeySchemaElement.builder().attributeName(name).keyType("HASH").build();
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
                LOG.infov("DDB_REGISTRATION_ADMISSION_REQUEST marker={0} id={1} service={2} account={3} region={4}"
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
                LOG.infov("DDB_REGISTRATION_ADMISSION_RESPONSE marker={0} id={1} status={2} headers={3} base64=",
                        marker, call.id, call.status, context.httpResponse().headers());
                return Optional.empty();
            }
            try (InputStream stream = context.responseBody().orElseThrow()) {
                call.response = stream.readNBytes(262145);
                LOG.infov("DDB_REGISTRATION_ADMISSION_RESPONSE marker={0} id={1} status={2} headers={3} base64={4}",
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

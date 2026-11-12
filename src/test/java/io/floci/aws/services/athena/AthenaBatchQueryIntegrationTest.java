package io.floci.aws.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.BatchGetQueryExecutionResponse;
import software.amazon.awssdk.services.athena.model.InvalidRequestException;
import software.amazon.awssdk.services.athena.model.QueryExecution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AthenaBatchQueryIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(742441000000L);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Inject
    RegionResolver regionResolver;
    private AthenaClient client;
    private String account;

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void openClient() {
        account = Long.toString(ACCOUNTS.incrementAndGet());
        client = AwsTestClients.create(AthenaClient.builder(), account, Region.US_EAST_1);
    }

    @AfterEach
    void closeClient() {
        client.close();
    }


    @Test
    void defaultAccountCannotReadOrMigrateAnotherAccountsPrefixedIdentifier() {
        String known = start(client, "SELECT 31");
        QueryExecution before = get(client, known);
        String alias = account + "/" + known;
        try (AthenaClient defaultOwner = AwsTestClients.create(AthenaClient.builder(),
                regionResolver.getDefaultAccountId(), Region.US_EAST_1)) {
            BatchGetQueryExecutionResponse response = defaultOwner.batchGetQueryExecution(
                    request -> request.queryExecutionIds(alias));
            assertTrue(response.queryExecutions().isEmpty());
            assertEquals(alias, response.unprocessedQueryExecutionIds().getFirst().queryExecutionId());
            assertEquals(before, get(client, known));
            assertEquals(List.of(before), client.batchGetQueryExecution(
                    request -> request.queryExecutionIds(known)).queryExecutions());
        }
    }

    @Test
    void nativeSdkMixedFoundAndMissingBatchesEqualCompleteTerminalGetsWithoutMutation() throws Exception {
        String first = start(client, "SELECT 11");
        String second = start(client, "SELECT 29");
        QueryExecution one = get(client, first);
        QueryExecution two = get(client, second);
        assertEquals("SUCCEEDED", one.status().stateAsString());
        assertEquals("SUCCEEDED", two.status().stateAsString());
        String missing = UUID.randomUUID().toString();
        BatchGetQueryExecutionResponse mixed = client.batchGetQueryExecution(r -> r.queryExecutionIds(second, missing, first));
        assertEquals(List.of(two, one), mixed.queryExecutions());
        assertEquals(1, mixed.unprocessedQueryExecutionIds().size());
        assertEquals(missing, mixed.unprocessedQueryExecutionIds().getFirst().queryExecutionId());
        assertEquals("QUERY_EXECUTION_NOT_FOUND", mixed.unprocessedQueryExecutionIds().getFirst().errorCode());
        assertEquals(List.of(one, two), client.batchGetQueryExecution(r -> r.queryExecutionIds(first, second)).queryExecutions());
        assertTrue(client.batchGetQueryExecution(r -> r.queryExecutionIds(first, second)).unprocessedQueryExecutionIds().isEmpty());
        assertTrue(client.batchGetQueryExecution(r -> r.queryExecutionIds(missing)).queryExecutions().isEmpty());
        assertEquals(one, get(client, first));
        assertEquals(two, get(client, second));
        JsonNode rawGet = raw("GetQueryExecution", Map.of("QueryExecutionId", first));
        JsonNode rawBatch = raw("BatchGetQueryExecution", Map.of("QueryExecutionIds", List.of(first)));
        assertEquals(rawGet.path("QueryExecution"), rawBatch.path("QueryExecutions").get(0));
        assertFalse(rawGet.toString().contains("_flociRegion"));
        assertFalse(rawGet.toString().contains("storedRegion"));
        assertFalse(rawBatch.toString().contains("_flociRegion"));
        assertTrue(rawGet.path("QueryExecution").path("Status").path("SubmissionDateTime").isNumber());
    }

    @Test
    void nativeSdkAccepts50AndRejects51WithoutChangingAnExistingRecord() {
        String known = start(client, "SELECT 1");
        QueryExecution before = get(client, known);
        List<String> ids = new ArrayList<>();
        ids.add(known);
        for (int i = 0; i < 49; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        BatchGetQueryExecutionResponse response = client.batchGetQueryExecution(r -> r.queryExecutionIds(ids));
        assertEquals(List.of(before), response.queryExecutions());
        assertEquals(49, response.unprocessedQueryExecutionIds().size());
        ids.add(UUID.randomUUID().toString());
        assertEquals(400, assertThrows(InvalidRequestException.class,
                () -> client.batchGetQueryExecution(r -> r.queryExecutionIds(ids))).statusCode());
        assertEquals(before, get(client, known));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"QueryExecutionIds\":null}", "{\"QueryExecutionIds\":[]}",
            "{\"QueryExecutionIds\":\"id\"}", "{\"QueryExecutionIds\":{}}", "{\"QueryExecutionIds\":[null]}",
            "{\"QueryExecutionIds\":[5]}", "{\"QueryExecutionIds\":[true]}", "{\"QueryExecutionIds\":[{}]}",
            "{\"QueryExecutionIds\":[\"\"]}", "{\"QueryExecutionIds\":[\"has space\"]}"})
    void invalidEnvelopeAndMembersReturnModeledRefusal(String body) {
        String known = start(client, "SELECT 1");
        QueryExecution before = get(client, known);
        String response = given().header("Authorization", auth(account, "athena"))
                .header("X-Amz-Target", "AmazonAthena.BatchGetQueryExecution")
                .contentType("application/x-amz-json-1.1").body(body).post("/").then().statusCode(400).extract().asString();
        assertTrue(response.contains("InvalidRequestException"));
        assertEquals(before, get(client, known));
    }

    @Test
    void identifierLengthBoundaryUsesThePublishedStringContractRatherThanUuidParsing() {
        assertEquals(1, client.batchGetQueryExecution(r -> r.queryExecutionIds("x".repeat(128)))
                .unprocessedQueryExecutionIds().size());
        assertThrows(InvalidRequestException.class,
                () -> client.batchGetQueryExecution(r -> r.queryExecutionIds("x".repeat(129))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-west-2", "cn-north-1", "us-gov-west-1"})
    void sameAccountRecordsRemainBoundToTheirOriginalRegionAndPartition(String region) {
        String original = start(client, "SELECT 11");
        try (AthenaClient other = AwsTestClients.create(AthenaClient.builder(), account, Region.of(region))) {
            String foreign = start(other, "SELECT 29");
            assertEquals(List.of(get(other, foreign)), other.batchGetQueryExecution(r -> r.queryExecutionIds(original, foreign))
                    .queryExecutions());
            assertEquals(List.of(get(client, original)), client.batchGetQueryExecution(r -> r.queryExecutionIds(original, foreign))
                    .queryExecutions());
            assertEquals(original, other.batchGetQueryExecution(r -> r.queryExecutionIds(original))
                    .unprocessedQueryExecutionIds().getFirst().queryExecutionId());
        }
    }

    @Test
    void anotherAccountCannotRetrieveAStoredExecution() {
        String original = start(client, "SELECT 11");
        try (AthenaClient other = AwsTestClients.create(AthenaClient.builder(), "742441999999", Region.US_EAST_1)) {
            assertTrue(other.batchGetQueryExecution(r -> r.queryExecutionIds(original)).queryExecutions().isEmpty());
            assertEquals("QUERY_EXECUTION_NOT_FOUND", other.batchGetQueryExecution(r -> r.queryExecutionIds(original))
                    .unprocessedQueryExecutionIds().getFirst().errorCode());
        }
    }

    private JsonNode raw(String action, Map<String, Object> body) throws Exception {
        String value = given().header("Authorization", auth(account, "athena"))
                .header("X-Amz-Target", "AmazonAthena." + action).contentType("application/x-amz-json-1.1")
                .body(MAPPER.writeValueAsString(body)).post("/").then().statusCode(200).extract().asString();
        return MAPPER.readTree(value);
    }

    private static String start(AthenaClient client, String query) {
        return client.startQueryExecution(r -> r.queryString(query)
                .resultConfiguration(c -> c.outputLocation("s3://batch-fixture/results/"))).queryExecutionId();
    }

    private static QueryExecution get(AthenaClient client, String id) {
        return client.getQueryExecution(r -> r.queryExecutionId(id)).queryExecution();
    }
}

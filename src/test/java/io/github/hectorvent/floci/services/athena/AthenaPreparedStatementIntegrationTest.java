package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.athena.model.UpdateWorkGroupRequest;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.BatchGetPreparedStatementResponse;
import software.amazon.awssdk.services.athena.model.InvalidRequestException;
import software.amazon.awssdk.services.athena.model.PreparedStatement;
import software.amazon.awssdk.services.athena.model.ResourceNotFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AthenaPreparedStatementIntegrationTest {
    private static final String REGION = "eu-west-2";
    private static final AtomicLong ACCOUNTS = new AtomicLong(741680900000L);
    private String account;
    private AthenaClient client;
    @Inject
    AthenaService injectedAthena;

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void openClient() {
        account = Long.toString(ACCOUNTS.incrementAndGet());
        client = AwsTestClients.create(AthenaClient.builder(), account, Region.of(REGION));
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void nativeSdkRoundTripsAllFiveOperationsWithNumericTimestampAndNoQueryExecution() {
        client.createWorkGroup(r -> r.name("owned"));
        Instant before = Instant.now().minusSeconds(1);
        create(client, "owned", "first", "select ? from no_such_table");
        client.createPreparedStatement(r -> r.workGroup("owned").statementName("second")
                .queryStatement("select 2").description("old description"));
        PreparedStatement saved = get(client, "owned", "first");
        assertEquals("first", saved.statementName());
        assertEquals("owned", saved.workGroupName());
        assertEquals("select ? from no_such_table", saved.queryStatement());
        assertNull(saved.description());
        assertNotNull(saved.lastModifiedTime());
        assertFalse(saved.lastModifiedTime().isBefore(before));
        assertFalse(saved.lastModifiedTime().isAfter(Instant.now().plusSeconds(1)));
        assertThrows(InvalidRequestException.class, () -> create(client, "owned", "first", "duplicate"));
        client.updatePreparedStatement(r -> r.workGroup("owned").statementName("second").queryStatement("updated"));
        assertNull(get(client, "owned", "second").description(), "documented Floci replacement policy");
        assertEquals(saved, get(client, "owned", "first"));
        BatchGetPreparedStatementResponse batch = client.batchGetPreparedStatement(r -> r.workGroup("owned")
                .preparedStatementNames("first", "missing", "second"));
        assertEquals(List.of("first", "second"), batch.preparedStatements().stream().map(PreparedStatement::statementName).toList());
        assertEquals("missing", batch.unprocessedPreparedStatementNames().getFirst().statementName());
        assertEquals("STATEMENT_NOT_FOUND", batch.unprocessedPreparedStatementNames().getFirst().errorCode());
        assertNotNull(batch.unprocessedPreparedStatementNames().getFirst().errorMessage());
        assertTrue(client.listQueryExecutions(r -> r.workGroup("owned")).queryExecutionIds().isEmpty());
        client.deletePreparedStatement(r -> r.workGroup("owned").statementName("first"));
        assertThrows(ResourceNotFoundException.class, () -> get(client, "owned", "first"));
        client.deletePreparedStatement(r -> r.workGroup("owned").statementName("second"));
        client.deleteWorkGroup(r -> r.workGroup("owned"));
    }

    @Test
    void sdkBatchAcceptsTheDocumented256NameLimitAndRejects257WithoutMutation() {
        create(client, "primary", "saved", "original");
        PreparedStatement saved = get(client, "primary", "saved");
        List<String> names = new ArrayList<>();
        names.add("saved");
        for (int i = 0; i < 255; i++) {
            names.add("missing" + i);
        }
        BatchGetPreparedStatementResponse batch = client.batchGetPreparedStatement(r -> r.workGroup("primary")
                .preparedStatementNames(names));
        assertEquals(List.of(saved), batch.preparedStatements());
        assertEquals(255, batch.unprocessedPreparedStatementNames().size());
        assertEquals(names.subList(1, 256), batch.unprocessedPreparedStatementNames().stream()
                .map(value -> value.statementName()).toList());
        names.add("overflow");
        InvalidRequestException failure = assertThrows(InvalidRequestException.class,
                () -> client.batchGetPreparedStatement(r -> r.workGroup("primary").preparedStatementNames(names)));
        assertEquals(400, failure.statusCode());
        assertTrue(failure.getMessage().contains("256"));
        assertEquals(saved, get(client, "primary", "saved"));
        client.deletePreparedStatement(r -> r.workGroup("primary").statementName("saved"));
    }

    @Test
    void flociPreparedChildDeletionPolicyRefusesNonrecursiveAndRecreatesEmpty() {
        client.createWorkGroup(r -> r.name("owned"));
        create(client, "owned", "saved", "original");
        assertThrows(InvalidRequestException.class, () -> client.deleteWorkGroup(r -> r.workGroup("owned")));
        assertThrows(InvalidRequestException.class, () -> client.deleteWorkGroup(r -> r.workGroup("owned").recursiveDeleteOption(false)));
        assertEquals("original", get(client, "owned", "saved").queryStatement());
        client.deleteWorkGroup(r -> r.workGroup("owned").recursiveDeleteOption(true));
        assertThrows(InvalidRequestException.class, () -> client.getWorkGroup(r -> r.workGroup("owned")));
        client.createWorkGroup(r -> r.name("owned"));
        assertThrows(ResourceNotFoundException.class, () -> get(client, "owned", "saved"));
        client.deleteWorkGroup(r -> r.workGroup("owned"));
        create(client, "primary", "saved", "primary");
        assertThrows(InvalidRequestException.class, () -> client.deleteWorkGroup(r -> r.workGroup("primary").recursiveDeleteOption(true)));
        assertEquals("primary", get(client, "primary", "saved").queryStatement());
        client.deletePreparedStatement(r -> r.workGroup("primary").statementName("saved"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-west-2", "cn-north-1", "us-gov-west-1", "eusc-de-east-1"})
    void accountRegionPartitionAndWorkGroupScopesHaveIndependentSameNamedStatements(String region) {
        client.createWorkGroup(r -> r.name("other"));
        create(client, "primary", "same", "original");
        create(client, "other", "same", "other group");
        try (AthenaClient otherAccount = AwsTestClients.create(AthenaClient.builder(), "741680999999", Region.of(REGION));
             AthenaClient otherRegion = AwsTestClients.create(AthenaClient.builder(), account, Region.of(region))) {
            assertThrows(ResourceNotFoundException.class, () -> get(otherAccount, "primary", "same"));
            assertThrows(ResourceNotFoundException.class, () -> get(otherRegion, "primary", "same"));
            create(otherAccount, "primary", "same", "other account");
            create(otherRegion, "primary", "same", "other region");
            assertEquals("original", get(client, "primary", "same").queryStatement());
            assertEquals("other group", get(client, "other", "same").queryStatement());
            assertEquals("other account", get(otherAccount, "primary", "same").queryStatement());
            assertEquals("other region", get(otherRegion, "primary", "same").queryStatement());
            otherAccount.deletePreparedStatement(r -> r.workGroup("primary").statementName("same"));
            otherRegion.deletePreparedStatement(r -> r.workGroup("primary").statementName("same"));
        }
        client.deleteWorkGroup(r -> r.workGroup("other").recursiveDeleteOption(true));
        client.deletePreparedStatement(r -> r.workGroup("primary").statementName("same"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"StatementName\":3}", "{\"StatementName\":null}", "{\"StatementName\":\"a\"}",
            "{\"QueryStatement\":[]}", "{\"QueryStatement\":null}", "{\"QueryStatement\":\"\"}",
            "{\"Description\":{}}", "{\"Description\":\"\"}", "{\"WorkGroup\":1}", "{\"WorkGroup\":\"bad group\"}"})
    void invalidRequestTypesAndShapesCannotMutateAnExistingStatement(String override) throws Exception {
        create(client, "primary", "saved", "original");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode().put("WorkGroup", "primary").put("StatementName", "saved")
                .put("QueryStatement", "changed");
        body.setAll((ObjectNode) mapper.readTree(override));
        raw("UpdatePreparedStatement", body.toString()).then().statusCode(400);
        assertEquals("original", get(client, "primary", "saved").queryStatement());
        client.deletePreparedStatement(r -> r.workGroup("primary").statementName("saved"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"WorkGroup\":\"primary\",\"PreparedStatementNames\":null}",
            "{\"WorkGroup\":\"primary\",\"PreparedStatementNames\":\"saved\"}",
            "{\"WorkGroup\":\"primary\",\"PreparedStatementNames\":[\"saved\",1]}",
            "{\"WorkGroup\":\"primary\",\"PreparedStatementNames\":[\"saved\",null]}"})
    void batchRejectsMissingAndWronglyTypedNames(String body) {
        raw("BatchGetPreparedStatement", body).then().statusCode(400);
    }



    @Test
    void cdiClientProxyUsesTheSameTargetMonitorAsExistingParentMutations() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker;
        synchronized (injectedAthena.workGroupLock()) {
            worker = Thread.ofPlatform().start(() -> {
                attempted.countDown();
                try {
                    injectedAthena.updateWorkGroup(new UpdateWorkGroupRequest(), REGION);
                } catch (Throwable expected) {
                    outcome.set(expected);
                }
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (worker.isAlive() && worker.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(Thread.State.BLOCKED, worker.getState(), "the real synchronized target must wait for this monitor");
            assertNull(outcome.get());
        }
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertInstanceOf(AwsException.class, outcome.get());
    }

    private Response raw(String action, String body) {
        return given().header("X-Amz-Target", "AmazonAthena." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20261112/" + REGION + "/athena/aws4_request, SignedHeaders=host, Signature=test")
                .contentType("application/x-amz-json-1.1").body(body).post("/");
    }

    private static void create(AthenaClient client, String group, String name, String query) {
        client.createPreparedStatement(r -> r.workGroup(group).statementName(name).queryStatement(query));
    }

    private static PreparedStatement get(AthenaClient client, String group, String name) {
        return client.getPreparedStatement(r -> r.workGroup(group).statementName(name)).preparedStatement();
    }
}

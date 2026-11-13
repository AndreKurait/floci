package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.athena.model.QueryExecution;
import io.github.hectorvent.floci.services.athena.model.QueryExecutionContext;
import io.github.hectorvent.floci.services.athena.model.QueryExecutionState;
import io.github.hectorvent.floci.services.athena.model.ResultConfiguration;
import io.github.hectorvent.floci.services.athena.model.WorkGroup;
import io.github.hectorvent.floci.services.athena.model.WorkGroupTag;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AthenaBatchQueryServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "742440000001";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(QueryExecutionState.class)
    void readsEveryStoredStateWithoutRewritingOrLosingFields(QueryExecutionState state) throws Exception {
        Fixture fixture = new Fixture(new InMemoryStorage<>());
        QueryExecution stored = execution("known", state);
        fixture.queries.put("known", stored);
        JsonNode before = MAPPER.valueToTree(stored);
        JsonNode result = MAPPER.valueToTree(fixture.batch.batchGet(REGION, request("known", "dd285f41-a523-4d8c-b35c-6f671416fdcc")));
        ObjectNode expected = (ObjectNode) before.deepCopy();
        expected.remove("_flociRegion");
        assertEquals(expected, result.path("QueryExecutions").get(0));
        assertEquals("QUERY_EXECUTION_NOT_FOUND", result.path("UnprocessedQueryExecutionIds").get(0).path("ErrorCode").asText());
        assertEquals(before, MAPPER.valueToTree(fixture.queries.get("known").orElseThrow()));
        stored.setQuery("changed after read");
        assertEquals("SELECT 7", result.path("QueryExecutions").get(0).path("Query").asText());
    }


    @ParameterizedTest
    @ValueSource(strings = {"742440000001", "742440000002"})
    void accountPrefixesCannotBeReinterpretedAsLegacyIdentifiers(String storedAccount) {
        InMemoryStorage<String, QueryExecution> raw = new InMemoryStorage<>();
        String id = "b81180c6-40f9-4e5f-b5d8-cbd12d6ab7e1";
        String alias = storedAccount + "/" + id;
        QueryExecution scoped = execution(id, QueryExecutionState.SUCCEEDED);
        raw.put(alias, scoped);
        Fixture defaultOwner = new Fixture(raw);
        JsonNode before = MAPPER.valueToTree(scoped);
        JsonNode response = MAPPER.valueToTree(defaultOwner.batch.batchGet(REGION, request(alias)));
        assertTrue(response.path("QueryExecutions").isEmpty());
        assertEquals(alias, response.path("UnprocessedQueryExecutionIds").get(0).path("QueryExecutionId").asText());
        assertEquals(before, MAPPER.valueToTree(raw.get(alias).orElseThrow()));
        assertTrue(raw.get(ACCOUNT + "/" + alias).isEmpty());
        Fixture actualOwner = new Fixture(raw, storedAccount);
        assertEquals(1, entries(actualOwner, REGION, id).size());
        String legacyId = "1d6b3b69-0eaa-4446-bcf5-e566af73ca12";
        QueryExecution legacy = execution(legacyId, QueryExecutionState.SUCCEEDED);
        legacy.storeRegion(null);
        raw.put(legacyId, legacy);
        assertEquals(1, entries(defaultOwner, REGION, legacyId).size());
        assertTrue(raw.get(legacyId).isEmpty());
        assertEquals(legacy, raw.get(ACCOUNT + "/" + legacyId).orElseThrow());
        assertEquals(before, MAPPER.valueToTree(raw.get(alias).orElseThrow()));
    }

    @Test
    void capturedRegionAndLegacyDefaultNeverFollowTheReceivingRegion() throws Exception {
        Fixture fixture = new Fixture(new InMemoryStorage<>());
        QueryExecution known = execution("known", QueryExecutionState.SUCCEEDED);
        known.storeRegion("cn-north-1");
        fixture.queries.put("known", known);
        QueryExecution legacy = MAPPER.readValue("{\"QueryExecutionId\":\"legacy\",\"Query\":\"old SQL\",\"WorkGroup\":\"primary\"}", QueryExecution.class);
        fixture.queries.put("legacy", legacy);
        assertEquals(1, entries(fixture, "cn-north-1", "known", "legacy").size());
        assertEquals(1, entries(fixture, REGION, "known", "legacy").size());
        assertEquals(0, entries(fixture, "us-west-2", "known", "legacy").size());
        assertNull(legacy.storedRegion());
        assertEquals("cn-north-1", known.storedRegion());
    }

    @Test
    void legacyRowsCannotBeMigratedByAnotherAccountOrWrongRegionBeforeRefusal() {
        InMemoryStorage<String, QueryExecution> raw = new InMemoryStorage<>();
        QueryExecution legacy = execution("legacy", QueryExecutionState.SUCCEEDED);
        legacy.storeRegion(null);
        raw.put("legacy", legacy);
        Fixture foreign = new Fixture(raw, "742440000002");
        assertEquals(0, entries(foreign, REGION, "legacy").size());
        assertEquals(legacy, raw.get("legacy").orElseThrow());
        assertTrue(raw.get("742440000002/legacy").isEmpty());
        Fixture owner = new Fixture(raw);
        assertEquals(0, entries(owner, "us-west-2", "legacy").size());
        assertEquals(legacy, raw.get("legacy").orElseThrow());
        assertTrue(raw.get(ACCOUNT + "/legacy").isEmpty());
        assertEquals(1, entries(owner, REGION, "legacy").size());
        assertTrue(raw.get("legacy").isEmpty());
        assertEquals(legacy, raw.get(ACCOUNT + "/legacy").orElseThrow());
        assertNull(legacy.storedRegion());
    }

    @Test
    void newPublicProjectionMatchesTheCapturedPreviousModelSerialization() throws Exception {
        JsonNode before = MAPPER.readTree(getClass().getResourceAsStream("/athena/batch-get-query-existing-wire.json"));
        QueryExecution current = MAPPER.treeToValue(before, QueryExecution.class);
        current.storeRegion("cn-north-1");
        Fixture fixture = new Fixture(new InMemoryStorage<>());
        assertEquals(before, MAPPER.readTree(MAPPER.writeValueAsBytes(fixture.batch.wireExecution(current))));
        assertEquals("cn-north-1", MAPPER.valueToTree(current).path("_flociRegion").asText());
    }

    @Test
    void queryAuthorizationCannotMigrateOrUseAnotherAccountsLegacyWorkGroupTags() {
        InMemoryStorage<String, QueryExecution> queries = new InMemoryStorage<>();
        InMemoryStorage<String, WorkGroup> groups = new InMemoryStorage<>();
        WorkGroup legacy = new WorkGroup();
        legacy.setName("legacy-group");
        legacy.setTags(List.of(new WorkGroupTag("Owner", "default-only")));
        groups.put(REGION + ":legacy-group", legacy);
        String other = "742440000002";
        Fixture foreign = new Fixture(queries, other, groups);
        QueryExecution foreignQuery = execution("foreign-query", QueryExecutionState.SUCCEEDED);
        foreignQuery.setWorkGroup("legacy-group");
        foreign.queries.put("foreign-query", foreignQuery);
        assertTrue(foreign.batch.authorizationTargets(REGION, other, request("foreign-query")).getFirst().context().isEmpty());
        assertTrue(groups.get(other + "/" + REGION + ":legacy-group").isEmpty());
        assertEquals(legacy, groups.get(REGION + ":legacy-group").orElseThrow());
        Fixture owner = new Fixture(queries, ACCOUNT, groups);
        QueryExecution ownQuery = execution("own-query", QueryExecutionState.SUCCEEDED);
        ownQuery.setWorkGroup("legacy-group");
        owner.queries.put("own-query", ownQuery);
        assertEquals(List.of("default-only"), owner.batch.authorizationTargets(REGION, ACCOUNT, request("own-query"))
                .getFirst().context().get("aws:ResourceTag/Owner"));
        assertSame(legacy, groups.get(REGION + ":legacy-group").orElseThrow());
        assertTrue(groups.get(ACCOUNT + "/" + REGION + ":legacy-group").isEmpty());
    }


    @Test
    void authorizationOfDefaultLegacyRowsIsReadOnlyBeforeExplicitBatchRead() {
        InMemoryStorage<String, QueryExecution> queries = new InMemoryStorage<>();
        InMemoryStorage<String, WorkGroup> groups = new InMemoryStorage<>();
        String id = "d8f617cc-0b6b-43de-96c9-5deacba72cac";
        QueryExecution legacy = execution(id, QueryExecutionState.SUCCEEDED);
        legacy.storeRegion(null);
        legacy.setWorkGroup("readonly-legacy");
        WorkGroup group = new WorkGroup();
        group.setName("readonly-legacy");
        group.setTags(List.of(new WorkGroupTag("Owner", "legacy-owner")));
        String groupKey = REGION + ":readonly-legacy";
        queries.put(id, legacy);
        groups.put(groupKey, group);
        JsonNode original = MAPPER.valueToTree(legacy);
        Fixture fixture = new Fixture(queries, ACCOUNT, groups);
        AthenaBatchQueryService.AuthorizationTarget target =
                fixture.batch.authorizationTargets(REGION, ACCOUNT, request(id)).getFirst();
        assertEquals("arn:aws:athena:" + REGION + ":" + ACCOUNT + ":workgroup/readonly-legacy", target.resource());
        assertEquals(List.of("legacy-owner"), target.context().get("aws:ResourceTag/Owner"));
        assertAll(
                () -> assertSame(legacy, queries.get(id).orElse(null)),
                () -> assertTrue(queries.get(ACCOUNT + "/" + id).isEmpty()),
                () -> assertSame(group, groups.get(groupKey).orElse(null)),
                () -> assertTrue(groups.get(ACCOUNT + "/" + groupKey).isEmpty()),
                () -> assertEquals(original, MAPPER.valueToTree(legacy)));
        assertEquals(1, entries(fixture, REGION, id).size());
        assertTrue(queries.get(id).isEmpty());
        assertSame(legacy, queries.get(ACCOUNT + "/" + id).orElseThrow());
        assertSame(group, groups.get(groupKey).orElseThrow());
        assertTrue(groups.get(ACCOUNT + "/" + groupKey).isEmpty());
    }

    @Test
    void startCapturesRegionInTheSameStoredRecordAndWireProjectionNeverExposesIt() {
        Fixture fixture = new Fixture(new InMemoryStorage<>());
        String id = fixture.athena.startQueryExecution("SELECT 1", "primary", null,
                new ResultConfiguration("s3://batch-fixture/results/"));
        QueryExecution record = fixture.queries.get(id).orElseThrow();
        assertEquals(REGION, record.storedRegion());
        assertEquals(REGION, MAPPER.valueToTree(record).path("_flociRegion").asText());
        assertFalse(fixture.batch.wireExecution(record).has("_flociRegion"));
        assertFalse(fixture.batch.wireExecution(record).has("storedRegion"));
    }

    @Test
    void rejectedInitialWriteDoesNotPublishRegionMetadataOrStartExecution() {
        InMemoryStorage<String, QueryExecution> rejecting = new InMemoryStorage<>() {
            @Override
            public void put(String key, QueryExecution value) {
                throw new IllegalStateException("injected pre-write failure");
            }
        };
        Fixture fixture = new Fixture(rejecting);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fixture.athena.startQueryExecution("SELECT 1", "primary", null,
                        new ResultConfiguration("s3://batch-fixture/results/")));
        assertEquals("injected pre-write failure", error.getMessage());
        assertTrue(rejecting.scan(key -> true).isEmpty());
    }

    @Test
    void backendFailureIsNotMisreportedAsMissing() {
        AthenaService failing = mock(AthenaService.class);
        when(failing.findBatchQueryExecution(REGION, "known")).thenThrow(new IllegalStateException("read failed"));
        AthenaBatchQueryService batch = new AthenaBatchQueryService(failing,
                new RegionResolver(REGION, ACCOUNT), MAPPER);
        assertEquals("read failed", assertThrows(IllegalStateException.class,
                () -> batch.batchGet(REGION, request("known"))).getMessage());
    }

    @Test
    void readWaitsForAStatusTransitionAndReturnsOneDetachedSnapshot() throws Exception {
        Fixture fixture = new Fixture(new InMemoryStorage<>());
        QueryExecution record = execution("known", QueryExecutionState.RUNNING);
        fixture.queries.put("known", record);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<JsonNode> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            started.countDown();
            try {
                result.set(MAPPER.valueToTree(fixture.batch.batchGet(REGION, request("known"))));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        Instant completed = Instant.parse("2026-11-12T10:00:01Z");
        synchronized (record) {
            reader.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (reader.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.BLOCKED, reader.getState());
            record.getStatus().setState(QueryExecutionState.SUCCEEDED);
            record.getStatus().setCompletionDateTime(completed);
        }
        reader.join(5000);
        assertFalse(reader.isAlive());
        assertNull(failure.get());
        JsonNode status = result.get().path("QueryExecutions").get(0).path("Status");
        assertEquals("SUCCEEDED", status.path("State").asText());
        assertEquals(completed.getEpochSecond(), status.path("CompletionDateTime").asLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void existingStorageModesRetainRegionAndReadRecordsAfterReloadWhereSupported(String mode) throws Exception {
        StorageBackend<String, QueryExecution> backend = backend(mode);
        backend.load();
        Fixture fixture = new Fixture(backend);
        QueryExecution record = execution("known", QueryExecutionState.SUCCEEDED);
        fixture.queries.put("known", record);
        JsonNode expected = fixture.batch.wireExecution(record);
        backend.flush();
        stop(backend);
        StorageBackend<String, QueryExecution> reopened = "memory".equals(mode) ? backend : backend(mode);
        try {
            reopened.load();
            Fixture restored = new Fixture(reopened);
            assertEquals(expected, entries(restored, REGION, "known").get(0));
            assertEquals(0, entries(restored, "us-west-2", "known").size());
            assertEquals(REGION, restored.queries.get("known").orElseThrow().storedRegion());
        } finally {
            stop(reopened);
        }
    }

    private StorageBackend<String, QueryExecution> backend(String mode) {
        TypeReference<Map<String, QueryExecution>> type = new TypeReference<>() {};
        return switch (mode) {
            case "memory" -> new InMemoryStorage<>();
            case "persistent" -> new PersistentStorage<>(directory.resolve("queries.json"), type);
            case "hybrid" -> new HybridStorage<>(directory.resolve("queries.json"), type, 60000);
            case "wal" -> new WalStorage<>(directory.resolve("queries.json"), directory.resolve("queries.wal"), type, 60000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void stop(StorageBackend<String, QueryExecution> backend) {
        if (backend instanceof HybridStorage<String, QueryExecution> hybrid) {
            hybrid.shutdown();
        } else if (backend instanceof WalStorage<String, QueryExecution> wal) {
            wal.shutdown();
        }
    }

    private static JsonNode request(String... ids) {
        return MAPPER.valueToTree(Map.of("QueryExecutionIds", List.of(ids)));
    }

    private static JsonNode entries(Fixture fixture, String region, String... ids) {
        return MAPPER.valueToTree(fixture.batch.batchGet(region, request(ids))).path("QueryExecutions");
    }

    private static QueryExecution execution(String id, QueryExecutionState state) {
        QueryExecutionContext context = new QueryExecutionContext();
        context.setDatabase("warehouse");
        context.setCatalog("AwsDataCatalog");
        QueryExecution value = new QueryExecution(id, "SELECT 7", "primary",
                new ResultConfiguration("s3://batch-fixture/" + id + ".csv"), context);
        value.storeRegion(REGION);
        value.getStatus().setState(state);
        value.getStatus().setSubmissionDateTime(Instant.parse("2026-11-12T10:00:00Z"));
        value.getStatus().setStateChangeReason("fixture reason");
        value.getStatistics().setDataScannedInBytes(27L);
        value.getStatistics().setEngineExecutionTimeInMillis(8L);
        return value;
    }

    private static final class Fixture {
        final AccountAwareStorageBackend<QueryExecution> queries;
        final AthenaService athena;
        final AthenaBatchQueryService batch;

        Fixture(StorageBackend<String, QueryExecution> backend) {
            this(backend, ACCOUNT);
        }

        Fixture(StorageBackend<String, QueryExecution> backend, String account) {
            this(backend, account, new InMemoryStorage<>());
        }

        Fixture(StorageBackend<String, QueryExecution> backend, String account, StorageBackend<String, WorkGroup> groups) {
            queries = new AccountAwareStorageBackend<>(backend, null, account);
            StorageFactory factory = mock(StorageFactory.class);
            doAnswer(call -> {
                String file = call.getArgument(1);
                if ("queries.json".equals(file)) {
                    return queries;
                }
                if ("workgroups.json".equals(file)) {
                    return new AccountAwareStorageBackend<>(groups, null, account);
                }
                return AccountAwareStorageBackend.inMemory(account);
            }).when(factory).create(anyString(), anyString(), any());
            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.AthenaServiceConfig serviceConfig = mock(EmulatorConfig.AthenaServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.athena()).thenReturn(serviceConfig);
            when(serviceConfig.mock()).thenReturn(true);
            RegionResolver region = new RegionResolver(REGION, ACCOUNT) {
                @Override
                public String getAccountId() {
                    return account;
                }
            };
            athena = new AthenaService(factory, mock(FlociDuckClient.class), mock(GlueService.class), mock(S3Service.class),
                    config, mock(Vertx.class), mock(GlueViewDdlBuilder.class), region);
            batch = new AthenaBatchQueryService(athena, region, MAPPER);
        }
    }
}

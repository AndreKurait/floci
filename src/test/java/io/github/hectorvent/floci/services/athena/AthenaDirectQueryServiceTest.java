package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.athena.model.QueryExecution;
import io.github.hectorvent.floci.services.athena.model.QueryExecutionState;
import io.github.hectorvent.floci.services.athena.model.ResultConfiguration;
import io.github.hectorvent.floci.services.athena.model.WorkGroup;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AthenaDirectQueryServiceTest {
    private static final String ACCOUNT = "742461000001";
    private static final String FOREIGN = "742461000002";
    private static final String REGION = "us-east-1";

    @Test
    void wrongRegionRefusesBeforeQueryMigrationAndBeforeS3Access() {
        Fixture fixture = new Fixture(ACCOUNT);
        QueryExecution query = execution("legacy");
        fixture.queries.put("legacy", query);
        assertThrows(AwsException.class, () -> fixture.service.getQueryResults("us-west-2", "legacy"));
        assertAll(
                () -> assertSame(query, fixture.queries.get("legacy").orElse(null)),
                () -> assertTrue(fixture.queries.get(ACCOUNT + "/legacy").isEmpty()));
        verifyNoInteractions(fixture.s3);
        fixture.csv();
        assertEquals("varchar", fixture.service.getQueryResults(REGION, "legacy")
                .getMetadata().getColumnInfo().getFirst().getType());
        assertTrue(fixture.queries.get("legacy").isEmpty());
        assertSame(query, fixture.queries.get(ACCOUNT + "/legacy").orElseThrow());
    }

    @Test
    void scopedHistoryWinsOverSameIdLegacyBeforeRegionFilteringWithoutWritingEitherRow() {
        Fixture fixture = new Fixture(ACCOUNT);
        QueryExecution legacy = execution("same");
        QueryExecution scoped = execution("same");
        scoped.storeRegion("us-west-2");
        fixture.queries.put("same", legacy);
        fixture.queries.put(ACCOUNT + "/same", scoped);
        assertTrue(fixture.service.listQueryExecutionsPage(REGION, null, 50, null).items().isEmpty());
        assertEquals(List.of(scoped), fixture.service.listQueryExecutionsPage("us-west-2", null, 50, null).items());
        assertSame(legacy, fixture.queries.get("same").orElseThrow());
        assertSame(scoped, fixture.queries.get(ACCOUNT + "/same").orElseThrow());
    }

    @Test
    void historyCannotMigrateAnotherAccountsLegacyWorkGroup() {
        Fixture fixture = new Fixture(FOREIGN);
        WorkGroup group = new WorkGroup();
        group.setName("legacy-group");
        fixture.groups.put(REGION + ":legacy-group", group);
        assertThrows(AwsException.class,
                () -> fixture.service.listQueryExecutionsPage(REGION, "legacy-group", 50, null));
        assertSame(group, fixture.groups.get(REGION + ":legacy-group").orElseThrow());
        assertTrue(fixture.groups.get(FOREIGN + "/" + REGION + ":legacy-group").isEmpty());
    }

    private static QueryExecution execution(String id) {
        QueryExecution query = new QueryExecution(id, "SELECT 7", "primary",
                new ResultConfiguration("s3://results/owned/"), null);
        query.getStatus().setState(QueryExecutionState.SUCCEEDED);
        return query;
    }

    private static final class Fixture {
        final InMemoryStorage<String, QueryExecution> queries = new InMemoryStorage<>();
        final InMemoryStorage<String, WorkGroup> groups = new InMemoryStorage<>();
        final S3Service s3 = mock(S3Service.class);
        final AthenaService service;

        Fixture(String account) {
            StorageFactory factory = mock(StorageFactory.class);
            doAnswer(call -> switch (call.<String>getArgument(1)) {
                case "queries.json" -> new AccountAwareStorageBackend<>(queries, null, ACCOUNT);
                case "workgroups.json" -> new AccountAwareStorageBackend<>(groups, null, ACCOUNT);
                default -> AccountAwareStorageBackend.inMemory(ACCOUNT);
            }).when(factory).create(anyString(), anyString(), any());
            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.AthenaServiceConfig athena = mock(EmulatorConfig.AthenaServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.athena()).thenReturn(athena);
            when(athena.mock()).thenReturn(false);
            RegionResolver region = new RegionResolver(REGION, ACCOUNT) {
                @Override
                public String getAccountId() {
                    return account;
                }
            };
            service = new AthenaService(factory, mock(FlociDuckClient.class), mock(GlueService.class),
                    s3, config, mock(Vertx.class), mock(GlueViewDdlBuilder.class), region);
        }

        void csv() {
            S3Object result = new S3Object("results", "owned/query.csv", "n\n7\n".getBytes(StandardCharsets.UTF_8), "text/csv");
            when(s3.listObjects(eq("results"), eq("owned/"), any(), anyInt())).thenReturn(List.of(result));
            when(s3.getObject("results", "owned/query.csv")).thenReturn(result);
        }
    }
}

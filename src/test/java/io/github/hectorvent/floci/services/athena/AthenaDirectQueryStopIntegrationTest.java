package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.athena.model.QueryExecution;
import io.github.hectorvent.floci.services.athena.model.QueryExecutionState;
import io.github.hectorvent.floci.services.athena.model.WorkGroup;
import io.github.hectorvent.floci.services.athena.model.WorkGroupTag;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.AthenaException;
import software.amazon.awssdk.services.athena.model.InvalidRequestException;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AthenaDirectQueryStopIntegrationTest {
    @Inject
    StorageFactory factory;
    @Inject
    RegionResolver regionResolver;
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void anotherRegionCannotCancelAnActiveOwnedRecord() throws Exception {
        StorageBackend<String, QueryExecution> raw = rawBackend(factory.create("athena", "queries.json",
                new TypeReference<Map<String, QueryExecution>>() {}));
        String account = "742462000001";
        String id = UUID.randomUUID().toString();
        QueryExecution query = active(id, "primary");
        query.storeRegion("us-east-1");
        raw.put(account + "/" + id, query);
        try (AthenaClient owner = AwsTestClients.create(AthenaClient.builder(), account, Region.US_EAST_1);
             AthenaClient other = AwsTestClients.create(AthenaClient.builder(), account, Region.US_WEST_2)) {
            assertThrows(InvalidRequestException.class, () -> other.stopQueryExecution(request -> request.queryExecutionId(id)));
            assertEquals("RUNNING", owner.getQueryExecution(request -> request.queryExecutionId(id))
                    .queryExecution().status().stateAsString());
            owner.stopQueryExecution(request -> request.queryExecutionId(id));
            assertEquals("CANCELLED", owner.getQueryExecution(request -> request.queryExecutionId(id))
                    .queryExecution().status().stateAsString());
            assertEquals("CANCELLED", owner.batchGetQueryExecution(request -> request.queryExecutionIds(id))
                    .queryExecutions().getFirst().status().stateAsString());
        } finally {
            raw.delete(account + "/" + id);
        }
    }

    @Test
    void deniedStopCannotCancelOrMigrateALegacyActiveRecordBeforeAuthorizedCancellation() throws Exception {
        StorageBackend<String, QueryExecution> raw = rawBackend(factory.create("athena", "queries.json",
                new TypeReference<Map<String, QueryExecution>>() {}));
        StorageBackend<String, WorkGroup> groups = rawBackend(factory.create("athena", "workgroups.json",
                new TypeReference<Map<String, WorkGroup>>() {}));
        String account = regionResolver.getDefaultAccountId();
        String id = UUID.randomUUID().toString();
        String name = "direct-active-" + UUID.randomUUID();
        String groupKey = "us-east-1:" + name;
        String resource = "arn:aws:athena:us-east-1:" + account + ":workgroup/" + name;
        QueryExecution query = active(id, name);
        WorkGroup group = new WorkGroup();
        group.setName(name);
        group.setTags(List.of(new WorkGroupTag("Owner", "active")));
        raw.put(id, query);
        groups.put(groupKey, group);
        String condition = ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/Owner\":\"active\"}}";
        String scoped = "\"Action\":\"athena:StopQueryExecution\",\"Resource\":\"" + resource + "\"" + condition;
        try (AthenaClient denied = caller("{\"Effect\":\"Allow\",\"Action\":\"athena:*\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\"," + scoped + "}")) {
            AthenaException error = assertThrows(AthenaException.class,
                    () -> denied.stopQueryExecution(request -> request.queryExecutionId(id)));
            assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
            assertAll(
                    () -> assertEquals(QueryExecutionState.RUNNING, query.getStatus().getState()),
                    () -> assertSame(query, raw.get(id).orElse(null)),
                    () -> assertTrue(raw.get(account + "/" + id).isEmpty()),
                    () -> assertSame(group, groups.get(groupKey).orElse(null)),
                    () -> assertTrue(groups.get(account + "/" + groupKey).isEmpty()));
            try (AthenaClient allowed = caller("{\"Effect\":\"Allow\"," + scoped + "}")) {
                allowed.stopQueryExecution(request -> request.queryExecutionId(id));
            }
            assertEquals(QueryExecutionState.CANCELLED, raw.get(account + "/" + id).orElseThrow().getStatus().getState());
            assertTrue(raw.get(id).isEmpty());
            assertSame(group, groups.get(groupKey).orElseThrow());
        } finally {
            raw.delete(id);
            raw.delete(account + "/" + id);
            groups.delete(groupKey);
            groups.delete(account + "/" + groupKey);
        }
    }

    private AthenaClient caller(String statements) {
        String user = "direct-active-caller-" + UUID.randomUUID();
        String iamAuth = auth("test", "iam");
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), iamAuth, user, "direct-active",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        return AwsTestClients.create(AthenaClient.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1);
    }

    /** Seed an active record without racing a real worker; callback account propagation remains separate. */
    private static QueryExecution active(String id, String group) {
        QueryExecution query = new QueryExecution(id, "SELECT 43", group, null, null);
        query.getStatus().setState(QueryExecutionState.RUNNING);
        return query;
    }

    @SuppressWarnings("unchecked")
    private static <T> StorageBackend<String, T> rawBackend(AccountAwareStorageBackend<T> backend)
            throws ReflectiveOperationException {
        Field delegate = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        return (StorageBackend<String, T>) delegate.get(backend);
    }
}

package io.floci.aws.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.athena.model.QueryExecution;
import io.floci.aws.services.athena.model.QueryExecutionState;
import io.floci.aws.services.athena.model.WorkGroup;
import io.floci.aws.services.athena.model.WorkGroupTag;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.AthenaException;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AthenaBatchQueryLegacyIamIntegrationTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    @Inject
    StorageFactory factory;
    @Inject
    RegionResolver regionResolver;
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void deniedNativeBatchPreservesLegacyRowsAndAllowedReadMigratesOnlyItsQuery() throws Exception {
        AccountAwareStorageBackend<QueryExecution> queryStore = factory.create("athena", "queries.json",
                new TypeReference<Map<String, QueryExecution>>() {});
        AccountAwareStorageBackend<WorkGroup> groupStore = factory.create("athena", "workgroups.json",
                new TypeReference<Map<String, WorkGroup>>() {});
        StorageBackend<String, QueryExecution> queries = rawFixtureBackend(queryStore);
        StorageBackend<String, WorkGroup> groups = rawFixtureBackend(groupStore);
        String account = regionResolver.getDefaultAccountId();
        String id = UUID.randomUUID().toString();
        String name = "batch-legacy-" + UUID.randomUUID();
        String groupKey = REGION + ":" + name;
        String resource = "arn:aws:athena:" + REGION + ":" + account + ":workgroup/" + name;
        QueryExecution legacy = new QueryExecution(id, "SELECT 43", name, null, null);
        legacy.getStatus().setState(QueryExecutionState.SUCCEEDED);
        WorkGroup group = new WorkGroup();
        group.setName(name);
        group.setTags(List.of(new WorkGroupTag("Owner", "legacy-owner")));
        JsonNode before = MAPPER.valueToTree(legacy);
        String condition = "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/Owner\":\"legacy-owner\"}}";
        try {
            queries.put(id, legacy);
            groups.put(groupKey, group);
            String denyPolicy = "{\"Effect\":\"Allow\",\"Action\":\"athena:BatchGetQueryExecution\",\"Resource\":\"*\"},"
                    + "{\"Effect\":\"Deny\",\"Action\":\"athena:BatchGetQueryExecution\",\"Resource\":\""
                    + resource + "\"," + condition + "}";
            try (AthenaClient denied = caller(denyPolicy)) {
                AthenaException error = assertThrows(AthenaException.class,
                        () -> denied.batchGetQueryExecution(request -> request.queryExecutionIds(id)));
                assertEquals(400, error.statusCode());
                assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
            }
            assertAll(
                    () -> assertSame(legacy, queries.get(id).orElse(null)),
                    () -> assertTrue(queries.get(account + "/" + id).isEmpty()),
                    () -> assertSame(group, groups.get(groupKey).orElse(null)),
                    () -> assertTrue(groups.get(account + "/" + groupKey).isEmpty()),
                    () -> assertEquals(before, MAPPER.valueToTree(legacy)));
            String allowPolicy = "{\"Effect\":\"Allow\",\"Action\":\"athena:BatchGetQueryExecution\",\"Resource\":\""
                    + resource + "\"," + condition + "}";
            try (AthenaClient allowed = caller(allowPolicy)) {
                assertEquals(id, allowed.batchGetQueryExecution(request -> request.queryExecutionIds(id))
                        .queryExecutions().getFirst().queryExecutionId());
            }
            assertTrue(queries.get(id).isEmpty());
            assertSame(legacy, queries.get(account + "/" + id).orElseThrow());
            assertSame(group, groups.get(groupKey).orElseThrow());
            assertTrue(groups.get(account + "/" + groupKey).isEmpty());
            assertEquals(before, MAPPER.valueToTree(legacy));
        } finally {
            queries.delete(id);
            queries.delete(account + "/" + id);
            groups.delete(groupKey);
            groups.delete(account + "/" + groupKey);
        }
    }

    private AthenaClient caller(String statements) {
        String user = "batch-legacy-caller-" + UUID.randomUUID();
        String iamAuth = auth("test", "iam");
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), iamAuth, user, "batch-legacy",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        return AwsTestClients.create(AthenaClient.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1);
    }

    /** Native APIs cannot create pre-account storage rows; seed only this test's UUID and workgroup. */
    @SuppressWarnings("unchecked")
    private static <T> StorageBackend<String, T> rawFixtureBackend(AccountAwareStorageBackend<T> backend)
            throws ReflectiveOperationException {
        Field delegate = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        return (StorageBackend<String, T>) delegate.get(backend);
    }
}

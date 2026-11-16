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
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class AthenaDirectQueryLegacyIamIntegrationTest {
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

    @ParameterizedTest
    @ValueSource(strings = {"GetQueryExecution", "GetQueryResults", "StopQueryExecution", "ListQueryExecutions"})
    void deniedNativeDirectActionPreservesAllLegacyKeysAndObjects(String action) throws Exception {
        AccountAwareStorageBackend<QueryExecution> queryStore = factory.create("athena", "queries.json",
                new TypeReference<Map<String, QueryExecution>>() {});
        AccountAwareStorageBackend<WorkGroup> groupStore = factory.create("athena", "workgroups.json",
                new TypeReference<Map<String, WorkGroup>>() {});
        StorageBackend<String, QueryExecution> queries = rawBackend(queryStore);
        StorageBackend<String, WorkGroup> groups = rawBackend(groupStore);
        String account = regionResolver.getDefaultAccountId();
        String id = UUID.randomUUID().toString();
        String name = "direct-legacy-" + UUID.randomUUID();
        String groupKey = REGION + ":" + name;
        String resource = "arn:aws:athena:" + REGION + ":" + account + ":workgroup/" + name;
        QueryExecution legacy = new QueryExecution(id, "SELECT 43", name, null, null);
        legacy.getStatus().setState(QueryExecutionState.SUCCEEDED);
        WorkGroup group = new WorkGroup();
        group.setName(name);
        group.setTags(List.of(new WorkGroupTag("Owner", "legacy-owner")));
        JsonNode beforeQuery = MAPPER.valueToTree(legacy);
        JsonNode beforeGroup = MAPPER.valueToTree(group);
        queries.put(id, legacy);
        groups.put(groupKey, group);
        String condition = "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/Owner\":\"legacy-owner\"}}";
        String statements = "{\"Effect\":\"Allow\",\"Action\":\"athena:*\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"athena:" + action + "\",\"Resource\":\""
                + resource + "\"," + condition + "}";
        try (AthenaClient denied = caller(statements)) {
            assertAll(
                    () -> {
                        AthenaException error = assertThrows(AthenaException.class,
                                () -> invoke(denied, action, id, name));
                        assertEquals(400, error.statusCode());
                        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
                    },
                    () -> assertSame(legacy, queries.get(id).orElse(null)),
                    () -> assertTrue(queries.get(account + "/" + id).isEmpty()),
                    () -> assertSame(group, groups.get(groupKey).orElse(null)),
                    () -> assertTrue(groups.get(account + "/" + groupKey).isEmpty()),
                    () -> assertEquals(beforeQuery, MAPPER.valueToTree(legacy)),
                    () -> assertEquals(beforeGroup, MAPPER.valueToTree(group)));
            String allowedPolicy = "{\"Effect\":\"Allow\",\"Action\":\"athena:" + action
                    + "\",\"Resource\":\"" + resource + "\"," + condition + "}";
            try (AthenaClient allowed = caller(allowedPolicy)) {
                invoke(allowed, action, id, name);
            }
            assertSame(group, groups.get(groupKey).orElseThrow());
            assertTrue(groups.get(account + "/" + groupKey).isEmpty());
            assertEquals(beforeQuery, MAPPER.valueToTree(legacy));
        } finally {
            queries.delete(id);
            queries.delete(account + "/" + id);
            groups.delete(groupKey);
            groups.delete(account + "/" + groupKey);
        }
    }

    private AthenaClient caller(String statements) {
        String user = "direct-legacy-caller-" + UUID.randomUUID();
        String iamAuth = auth("test", "iam");
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), iamAuth, user, "direct-legacy",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        return AwsTestClients.create(AthenaClient.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1);
    }

    private static Object invoke(AthenaClient client, String action, String id, String group) {
        return switch (action) {
            case "GetQueryExecution" -> client.getQueryExecution(request -> request.queryExecutionId(id));
            case "GetQueryResults" -> client.getQueryResults(request -> request.queryExecutionId(id));
            case "StopQueryExecution" -> client.stopQueryExecution(request -> request.queryExecutionId(id));
            case "ListQueryExecutions" -> {
                List<String> ids = client.listQueryExecutions(request -> request.workGroup(group)).queryExecutionIds();
                assertEquals(List.of(id), ids);
                yield ids;
            }
            default -> throw new IllegalArgumentException(action);
        };
    }

    /** The native API cannot manufacture pre-account rows; owned fixtures seed only these two keys. */
    @SuppressWarnings("unchecked")
    private static <T> StorageBackend<String, T> rawBackend(AccountAwareStorageBackend<T> backend)
            throws ReflectiveOperationException {
        Field delegate = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        return (StorageBackend<String, T>) delegate.get(backend);
    }
}

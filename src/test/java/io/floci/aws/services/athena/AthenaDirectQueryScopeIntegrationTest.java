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
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.InvalidRequestException;
import software.amazon.awssdk.services.athena.model.ListQueryExecutionsResponse;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AthenaDirectQueryScopeIntegrationTest {
    // Storage and SDK models share QueryExecution; qualify the less-used SDK model below.
    private static final AtomicLong ACCOUNTS = new AtomicLong(742460000000L);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    @Inject
    StorageFactory factory;
    @Inject
    RegionResolver regionResolver;

    @ParameterizedTest
    @CsvSource({"get,us-west-2", "results,us-west-2", "stop,us-west-2",
            "get,cn-north-1", "results,cn-north-1", "stop,cn-north-1",
            "get,us-gov-west-1", "results,us-gov-west-1", "stop,us-gov-west-1"})
    void directQueryOperationsRefuseAnotherRegionWithoutChangingTheOwner(String operation, String otherRegion) {
        String account = Long.toString(ACCOUNTS.incrementAndGet());
        try (AthenaClient owner = client(account, Region.US_EAST_1);
             AthenaClient other = client(account, Region.of(otherRegion))) {
            String id = start(owner, "SELECT 11");
            software.amazon.awssdk.services.athena.model.QueryExecution before =
                    owner.getQueryExecution(request -> request.queryExecutionId(id)).queryExecution();
            assertAll(
                    () -> assertEquals(400, assertThrows(InvalidRequestException.class,
                            () -> invoke(other, operation, id)).statusCode()),
                    () -> assertEquals(before,
                            owner.getQueryExecution(request -> request.queryExecutionId(id)).queryExecution()),
                    () -> assertEquals(List.of(before), owner.batchGetQueryExecution(
                            request -> request.queryExecutionIds(id)).queryExecutions()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-west-2", "cn-north-1", "us-gov-west-1"})
    void historyFiltersRegionBeforePagingAndRetainsTheTokenScope(String otherRegion) {
        String account = Long.toString(ACCOUNTS.incrementAndGet());
        try (AthenaClient owner = client(account, Region.US_EAST_1);
             AthenaClient other = client(account, Region.of(otherRegion))) {
            String one = start(owner, "SELECT 11");
            String foreign = start(other, "SELECT 29");
            String two = start(owner, "SELECT 13");
            ListQueryExecutionsResponse first = owner.listQueryExecutions(request -> request.maxResults(1));
            List<String> ids = new ArrayList<>(first.queryExecutionIds());
            String token = first.nextToken();
            assertTrue(token != null && !token.isEmpty());
            while (token != null) {
                String next = token;
                ListQueryExecutionsResponse page = owner.listQueryExecutions(request -> request.maxResults(1).nextToken(next));
                ids.addAll(page.queryExecutionIds());
                assertTrue(ids.size() <= 3, "pagination must make bounded progress across the three fixture records");
                token = page.nextToken();
            }
            assertAll(
                    () -> assertEquals(2, ids.size()),
                    () -> assertEquals(Set.of(one, two), Set.copyOf(ids)),
                    () -> assertEquals(List.of(foreign), other.listQueryExecutions().queryExecutionIds()),
                    () -> assertThrows(InvalidRequestException.class,
                            () -> other.listQueryExecutions(request -> request.nextToken(first.nextToken()))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "results", "stop"})
    void rawLegacyQueryCannotBeAdoptedByAnotherAccount(String operation) throws Exception {
        AccountAwareStorageBackend<QueryExecution> scoped = factory.create("athena", "queries.json",
                new TypeReference<Map<String, QueryExecution>>() {});
        StorageBackend<String, QueryExecution> raw = rawBackend(scoped);
        String account = Long.toString(ACCOUNTS.incrementAndGet());
        String id = UUID.randomUUID().toString();
        QueryExecution legacy = legacy(id);
        JsonNode before = MAPPER.valueToTree(legacy);
        raw.put(id, legacy);
        try (AthenaClient other = client(account, Region.US_EAST_1)) {
            assertAll(
                    () -> assertThrows(InvalidRequestException.class, () -> invoke(other, operation, id)),
                    () -> assertSame(legacy, raw.get(id).orElse(null)),
                    () -> assertTrue(raw.get(account + "/" + id).isEmpty()),
                    () -> assertEquals(before, MAPPER.valueToTree(legacy)));
        } finally {
            raw.delete(id);
            raw.delete(account + "/" + id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "results", "stop"})
    void defaultAccountCannotReinterpretAnAccountPrefixedIdAsLegacy(String operation) {
        String account = Long.toString(ACCOUNTS.incrementAndGet());
        try (AthenaClient owner = client(account, Region.US_EAST_1);
             AthenaClient defaultOwner = client(regionResolver.getDefaultAccountId(), Region.US_EAST_1)) {
            String id = start(owner, "SELECT 31");
            software.amazon.awssdk.services.athena.model.QueryExecution before =
                    owner.getQueryExecution(request -> request.queryExecutionId(id)).queryExecution();
            assertAll(
                    () -> assertThrows(InvalidRequestException.class,
                            () -> invoke(defaultOwner, operation, account + "/" + id)),
                    () -> assertEquals(before, owner.getQueryExecution(
                            request -> request.queryExecutionId(id)).queryExecution()),
                    () -> assertEquals(List.of(before), owner.batchGetQueryExecution(
                            request -> request.queryExecutionIds(id)).queryExecutions()));
        }
    }

    @Test
    void genuineDefaultLegacyAndAnExplicitlyScopedPrefixShapedLogicalIdRemainReadable() throws Exception {
        AccountAwareStorageBackend<QueryExecution> scoped = factory.create("athena", "queries.json",
                new TypeReference<Map<String, QueryExecution>>() {});
        StorageBackend<String, QueryExecution> raw = rawBackend(scoped);
        String account = regionResolver.getDefaultAccountId();
        String id = UUID.randomUUID().toString();
        String logical = account + "/" + UUID.randomUUID();
        QueryExecution legacy = legacy(id);
        QueryExecution explicit = legacy(logical);
        raw.put(id, legacy);
        raw.put(account + "/" + logical, explicit);
        try (AthenaClient owner = client(account, Region.US_EAST_1)) {
            assertEquals(id, owner.getQueryExecution(request -> request.queryExecutionId(id)).queryExecution().queryExecutionId());
            assertEquals(logical, owner.getQueryExecution(
                    request -> request.queryExecutionId(logical)).queryExecution().queryExecutionId());
            assertSame(explicit, raw.get(account + "/" + logical).orElseThrow());
            assertTrue(raw.get(logical).isEmpty());
        } finally {
            raw.delete(id);
            raw.delete(account + "/" + id);
            raw.delete(account + "/" + logical);
        }
    }

    private static QueryExecution legacy(String id) {
        QueryExecution query = new QueryExecution(id, "SELECT 43", "primary", null, null);
        query.getStatus().setState(QueryExecutionState.SUCCEEDED);
        return query;
    }

    private static AthenaClient client(String account, Region region) {
        return AwsTestClients.create(AthenaClient.builder(), account, region);
    }

    private static String start(AthenaClient client, String query) {
        return client.startQueryExecution(request -> request.queryString(query)).queryExecutionId();
    }

    private static Object invoke(AthenaClient client, String operation, String id) {
        return switch (operation) {
            case "get" -> client.getQueryExecution(request -> request.queryExecutionId(id));
            case "results" -> client.getQueryResults(request -> request.queryExecutionId(id));
            case "stop" -> client.stopQueryExecution(request -> request.queryExecutionId(id));
            default -> throw new IllegalArgumentException(operation);
        };
    }

    /** The native API cannot manufacture pre-account storage; only owned fixture rows use this seam. */
    @SuppressWarnings("unchecked")
    private static <T> StorageBackend<String, T> rawBackend(AccountAwareStorageBackend<T> backend)
            throws ReflectiveOperationException {
        Field delegate = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        return (StorageBackend<String, T>) delegate.get(backend);
    }
}

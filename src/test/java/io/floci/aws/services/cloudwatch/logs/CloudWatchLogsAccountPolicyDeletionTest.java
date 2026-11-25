package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.cloudwatch.logs.model.AccountPolicy;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudWatchLogsAccountPolicyDeletionTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String NAME = "original";
    private static final String TRANSFORMER = "TRANSFORMER_POLICY";
    private static final String FIELD_INDEX = "FIELD_INDEX_POLICY";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);
    private FaultStore policies;
    private CloudWatchLogsCrossAccountService service;
    private CloudWatchLogsService logs;
    private CloudWatchLogsHandler handler;

    @BeforeEach
    void setUp() {
        policies = new FaultStore();
        service = new CloudWatchLogsCrossAccountService(
                new InMemoryStorage<>(), policies, resolver, MAPPER,
                mock(CloudWatchLogsDestinationTargetService.class));
        logs = new CloudWatchLogsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10_000, resolver);
        handler = new CloudWatchLogsHandler(
                logs, service, mock(CloudWatchLogsMetricFilterService.class), MAPPER,
                mock(CloudWatchLogsSubscriptionAdmissionService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DATA_PROTECTION_POLICY", "SUBSCRIPTION_FILTER_POLICY", "TRANSFORMER_POLICY",
            "FIELD_INDEX_POLICY", "METRIC_EXTRACTION_POLICY"})
    void deletesOnlyTheStoredTypeAndReleasesItsExistingQuota(String type) {
        // These are storage controls for existing service-accepted documents; SDK setup is separate.
        String siblingType = TRANSFORMER.equals(type) ? FIELD_INDEX : TRANSFORMER;
        service.putAccountPolicy(NAME, document(type), type, null, "ALL", REGION);
        AccountPolicy sibling = service.putAccountPolicy(
                NAME, document(siblingType), siblingType, null, "ALL", REGION);
        JsonNode siblingBefore = MAPPER.valueToTree(sibling);

        service.deleteAccountPolicy(NAME, type, REGION);

        missing(() -> service.describeAccountPolicies(type, NAME, REGION));
        assertEquals(siblingBefore,
                MAPPER.valueToTree(service.describeAccountPolicies(siblingType, NAME, REGION).getFirst()));
        service.putAccountPolicy("replacement", document(type), type, null, "ALL", REGION);
        assertEquals(List.of("replacement"), service.describeAccountPolicies(type, null, REGION).stream()
                .map(AccountPolicy::getPolicyName).toList());
        assertEquals(siblingBefore,
                MAPPER.valueToTree(service.describeAccountPolicies(siblingType, NAME, REGION).getFirst()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "\"true\"", "0", "1", "[]", "{}"})
    void unsupportedOrMalformedDryRunPreservesTheCompletePolicy(String json) throws Exception {
        putOriginal();
        JsonNode before = snapshot();
        ObjectNode request = request();
        request.set("dryRun", MAPPER.readTree(json));

        invalid(() -> handler.handle("DeleteAccountPolicy", request, REGION));

        assertEquals(before, snapshot());
    }

    @Test
    void nullDryRunUsesTheOrdinaryDelete() {
        putOriginal();
        ObjectNode request = request().putNull("dryRun");

        try (Response response = handler.handle("DeleteAccountPolicy", request, REGION)) {
            assertEquals(200, response.getStatus());
            assertEquals(MAPPER.createObjectNode(), response.getEntity());
        }

        missing(() -> service.describeAccountPolicies(TRANSFORMER, NAME, REGION));
    }

    @ParameterizedTest
    @MethodSource("malformedIdentityFields")
    void nonStringIdentityFieldsCannotDeleteAnExistingPolicy(String field, String json) throws Exception {
        putOriginal();
        JsonNode before = snapshot();
        ObjectNode request = request();
        request.set(field, MAPPER.readTree(json));

        invalid(() -> handler.handle("DeleteAccountPolicy", request, REGION));

        assertEquals(before, snapshot());
    }

    private static Stream<Arguments> malformedIdentityFields() {
        return Stream.of("policyName", "policyType").flatMap(field ->
                Stream.of("null", "123", "true", "{}", "[]").map(json -> Arguments.of(field, json)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"policyName", "policyType"})
    void missingIdentityFieldsPreserveTheCompletePolicy(String field) {
        putOriginal();
        JsonNode before = snapshot();
        ObjectNode request = request();
        request.remove(field);

        invalid(() -> handler.handle("DeleteAccountPolicy", request, REGION));

        assertEquals(before, snapshot());
    }

    @Test
    void unsupportedRetentionTypeDoesNotDeleteAnotherPolicyWithTheSameName() {
        putOriginal();
        JsonNode before = snapshot();

        invalid(() -> handler.handle("DeleteAccountPolicy",
                request().put("policyType", "RETENTION_POLICY"), REGION));

        assertEquals(before, snapshot());
    }

    @Test
    void deletionSurvivesPersistentReloadAndKeepsTheSiblingDefinition(@TempDir Path directory) {
        TypeReference<Map<String, AccountPolicy>> type = new TypeReference<>() {};
        Path file = directory.resolve("account-policies.json");
        PersistentStorage<String, AccountPolicy> firstStore = new PersistentStorage<>(file, type);
        CloudWatchLogsCrossAccountService first = new CloudWatchLogsCrossAccountService(
                new InMemoryStorage<>(), firstStore, resolver, MAPPER,
                mock(CloudWatchLogsDestinationTargetService.class));
        first.putAccountPolicy(NAME, document(TRANSFORMER), TRANSFORMER, null, "ALL", REGION);
        JsonNode siblingBefore = MAPPER.valueToTree(
                first.putAccountPolicy(NAME, document(FIELD_INDEX), FIELD_INDEX, null, "ALL", REGION));
        first.deleteAccountPolicy(NAME, TRANSFORMER, REGION);

        PersistentStorage<String, AccountPolicy> restoredStore = new PersistentStorage<>(file, type);
        restoredStore.load();
        CloudWatchLogsCrossAccountService restored = new CloudWatchLogsCrossAccountService(
                new InMemoryStorage<>(), restoredStore, resolver, MAPPER,
                mock(CloudWatchLogsDestinationTargetService.class));

        missing(() -> restored.describeAccountPolicies(TRANSFORMER, NAME, REGION));
        assertEquals(siblingBefore,
                MAPPER.valueToTree(restored.describeAccountPolicies(FIELD_INDEX, NAME, REGION).getFirst()));
        restored.putAccountPolicy("replacement", document(TRANSFORMER), TRANSFORMER, null, "ALL", REGION);
        assertEquals("replacement", restored.describeAccountPolicies(TRANSFORMER, null, REGION)
                .getFirst().getPolicyName());
    }

    @Test
    void aStorageRefusalPreservesTheCompleteDefinitionAndAnExactRetrySucceeds() {
        putOriginal();
        JsonNode before = snapshot();
        policies.refuseDelete = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.deleteAccountPolicy(NAME, TRANSFORMER, REGION));

        assertEquals("controlled storage refusal", failure.getMessage());
        assertEquals(before, snapshot());
        policies.refuseDelete = false;
        service.deleteAccountPolicy(NAME, TRANSFORMER, REGION);
        missing(() -> service.describeAccountPolicies(TRANSFORMER, NAME, REGION));
    }

    @Test
    void accountPolicyDeletionPreservesGroupRetentionAndResourcePolicies() {
        putOriginal();
        logs.createLogGroup("retained-group", null, Map.of("owner", "group"), REGION);
        logs.putRetentionPolicy("retained-group", 7, REGION);
        ObjectNode resourcePolicy = MAPPER.createObjectNode().put("policyName", NAME)
                .put("policyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        handler.handle("PutResourcePolicy", resourcePolicy, REGION).close();
        JsonNode groupsBefore = MAPPER.valueToTree(logs.describeLogGroups(null, REGION));
        JsonNode resourceBefore = resourcePolicies();

        handler.handle("DeleteAccountPolicy", request(), REGION).close();

        missing(() -> service.describeAccountPolicies(TRANSFORMER, NAME, REGION));
        assertEquals(groupsBefore, MAPPER.valueToTree(logs.describeLogGroups(null, REGION)));
        assertEquals(resourceBefore, resourcePolicies());
    }


    @ParameterizedTest
    @ValueSource(strings = {ACCOUNT, "815670000022"})
    void accountScopedDeleteNeverClaimsOrRemovesAnUnprefixedLegacyPolicy(String callerAccount) {
        putOriginal();
        AccountPolicy legacy = service.describeAccountPolicies(TRANSFORMER, NAME, REGION).getFirst();
        JsonNode legacyBefore = MAPPER.valueToTree(legacy);
        String key = REGION + "::" + TRANSFORMER + "::" + NAME;
        InMemoryStorage<String, AccountPolicy> raw = new InMemoryStorage<>();
        raw.put(key, legacy);
        CloudWatchLogsCrossAccountService scopedService = scopedService(raw, callerAccount);

        missing(() -> scopedService.describeAccountPolicies(TRANSFORMER, NAME, REGION));
        missing(() -> scopedService.deleteAccountPolicy(NAME, TRANSFORMER, REGION));

        assertEquals(Set.of(key), raw.keys());
        assertEquals(legacyBefore, MAPPER.valueToTree(raw.get(key).orElseThrow()));
        AccountPolicy scoped = MAPPER.convertValue(legacy, AccountPolicy.class);
        scoped.setAccountId(callerAccount);
        scoped.setPolicyDocument("[{\"parseJSON\":{\"destination\":\"owned\"}}]");
        scoped.setLastUpdatedTime(legacy.getLastUpdatedTime() + 1);
        raw.put(callerAccount + "/" + key, scoped);
        assertEquals(MAPPER.valueToTree(scoped), MAPPER.valueToTree(
                scopedService.describeAccountPolicies(TRANSFORMER, NAME, REGION).getFirst()));

        scopedService.deleteAccountPolicy(NAME, TRANSFORMER, REGION);

        assertEquals(Set.of(key), raw.keys());
        assertEquals(legacyBefore, MAPPER.valueToTree(raw.get(key).orElseThrow()));
        missing(() -> scopedService.describeAccountPolicies(TRANSFORMER, NAME, REGION));
        missing(() -> scopedService.deleteAccountPolicy(NAME, TRANSFORMER, REGION));
        assertEquals(Set.of(key), raw.keys());
        assertEquals(legacyBefore, MAPPER.valueToTree(raw.get(key).orElseThrow()));
    }

    @SuppressWarnings("unchecked")
    private CloudWatchLogsCrossAccountService scopedService(
            InMemoryStorage<String, AccountPolicy> raw, String callerAccount) {
        RequestContext context = new RequestContext();
        context.setAccountId(callerAccount);
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        AccountAwareStorageBackend<AccountPolicy> scoped =
                new AccountAwareStorageBackend<>(raw, contexts, ACCOUNT);
        return new CloudWatchLogsCrossAccountService(
                new InMemoryStorage<>(), scoped, resolver, MAPPER,
                mock(CloudWatchLogsDestinationTargetService.class));
    }

    private JsonNode resourcePolicies() {
        try (Response response = handler.handle("DescribeResourcePolicies", MAPPER.createObjectNode(), REGION)) {
            assertEquals(200, response.getStatus());
            return ((JsonNode) response.getEntity()).deepCopy();
        }
    }

    private void putOriginal() {
        service.putAccountPolicy(NAME, document(TRANSFORMER), TRANSFORMER, null, "ALL", REGION);
    }

    private JsonNode snapshot() {
        List<AccountPolicy> values = service.describeAccountPolicies(TRANSFORMER, NAME, REGION);
        assertEquals(1, values.size());
        assertTrue(values.getFirst().getLastUpdatedTime() > 0);
        return MAPPER.valueToTree(values.getFirst());
    }

    private static ObjectNode request() {
        return MAPPER.createObjectNode().put("policyName", NAME).put("policyType", TRANSFORMER);
    }

    private static String document(String type) {
        if (TRANSFORMER.equals(type)) {
            return "[{\"parseJSON\":{\"destination\":\"parsed\"}}]";
        }
        return FIELD_INDEX.equals(type) ? "{\"Fields\":[\"requestId\"]}" : "{}";
    }

    private static void invalid(Runnable action) {
        AwsException failure = assertThrows(AwsException.class, action::run);
        assertEquals("InvalidParameterException", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
    }

    private static void missing(Runnable action) {
        AwsException failure = assertThrows(AwsException.class, action::run);
        assertEquals("ResourceNotFoundException", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
    }

    private static final class FaultStore extends InMemoryStorage<String, AccountPolicy> {
        private boolean refuseDelete;

        @Override
        public void delete(String key) {
            if (refuseDelete) {
                throw new IllegalStateException("controlled storage refusal");
            }
            super.delete(key);
        }
    }
}

package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.model.AccountPolicy;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeAccountPoliciesResponse;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.AccessKeyMetadata;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.ListAccessKeysResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real SDK deletion controls; BEFORE cleanup uses an explicitly scoped storage seam. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@Timeout(120)
class CloudWatchLogsDeleteAccountPolicySdkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "815670000021";
    private static final String OTHER_ACCOUNT = "815670000022";
    private static final Region REGION = Region.US_EAST_1;
    private static final Region OTHER_REGION = Region.US_WEST_2;
    private static final String TRANSFORMER = "TRANSFORMER_POLICY";
    private static final String FIELD_INDEX = "FIELD_INDEX_POLICY";

    @Inject
    StorageFactory storageFactory;

    @Test
    void putDescribeDeleteAndRecreateUseTheSamePolicyIdentity(TestInfo info) {
        try (Owned owned = new Owned(info, "")) {
            owned.put(ACCOUNT, REGION, TRANSFORMER, transformer("first"));
            Object before = policy(owned.root, owned.name, TRANSFORMER);
            assertEquals(before, policy(owned.root, owned.name, TRANSFORMER));
            assertEquals(200, owned.root.deleteAccountPolicy(request -> request.policyName(owned.name)
                    .policyType(TRANSFORMER)).sdkHttpResponse().statusCode());
            missing(() -> policy(owned.root, owned.name, TRANSFORMER));
            owned.put(ACCOUNT, REGION, TRANSFORMER, transformer("second"));
            DescribeAccountPoliciesResponse recreated = owned.root.describeAccountPolicies(
                    request -> request.policyName(owned.name).policyType(TRANSFORMER));
            assertEquals(1, recreated.accountPolicies().size());
            assertEquals(owned.name, recreated.accountPolicies().getFirst().policyName());
            assertEquals(ACCOUNT, recreated.accountPolicies().getFirst().accountId());
            assertEquals("ALL", recreated.accountPolicies().getFirst().scopeAsString());
            assertEquals(transformer("second"), recreated.accountPolicies().getFirst().policyDocument());
            assertNotNull(recreated.accountPolicies().getFirst().lastUpdatedTime());
            owned.root.deleteAccountPolicy(request -> request.policyName(owned.name).policyType(TRANSFORMER));
            missing(() -> policy(owned.root, owned.name, TRANSFORMER));
        }
    }

    @Test
    void missingWrongTypeAndInvalidTypeLeaveTheCompletePolicyUnchanged(TestInfo info) {
        try (Owned owned = new Owned(info, "")) {
            owned.put(ACCOUNT, REGION, TRANSFORMER, transformer("preserved"));
            Object before = policy(owned.root, owned.name, TRANSFORMER);
            missing(() -> owned.root.deleteAccountPolicy(
                    request -> request.policyName(owned.name).policyType(FIELD_INDEX)));
            assertEquals(before, policy(owned.root, owned.name, TRANSFORMER));
            missing(() -> owned.root.deleteAccountPolicy(
                    request -> request.policyName(owned.name + "-missing").policyType(TRANSFORMER)));
            assertEquals(before, policy(owned.root, owned.name, TRANSFORMER));
            invalid(() -> owned.root.deleteAccountPolicy(
                    request -> request.policyName(owned.name).policyType("UNKNOWN_POLICY")));
            assertEquals(before, policy(owned.root, owned.name, TRANSFORMER));
        }
    }

    @Test
    void deletingOneAccountAndRegionPreservesBothOtherPolicies(TestInfo info) {
        try (Owned owned = new Owned(info, "")) {
            owned.put(ACCOUNT, REGION, TRANSFORMER, transformer("source"));
            owned.put(OTHER_ACCOUNT, REGION, TRANSFORMER, transformer("otherAccount"));
            owned.put(ACCOUNT, OTHER_REGION, TRANSFORMER, transformer("otherRegion"));
            Object foreign = policy(owned.foreign, owned.name, TRANSFORMER);
            Object regional = policy(owned.regional, owned.name, TRANSFORMER);
            owned.root.deleteAccountPolicy(request -> request.policyName(owned.name).policyType(TRANSFORMER));
            missing(() -> policy(owned.root, owned.name, TRANSFORMER));
            assertEquals(foreign, policy(owned.foreign, owned.name, TRANSFORMER));
            assertEquals(regional, policy(owned.regional, owned.name, TRANSFORMER));
        }
    }

    @Test
    void fieldIndexPolicyDeletionLeavesTheTransformerPolicyUnchanged(TestInfo info) {
        try (Owned owned = new Owned(info, "")) {
            owned.put(ACCOUNT, REGION, TRANSFORMER, transformer("sibling"));
            owned.put(ACCOUNT, REGION, FIELD_INDEX, "{\"Fields\":[\"requestId\"]}");
            Object before = policy(owned.root, owned.name, TRANSFORMER);
            assertEquals(1, owned.root.describeAccountPolicies(request -> request.policyName(owned.name)
                    .policyType(FIELD_INDEX)).accountPolicies().size());
            owned.root.deleteAccountPolicy(request -> request.policyName(owned.name).policyType(FIELD_INDEX));
            missing(() -> policy(owned.root, owned.name, FIELD_INDEX));
            assertEquals(before, policy(owned.root, owned.name, TRANSFORMER));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "DATA_PROTECTION_POLICY, DeleteDataProtectionPolicy",
            "SUBSCRIPTION_FILTER_POLICY, DeleteSubscriptionFilter",
            "TRANSFORMER_POLICY, DeleteTransformer",
            "FIELD_INDEX_POLICY, DeleteIndexPolicy"})
    void deletionRequiresBothThePrimaryAndDocumentedTypePermission(
            String type, String typeAction, TestInfo info) {
        try (Owned owned = new Owned(info, type)) {
            owned.put(ACCOUNT, REGION, TRANSFORMER, transformer("authorization"));
            Object before = policy(owned.root, owned.name, TRANSFORMER);
            owned.createUser();
            owned.permissions(allow("DeleteAccountPolicy"));
            denied(() -> owned.user.deleteAccountPolicy(request -> request.policyName(owned.name).policyType(type)),
                    "logs:" + typeAction);
            assertEquals(before, policy(owned.root, owned.name, TRANSFORMER));
            owned.permissions(allow(typeAction));
            denied(() -> owned.user.deleteAccountPolicy(request -> request.policyName(owned.name).policyType(type)),
                    "logs:DeleteAccountPolicy");
            assertEquals(before, policy(owned.root, owned.name, TRANSFORMER));
            owned.permissions(allow("DeleteAccountPolicy") + "," + allow(typeAction) + """
                    ,{"Effect":"Deny","Action":"logs:%s","Resource":"*",
                    "Condition":{"StringEquals":{"aws:RequestedRegion":"%s"}}}
                    """.formatted(typeAction, REGION.id()));
            denied(() -> owned.user.deleteAccountPolicy(request -> request.policyName(owned.name).policyType(type)),
                    "logs:" + typeAction);
            assertEquals(before, policy(owned.root, owned.name, TRANSFORMER));
            owned.permissions(allow("DeleteAccountPolicy") + "," + allow(typeAction));
            if (TRANSFORMER.equals(type)) {
                owned.user.deleteAccountPolicy(request -> request.policyName(owned.name).policyType(type));
                missing(() -> policy(owned.root, owned.name, TRANSFORMER));
            } else {
                // Authorization succeeds before the exact typed lookup reports this policy type absent.
                missing(() -> owned.user.deleteAccountPolicy(
                        request -> request.policyName(owned.name).policyType(type)));
                assertEquals(before, policy(owned.root, owned.name, TRANSFORMER));
            }
        }
    }

    @Test
    void metricExtractionMissingPolicyNeedsNoUndocumentedSecondaryPermission(TestInfo info) {
        try (Owned owned = new Owned(info, "")) {
            owned.put(ACCOUNT, REGION, TRANSFORMER, transformer("metricSibling"));
            Object before = policy(owned.root, owned.name, TRANSFORMER);
            owned.createUser();
            owned.permissions(allow("DeleteAccountPolicy"));
            missing(() -> owned.user.deleteAccountPolicy(request -> request.policyName(owned.name)
                    .policyType("METRIC_EXTRACTION_POLICY")));
            assertEquals(before, policy(owned.root, owned.name, TRANSFORMER));
        }
    }

    private static String transformer(String destination) {
        return "[{\"parseJSON\":{\"destination\":\"" + destination + "\"}}]";
    }

    private static String allow(String action) {
        return "{\"Effect\":\"Allow\",\"Action\":\"logs:" + action + "\",\"Resource\":\"*\"}";
    }

    private static Object policy(CloudWatchLogsClient client, String name, String type) {
        DescribeAccountPoliciesResponse response = client.describeAccountPolicies(
                request -> request.policyName(name).policyType(type));
        assertEquals(1, response.accountPolicies().size());
        return WireReceipt.model(response.accountPolicies().getFirst());
    }

    private record OwnedPolicy(String account, Region region, String type) {}

    private final class Owned implements AutoCloseable {
        private final String name = "logs-delete-policy-" + UUID.randomUUID();
        private final WireReceipt wire = new WireReceipt();
        private final String caseName;
        private final CloudWatchLogsClient root = client(CloudWatchLogsClient.builder(), ACCOUNT, REGION);
        private final CloudWatchLogsClient foreign = client(CloudWatchLogsClient.builder(), OTHER_ACCOUNT, REGION);
        private final CloudWatchLogsClient regional = client(CloudWatchLogsClient.builder(), ACCOUNT, OTHER_REGION);
        private final IamClient iam = client(IamClient.builder(), ACCOUNT, REGION);
        private final Set<OwnedPolicy> policies = new LinkedHashSet<>();
        private CloudWatchLogsClient user;
        private boolean userAttempted;
        private boolean userPolicyAttempted;
        private boolean accessKeyAttempted;

        private Owned(TestInfo info, String parameter) {
            caseName = info.getTestMethod().orElseThrow().getName()
                    + (parameter.isEmpty() ? "" : ":" + parameter);
        }

        private <B extends AwsClientBuilder<B, T> & AwsSyncClientBuilder<B, T>, T> T client(
                B builder, String account, Region region) {
            configure(builder);
            return AwsTestClients.create(builder, account, region);
        }

        private <B extends AwsClientBuilder<B, T> & AwsSyncClientBuilder<B, T>, T> void configure(B builder) {
            builder.overrideConfiguration(config -> config.addExecutionInterceptor(wire)
                    .retryPolicy(RetryPolicy.none()).apiCallTimeout(Duration.ofSeconds(15))
                    .apiCallAttemptTimeout(Duration.ofSeconds(10)));
        }

        private CloudWatchLogsClient scoped(String account, Region region) {
            if (OTHER_ACCOUNT.equals(account) && REGION.equals(region)) {
                return foreign;
            }
            assertEquals(ACCOUNT, account);
            if (OTHER_REGION.equals(region)) {
                return regional;
            }
            assertEquals(REGION, region);
            return root;
        }

        private void put(String account, Region region, String type, String document) {
            CloudWatchLogsClient client = scoped(account, region);
            OwnedPolicy owned = new OwnedPolicy(account, region, type);
            if (!policies.contains(owned)) {
                missing(() -> policy(client, name, type));
                policies.add(owned);
            }
            client.putAccountPolicy(request -> request.policyName(name).policyType(type)
                    .policyDocument(document).scope("ALL"));
        }

        private void createUser() {
            missingIam(assertThrows(IamException.class, () -> iam.getUser(request -> request.userName(name))));
            userAttempted = true;
            iam.createUser(request -> request.userName(name));
            accessKeyAttempted = true;
            AccessKey key = iam.createAccessKey(request -> request.userName(name)).accessKey();
            configureUser(key);
        }

        private void configureUser(AccessKey key) {
            user = AwsTestClients.create(CloudWatchLogsClient.builder().overrideConfiguration(
                    config -> config.addExecutionInterceptor(wire).retryPolicy(RetryPolicy.none())
                            .apiCallTimeout(Duration.ofSeconds(15))
                            .apiCallAttemptTimeout(Duration.ofSeconds(10))),
                    key.accessKeyId(), key.secretAccessKey(), REGION);
        }

        private void permissions(String statements) {
            userPolicyAttempted = true;
            iam.putUserPolicy(request -> request.userName(name).policyName("delete-policy")
                    .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}"));
        }

        private List<AccessKeyMetadata> accessKeys() {
            List<AccessKeyMetadata> values = new ArrayList<>();
            Set<String> markers = new LinkedHashSet<>();
            String marker = null;
            do {
                String next = marker;
                ListAccessKeysResponse page = iam.listAccessKeys(
                        request -> request.userName(name).marker(next).maxItems(100));
                values.addAll(page.accessKeyMetadata());
                if (Boolean.TRUE.equals(page.isTruncated())) {
                    marker = page.marker();
                    assertNotNull(marker);
                    assertTrue(!marker.isBlank() && markers.add(marker));
                } else {
                    marker = null;
                }
                assertTrue(markers.size() < 10);
            } while (marker != null);
            assertTrue(values.stream().allMatch(value -> name.equals(value.userName())));
            return values;
        }

        @Override
        public void close() {
            IllegalStateException failures = new IllegalStateException("Owned account-policy cleanup failed");
            for (OwnedPolicy owned : policies) {
                cleanup(() -> {
                    // BEFORE lacks DeleteAccountPolicy. This exact registered key is a local cleanup seam.
                    StorageBackend<String, AccountPolicy> backend = storageFactory.create("cloudwatchlogs",
                            "cwlogs-account-policies.json", new TypeReference<Map<String, AccountPolicy>>() {});
                    RequestScopes.runAs(owned.account(), owned.region().id(), () -> backend.delete(
                            owned.region().id() + "::" + owned.type() + "::" + name));
                    missing(() -> policy(scoped(owned.account(), owned.region()), name, owned.type()));
                }, failures);
            }
            if (userPolicyAttempted) {
                cleanup(() -> {
                    try {
                        iam.deleteUserPolicy(request -> request.userName(name).policyName("delete-policy"));
                    } catch (IamException expected) {
                        missingIam(expected);
                    }
                    missingIam(assertThrows(IamException.class, () -> iam.getUserPolicy(
                            request -> request.userName(name).policyName("delete-policy"))));
                }, failures);
            }
            if (accessKeyAttempted) {
                cleanup(() -> {
                    for (AccessKeyMetadata key : accessKeys()) {
                        cleanup(() -> iam.deleteAccessKey(
                                request -> request.userName(name).accessKeyId(key.accessKeyId())), failures);
                    }
                    assertTrue(accessKeys().isEmpty());
                }, failures);
            }
            if (userAttempted) {
                cleanup(() -> {
                    try {
                        iam.deleteUser(request -> request.userName(name));
                    } catch (IamException expected) {
                        missingIam(expected);
                    }
                    missingIam(assertThrows(IamException.class, () -> iam.getUser(request -> request.userName(name))));
                }, failures);
            }
            if (user != null) {
                cleanup(user::close, failures);
            }
            cleanup(root::close, failures);
            cleanup(foreign::close, failures);
            cleanup(regional::close, failures);
            cleanup(iam::close, failures);
            cleanup(() -> wire.save(caseName, name, failures.getSuppressed().length == 0), failures);
            if (failures.getSuppressed().length > 0) {
                throw failures;
            }
        }
    }

    private static void cleanup(Runnable action, IllegalStateException failures) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (failure != failures) {
                failures.addSuppressed(failure);
            }
        }
    }

    private static void missing(Runnable action) {
        CloudWatchLogsException failure = assertThrows(CloudWatchLogsException.class, action::run);
        assertEquals(400, failure.statusCode());
        assertEquals("ResourceNotFoundException", failure.awsErrorDetails().errorCode());
    }

    private static void invalid(Runnable action) {
        CloudWatchLogsException failure = assertThrows(CloudWatchLogsException.class, action::run);
        assertEquals(400, failure.statusCode());
        assertEquals("InvalidParameterException", failure.awsErrorDetails().errorCode());
    }

    private static void denied(Runnable action, String requiredAction) {
        CloudWatchLogsException failure = assertThrows(CloudWatchLogsException.class, action::run);
        assertEquals(400, failure.statusCode());
        assertEquals("AccessDeniedException", failure.awsErrorDetails().errorCode());
        assertTrue(failure.awsErrorDetails().errorMessage().contains(requiredAction));
    }

    private static void missingIam(IamException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("NoSuchEntity", failure.awsErrorDetails().errorCode());
    }

    private static final class WireReceipt implements ExecutionInterceptor {
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("logs-delete-account-policy-wire-id");
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            assertTrue(calls.size() < 3000);
            attributes.putAttribute(ID, calls.size());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", calls.size());
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("requestModel", model(context.request()));
            Map<String, List<String>> headers = new LinkedHashMap<>(context.httpRequest().headers());
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("authorization")
                    || name.equalsIgnoreCase("x-amz-security-token"));
            row.put("requestHeaders", headers);
            row.put("requestBodyAvailable", context.requestBody().isPresent());
            try (InputStream stream = context.requestBody().isPresent()
                    ? context.requestBody().orElseThrow().contentStreamProvider().newStream()
                    : InputStream.nullInputStream()) {
                row.put("requestBase64", encode(stream));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            calls.add(row);
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            Map<String, Object> row = calls.get(attributes.getAttribute(ID));
            row.put("status", context.httpResponse().statusCode());
            row.put("responseHeaders", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElseGet(InputStream::nullInputStream)) {
                String encoded = encode(stream);
                row.put("responseBase64", encoded);
                return Optional.of(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
            calls.get(attributes.getAttribute(ID)).put("responseModel", model(context.response()));
        }

        @Override
        public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
            Integer id = attributes.getAttribute(ID);
            if (id == null) {
                return;
            }
            Map<String, Object> row = calls.get(id);
            row.put("exceptionType", context.exception().getClass().getName());
            if (context.exception() instanceof AwsServiceException failure) {
                row.put("errorCode", failure.awsErrorDetails().errorCode());
                row.put("errorMessage", failure.awsErrorDetails().errorMessage());
                row.put("errorStatus", failure.statusCode());
            }
        }

        private static Object model(Object value) {
            if (value instanceof SdkPojo pojo) {
                Map<String, Object> fields = new LinkedHashMap<>();
                pojo.sdkFields().forEach(field -> {
                    Object member = field.getValueOrDefault(pojo);
                    if (member != null) {
                        fields.put(field.memberName(), model(member));
                    }
                });
                return fields;
            }
            if (value instanceof List<?> list) {
                return list.stream().map(WireReceipt::model).toList();
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> fields = new LinkedHashMap<>();
                map.forEach((key, member) -> fields.put(key.toString(), model(member)));
                return fields;
            }
            if (value instanceof SdkBytes bytes) {
                return Base64.getEncoder().encodeToString(bytes.asByteArray());
            }
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                return value;
            }
            return value.toString();
        }

        private static String encode(InputStream stream) throws IOException {
            byte[] bytes = stream.readNBytes(1_048_577);
            assertTrue(bytes.length <= 1_048_576);
            return Base64.getEncoder().encodeToString(bytes);
        }

        private void save(String caseName, String marker, boolean cleanupComplete) {
            try {
                Path directory = Path.of(System.getProperty("audit.logs.deleteAccountPolicy.directory",
                        "target/logs-delete-account-policy-wire"));
                Files.createDirectories(directory);
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(),
                        Map.of("case", caseName, "marker", marker, "cleanupComplete", cleanupComplete, "calls", calls));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "every attempted transmission has a complete captured response");
        }
    }
}

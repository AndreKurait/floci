package io.floci.aws.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.model.LambdaRuntimeManagementConfig;
import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.LambdaException;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaRuntimeManagementIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;
    @Inject
    StorageFactory storage;
    @Inject
    ObjectMapper mapper;
    private String user;
    private AccessKey key;
    private LambdaClient caller;

    @BeforeEach
    void createRestrictedCaller() {
        user = "RuntimeIam" + UUID.randomUUID().toString().substring(0, 10);
        iam.createUser(user, "/");
        key = iam.createAccessKey(user);
        caller = LambdaClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(key.getAccessKeyId(), key.getSecretAccessKey()))).build();
        policy("lambda:ListFunctions", "*", null);
    }

    @AfterEach
    void deleteRestrictedCaller() {
        caller.close();
        iam.deleteUserPolicy(user, "RuntimeSettings");
        iam.deleteAccessKey(user, key.getAccessKeyId());
        iam.deleteUser(user);
    }

    @Test
    void bothActionsRequireTheirOwnGrantAndTheExactFunction() throws Exception {
        try (LambdaRuntimeManagementIntegrationTest.Owned one =
                     new LambdaRuntimeManagementIntegrationTest.Owned(endpoint, "us-east-1");
             LambdaRuntimeManagementIntegrationTest.Owned two =
                     new LambdaRuntimeManagementIntegrationTest.Owned(endpoint, "us-east-1")) {
            policy("lambda:GetRuntimeManagementConfig", one.arn, null);
            assertEquals("Auto", caller.getRuntimeManagementConfig(r -> r.functionName(one.name)).updateRuntimeOnAsString());
            denied(() -> caller.putRuntimeManagementConfig(r -> r.functionName(one.name).updateRuntimeOn("FunctionUpdate")));
            denied(() -> caller.getRuntimeManagementConfig(r -> r.functionName(two.name)));
            policy("lambda:PutRuntimeManagementConfig", one.arn, null);
            caller.putRuntimeManagementConfig(r -> r.functionName(one.name).updateRuntimeOn("FunctionUpdate"));
            denied(() -> caller.getRuntimeManagementConfig(r -> r.functionName(one.name)));
            assertEquals("FunctionUpdate", one.get(null).updateRuntimeOnAsString());
            assertEquals("Auto", two.get(null).updateRuntimeOnAsString());
        }
    }

    @Test
    void qualifiedPolicyMatchesTheExactRequestedVersionAndAlias() throws Exception {
        try (LambdaRuntimeManagementIntegrationTest.Owned one =
                     new LambdaRuntimeManagementIntegrationTest.Owned(endpoint, "us-east-1")) {
            String version = one.lambda.publishVersion(r -> r.functionName(one.name)).version();
            one.lambda.createAlias(r -> r.functionName(one.name).name("live").functionVersion(version));
            policy("lambda:GetRuntimeManagementConfig", one.arn + ":" + version, null);
            caller.getRuntimeManagementConfig(r -> r.functionName(one.name).qualifier(version));
            denied(() -> caller.getRuntimeManagementConfig(r -> r.functionName(one.name)));
            denied(() -> caller.getRuntimeManagementConfig(r -> r.functionName(one.name).qualifier("live")));
            policy("lambda:GetRuntimeManagementConfig", one.arn + ":live", null);
            assertEquals(one.arn + ":live", caller.getRuntimeManagementConfig(
                    r -> r.functionName(one.name).qualifier("live")).functionArn());
        }
    }

    @Test
    void liveResourceTagsIncludeEmptyValuesAndAreReevaluated() throws Exception {
        try (LambdaRuntimeManagementIntegrationTest.Owned one =
                     new LambdaRuntimeManagementIntegrationTest.Owned(endpoint, "us-east-1")) {
            one.lambda.tagResource(r -> r.resource(one.arn).tags(Map.of("team", "blue", "empty", "")));
            policy("lambda:*RuntimeManagementConfig", one.arn,
                    Map.of("StringEquals", Map.of("aws:ResourceTag/team", "blue", "aws:ResourceTag/empty", "")));
            caller.putRuntimeManagementConfig(r -> r.functionName(one.name).updateRuntimeOn("FunctionUpdate"));
            caller.getRuntimeManagementConfig(r -> r.functionName(one.name));
            one.lambda.tagResource(r -> r.resource(one.arn).tags(Map.of("team", "red")));
            denied(() -> caller.getRuntimeManagementConfig(r -> r.functionName(one.name)));
            denied(() -> caller.putRuntimeManagementConfig(r -> r.functionName(one.name).updateRuntimeOn("Auto")));
            assertEquals("FunctionUpdate", one.get(null).updateRuntimeOnAsString());
        }
    }

    @Test
    void spoofedTargetCannotReplaceTheMatchedNativeAction() throws Exception {
        try (LambdaRuntimeManagementIntegrationTest.Owned one =
                     new LambdaRuntimeManagementIntegrationTest.Owned(endpoint, "us-east-1")) {
            policy("lambda:ListFunctions", "*", null);
            denied(() -> caller.getRuntimeManagementConfig(r -> r.functionName(one.name)
                    .overrideConfiguration(c -> c.putHeader("X-Amz-Target", "Lambda.ListFunctions"))));
            denied(() -> caller.putRuntimeManagementConfig(r -> r.functionName(one.name).updateRuntimeOn("FunctionUpdate")
                    .overrideConfiguration(c -> c.putHeader("X-Amz-Target", "Lambda.ListFunctions"))));
            assertEquals("Auto", one.get(null).updateRuntimeOnAsString());
        }
    }

    @Test
    void deniedNativeLegacyCallsLeaveRawKeysObjectAndSettingsUnchanged() throws Exception {
        try (LambdaRuntimeManagementIntegrationTest.Owned one =
                     new LambdaRuntimeManagementIntegrationTest.Owned(endpoint, "us-east-1")) {
            AccountAwareStorageBackend<LambdaFunction> functions = storage.create("lambda", "lambda-functions.json",
                    new TypeReference<>() {});
            StorageBackend<String, LambdaFunction> raw = raw(functions);
            String logical = "lambda::us-east-1::" + one.name + "::$LATEST";
            String scoped = "000000000000/" + logical;
            LambdaFunction legacy = raw.get(scoped).orElseThrow();
            legacy.setRuntimeManagementGeneration(null);
            raw.put(logical, legacy);
            raw.delete(scoped);
            Set<String> keys = Set.copyOf(raw.keys());
            byte[] before = mapper.writeValueAsBytes(legacy);
            AccountAwareStorageBackend<LambdaRuntimeManagementConfig> settings =
                    storage.create("lambda", "lambda-runtime-management.json", new TypeReference<>() {});
            Map<String, LambdaRuntimeManagementConfig> oldSettings = settings.scanAllAccountsWithRawKeys();
            try {
                policy("lambda:*RuntimeManagementConfig", one.arn,
                        Map.of("StringEquals", Map.of("aws:ResourceTag/team", "denied")));
                denied(() -> caller.getRuntimeManagementConfig(r -> r.functionName(one.name)));
                denied(() -> caller.putRuntimeManagementConfig(r -> r.functionName(one.name).updateRuntimeOn("FunctionUpdate")));
                assertEquals(keys, raw.keys());
                assertSame(legacy, raw.get(logical).orElseThrow());
                assertArrayEquals(before, mapper.writeValueAsBytes(legacy));
                assertEquals(oldSettings, settings.scanAllAccountsWithRawKeys());
            } finally {
                raw.put(scoped, legacy);
                raw.delete(logical);
            }
        }
    }

    private void policy(String action, String resource, Map<String, Object> condition) {
        try {
            Map<String, Object> statement = new LinkedHashMap<>();
            statement.put("Effect", "Allow");
            statement.put("Action", action);
            statement.put("Resource", resource);
            if (condition != null) {
                statement.put("Condition", condition);
            }
            iam.putUserPolicy(user, "RuntimeSettings", mapper.writeValueAsString(
                    Map.of("Version", "2012-10-17", "Statement", List.of(statement))));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void denied(Runnable request) {
        LambdaException failure = assertThrows(LambdaException.class, request::run);
        assertEquals(403, failure.statusCode());
        assertEquals("AccessDeniedException", failure.awsErrorDetails().errorCode());
    }

    @SuppressWarnings("unchecked")
    private static <T> StorageBackend<String, T> raw(AccountAwareStorageBackend<T> scoped) throws Exception {
        Field delegate = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        return (StorageBackend<String, T>) delegate.get(scoped);
    }
}

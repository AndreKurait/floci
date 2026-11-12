package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.RestApi;
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
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ApiGatewayException;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ApiGatewayDocumentationVersionTagIamIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String FOREIGN = "271828182845";

    @Inject
    StorageFactory storage;
    @Inject
    EmulatorConfig config;
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"POST,false", "POST,true", "LIST,false", "LIST,true", "GET,false", "GET,true",
            "PATCH,false", "PATCH,true", "DELETE,false", "DELETE,true"})
    void nativeActionsUseLiveInheritedTagsAndNeverMigrateWhileDenied(String operation, boolean legacy) throws Exception {
        String account = config.defaultAccountId();
        String authorization = auth(account, REGION, "iam");
        String user = "docv-tags-" + UUID.randomUUID();
        UserCredentials key = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, user);
        try (ApiGatewayClient root = AwsTestClients.create(ApiGatewayClient.builder(), account, Region.of(REGION),
                     Apache5HttpClient.builder());
             ApiGatewayClient reader = ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                     .httpClientBuilder(Apache5HttpClient.builder()).credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()))).build();
             ApiGatewayClient foreign = AwsTestClients.create(ApiGatewayClient.builder(), FOREIGN, Region.of(REGION),
                     Apache5HttpClient.builder())) {
            String api = root.createRestApi(r -> r.name("documentation-tag-" + UUID.randomUUID()).tags(Map.of("team", "owned"))).id();
            try {
                root.createDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1").description("Original"));
                root.createStage(r -> r.restApiId(api).stageName("live").deploymentId("fixture-deployment"));
                AccountAwareStorageBackend<RestApi> parents = storage.create("apigateway", "apigateway-apis.json",
                        new TypeReference<>() {});
                AccountAwareStorageBackend<JsonNode> stages = storage.create("apigateway", ApiGatewayStageDocumentationStore.FILE,
                        new TypeReference<>() {});
                String parentKey = REGION + "::" + api;
                String stageKey = parentKey + "::live";
                if (legacy) {
                    moveToLegacy(parents, account, parentKey);
                    moveToLegacy(stages, account, stageKey);
                }
                String collection = "arn:aws:apigateway:" + REGION + "::/restapis/" + api + "/documentation/versions";
                String target = "POST".equals(operation) || "LIST".equals(operation) ? collection : collection + "/v1";
                String action = "apigateway:" + ("LIST".equals(operation) ? "GET" : operation);
                Map<String, JsonNode> before = records(parents, stages, api);
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> invoke(reader, operation, api, "v2")).statusCode());
                assertEquals(before, records(parents, stages, api));
                policy(authorization, user, statement("Allow", action, target, "different"));
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> invoke(reader, operation, api, "v2")).statusCode());
                assertEquals(before, records(parents, stages, api));
                policy(authorization, user, statement("Allow", action, target, "owned"));
                invoke(reader, operation, api, "v2");
                assertEffect(root, operation, api, "v2");
                // A read-only allowed request also leaves the genuine default-account legacy rows intact.
                if ("GET".equals(operation) || "LIST".equals(operation)) {
                    assertEquals(before, records(parents, stages, api));
                }
                if ("DELETE".equals(operation)) {
                    root.createDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1").description("Restored"));
                }
                assertEquals(404, assertThrows(ApiGatewayException.class, () -> invoke(foreign, operation, api, "v3")).statusCode());
                root.tagResource(r -> r.resourceArn("arn:aws:apigateway:" + REGION + "::/restapis/" + api)
                        .tags(Map.of("team", "changed")));
                Map<String, JsonNode> changed = records(parents, stages, api);
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> invoke(reader, operation, api, "v3")).statusCode());
                assertEquals(changed, records(parents, stages, api));
                root.tagResource(r -> r.resourceArn("arn:aws:apigateway:" + REGION + "::/restapis/" + api)
                        .tags(Map.of("team", "owned")));
                policy(authorization, user, statement("Allow", "apigateway:*", "*", null)
                        + "," + statement("Deny", action, target, null));
                Map<String, JsonNode> restored = records(parents, stages, api);
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> invoke(reader, operation, api, "v3")).statusCode());
                assertEquals(restored, records(parents, stages, api));
                policy(authorization, user, statement("Allow", action, target + "/wrong", "owned"));
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> invoke(reader, operation, api, "v3")).statusCode());
                assertEquals(restored, records(parents, stages, api));
                policy(authorization, user, statement("Allow", action, target, "owned"));
                invoke(reader, operation, api, "v3");
                assertEffect(root, operation, api, "v3");
            } finally {
                root.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    private static void invoke(ApiGatewayClient client, String operation, String api, String next) {
        switch (operation) {
            case "POST" -> client.createDocumentationVersion(r -> r.restApiId(api).documentationVersion(next).stageName("live")
                    .overrideConfiguration(c -> c.putHeader("X-Amz-Account-Id", FOREIGN)));
            case "LIST" -> client.getDocumentationVersions(r -> r.restApiId(api)
                    .overrideConfiguration(c -> c.putHeader("X-Amz-Account-Id", FOREIGN)));
            case "GET" -> client.getDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1")
                    .overrideConfiguration(c -> c.putHeader("X-Amz-Account-Id", FOREIGN)));
            case "PATCH" -> client.updateDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1")
                    .patchOperations(PatchOperation.builder().op("replace").path("/description").value(next).build())
                    .overrideConfiguration(c -> c.putHeader("X-Amz-Account-Id", FOREIGN)));
            default -> client.deleteDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1")
                    .overrideConfiguration(c -> c.putHeader("X-Amz-Account-Id", FOREIGN)));
        }
    }

    private static void assertEffect(ApiGatewayClient root, String operation, String api, String next) {
        switch (operation) {
            case "POST" -> {
                assertEquals(next, root.getDocumentationVersion(r -> r.restApiId(api).documentationVersion(next)).version());
                assertEquals(next, root.getStage(r -> r.restApiId(api).stageName("live")).documentationVersion());
            }
            case "PATCH" -> assertEquals(next, root.getDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1")).description());
            case "DELETE" -> assertEquals(404, assertThrows(ApiGatewayException.class,
                    () -> root.getDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1"))).statusCode());
            default -> assertTrue(root.getDocumentationVersions(r -> r.restApiId(api)).items().stream()
                    .anyMatch(version -> "v1".equals(version.version())));
        }
    }

    private static <T> void moveToLegacy(AccountAwareStorageBackend<T> store, String account, String key) throws Exception {
        StorageBackend<String, T> raw = raw(store);
        raw.put(key, raw.get(account + "/" + key).orElseThrow());
        raw.delete(account + "/" + key);
    }

    private Map<String, JsonNode> records(AccountAwareStorageBackend<RestApi> parents,
                                                AccountAwareStorageBackend<JsonNode> stages, String api) throws Exception {
        Map<String, JsonNode> result = new TreeMap<>();
        if (!"memory".equals(config.storage().mode())) {
            // Establish a durable boundary before each comparison, including hybrid's asynchronous writes.
            storage.flushAll();
            Path directory = Path.of(config.storage().persistentPath());
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().startsWith("apigateway-")).toList()) {
                    result.put("file:" + directory.relativize(path), JSON.valueToTree(
                            Base64.getEncoder().encodeToString(Files.readAllBytes(path))));
                }
            }
        }
        StorageBackend<String, RestApi> rawParents = raw(parents);
        for (String key : rawParents.keys()) {
            if (key.endsWith("::" + api)) {
                result.put("parent:" + key, JSON.valueToTree(rawParents.get(key).orElseThrow()));
            }
        }
        StorageBackend<String, JsonNode> rawStages = raw(stages);
        for (String key : rawStages.keys()) {
            JsonNode value = rawStages.get(key).orElseThrow();
            if (key.contains("::" + api + "::") || api.equals(value.path("apiId").asText())) {
                result.put("stage-or-version:" + key, value.deepCopy());
            }
        }
        return result;
    }

    private static <T> StorageBackend<String, T> raw(AccountAwareStorageBackend<T> store) throws Exception {
        Field field = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        StorageBackend<String, T> backend = (StorageBackend<String, T>) field.get(store);
        return backend;
    }

    private void policy(String authorization, String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "documentation-inherited-tags",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static String statement(String effect, String action, String resource, String team) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\""
                + (team == null ? "" : ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"" + team + "\"}}") + "}";
    }
}

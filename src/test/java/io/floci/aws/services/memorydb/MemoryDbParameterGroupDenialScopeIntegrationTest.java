package io.floci.aws.services.memorydb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.memorydb.MemoryDbClient;
import software.amazon.awssdk.services.memorydb.model.DescribeParametersResponse;
import software.amazon.awssdk.services.memorydb.model.Parameter;
import software.amazon.awssdk.services.memorydb.model.ParameterGroup;
import software.amazon.awssdk.services.memorydb.model.ParameterGroupNotFoundException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signed SDK response-scope controls, including state after refused group mutations. */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class MemoryDbParameterGroupDenialScopeIntegrationTest {
    private static final AwsCredentials ROOT = AwsBasicCredentials.create("test", "test");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<String> ownedNames = new ArrayList<>();

    @TestHTTPResource
    URI endpoint;

    @AfterEach
    void removeOwnedGroups() {
        try (MemoryDbClient root = client(ROOT)) {
            List<Executable> cleanup = new ArrayList<>();
            for (String name : ownedNames) {
                cleanup.add(() -> {
                    try {
                        root.deleteParameterGroup(request -> request.parameterGroupName(name));
                    } catch (ParameterGroupNotFoundException missing) {
                        assertEquals(400, missing.statusCode());
                    }
                    assertTrue(snapshot(root, name).isEmpty(), name);
                });
            }
            assertAll(cleanup);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateParameterGroup", "DeleteParameterGroup", "DescribeParameterGroups",
            "DescribeParameters", "UpdateParameterGroup", "ResetParameterGroup",
            "ListTags", "TagResource", "UntagResource"})
    void groupDenialsKeepTheWholeObservableStateAndUse403(String operation) throws Exception {
        String name = owned();
        String target = operation.equals("CreateParameterGroup") ? "*" : groupArn(name);
        try (MemoryDbClient root = client(ROOT)) {
            if (!operation.equals("CreateParameterGroup")) {
                root.createParameterGroup(request -> request.parameterGroupName(name).family("memorydb_redis6")
                        .description("owned denial scope control")
                        .tags(tag -> tag.key("mode").value("blue")));
                root.updateParameterGroup(request -> request.parameterGroupName(name).parameterNameValues(
                        parameter -> parameter.parameterName("timeout").parameterValue("20")));
            }
            Optional<Snapshot> before = snapshot(root, name);
            try (SignedUser user = user(policy("memorydb:" + operation, target));
                 MemoryDbClient caller = client(user.credentials())) {
                Call response = capture(() -> invoke(caller, operation, name, groupArn(name)));
                Optional<Snapshot> after = snapshot(root, name);
                observe(operation, response, before, after);
                assertAll(() -> assertDenied(response, 403, operation),
                        () -> assertEquals(before, after));
            }
        }
    }

    @Test
    void secondaryTagDenialKeepsTheOriginalCreateRequestResponseScope() throws Exception {
        String name = owned();
        try (MemoryDbClient root = client(ROOT)) {
            Optional<Snapshot> before = snapshot(root, name);
            assertTrue(before.isEmpty());
            try (SignedUser user = user(policy("memorydb:TagResource", groupArn(name)));
                 MemoryDbClient caller = client(user.credentials())) {
                Call response = capture(() -> caller.createParameterGroup(request -> request
                        .parameterGroupName(name).family("memorydb_redis6")
                        .tags(tag -> tag.key("mode").value("blue"))));
                Optional<Snapshot> after = snapshot(root, name);
                observe("CreateParameterGroup secondary TagResource", response, before, after);
                assertAll(() -> assertDenied(response, 403, "TagResource"),
                        () -> assertEquals(before, after));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DescribeClusters", "DescribeUsers", "DescribeACLs"})
    void legacyDescribeDenialsKeepTheirInherited400Response(String operation) throws Exception {
        try (SignedUser user = user(policy("memorydb:" + operation, "*"));
             MemoryDbClient caller = client(user.credentials())) {
            Call response = capture(() -> switch (operation) {
                case "DescribeClusters" -> caller.describeClusters(request -> {});
                case "DescribeUsers" -> caller.describeUsers(request -> {});
                case "DescribeACLs" -> caller.describeACLs(request -> {});
                default -> throw new IllegalArgumentException(operation);
            });
            observeLegacy(operation, response);
            assertDenied(response, 400, operation);
        }
    }

    @ParameterizedTest
    @CsvSource({"cluster,ListTags", "cluster,TagResource", "cluster,UntagResource",
            "user,ListTags", "user,TagResource", "user,UntagResource",
            "acl,ListTags", "acl,TagResource", "acl,UntagResource"})
    void legacyTagTargetDenialsKeepTheirInherited400Response(String kind, String operation) throws Exception {
        String target = "arn:aws:memorydb:us-east-1:000000000000:" + kind + "/denial-scope-existing-shape";
        try (SignedUser user = user(policy("memorydb:" + operation, "*"));
             MemoryDbClient caller = client(user.credentials())) {
            Call response = capture(() -> invoke(caller, operation, "", target));
            observeLegacy(kind + " " + operation, response);
            assertDenied(response, 400, operation);
        }
    }

    private static SdkResponse invoke(MemoryDbClient client, String operation, String name, String arn) {
        return switch (operation) {
            case "CreateParameterGroup" -> client.createParameterGroup(request ->
                    request.parameterGroupName(name).family("memorydb_redis6"));
            case "DeleteParameterGroup" -> client.deleteParameterGroup(request -> request.parameterGroupName(name));
            case "DescribeParameterGroups" -> client.describeParameterGroups(request -> request.parameterGroupName(name));
            case "DescribeParameters" -> client.describeParameters(request -> request.parameterGroupName(name));
            case "UpdateParameterGroup" -> client.updateParameterGroup(request -> request.parameterGroupName(name)
                    .parameterNameValues(value -> value.parameterName("timeout").parameterValue("30")));
            case "ResetParameterGroup" -> client.resetParameterGroup(request ->
                    request.parameterGroupName(name).allParameters(true));
            case "ListTags" -> client.listTags(request -> request.resourceArn(arn));
            case "TagResource" -> client.tagResource(request -> request.resourceArn(arn)
                    .tags(tag -> tag.key("mode").value("red")));
            case "UntagResource" -> client.untagResource(request -> request.resourceArn(arn).tagKeys("mode"));
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private static Optional<Snapshot> snapshot(MemoryDbClient root, String name) {
        ParameterGroup group;
        try {
            List<ParameterGroup> groups = root.describeParameterGroups(request -> request.parameterGroupName(name))
                    .parameterGroups();
            assertEquals(1, groups.size());
            group = groups.getFirst();
        } catch (ParameterGroupNotFoundException missing) {
            assertEquals(400, missing.statusCode());
            return Optional.empty();
        }
        DescribeParametersResponse parameters = root.describeParameters(
                request -> request.parameterGroupName(name).maxResults(100));
        assertTrue(parameters.nextToken() == null || parameters.nextToken().isEmpty());
        Map<String, Parameter> wholeParameters = new TreeMap<>();
        parameters.parameters().forEach(parameter ->
                assertNull(wholeParameters.put(parameter.name(), parameter)));
        assertFalse(wholeParameters.isEmpty());
        Map<String, String> tags = new TreeMap<>();
        root.listTags(request -> request.resourceArn(group.arn())).tagList().forEach(tag ->
                assertNull(tags.put(tag.key(), tag.value())));
        return Optional.of(new Snapshot(group, Map.copyOf(wholeParameters), Map.copyOf(tags)));
    }

    private static Call capture(Supplier<? extends SdkResponse> request) {
        try {
            SdkResponse response = request.get();
            return new Call(response.sdkHttpResponse().statusCode(), "", response.toString());
        } catch (AwsServiceException denied) {
            return new Call(denied.statusCode(), denied.awsErrorDetails().errorCode(),
                    denied.awsErrorDetails().errorMessage());
        }
    }

    private static void assertDenied(Call response, int status, String operation) {
        assertAll(() -> assertEquals(status, response.status()),
                () -> assertEquals("AccessDeniedException", response.errorCode()),
                () -> assertTrue(response.detail().contains("memorydb:" + operation), response.detail()));
    }

    private static void observe(String operation, Call response,
                                Optional<Snapshot> before, Optional<Snapshot> after) throws Exception {
        System.out.println("MEMORYDB_DENIAL_SCOPE_OBSERVATION " + MAPPER.writeValueAsString(
                Map.of("operation", operation, "status", response.status(), "errorCode", response.errorCode(),
                        "detail", response.detail(), "before", before.toString(), "after", after.toString())));
    }

    private static void observeLegacy(String operation, Call response) throws Exception {
        System.out.println("MEMORYDB_DENIAL_SCOPE_OBSERVATION " + MAPPER.writeValueAsString(
                Map.of("operation", operation, "status", response.status(), "errorCode", response.errorCode(),
                        "detail", response.detail(), "scope", "Response status only; no legacy state read")));
    }

    private MemoryDbClient client(AwsCredentials credentials) {
        return MemoryDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(value -> value.retryPolicy(RetryPolicy.none())
                        .apiCallTimeout(Duration.ofSeconds(10))).build();
    }

    private SignedUser user(String policy) throws Exception {
        SignedUser user = new SignedUser("memory-denial-" + UUID.randomUUID().toString().substring(0, 8));
        iam("CreateUser", Map.of("UserName", user.name)).then().statusCode(200);
        try {
            Response keys = iam("CreateAccessKey", Map.of("UserName", user.name));
            keys.then().statusCode(200);
            user.accessKey = keys.xmlPath().getString(
                    "CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
            user.secretKey = keys.xmlPath().getString(
                    "CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey");
            iam("PutUserPolicy", Map.of("UserName", user.name, "PolicyName", "access", "PolicyDocument", policy))
                    .then().statusCode(200);
            user.policyCreated = true;
            return user;
        } catch (Exception | AssertionError failure) {
            try {
                user.close();
            } catch (Exception | AssertionError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private Response iam(String action, Map<String, String> parameters) {
        String body = "Action=" + action + "&Version=2010-05-08&" + parameters.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return given().config(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 2000).setParam("http.socket.timeout", 5000)))
                .filter(AwsRequestSigner.signedAs("test", "test", "iam").inRegion("us-east-1"))
                .contentType("application/x-www-form-urlencoded").body(body).post(endpoint);
    }

    private final class SignedUser implements AutoCloseable {
        private final String name;
        private String accessKey;
        private String secretKey;
        private boolean policyCreated;

        private SignedUser(String name) {
            this.name = name;
        }

        private AwsCredentials credentials() {
            return AwsBasicCredentials.create(accessKey, secretKey);
        }

        @Override
        public void close() {
            List<Executable> cleanup = new ArrayList<>();
            if (accessKey != null) {
                cleanup.add(() -> iam("DeleteAccessKey", Map.of("UserName", name, "AccessKeyId", accessKey))
                        .then().statusCode(200));
            }
            if (policyCreated) {
                cleanup.add(() -> iam("DeleteUserPolicy", Map.of("UserName", name, "PolicyName", "access"))
                        .then().statusCode(200));
            }
            cleanup.add(() -> iam("DeleteUser", Map.of("UserName", name)).then().statusCode(200));
            assertAll(cleanup);
        }
    }

    private static String policy(String deniedAction, String deniedResource) throws Exception {
        return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(
                Map.of("Effect", "Allow", "Action", "memorydb:*", "Resource", "*"),
                Map.of("Effect", "Deny", "Action", deniedAction, "Resource", deniedResource))));
    }

    private String owned() {
        String name = "denial-" + UUID.randomUUID().toString().replace("-", "");
        ownedNames.add(name);
        return name;
    }

    private static String groupArn(String name) {
        return "arn:aws:memorydb:us-east-1:000000000000:parametergroup/" + name;
    }

    private record Snapshot(ParameterGroup group, Map<String, Parameter> parameters, Map<String, String> tags) {}
    private record Call(int status, String errorCode, String detail) {}
}

package io.floci.aws.services.memorydb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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
import java.util.Locale;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual request-partition controls for a supported local unknown-region configuration. */
@QuarkusTest
@TestProfile(MemoryDbParameterGroupUnknownRegionIamIntegrationTest.ChinaUnknownRegionProfile.class)
class MemoryDbParameterGroupUnknownRegionIamIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AwsCredentials ROOT = AwsBasicCredentials.create("test", "test");
    private static final String UNKNOWN = "future-memorydb-audit-1";
    private static final String PUBLISHED = "cn-north-1";
    private static final String ACCOUNT = "000000000000";
    private final List<OwnedGroup> ownedGroups = new ArrayList<>();

    @TestHTTPResource("/")
    URI endpoint;

    @AfterEach
    void removeEveryOwnedGroupIncludingUnexpectedAdmissions() {
        assertAll("owned group cleanup", ownedGroups.stream().map(group -> (Executable) () -> {
            try (MemoryDbClient root = client(ROOT, group.region())) {
                try {
                    root.deleteParameterGroup(request -> request.parameterGroupName(group.name()));
                } catch (ParameterGroupNotFoundException missing) {
                    assertEquals(400, missing.statusCode());
                }
                assertTrue(observeGroup(root, group.name()).isEmpty());
            }
        }));
    }

    @Test
    void actualUnknownRegionArnGrantsTheNamedReadAndCommercialSiblingDoesNot() throws Exception {
        String name = owned(UNKNOWN);
        try (MemoryDbClient root = client(ROOT, UNKNOWN)) {
            createRoot(root, UNKNOWN, name);
            try (SignedUser actual = user(policy(allow("memorydb:DescribeParameters", arn("aws-cn", UNKNOWN, name))));
                 SignedUser sibling = user(policy(allow("memorydb:DescribeParameters", arn("aws", UNKNOWN, name))));
                 MemoryDbClient allowed = client(actual.credentials(), UNKNOWN);
                 MemoryDbClient refused = client(sibling.credentials(), UNKNOWN)) {
                Call actualCall = capture("actual-unknown-read", () -> allowed.describeParameters(
                        request -> request.parameterGroupName(name)));
                Call siblingCall = capture("sibling-unknown-read", () -> refused.describeParameters(
                        request -> request.parameterGroupName(name)));
                assertAll(() -> assertEquals(200, actualCall.status()),
                        () -> assertDenied(siblingCall));
            }
        }
    }

    @Test
    void commercialSiblingUpdateGrantCannotChangeAnyStoredGroupState() throws Exception {
        String name = owned(UNKNOWN);
        try (MemoryDbClient root = client(ROOT, UNKNOWN)) {
            createRoot(root, UNKNOWN, name);
            Snapshot before = snapshot(root, name);
            try (SignedUser sibling = user(policy(allow("memorydb:UpdateParameterGroup", arn("aws", UNKNOWN, name))));
                 MemoryDbClient caller = client(sibling.credentials(), UNKNOWN)) {
                Call response = capture("sibling-unknown-update", () -> caller.updateParameterGroup(
                        request -> request.parameterGroupName(name).parameterNameValues(
                                value -> value.parameterName("timeout").parameterValue("20"))));
                Snapshot after = snapshot(root, name);
                record("update-before", Map.of("snapshot", before.toString()));
                record("update-after", Map.of("snapshot", after.toString()));
                assertAll(() -> assertDenied(response), () -> assertEquals(before, after));
            }
        }
    }

    @Test
    void mixedCaseTaggedCreateUsesTheActualConfiguredPartitionArn() throws Exception {
        String name = owned(UNKNOWN);
        String actualArn = arn("aws-cn", UNKNOWN, name);
        try (MemoryDbClient root = client(ROOT, UNKNOWN);
             SignedUser actual = user(policy(allow("memorydb:CreateParameterGroup", "*"),
                     allow("memorydb:TagResource", actualArn)));
             MemoryDbClient caller = client(actual.credentials(), UNKNOWN)) {
            Call response = capture("actual-unknown-tagged-create", () -> caller.createParameterGroup(
                    request -> request.parameterGroupName(name.toUpperCase(Locale.ROOT)).family("memorydb_redis6")
                            .tags(tag -> tag.key("mode").value("blue"))));
            Optional<ParameterGroup> observed = observeGroup(root, name);
            Map<String, String> tags = observed.isEmpty() ? Map.of() : tags(root, actualArn);
            record("actual-tagged-create-readback", Map.of("group", observed.toString(), "tags", tags));
            assertAll(() -> assertEquals(200, response.status()),
                    () -> assertEquals(actualArn, observed.map(ParameterGroup::arn).orElse(null)),
                    () -> assertEquals(name, observed.map(ParameterGroup::name).orElse(null)),
                    () -> assertEquals(Map.of("mode", "blue"), tags));
        }
    }

    @Test
    void commercialSiblingTagGrantCannotAdmitTaggedCreate() throws Exception {
        String name = owned(UNKNOWN);
        try (MemoryDbClient root = client(ROOT, UNKNOWN);
             SignedUser sibling = user(policy(allow("memorydb:CreateParameterGroup", "*"),
                     allow("memorydb:TagResource", arn("aws", UNKNOWN, name))));
             MemoryDbClient caller = client(sibling.credentials(), UNKNOWN)) {
            Call response = capture("sibling-unknown-tagged-create", () -> caller.createParameterGroup(
                    request -> request.parameterGroupName(name.toUpperCase(Locale.ROOT)).family("memorydb_redis6")
                            .tags(tag -> tag.key("mode").value("blue"))));
            Optional<ParameterGroup> observed = observeGroup(root, name);
            record("sibling-tagged-create-readback", Map.of("group", observed.toString()));
            assertAll(() -> assertDenied(response), () -> assertTrue(observed.isEmpty()));
        }
    }

    @Test
    void aNamedCreateGrantStillRequiresThePrimaryWildcardResource() throws Exception {
        String name = owned(UNKNOWN);
        try (MemoryDbClient root = client(ROOT, UNKNOWN);
             SignedUser named = user(policy(allow("memorydb:CreateParameterGroup", arn("aws-cn", UNKNOWN, name))));
             MemoryDbClient caller = client(named.credentials(), UNKNOWN)) {
            Call response = capture("named-unknown-primary-create", () -> caller.createParameterGroup(
                    request -> request.parameterGroupName(name).family("memorydb_redis6")));
            Optional<ParameterGroup> observed = observeGroup(root, name);
            record("named-create-readback", Map.of("group", observed.toString()));
            assertAll(() -> assertDenied(response), () -> assertTrue(observed.isEmpty()));
        }
    }

    @Test
    void publishedRegionControlUsesTheCanonicalArnAndCapturesDenialEnvelope() throws Exception {
        String name = owned(PUBLISHED);
        try (MemoryDbClient root = client(ROOT, PUBLISHED)) {
            createRoot(root, PUBLISHED, name);
            try (SignedUser actual = user(policy(allow("memorydb:DescribeParameters", arn("aws-cn", PUBLISHED, name))));
                 SignedUser sibling = user(policy(allow("memorydb:DescribeParameters", arn("aws", PUBLISHED, name))));
                 MemoryDbClient allowed = client(actual.credentials(), PUBLISHED);
                 MemoryDbClient refused = client(sibling.credentials(), PUBLISHED)) {
                Call actualCall = capture("actual-published-read", () -> allowed.describeParameters(
                        request -> request.parameterGroupName(name)));
                Call siblingCall = capture("sibling-published-read", () -> refused.describeParameters(
                        request -> request.parameterGroupName(name)));
                String body = MAPPER.writeValueAsString(Map.of("ParameterGroupName", name));
                Response http = given().config(httpConfig())
                        .filter(AwsRequestSigner.signedAs(sibling.accessKey, sibling.secretKey, "memorydb")
                                .inRegion(PUBLISHED))
                        .contentType("application/x-amz-json-1.1")
                        .header("X-Amz-Target", "AmazonMemoryDB.DescribeParameters").body(body).post(endpoint);
                String rawBody = http.asString();
                record("published-denial-http", Map.of("status", http.statusCode(), "body", rawBody));
                String error = MAPPER.readTree(rawBody).path("__type").asText();
                assertAll(() -> assertEquals(200, actualCall.status()),
                        () -> assertDenied(siblingCall),
                        () -> assertEquals(siblingCall.status(), http.statusCode()),
                        () -> assertEquals("AccessDeniedException", error));
            }
        }
    }

    private String owned(String region) {
        String name = "target-" + UUID.randomUUID().toString().replace("-", "");
        ownedGroups.add(new OwnedGroup(region, name));
        return name;
    }

    private static void createRoot(MemoryDbClient root, String region, String name) {
        ParameterGroup group = root.createParameterGroup(request -> request.parameterGroupName(name)
                .family("memorydb_redis6").description("owned canonical-target control")
                .tags(tag -> tag.key("mode").value("blue"))).parameterGroup();
        assertEquals(arn("aws-cn", region, name), group.arn());
        assertEquals(name, group.name());
    }

    private static Optional<ParameterGroup> observeGroup(MemoryDbClient root, String name) {
        try {
            List<ParameterGroup> groups = root.describeParameterGroups(
                    request -> request.parameterGroupName(name)).parameterGroups();
            assertEquals(1, groups.size());
            return Optional.of(groups.getFirst());
        } catch (ParameterGroupNotFoundException missing) {
            assertEquals(400, missing.statusCode());
            return Optional.empty();
        }
    }

    private static Snapshot snapshot(MemoryDbClient root, String name) {
        ParameterGroup group = observeGroup(root, name).orElseThrow();
        Map<String, Parameter> parameters = new TreeMap<>();
        DescribeParametersResponse response =
                root.describeParameters(request -> request.parameterGroupName(name).maxResults(100));
        assertNull(response.nextToken());
        assertFalse(response.parameters().isEmpty());
        response.parameters().forEach(parameter -> {
            assertNull(parameters.put(parameter.name(), parameter));
        });
        return new Snapshot(group, Map.copyOf(parameters), tags(root, group.arn()));
    }

    private static Map<String, String> tags(MemoryDbClient root, String arn) {
        Map<String, String> result = new TreeMap<>();
        root.listTags(request -> request.resourceArn(arn)).tagList().forEach(
                tag -> assertNull(result.put(tag.key(), tag.value())));
        return Map.copyOf(result);
    }

    private MemoryDbClient client(AwsCredentials credentials, String region) {
        return MemoryDbClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(value -> value.retryPolicy(RetryPolicy.none())
                        .apiCallTimeout(Duration.ofSeconds(10))).build();
    }

    private static Call capture(String label, Supplier<? extends SdkResponse> operation) {
        Call result;
        try {
            SdkResponse response = operation.get();
            result = new Call(response.sdkHttpResponse().statusCode(), "", response.toString());
        } catch (AwsServiceException failure) {
            assertNotNull(failure.awsErrorDetails());
            result = new Call(failure.statusCode(), failure.awsErrorDetails().errorCode(),
                    failure.awsErrorDetails().errorMessage());
        }
        record(label, Map.of("status", result.status(), "errorCode", result.errorCode(), "detail", result.detail()));
        return result;
    }

    private static void assertDenied(Call response) {
        assertAll(() -> assertEquals(403, response.status()),
                () -> assertEquals("AccessDeniedException", response.errorCode()));
    }

    private SignedUser user(String policy) throws Exception {
        String name = "memory-target-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = iam("CreateUser", Map.of("UserName", name));
        created.then().statusCode(200);
        SignedUser user = new SignedUser(name);
        try {
            assertEquals("arn:aws-cn:iam::" + ACCOUNT + ":user/" + name,
                    created.xmlPath().getString("CreateUserResponse.CreateUserResult.User.Arn"));
            Response key = iam("CreateAccessKey", Map.of("UserName", name));
            key.then().statusCode(200);
            user.accessKey = key.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
            user.secretKey = key.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey");
            iam("PutUserPolicy", Map.of("UserName", name, "PolicyName", "target", "PolicyDocument", policy))
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
        return given().config(httpConfig())
                .filter(AwsRequestSigner.signedAs("test", "test", "iam").inRegion(PUBLISHED))
                .contentType("application/x-www-form-urlencoded").body(body).post(endpoint);
    }

    private static RestAssuredConfig httpConfig() {
        return RestAssuredConfig.config().encoderConfig(EncoderConfig.encoderConfig()
                .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT))
                .httpClient(HttpClientConfig.httpClientConfig()
                .setParam("http.connection.timeout", 2000).setParam("http.socket.timeout", 5000));
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
            assertAll("owned IAM cleanup",
                    () -> {
                        if (accessKey != null) {
                            iam("DeleteAccessKey", Map.of("UserName", name, "AccessKeyId", accessKey))
                                    .then().statusCode(200);
                        }
                    },
                    () -> {
                        if (policyCreated) {
                            iam("DeleteUserPolicy", Map.of("UserName", name, "PolicyName", "target"))
                                    .then().statusCode(200);
                        }
                    },
                    () -> iam("DeleteUser", Map.of("UserName", name)).then().statusCode(200));
        }
    }

    private static Map<String, String> allow(String action, String resource) {
        return Map.of("Effect", "Allow", "Action", action, "Resource", resource);
    }

    @SafeVarargs
    private static String policy(Map<String, String>... statements) throws Exception {
        return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(statements)));
    }

    private static String arn(String partition, String region, String name) {
        return "arn:" + partition + ":memorydb:" + region + ":" + ACCOUNT + ":parametergroup/" + name;
    }

    private static void record(String label, Map<String, ?> observation) {
        try {
            System.out.println("MEMORYDB_TARGET_OBSERVATION " + MAPPER.writeValueAsString(
                    Map.of("label", label, "observation", observation)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private record OwnedGroup(String region, String name) {}
    private record Snapshot(ParameterGroup group, Map<String, Parameter> parameters, Map<String, String> tags) {}
    private record Call(int status, String errorCode, String detail) {}

    public static final class ChinaUnknownRegionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.default-region", PUBLISHED,
                    "floci.partitions.id", "aws-cn",
                    "floci.partitions.allow-unknown-regions", "true",
                    "floci.partitions.strict", "false",
                    "floci.auth.validate-signatures", "true",
                    "floci.services.iam.enforcement-enabled", "true");
        }
    }
}

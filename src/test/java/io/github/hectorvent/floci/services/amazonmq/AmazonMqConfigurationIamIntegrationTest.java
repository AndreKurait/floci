package io.github.hectorvent.floci.services.amazonmq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amazonmq.model.ConfigurationState;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testing.SignedIamUsers.User;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.mq.MqClient;
import software.amazon.awssdk.services.mq.model.CreateConfigurationResponse;
import software.amazon.awssdk.services.mq.model.DescribeConfigurationResponse;
import software.amazon.awssdk.services.mq.model.EngineType;
import software.amazon.awssdk.services.mq.model.MqException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual JVM HTTP signatures and IAM evaluation, with only the ID source made deterministic. */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AmazonMqConfigurationIamIntegrationTest {

    private static final String PREFIX = "arn:aws:mq:us-east-1:000000000000:configuration:";
    private static final String CONFIG_ACTIONS = "[\"mq:DescribeConfiguration\",\"mq:UpdateConfiguration\","
            + "\"mq:DeleteConfiguration\",\"mq:DescribeConfigurationRevision\",\"mq:ListConfigurationRevisions\"]";
    private static final ObjectMapper JSON = new ObjectMapper();
    @Inject
    StorageFactory storageFactory;
    @Inject
    RegionResolver regionResolver;
    @TestHTTPResource("/")
    URI endpoint;
    private final List<Owned> owned = new ArrayList<>();
    private MqClient root;
    private String marker;
    private AtomicLong sequence;

    private record Owned(String id, String arn, String name, Map<String, String> tags) {}

    @BeforeEach
    void setup() {
        marker = "mq-iam-" + UUID.randomUUID().toString().substring(0, 8);
        sequence = new AtomicLong(UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
        AmazonMqConfigurationService deterministic = new AmazonMqConfigurationService(
                storageFactory.create("amazonmq", "amazonmq-configurations.json",
                        new TypeReference<Map<String, ConfigurationState>>() {}), regionResolver, Clock.systemUTC(),
                () -> "c-" + new UUID(0, sequence.incrementAndGet()));
        QuarkusMock.installMockForType(deterministic, AmazonMqConfigurationService.class);
        root = AwsTestClients.create(MqClient.builder(), "test", Region.US_EAST_1, endpoint);
    }

    @AfterEach
    void cleanup() {
        List<AssertionError> failures = new ArrayList<>();
        for (Owned row : owned.reversed()) {
            try {
                DescribeConfigurationResponse current;
                try {
                    current = root.describeConfiguration(request -> request.configurationId(row.id()));
                } catch (MqException missing) {
                    assertEquals(404, missing.statusCode());
                    continue;
                }
                assertEquals(row.arn(), current.arn());
                assertEquals(row.name(), current.name());
                assertEquals(row.tags(), current.tags());
                root.deleteConfiguration(request -> request.configurationId(row.id()));
                assertEquals(404, assertThrows(MqException.class,
                        () -> root.describeConfiguration(request -> request.configurationId(row.id()))).statusCode());
            } catch (RuntimeException | AssertionError failure) {
                failures.add(new AssertionError("Owned configuration cleanup failed: " + row.id(), failure));
            }
        }
        root.close();
        assertAll(failures.stream().map(failure -> () -> { throw failure; }));
    }

    @Test
    void allSevenActionsRequireIdentityGrantAndDenialsLeaveExistingStateUnchanged() throws Exception {
        Owned a = create(root, "existing", Map.of("owner", marker));
        DescribeConfigurationResponse before = describe(root, a);
        try (User user = SignedIamUsers.createUser(endpoint, "mq-none-"); MqClient caller = client(user)) {
            denied(() -> caller.createConfiguration(request -> request.name(marker + "-denied")
                    .engineType(EngineType.RABBITMQ).engineVersion("3.13")));
            denied(() -> caller.listConfigurations(request -> request.maxResults(100)));
            denied(() -> caller.describeConfiguration(request -> request.configurationId(a.id())));
            denied(() -> caller.updateConfiguration(request -> request.configurationId(a.id()).data("eA==")));
            denied(() -> caller.deleteConfiguration(request -> request.configurationId(a.id())));
            denied(() -> caller.describeConfigurationRevision(request -> request.configurationId(a.id())
                    .configurationRevision("1")));
            denied(() -> caller.listConfigurationRevisions(request -> request.configurationId(a.id())));
            assertTrue(before.equalsBySdkFields(describe(root, a)));
            assertNoName(marker + "-denied");
        }
    }

    @Test
    void blankAuthorizationPresignedCreateRequiresExactCandidateTagGrantBeforePublication() throws Exception {
        String name = marker + "-presigned";
        String body = JSON.writeValueAsString(Map.of("name", name, "engineType", "RABBITMQ",
                "engineVersion", "3.13", "tags", Map.of("owner", marker)));
        try (User user = user(allow("mq:CreateConfiguration", "*"))) {
            HttpResponse<String> denied = presigned(user, body);
            assertEquals(403, denied.statusCode());
            assertTrue(denied.body().contains("AccessDenied"));
            assertNoName(name);
            policy(user, allow("mq:CreateConfiguration", "*") + "," + allow("mq:CreateTags", PREFIX + "c-"
                    + new UUID(0, sequence.get() + 99)));
            assertEquals(403, presigned(user, body).statusCode());
            assertNoName(name);
            String exactArn = nextArn();
            policy(user, allow("mq:CreateConfiguration", "*") + "," + allow("mq:CreateTags", exactArn));
            HttpResponse<String> accepted = presigned(user, body);
            JsonNode receipt = JSON.readTree(accepted.body());
            if (accepted.statusCode() == 200 && receipt.path("id").isTextual() && receipt.path("arn").isTextual()) {
                owned.add(new Owned(receipt.path("id").asText(), receipt.path("arn").asText(), name,
                        Map.of("owner", marker)));
            }
            assertEquals(200, accepted.statusCode(), accepted.body());
            assertEquals(exactArn, receipt.path("arn").asText());
            assertEquals(Map.of("owner", marker), root.describeConfiguration(request -> request
                    .configurationId(receipt.path("id").asText())).tags());
        }
    }

    @Test
    void taglessCreateNeedsNoTagPermissionAndTaggedCreateCannotReplacePrimaryGrant() throws Exception {
        try (User user = user(allow("mq:CreateConfiguration", "*")); MqClient caller = client(user)) {
            Owned a = create(caller, "tagless", Map.of());
            assertTrue(describe(root, a).tags().isEmpty());
            policy(user, allow("mq:CreateTags", "*"));
            denied(() -> caller.createConfiguration(request -> request.name(marker + "-missing-primary")
                    .engineType(EngineType.RABBITMQ).engineVersion("3.13").tags(Map.of("owner", marker))));
            assertNoName(marker + "-missing-primary");
        }
    }

    @Test
    void requestTagsAndStoredResourceTagsUseOnlyActualConfigurationFacts() throws Exception {
        String conditions = "\"StringEquals\":{\"aws:RequestTag/owner\":\"" + marker + "\"},"
                + "\"ForAllValues:StringEquals\":{\"aws:TagKeys\":[\"owner\"]}";
        try (User user = user(allowWithCondition("\"mq:CreateConfiguration\"", "*", conditions)
                + "," + allowWithCondition("\"mq:CreateTags\"", "*", conditions)); MqClient caller = client(user)) {
            denied(() -> caller.createConfiguration(request -> request.name(marker + "-wrong-tag")
                    .engineType(EngineType.RABBITMQ).engineVersion("3.13").tags(Map.of("owner", "wrong"))));
            assertNoName(marker + "-wrong-tag");
            Owned a = create(caller, "conditioned", Map.of("owner", marker));
            policy(user, allowWithCondition(CONFIG_ACTIONS, a.arn(),
                    "\"StringEquals\":{\"aws:ResourceTag/owner\":\"" + marker + "\"}"));
            assertEquals(a.id(), describe(caller, a).id());
            caller.updateConfiguration(request -> request.configurationId(a.id()).data("eA==").description("second"));
            assertEquals(2, caller.listConfigurationRevisions(request -> request.configurationId(a.id())).revisions().size());
            assertEquals("eA==", caller.describeConfigurationRevision(request -> request.configurationId(a.id())
                    .configurationRevision("2")).data());
            DescribeConfigurationResponse before = describe(root, a);
            policy(user, allowWithCondition(CONFIG_ACTIONS, a.arn(),
                    "\"StringEquals\":{\"aws:ResourceTag/owner\":\"wrong\"}"));
            denied(() -> caller.updateConfiguration(request -> request.configurationId(a.id()).data("eQ==")));
            assertTrue(before.equalsBySdkFields(describe(root, a)));
        }
    }

    @Test
    void exactResourceGrantCannotReadMutateOrDeleteSibling() throws Exception {
        Owned a = create(root, "allowed", Map.of("owner", marker));
        Owned b = create(root, "sibling", Map.of("owner", marker));
        DescribeConfigurationResponse siblingBefore = describe(root, b);
        try (User user = user("{\"Effect\":\"Allow\",\"Action\":" + CONFIG_ACTIONS
                + ",\"Resource\":\"" + a.arn() + "\"}"); MqClient caller = client(user)) {
            assertEquals(a.id(), describe(caller, a).id());
            denied(() -> caller.describeConfiguration(request -> request.configurationId(b.id())));
            denied(() -> caller.updateConfiguration(request -> request.configurationId(b.id()).data("eA==")));
            denied(() -> caller.deleteConfiguration(request -> request.configurationId(b.id())));
            denied(() -> caller.describeConfigurationRevision(request -> request.configurationId(b.id())
                    .configurationRevision("1")));
            denied(() -> caller.listConfigurationRevisions(request -> request.configurationId(b.id())));
            caller.deleteConfiguration(request -> request.configurationId(a.id()));
            assertTrue(siblingBefore.equalsBySdkFields(describe(root, b)));
        }
    }

    @Test
    void explicitTagDenyOverridesAllowAndNoCandidateIsPublished() throws Exception {
        try (User user = user(allow("mq:*", "*") + ","
                + "{\"Effect\":\"Deny\",\"Action\":\"mq:CreateTags\",\"Resource\":\"*\"}");
                MqClient caller = client(user)) {
            denied(() -> caller.createConfiguration(request -> request.name(marker + "-explicit")
                    .engineType(EngineType.RABBITMQ).engineVersion("3.13").tags(Map.of("owner", marker))));
            assertNoName(marker + "-explicit");
        }
    }

    @Test
    void misleadingOperationHeadersCannotChangeTheMatchedCreateAuthorization() throws Exception {
        try (User user = user(allow("mq:ListConfigurations", "*"))) {
            String body = JSON.writeValueAsString(Map.of("name", marker + "-header", "engineType", "RABBITMQ",
                    "engineVersion", "3.13"));
            URI uri = endpoint.resolve("v1/configurations?Action=ListConfigurations&Operation=ListConfigurations");
            Map<String, String> headers = user.signer("mq").headersFor("POST", uri,
                    Map.of("X-Amz-Target", "AmazonMQ.ListConfigurations"), body.getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach((name, value) -> { if (!name.equalsIgnoreCase("host")) { request.header(name, value); } });
            try (HttpClient http = HttpClient.newHttpClient()) {
                HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, response.statusCode(), response.body());
                assertNoName(marker + "-header");
            }
        }
    }

    @Test
    void permissionsBoundaryCapsTheAdditionalTagGrantBeforePublication() throws Exception {
        try (User user = user(allow("mq:*", "*")); MqClient caller = client(user)) {
            String boundary = SignedIamUsers.iam(endpoint, "CreatePolicy", Map.of("PolicyName", marker + "-boundary",
                    "PolicyDocument", policyDocument(allow("mq:CreateConfiguration", "*")))).then().statusCode(200)
                    .extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
            assertNotNull(boundary);
            try {
                SignedIamUsers.iam(endpoint, "PutUserPermissionsBoundary", Map.of("UserName", user.name(),
                        "PermissionsBoundary", boundary)).then().statusCode(200);
                denied(() -> caller.createConfiguration(request -> request.name(marker + "-boundary-denied")
                        .engineType(EngineType.RABBITMQ).engineVersion("3.13").tags(Map.of("owner", marker))));
                assertNoName(marker + "-boundary-denied");
                create(caller, "boundary-tagless", Map.of());
            } finally {
                SignedIamUsers.iam(endpoint, "DeleteUserPermissionsBoundary", Map.of("UserName", user.name()))
                        .then().statusCode(200);
                SignedIamUsers.iam(endpoint, "DeletePolicy", Map.of("PolicyArn", boundary)).then().statusCode(200);
            }
        }
    }

    @Test
    void assumedRoleSessionPolicyCapsTheAdditionalTagGrant() throws Exception {
        String roleName = marker + "-role";
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},\"Action\":\"sts:AssumeRole\"}]}";
        String roleArn = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", roleName,
                "AssumeRolePolicyDocument", trust)).then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        assertNotNull(roleArn);
        try {
            SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", roleName, "PolicyName", "mq",
                    "PolicyDocument", policyDocument(allow("mq:*", "*")))).then().statusCode(200);
            try (StsClient sts = AwsTestClients.create(StsClient.builder(), "test", Region.US_EAST_1, endpoint)) {
                Credentials credentials = sts.assumeRole(request -> request.roleArn(roleArn).roleSessionName("mq-test")
                        .policy(policyDocument(allow("mq:CreateConfiguration", "*")))).credentials();
                try (MqClient caller = MqClient.builder().endpointOverride(endpoint)
                        .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                                credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken())))
                        .build()) {
                    denied(() -> caller.createConfiguration(request -> request.name(marker + "-session-denied")
                            .engineType(EngineType.RABBITMQ).engineVersion("3.13").tags(Map.of("owner", marker))));
                    assertNoName(marker + "-session-denied");
                    create(caller, "session-tagless", Map.of());
                }
            }
        } finally {
            SignedIamUsers.iam(endpoint, "DeleteRolePolicy", Map.of("RoleName", roleName, "PolicyName", "mq"));
            SignedIamUsers.iam(endpoint, "DeleteRole", Map.of("RoleName", roleName)).then().statusCode(200);
        }
    }

    @Test
    void additionalGrantUsesActualHttpTransportContext() throws Exception {
        String context = "\"Bool\":{\"aws:SecureTransport\":\"false\"}";
        try (User user = user(allow("mq:CreateConfiguration", "*") + ","
                + allowWithCondition("\"mq:CreateTags\"", "*", context)); MqClient caller = client(user)) {
            create(caller, "http-context", Map.of("owner", marker));
            policy(user, allow("mq:CreateConfiguration", "*") + ","
                    + allowWithCondition("\"mq:CreateTags\"", "*",
                    "\"Bool\":{\"aws:SecureTransport\":\"true\"}"));
            denied(() -> caller.createConfiguration(request -> request.name(marker + "-https-condition")
                    .engineType(EngineType.RABBITMQ).engineVersion("3.13").tags(Map.of("owner", marker))));
            assertNoName(marker + "-https-condition");
        }
    }

    @Test
    void encodedTrailingAndInternalPathsRetainExactMatchedResourceAuthorization() throws Exception {
        Owned a = create(root, "path", Map.of("owner", marker));
        DescribeConfigurationResponse before = describe(root, a);
        List<String> paths = List.of("v1/configurations/%63" + a.id().substring(1),
                "v1/configurations/" + a.id() + "/", "_amazonmq-config/v1/configurations/" + a.id());
        try (User user = user(allow("mq:DescribeConfiguration", a.arn()))) {
            for (String path : paths) {
                HttpResponse<String> accepted = signedGet(user, "mq", path);
                assertEquals(200, accepted.statusCode(), path + ": " + accepted.body());
                assertEquals(a.id(), JSON.readTree(accepted.body()).path("id").asText());
                assertEquals(a.arn(), JSON.readTree(accepted.body()).path("arn").asText());
            }
            policy(user, allow("mq:ListConfigurations", "*"));
            for (String path : paths) {
                HttpResponse<String> refused = signedGet(user, "mq", path);
                assertEquals(403, refused.statusCode(), path + ": " + refused.body());
                assertTrue(refused.body().contains("AccessDenied"), refused.body());
                assertTrue(refused.body().contains("mq:DescribeConfiguration"), refused.body());
            }
            assertTrue(before.equalsBySdkFields(describe(root, a)));
        }
    }

    @Test
    void directInternalPathRejectsOtherSigningServicesWithoutReadingTheConfiguration() throws Exception {
        Owned a = create(root, "scope", Map.of("owner", marker));
        DescribeConfigurationResponse before = describe(root, a);
        try (User user = user(allow("*", "*"))) {
            for (String scope : List.of("kafka", "s3", "unknown")) {
                HttpResponse<String> refused = signedGet(user, scope, "_amazonmq-config/v1/configurations/" + a.id());
                assertEquals(400, refused.statusCode(), scope + ": " + refused.body());
                assertTrue(refused.body().contains("InvalidBucketName"), refused.body());
                assertFalse(refused.body().contains(a.arn()), refused.body());
            }
            assertTrue(before.equalsBySdkFields(describe(root, a)));
        }
    }

    @Test
    void explicitDenyBlocksAllSevenActionsDespiteBroadAllow() throws Exception {
        Owned a = create(root, "explicit-all", Map.of("owner", marker));
        DescribeConfigurationResponse before = describe(root, a);
        try (User user = user(allow("mq:*", "*") + ","
                + "{\"Effect\":\"Deny\",\"Action\":\"mq:*\",\"Resource\":\"*\"}");
                MqClient caller = client(user)) {
            denied(() -> caller.createConfiguration(request -> request.name(marker + "-explicit-all-denied")
                    .engineType(EngineType.RABBITMQ).engineVersion("3.13")));
            denied(() -> caller.listConfigurations(request -> request.maxResults(100)));
            denied(() -> caller.describeConfiguration(request -> request.configurationId(a.id())));
            denied(() -> caller.updateConfiguration(request -> request.configurationId(a.id()).data("eA==")));
            denied(() -> caller.deleteConfiguration(request -> request.configurationId(a.id())));
            denied(() -> caller.describeConfigurationRevision(request -> request.configurationId(a.id())
                    .configurationRevision("1")));
            denied(() -> caller.listConfigurationRevisions(request -> request.configurationId(a.id())));
            assertTrue(before.equalsBySdkFields(describe(root, a)));
            assertNoName(marker + "-explicit-all-denied");
        }
    }

    private HttpResponse<String> signedGet(User user, String scope, String path) throws Exception {
        URI uri = endpoint.resolve(path);
        Map<String, String> headers = user.signer(scope).headersFor("GET", uri, Map.of(), new byte[0]);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
        headers.forEach((name, value) -> { if (!name.equalsIgnoreCase("host")) { request.header(name, value); } });
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> presigned(User user, String body) throws Exception {
        URI signed = user.signer("mq").presignedUri("POST", endpoint.resolve("v1/configurations"), Map.of(),
                body.getBytes(StandardCharsets.UTF_8));
        assertTrue(signed.getRawQuery().contains("X-Amz-Signature="));
        HttpRequest request = HttpRequest.newBuilder(signed).timeout(Duration.ofSeconds(10))
                .header("Authorization", "").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private Owned create(MqClient client, String suffix, Map<String, String> tags) {
        String name = marker + "-" + suffix;
        CreateConfigurationResponse created = client.createConfiguration(request -> request.name(name)
                .engineType(EngineType.RABBITMQ).engineVersion("3.13").tags(tags));
        Owned receipt = new Owned(created.id(), created.arn(), name, tags);
        owned.add(receipt);
        assertNotNull(created.id());
        assertNotNull(created.arn());
        assertEquals(name, created.name());
        return receipt;
    }

    private User user(String statements) throws Exception {
        return SignedIamUsers.createUser(endpoint, "mq-policy-", "mq", policyDocument(statements));
    }

    private void policy(User user, String statements) throws Exception {
        SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(), "PolicyName", "mq",
                "PolicyDocument", policyDocument(statements))).then().statusCode(200);
    }

    private MqClient client(User user) {
        return AwsTestClients.create(MqClient.builder(), user.accessKeyId(), user.secretAccessKey(), Region.US_EAST_1, endpoint);
    }

    private static DescribeConfigurationResponse describe(MqClient client, Owned row) {
        return client.describeConfiguration(request -> request.configurationId(row.id()));
    }

    private void assertNoName(String name) {
        assertFalse(root.listConfigurations(request -> request.maxResults(100)).configurations().stream()
                .anyMatch(row -> name.equals(row.name())));
    }

    private String nextArn() {
        return PREFIX + "c-" + new UUID(0, sequence.get() + 1);
    }

    private static String policyDocument(String statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}";
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String allowWithCondition(String action, String resource, String condition) {
        return "{\"Effect\":\"Allow\",\"Action\":" + action + ",\"Resource\":\"" + resource
                + "\",\"Condition\":{" + condition + "}}";
    }

    private static void denied(Runnable operation) {
        MqException denied = assertThrows(MqException.class, operation::run);
        assertEquals(403, denied.statusCode());
        assertTrue(denied.awsErrorDetails().errorCode().contains("AccessDenied"));
    }
}

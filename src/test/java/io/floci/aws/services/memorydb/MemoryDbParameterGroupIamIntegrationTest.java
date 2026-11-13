package io.floci.aws.services.memorydb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.memorydb.MemoryDbClient;
import software.amazon.awssdk.services.memorydb.model.ParameterGroup;
import software.amazon.awssdk.services.memorydb.model.ParameterGroupNotFoundException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class MemoryDbParameterGroupIamIntegrationTest {
    private static final AwsCredentials ROOT = AwsBasicCredentials.create("test", "test");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"DescribeParameterGroups", "DescribeParameters", "UpdateParameterGroup",
            "ResetParameterGroup", "DeleteParameterGroup"})
    void namedGrantUsesCanonicalActualGroupAndDoesNotGrantAnotherGroup(String operation) throws Exception {
        String name = fresh();
        String other = fresh();
        try (MemoryDbClient root = client(ROOT)) {
            String arn = create(root, name);
            create(root, other);
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "memory-group-", "access",
                    policy(allow("memorydb:" + operation, arn)));
                 MemoryDbClient caller = client(credentials(user))) {
                denied(() -> invoke(caller, operation, other));
                assertEquals(other, root.describeParameterGroups(request -> request.parameterGroupName(other))
                        .parameterGroups().getFirst().name());
                invoke(caller, operation, name.toUpperCase(Locale.ROOT));
                if (!operation.equals("DeleteParameterGroup")) {
                    assertEquals(name, root.describeParameterGroups(request -> request.parameterGroupName(name))
                            .parameterGroups().getFirst().name());
                }
            } finally {
                delete(root, name);
                delete(root, other);
            }
        }
    }

    @Test
    void primaryCreateRequiresWildcardAndTaggedCreateRequiresAdditionalTagPermission() throws Exception {
        String name = fresh();
        try (MemoryDbClient root = client(ROOT);
             SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "memory-create-", "access",
                     policy(allow("memorydb:CreateParameterGroup", "*")));
             MemoryDbClient caller = client(credentials(user))) {
            try {
                denied(() -> caller.createParameterGroup(request -> request.parameterGroupName(name)
                        .family("memorydb_redis6").tags(tag -> tag.key("team").value("blue"))));
                assertThrows(ParameterGroupNotFoundException.class,
                        () -> root.describeParameterGroups(request -> request.parameterGroupName(name)));
                assertEquals(name, caller.createParameterGroup(request -> request.parameterGroupName(name)
                        .family("memorydb_redis6")).parameterGroup().name());
            } finally {
                delete(root, name);
            }
        }
    }

    @Test
    void taggedMixedCaseCreateChecksTheCanonicalCreatedArnAndRequestTags() throws Exception {
        String name = fresh();
        String arn = arn(name);
        String document = policy(allow("memorydb:CreateParameterGroup", "*"),
                Map.of("Effect", "Allow", "Action", "memorydb:TagResource", "Resource", arn,
                        "Condition", Map.of("StringEquals", Map.of("aws:RequestTag/team", "blue"),
                                "ForAllValues:StringEquals", Map.of("aws:TagKeys", List.of("team")))));
        try (MemoryDbClient root = client(ROOT);
             SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "memory-canonical-", "access", document);
             MemoryDbClient caller = client(credentials(user))) {
            try {
                denied(() -> caller.createParameterGroup(request -> request.parameterGroupName(name.toUpperCase(Locale.ROOT))
                        .family("memorydb_redis6").tags(tag -> tag.key("team").value("red"))));
                assertThrows(ParameterGroupNotFoundException.class,
                        () -> root.describeParameterGroups(request -> request.parameterGroupName(name)));
                ParameterGroup created = caller.createParameterGroup(request -> request.parameterGroupName(name.toUpperCase(Locale.ROOT))
                        .family("memorydb_redis6").tags(tag -> tag.key("team").value("blue"))).parameterGroup();
                assertEquals(name, created.name());
                assertEquals(arn, created.arn());
                assertEquals("blue", root.listTags(request -> request.resourceArn(arn)).tagList().getFirst().value());
            } finally {
                delete(root, name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void presignedTaggedCreateKeepsCallerIncludingBlankAuthorization(boolean blankAuthorization) throws Exception {
        String name = fresh();
        try (MemoryDbClient root = client(ROOT);
             SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "memory-presign-", "access",
                     policy(allow("memorydb:CreateParameterGroup", "*")));
             HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            byte[] body = MAPPER.writeValueAsBytes(Map.of("ParameterGroupName", name.toUpperCase(Locale.ROOT),
                    "Family", "memorydb_redis6", "Tags", List.of(Map.of("Key", "team", "Value", "blue"))));
            try {
                URI signed = user.signer("memorydb").presignedUri("POST", endpoint, Map.of(), body);
                HttpRequest.Builder request = HttpRequest.newBuilder(signed).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-amz-json-1.1")
                        .header("X-Amz-Target", "AmazonMemoryDB.CreateParameterGroup")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                if (blankAuthorization) {
                    request.header("Authorization", " ");
                }
                assertEquals(403, http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertThrows(ParameterGroupNotFoundException.class,
                        () -> root.describeParameterGroups(value -> value.parameterGroupName(name)));
                SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(), "PolicyName", "access",
                        "PolicyDocument", policy(allow("memorydb:CreateParameterGroup", "*"),
                                allow("memorydb:TagResource", arn(name))))).then().statusCode(200);
                HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(arn(name), MAPPER.readTree(response.body()).path("ParameterGroup").path("ARN").textValue());
                assertEquals("blue", root.listTags(value -> value.resourceArn(arn(name))).tagList().getFirst().value());
            } finally {
                delete(root, name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DescribeParameterGroups", "DescribeParameters", "UpdateParameterGroup", "ResetParameterGroup"})
    void freshResourceTagsControlEachNamedReadOrMutation(String operation) throws Exception {
        String name = fresh();
        String document = policy(Map.of("Effect", "Allow", "Action", "memorydb:" + operation,
                "Resource", arn(name), "Condition", Map.of("StringEquals", Map.of("aws:ResourceTag/team", "blue"))));
        try (MemoryDbClient root = client(ROOT)) {
            String arn = create(root, name);
            root.tagResource(request -> request.resourceArn(arn).tags(tag -> tag.key("team").value("blue")));
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "memory-tags-", "access", document);
                 MemoryDbClient caller = client(credentials(user))) {
                invoke(caller, operation, name);
                root.tagResource(request -> request.resourceArn(arn).tags(tag -> tag.key("team").value("red")));
                denied(() -> invoke(caller, operation, name));
                root.untagResource(request -> request.resourceArn(arn).tagKeys("team"));
                denied(() -> invoke(caller, operation, name));
                root.tagResource(request -> request.resourceArn(arn).tags(tag -> tag.key("team").value("blue")));
                invoke(caller, operation, name);
            } finally {
                delete(root, name);
            }
        }
    }

    @Test
    void explicitDeleteDenyFromCurrentTagsPreventsDeletion() throws Exception {
        String name = fresh();
        String document = policy(allow("memorydb:DeleteParameterGroup", arn(name)),
                Map.of("Effect", "Deny", "Action", "memorydb:DeleteParameterGroup", "Resource", arn(name),
                        "Condition", Map.of("StringEquals", Map.of("aws:ResourceTag/keep", "yes"))));
        try (MemoryDbClient root = client(ROOT)) {
            String arn = create(root, name);
            root.tagResource(request -> request.resourceArn(arn).tags(tag -> tag.key("keep").value("yes")));
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "memory-deny-", "access", document);
                 MemoryDbClient caller = client(credentials(user))) {
                denied(() -> caller.deleteParameterGroup(request -> request.parameterGroupName(name)));
                assertEquals(name, root.describeParameterGroups(request -> request.parameterGroupName(name))
                        .parameterGroups().getFirst().name());
                root.untagResource(request -> request.resourceArn(arn).tagKeys("keep"));
                caller.deleteParameterGroup(request -> request.parameterGroupName(name));
            } finally {
                delete(root, name);
            }
        }
    }

    @Test
    void permissionBoundaryLimitsGroupMutation() throws Exception {
        String name = fresh();
        String boundaryName = fresh();
        String boundary = null;
        try (MemoryDbClient root = client(ROOT);
             SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "memory-boundary-", "access",
                     policy(allow("memorydb:*", arn(name))));
             MemoryDbClient caller = client(credentials(user))) {
            create(root, name);
            try {
                boundary = SignedIamUsers.iam(endpoint, "CreatePolicy", Map.of("PolicyName", boundaryName,
                        "PolicyDocument", policy(allow("memorydb:DescribeParameters", arn(name)))))
                        .then().statusCode(200).extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
                SignedIamUsers.iam(endpoint, "PutUserPermissionsBoundary", Map.of("UserName", user.name(),
                        "PermissionsBoundary", boundary)).then().statusCode(200);
                assertFalse(caller.describeParameters(request -> request.parameterGroupName(name)).parameters().isEmpty());
                denied(() -> invoke(caller, "UpdateParameterGroup", name));
                assertEquals("0", root.describeParameters(request -> request.parameterGroupName(name)).parameters().stream()
                        .filter(value -> value.name().equals("timeout")).findFirst().orElseThrow().value());
            } finally {
                SignedIamUsers.iam(endpoint, "DeleteUserPermissionsBoundary", Map.of("UserName", user.name()))
                        .then().statusCode(200);
                if (boundary != null) {
                    SignedIamUsers.iam(endpoint, "DeletePolicy", Map.of("PolicyArn", boundary)).then().statusCode(200);
                }
                delete(root, name);
            }
        }
    }

    @Test
    void actualStsSessionPolicyLimitsGroupMutation() throws Exception {
        String name = fresh();
        String role = fresh();
        try (MemoryDbClient root = client(ROOT);
             StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(ROOT))
                     .httpClientBuilder(UrlConnectionHttpClient.builder())
                     .overrideConfiguration(value -> value.retryPolicy(RetryPolicy.none())
                             .apiCallTimeout(Duration.ofSeconds(10))).build()) {
            create(root, name);
            String trust = policy(Map.of("Effect", "Allow", "Action", "sts:AssumeRole",
                    "Principal", Map.of("AWS", "arn:aws:iam::000000000000:root")));
            String roleArn = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", role,
                    "AssumeRolePolicyDocument", trust)).then().statusCode(200).extract().xmlPath()
                    .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
            try {
                SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", role, "PolicyName", "access",
                        "PolicyDocument", policy(allow("memorydb:*", arn(name))))).then().statusCode(200);
                Credentials session = sts.assumeRole(request -> request.roleArn(roleArn).roleSessionName("memory-session")
                        .policy(policyUnchecked(allow("memorydb:DescribeParameters", arn(name))))).credentials();
                try (MemoryDbClient caller = client(AwsSessionCredentials.create(session.accessKeyId(),
                        session.secretAccessKey(), session.sessionToken()))) {
                    assertFalse(caller.describeParameters(request -> request.parameterGroupName(name)).parameters().isEmpty());
                    denied(() -> invoke(caller, "UpdateParameterGroup", name));
                }
            } finally {
                SignedIamUsers.iam(endpoint, "DeleteRolePolicy", Map.of("RoleName", role, "PolicyName", "access"));
                SignedIamUsers.iam(endpoint, "DeleteRole", Map.of("RoleName", role)).then().statusCode(200);
                delete(root, name);
            }
        }
    }

    private MemoryDbClient client(AwsCredentials credentials) {
        return MemoryDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(value -> value.retryPolicy(RetryPolicy.none())
                        .apiCallTimeout(Duration.ofSeconds(10))).build();
    }

    private static AwsCredentials credentials(SignedIamUsers.User user) {
        return AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey());
    }

    private static String create(MemoryDbClient client, String name) {
        return client.createParameterGroup(request -> request.parameterGroupName(name).family("memorydb_redis6"))
                .parameterGroup().arn();
    }

    private static void delete(MemoryDbClient client, String name) {
        try {
            client.deleteParameterGroup(request -> request.parameterGroupName(name));
        } catch (ParameterGroupNotFoundException ignored) {
            // The authorized delete case already removed this exact owned name.
        }
    }

    private static void invoke(MemoryDbClient client, String action, String name) {
        switch (action) {
            case "DescribeParameterGroups" -> client.describeParameterGroups(request -> request.parameterGroupName(name));
            case "DescribeParameters" -> client.describeParameters(request -> request.parameterGroupName(name));
            case "UpdateParameterGroup" -> client.updateParameterGroup(request -> request.parameterGroupName(name)
                    .parameterNameValues(value -> value.parameterName("timeout").parameterValue("20")));
            case "ResetParameterGroup" -> client.resetParameterGroup(request -> request.parameterGroupName(name).allParameters(true));
            case "DeleteParameterGroup" -> client.deleteParameterGroup(request -> request.parameterGroupName(name));
            default -> throw new IllegalArgumentException(action);
        }
    }

    private static void denied(Executable action) {
        assertEquals(403, assertThrows(SdkServiceException.class, action).statusCode());
    }

    private static Map<String, Object> allow(String action, String resource) {
        return Map.of("Effect", "Allow", "Action", action, "Resource", resource);
    }

    @SafeVarargs
    private static String policy(Map<String, ?>... statements) throws Exception {
        return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(statements)));
    }

    @SafeVarargs
    private static String policyUnchecked(Map<String, ?>... statements) {
        try {
            return policy(statements);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String arn(String name) {
        return "arn:aws:memorydb:us-east-1:000000000000:parametergroup/" + name;
    }

    private static String fresh() {
        return "memory-" + UUID.randomUUID().toString().replace("-", "");
    }
}

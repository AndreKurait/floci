package io.floci.aws.services.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
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
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.batch.BatchClient;
import software.amazon.awssdk.services.batch.model.BatchException;
import software.amazon.awssdk.services.batch.model.ListSchedulingPoliciesResponse;
import software.amazon.awssdk.services.batch.model.SchedulingPolicyDetail;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class BatchSchedulingPolicyIamIntegrationTest {
    private static final AwsCredentials ROOT = AwsBasicCredentials.create("test", "test");
    private static final Region REGION = Region.US_WEST_2;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"CreateSchedulingPolicy", "DescribeSchedulingPolicies", "ListSchedulingPolicies",
            "UpdateSchedulingPolicy", "DeleteSchedulingPolicy", "TagResource", "UntagResource", "ListTagsForResource"})
    void eachActualActionHonorsExplicitDenyWithoutChangingCompleteState(String action) throws Exception {
        String held = fresh();
        String candidate = fresh();
        try (BatchClient root = client(ROOT);
             SignedIamUsers.User user = user(policy(allow("batch:*", "*"),
                     Map.of("Effect", "Deny", "Action", "batch:" + action, "Resource", "*")));
             BatchClient caller = client(credentials(user))) {
            String arn = create(root, held);
            try {
                SchedulingPolicyDetail before = detail(root, arn);
                denied(() -> invoke(caller, action, arn, candidate));
                assertEquals(before, detail(root, arn));
                assertAbsent(root, arn(candidate));
            } finally {
                root.deleteSchedulingPolicy(request -> request.arn(arn));
                root.deleteSchedulingPolicy(request -> request.arn(arn(candidate)));
            }
        }
    }

    @Test
    void taggedCreateNeedsExactMintedArnAndBothActionsWithActualUncachedBodyConditions() throws Exception {
        String name = fresh();
        String arn = arn(name);
        Map<String, Object> conditions = Map.of("StringEquals", Map.of("aws:RequestTag/team", "blue"),
                "ForAllValues:StringEquals", Map.of("aws:TagKeys", List.of("team")));
        Map<String, Object> create = Map.of("Effect", "Allow", "Action", "batch:CreateSchedulingPolicy",
                "Resource", arn, "Condition", conditions);
        try (BatchClient root = client(ROOT);
             SignedIamUsers.User user = user(policy(create));
             BatchClient caller = client(credentials(user))) {
            try {
                denied(() -> caller.createSchedulingPolicy(request -> request.name(name).tags(Map.of("team", "blue"))));
                assertAbsent(root, arn);
                replace(user, policy(create, allow("batch:TagResource", arn(fresh()))));
                denied(() -> caller.createSchedulingPolicy(request -> request.name(name).tags(Map.of("team", "blue"))));
                assertAbsent(root, arn);
                replace(user, policy(create, Map.of("Effect", "Allow", "Action", "batch:TagResource",
                        "Resource", arn, "Condition", conditions)));
                denied(() -> caller.createSchedulingPolicy(request -> request.name(name).tags(Map.of("team", "red"))));
                assertAbsent(root, arn);
                assertEquals(arn, caller.createSchedulingPolicy(request -> request.name(name).tags(Map.of("team", "blue"))).arn());
                assertEquals(Map.of("team", "blue"), detail(root, arn).tags());
            } finally {
                root.deleteSchedulingPolicy(request -> request.arn(arn));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void presignedCreateKeepsCallerWithAbsentOrBlankAuthorization(boolean blank) throws Exception {
        String name = fresh();
        String arn = arn(name);
        try (BatchClient root = client(ROOT);
             SignedIamUsers.User user = user(policy(allow("batch:CreateSchedulingPolicy", arn)));
             HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            byte[] body = MAPPER.writeValueAsBytes(Map.of("name", name, "tags", Map.of("team", "blue")));
            URI signed = user.signer("batch").inRegion(REGION.id()).presignedUri("POST",
                    endpoint.resolve("/v1/createschedulingpolicy"), Map.of(), body);
            HttpRequest.Builder request = HttpRequest.newBuilder(signed).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (blank) {
                request.header("Authorization", " ");
            }
            try {
                HttpResponse<String> denied = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, denied.statusCode(), denied.body());
                assertAbsent(root, arn);
                replace(user, policy(allow("batch:CreateSchedulingPolicy", arn), allow("batch:TagResource", arn)));
                HttpResponse<String> allowed = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, allowed.statusCode(), allowed.body());
                assertEquals(arn, MAPPER.readTree(allowed.body()).path("arn").textValue());
                assertEquals(Map.of("team", "blue"), detail(root, arn).tags());
            } finally {
                root.deleteSchedulingPolicy(value -> value.arn(arn));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UpdateSchedulingPolicy", "DeleteSchedulingPolicy", "TagResource", "UntagResource", "ListTagsForResource"})
    void namedActionsUseFreshStoredTagsAndPreserveFullDeniedSnapshots(String action) throws Exception {
        String name = fresh();
        String arn = arn(name);
        try (BatchClient root = client(ROOT);
             SignedIamUsers.User user = user(policy(Map.of("Effect", "Allow", "Action", "batch:" + action,
                     "Resource", arn, "Condition", Map.of("StringEquals", Map.of("aws:ResourceTag/team", "blue")))));
             BatchClient caller = client(credentials(user))) {
            create(root, name);
            try {
                root.tagResource(request -> request.resourceArn(arn).tags(Map.of("team", "red")));
                SchedulingPolicyDetail red = detail(root, arn);
                denied(() -> invoke(caller, action, arn, fresh()));
                assertEquals(red, detail(root, arn));
                root.untagResource(request -> request.resourceArn(arn).tagKeys("team"));
                SchedulingPolicyDetail absent = detail(root, arn);
                denied(() -> invoke(caller, action, arn, fresh()));
                assertEquals(absent, detail(root, arn));
                root.tagResource(request -> request.resourceArn(arn).tags(Map.of("team", "blue")));
                invoke(caller, action, arn, fresh());
                if (!action.equals("DeleteSchedulingPolicy")) {
                    assertEquals(arn, detail(root, arn).arn());
                }
            } finally {
                root.deleteSchedulingPolicy(request -> request.arn(arn));
            }
        }
    }

    @Test
    void actualEncodedTagRouteAuthorizesItsDecodedArnAndMatchedMethodDespiteMisleadingTarget() throws Exception {
        String held = fresh();
        String other = fresh();
        String arn = arn(held);
        try (BatchClient root = client(ROOT);
             SignedIamUsers.User user = user(policy(allow("batch:ListTagsForResource", arn)));
             HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            create(root, held);
            create(root, other);
            try {
                SchedulingPolicyDetail before = detail(root, arn);
                URI path = endpoint.resolve("/v1/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8));
                HttpResponse<String> read = send(http, user.signer("batch"), "GET", path, "",
                        Map.of("x-amz-target", "Batch.TagResource", "x-amz-action", "TagResource"));
                assertEquals(200, read.statusCode(), read.body());
                assertEquals("blue", MAPPER.readTree(read.body()).path("tags").path("team").textValue());
                HttpResponse<String> write = send(http, user.signer("batch"), "POST", path, "{\"tags\":{\"team\":\"red\"}}",
                        Map.of("x-amz-target", "Batch.ListTagsForResource", "x-amz-action", "ListTagsForResource"));
                assertEquals(403, write.statusCode(), write.body());
                assertEquals(before, detail(root, arn));
                URI otherPath = endpoint.resolve("/v1/tags/" + URLEncoder.encode(arn(other), StandardCharsets.UTF_8));
                assertEquals(403, send(http, user.signer("batch"), "GET", otherPath, "", Map.of()).statusCode());
                replace(user, policy(allow("batch:TagResource", arn)));
                assertEquals(200, send(http, user.signer("batch"), "POST", path,
                        "{\"tags\":{\"team\":\"green\"}}", Map.of()).statusCode());
                assertEquals(Map.of("team", "green"), detail(root, arn).tags());
            } finally {
                root.deleteSchedulingPolicy(request -> request.arn(arn));
                root.deleteSchedulingPolicy(request -> request.arn(arn(other)));
            }
        }
    }

    @Test
    void strictRawTagFailuresLeaveCompletePolicyAndValidEmptyMapRemainsAccepted() throws Exception {
        String name = fresh();
        String arn = arn(name);
        try (BatchClient root = client(ROOT);
             HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            create(root, name);
            try {
                SchedulingPolicyDetail before = detail(root, arn);
                URI path = endpoint.resolve("/v1/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8));
                for (String body : List.of("", "[]", "{\"tags\":{\"x\":1}}", "{\"tags\":{\"x\":null}}",
                        "{\"tags\":{\"x\":\"first\",\"x\":\"second\"}}", "{\"tags\":{}} {}")) {
                    HttpResponse<String> response = send(http, AwsRequestSigner.signedAs("test", "test", "batch"),
                            "POST", path, body, Map.of());
                    assertEquals(400, response.statusCode(), response.body());
                    assertEquals(before, detail(root, arn));
                }
                assertEquals(200, send(http, AwsRequestSigner.signedAs("test", "test", "batch"),
                        "POST", path, "{\"tags\":{}}", Map.of()).statusCode());
                assertEquals(before, detail(root, arn));
            } finally {
                root.deleteSchedulingPolicy(request -> request.arn(arn));
            }
        }
    }

    @Test
    void permissionBoundaryRefusalKeepsFullPolicy() throws Exception {
        String name = fresh();
        String arn = arn(name);
        String boundary = null;
        try (BatchClient root = client(ROOT);
             SignedIamUsers.User user = user(policy(allow("batch:*", "*")));
             BatchClient caller = client(credentials(user))) {
            create(root, name);
            try {
                boundary = SignedIamUsers.iam(endpoint, "CreatePolicy", Map.of("PolicyName", fresh(),
                        "PolicyDocument", policy(allow("batch:DescribeSchedulingPolicies", "*"))))
                        .then().statusCode(200).extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
                SignedIamUsers.iam(endpoint, "PutUserPermissionsBoundary", Map.of("UserName", user.name(),
                        "PermissionsBoundary", boundary)).then().statusCode(200);
                assertEquals(arn, detail(caller, arn).arn());
                SchedulingPolicyDetail before = detail(root, arn);
                denied(() -> invoke(caller, "UpdateSchedulingPolicy", arn, fresh()));
                assertEquals(before, detail(root, arn));
            } finally {
                SignedIamUsers.iam(endpoint, "DeleteUserPermissionsBoundary", Map.of("UserName", user.name())).then().statusCode(200);
                if (boundary != null) {
                    SignedIamUsers.iam(endpoint, "DeletePolicy", Map.of("PolicyArn", boundary)).then().statusCode(200);
                }
                root.deleteSchedulingPolicy(request -> request.arn(arn));
            }
        }
    }

    @Test
    void actualStsSessionPolicyRefusalKeepsFullPolicy() throws Exception {
        String name = fresh();
        String arn = arn(name);
        String role = fresh();
        try (BatchClient root = client(ROOT);
             StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(ROOT))
                     .httpClientBuilder(UrlConnectionHttpClient.builder())
                     .overrideConfiguration(value -> value.retryPolicy(RetryPolicy.none())
                             .apiCallTimeout(Duration.ofSeconds(10))).build()) {
            create(root, name);
            String roleArn = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", role,
                    "AssumeRolePolicyDocument", policy(Map.of("Effect", "Allow", "Action", "sts:AssumeRole",
                            "Principal", Map.of("AWS", "arn:aws:iam::000000000000:root")))))
                    .then().statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
            try {
                SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", role, "PolicyName", "access",
                        "PolicyDocument", policy(allow("batch:*", "*")))).then().statusCode(200);
                String sessionPolicy = policy(allow("batch:DescribeSchedulingPolicies", "*"));
                Credentials session = sts.assumeRole(request -> request.roleArn(roleArn).roleSessionName("batch-session")
                        .policy(sessionPolicy)).credentials();
                try (BatchClient caller = client(AwsSessionCredentials.create(session.accessKeyId(),
                        session.secretAccessKey(), session.sessionToken()))) {
                    assertEquals(arn, detail(caller, arn).arn());
                    SchedulingPolicyDetail before = detail(root, arn);
                    denied(() -> invoke(caller, "UpdateSchedulingPolicy", arn, fresh()));
                    assertEquals(before, detail(root, arn));
                }
            } finally {
                SignedIamUsers.iam(endpoint, "DeleteRolePolicy", Map.of("RoleName", role, "PolicyName", "access"));
                SignedIamUsers.iam(endpoint, "DeleteRole", Map.of("RoleName", role)).then().statusCode(200);
                root.deleteSchedulingPolicy(request -> request.arn(arn));
            }
        }
    }

    private HttpResponse<String> send(HttpClient http, AwsRequestSigner signer, String method, URI uri,
                                      String text, Map<String, String> extra) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        extra.forEach(request::header);
        signer.inRegion(REGION.id()).headersFor(method, uri, extra, body).forEach((key, value) -> {
            if (!key.equalsIgnoreCase("host")) {
                request.header(key, value);
            }
        });
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private BatchClient client(AwsCredentials credentials) {
        return BatchClient.builder().endpointOverride(endpoint).region(REGION)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(value -> value.retryPolicy(RetryPolicy.none())
                        .apiCallTimeout(Duration.ofSeconds(10))).build();
    }

    private SignedIamUsers.User user(String policy) throws Exception {
        return SignedIamUsers.createUser(endpoint, "batch-policy-", "access", policy);
    }

    private void replace(SignedIamUsers.User user, String document) throws Exception {
        SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(), "PolicyName", "access",
                "PolicyDocument", document)).then().statusCode(200);
    }

    private static AwsCredentials credentials(SignedIamUsers.User user) {
        return AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey());
    }

    private static String create(BatchClient client, String name) {
        return client.createSchedulingPolicy(request -> request.name(name).tags(Map.of("team", "blue"))
                .fairsharePolicy(policy -> policy.shareDecaySeconds(30).computeReservation(20))).arn();
    }

    private static SchedulingPolicyDetail detail(BatchClient client, String arn) {
        return client.describeSchedulingPolicies(request -> request.arns(arn)).schedulingPolicies().getFirst();
    }

    private static void assertAbsent(BatchClient client, String arn) {
        assertTrue(client.describeSchedulingPolicies(request -> request.arns(arn)).schedulingPolicies().isEmpty());
        String token = null;
        Set<String> seen = new HashSet<>();
        List<String> inventory = new ArrayList<>();
        do {
            String current = token;
            ListSchedulingPoliciesResponse page =
                    client.listSchedulingPolicies(request -> request.maxResults(100).nextToken(current));
            page.schedulingPolicies().forEach(policy -> inventory.add(policy.arn()));
            token = page.nextToken();
            assertTrue(token == null || seen.add(token), "pagination must reach complete EOF");
            assertTrue(seen.size() <= 20, "bounded fixture inventory");
        } while (token != null);
        assertFalse(inventory.contains(arn));
    }

    private static void invoke(BatchClient client, String action, String arn, String candidate) {
        switch (action) {
            case "CreateSchedulingPolicy" -> client.createSchedulingPolicy(request -> request.name(candidate));
            case "DescribeSchedulingPolicies" -> client.describeSchedulingPolicies(request -> request.arns(arn));
            case "ListSchedulingPolicies" -> client.listSchedulingPolicies(b -> {});
            case "UpdateSchedulingPolicy" -> client.updateSchedulingPolicy(request -> request.arn(arn)
                    .fairsharePolicy(policy -> policy.shareDecaySeconds(90)));
            case "DeleteSchedulingPolicy" -> client.deleteSchedulingPolicy(request -> request.arn(arn));
            case "TagResource" -> client.tagResource(request -> request.resourceArn(arn).tags(Map.of("team", "changed")));
            case "UntagResource" -> client.untagResource(request -> request.resourceArn(arn).tagKeys("team"));
            case "ListTagsForResource" -> client.listTagsForResource(request -> request.resourceArn(arn));
            default -> throw new IllegalArgumentException(action);
        }
    }

    private static void denied(Executable operation) {
        BatchException failure = assertThrows(BatchException.class, operation);
        assertEquals(403, failure.statusCode(), failure.toString());
        assertNotNull(failure.awsErrorDetails());
        assertTrue(failure.awsErrorDetails().errorCode().contains("AccessDenied"), failure.toString());
    }

    private static Map<String, Object> allow(String action, String resource) {
        return Map.of("Effect", "Allow", "Action", action, "Resource", resource);
    }

    @SafeVarargs
    private static String policy(Map<String, ?>... statements) throws Exception {
        return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(statements)));
    }

    private static String arn(String name) {
        return "arn:aws:batch:us-west-2:000000000000:scheduling-policy/" + name;
    }

    private static String fresh() {
        return "batch-policy-" + UUID.randomUUID().toString().replace("-", "");
    }
}

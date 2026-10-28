package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class RestRouteIamIntegrationTest {

    private static final Logger LOG = Logger.getLogger(RestRouteIamIntegrationTest.class);

    private static final String OWNER = "000000000000";

    @Inject
    IamService iam;

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void signedUserRetainsFalsePrincipalServiceFlag() throws Exception {
        Fixture fixture = fixture("GET");
        String document = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\","
                + "\"Resource\":\"" + fixture.arn() + "\",\"Condition\":{\"Bool\":{\"aws:PrincipalIsAWSService\":\"false\"}}}}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "rest-principal-", "invoke", document)) {
            Key key = new Key(user.accessKeyId(), user.secretAccessKey(), null);
            invoke(fixture, key, 200);
            putPolicy(user, document.replace("\"Bool\"", "\"Null\"").replace("\"false\"", "\"true\""));
            invoke(fixture, key, 403);
            assertEquals(1, fixture.hits().get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "ANY"})
    void signedCallerNeedsExactRoutePermissionBeforeHttpBackend(String routeKey) throws Exception {
        Fixture fixture = fixture(routeKey);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "rest-route-", "invoke",
                "{\"Statement\":[]}")) {
            Key key = new Key(user.accessKeyId(), user.secretAccessKey(), null);
            invoke(fixture, key, 403);
            String[] wrongArns = {fixture.arn().replace("/prod/", "/other/"),
                    fixture.arn().replace("/GET/", "/POST/"), fixture.arn().replace("/items", "/other")};
            for (String wrong : wrongArns) {
                putPolicy(user, policy(wrong));
                invoke(fixture, key, 403);
            }
            assertEquals(0, fixture.hits().get());
            putPolicy(user, policy(fixture.arn()));
            invoke(fixture, key, 200);
            assertEquals(1, fixture.hits().get());
            putPolicy(user, policy(fixture.arn()).replace("Allow", "Deny"));
            invoke(fixture, key, 403);
            assertEquals(1, fixture.hits().get());
        }
    }

    @Test
    void issuedRoleSessionsRetainPolicyTokenAndRevocationChecks() throws Exception {
        Fixture fixture = fixture("GET");
        String name = "rest-route-role-" + UUID.randomUUID();
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                + OWNER + ":root\"},\"Action\":\"sts:AssumeRole\"}}";
        cleanup.register(() -> cleanupIam("DeleteRole", Map.of("RoleName", name)));
        String role = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", name, "Path", "/service-role/",
                "AssumeRolePolicyDocument", trust)).then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> cleanupIam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
        SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy(fixture.arn()))).then().statusCode(200);
        Key denied = assume(role, policy(fixture.arn()).replace("Allow", "Deny"));
        invoke(fixture, denied, 403);
        Key allowed = assume(role, null);
        invoke(fixture, new Key(allowed.accessKey(), allowed.secret(), null), 403);
        invoke(fixture, new Key(allowed.accessKey(), allowed.secret(), "wrong-token"), 403);
        assertEquals(0, fixture.hits().get());
        invoke(fixture, allowed, 200);
        SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy(fixture.arn()).replace("Allow", "Deny"))).then().statusCode(200);
        invoke(fixture, allowed, 403);
        assertEquals(1, fixture.hits().get());
    }

    @Test
    void verifiedLocalRootRetainsAccess() throws Exception {
        Fixture fixture = fixture("GET");
        invoke(fixture, new Key("test", "test", null), 200);
        assertEquals(1, fixture.hits().get());
    }

    @Test
    void resourcePolicyGrantAndDenyAreEnforcedAlongsideIdentity() throws Exception {
        Fixture fixture = fixture("GET");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "rest-resource-", "invoke",
                "{\"Statement\":[]}")) {
            Key key = new Key(user.accessKeyId(), user.secretAccessKey(), null);
            invoke(fixture, key, 403);
            String principal = "arn:aws:iam::" + OWNER + ":user/" + user.name();
            String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + principal
                    + "\"},\"Action\":\"execute-api:Invoke\",\"Resource\":\"execute-api:/prod/GET/items\"}}";
            fixture.api().updateRestApi(request -> request.restApiId(fixture.apiId()).patchOperations(patch ->
                    patch.op("replace").path("/policy").value(policy)));
            invoke(fixture, key, 200);
            putPolicy(user, policy(fixture.arn()));
            fixture.api().updateRestApi(request -> request.restApiId(fixture.apiId()).patchOperations(patch ->
                    patch.op("replace").path("/policy").value(policy.replace("Allow", "Deny"))));
            invoke(fixture, key, 403);
            assertEquals(1, fixture.hits().get());
        }
    }

    @Test
    void foreignSignedCallerNeedsIdentityAndApiResourceGrants() throws Exception {
        Fixture fixture = fixture("GET");
        String account = "444455556666";
        String name = "rest-foreign-" + UUID.randomUUID();
        cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUser(name)));
        RequestScopes.runAs(account, () -> iam.createUser(name, "/"));
        AccessKey access = RequestScopes.callAs(account, () -> iam.createAccessKey(name));
        cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteAccessKey(name, access.getAccessKeyId())));
        cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUserPolicy(name, "invoke")));
        RequestScopes.runAs(account, () -> iam.putUserPolicy(name, "invoke", policy(fixture.arn())));
        Key key = new Key(access.getAccessKeyId(), access.getSecretAccessKey(), null);
        invoke(fixture, key, 403);
        String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                + account + ":user/" + name + "\"},\"Action\":\"execute-api:Invoke\",\"Resource\":\""
                + fixture.arn() + "\"}}";
        fixture.api().updateRestApi(request -> request.restApiId(fixture.apiId()).patchOperations(patch ->
                patch.op("replace").path("/policy").value(policy)));
        invoke(fixture, key, 200);
        RequestScopes.runAs(account, () -> iam.putUserPolicy(name, "invoke", "{\"Statement\":[]}"));
        invoke(fixture, key, 403);
        assertEquals(1, fixture.hits().get());
    }

    @Test
    void signedCallerTransportConditionsUseConnectionInsteadOfForwardedHeaders() throws Exception {
        Fixture fixture = fixture("GET");
        String conditions = "\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.0/8\"},"
                + "\"Bool\":{\"aws:SecureTransport\":\"false\"}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "rest-transport-", "invoke",
                conditionedPolicy(fixture.arn(), conditions))) {
            Key key = new Key(user.accessKeyId(), user.secretAccessKey(), null);
            invoke(fixture, key, 200, Map.of("X-Forwarded-For", "192.0.2.1", "X-Forwarded-Proto", "https"));
            putPolicy(user, conditionedPolicy(fixture.arn(), conditions.replace("127.0.0.0/8", "192.0.2.0/24")));
            invoke(fixture, key, 403, Map.of("X-Forwarded-For", "192.0.2.1"));
            assertEquals(1, fixture.hits().get());
        }
    }

    @Test
    void signedCallerResourcePolicyTransportDenyStopsBackend() throws Exception {
        Fixture fixture = fixture("GET");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "rest-transport-deny-", "invoke",
                policy(fixture.arn()))) {
            String deny = conditionedPolicy(fixture.arn(), "\"Bool\":{\"aws:SecureTransport\":\"false\"}")
                    .replace("\"Effect\":\"Allow\"", "\"Effect\":\"Deny\",\"Principal\":\"*\"");
            fixture.api().updateRestApi(request -> request.restApiId(fixture.apiId()).patchOperations(patch ->
                    patch.op("replace").path("/policy").value(deny)));
            invoke(fixture, new Key(user.accessKeyId(), user.secretAccessKey(), null), 403,
                    Map.of("X-Forwarded-Proto", "https"));
            assertEquals(0, fixture.hits().get());
        }
    }

    @Test
    void issuedSessionEvaluatesTransportConditionsAlongsideRolePolicy() throws Exception {
        Fixture fixture = fixture("GET");
        String name = "rest-transport-session-" + UUID.randomUUID();
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                + OWNER + ":root\"},\"Action\":\"sts:AssumeRole\"}}";
        cleanup.register(() -> cleanupIam("DeleteRole", Map.of("RoleName", name)));
        String role = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", name, "Path", "/service-role/",
                "AssumeRolePolicyDocument", trust)).then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> cleanupIam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
        SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy(fixture.arn()))).then().statusCode(200);
        String sessionPolicy = conditionedPolicy(fixture.arn(), "\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.0/8\"}");
        invoke(fixture, assume(role, sessionPolicy), 200);
        invoke(fixture, assume(role, sessionPolicy.replace("127.0.0.0/8", "192.0.2.0/24")), 403);
        assertEquals(1, fixture.hits().get());
    }

    @Test
    void signedCallerTimeWindowAndResourceDenyUseServerTime() throws Exception {
        Fixture fixture = fixture("GET");
        String conditions = "\"DateGreaterThan\":{\"aws:CurrentTime\":\"2000-01-01T00:00:00Z\"},"
                + "\"NumericLessThan\":{\"aws:EpochTime\":\"4102444800\"}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "rest-time-", "invoke",
                conditionedPolicy(fixture.arn(), conditions))) {
            Key key = new Key(user.accessKeyId(), user.secretAccessKey(), null);
            invoke(fixture, key, 200);
            String deny = conditionedPolicy(fixture.arn(), conditions)
                    .replace("\"Effect\":\"Allow\"", "\"Effect\":\"Deny\",\"Principal\":\"*\"");
            fixture.api().updateRestApi(request -> request.restApiId(fixture.apiId()).patchOperations(patch ->
                    patch.op("replace").path("/policy").value(deny)));
            invoke(fixture, key, 403);
            assertEquals(1, fixture.hits().get());
        }
    }

    @Test
    void issuedSessionTokenIssueTimeIsEvaluatedAlongsideRolePolicy() throws Exception {
        Fixture fixture = fixture("GET");
        String name = "rest-token-time-session-" + UUID.randomUUID();
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                + OWNER + ":root\"},\"Action\":\"sts:AssumeRole\"}}";
        cleanup.register(() -> cleanupIam("DeleteRole", Map.of("RoleName", name)));
        String role = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", name, "Path", "/service-role/",
                "AssumeRolePolicyDocument", trust)).then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> cleanupIam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
        SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy(fixture.arn()))).then().statusCode(200);
        String sessionPolicy = conditionedPolicy(fixture.arn(),
                "\"DateLessThan\":{\"aws:TokenIssueTime\":\"2100-01-01T00:00:00Z\"}");
        invoke(fixture, assume(role, sessionPolicy), 200);
        invoke(fixture, assume(role, sessionPolicy.replace("2100-01-01", "2000-01-01")), 403);
        assertEquals(1, fixture.hits().get());
    }

    @Test
    void issuedSessionTimeWindowIsEvaluatedAlongsideRolePolicy() throws Exception {
        Fixture fixture = fixture("GET");
        String name = "rest-time-session-" + UUID.randomUUID();
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                + OWNER + ":root\"},\"Action\":\"sts:AssumeRole\"}}";
        cleanup.register(() -> cleanupIam("DeleteRole", Map.of("RoleName", name)));
        String role = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", name, "Path", "/service-role/",
                "AssumeRolePolicyDocument", trust)).then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> cleanupIam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
        SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy(fixture.arn()))).then().statusCode(200);
        String sessionPolicy = conditionedPolicy(fixture.arn(),
                "\"DateLessThan\":{\"aws:CurrentTime\":\"2100-01-01T00:00:00Z\"}");
        invoke(fixture, assume(role, sessionPolicy), 200);
        invoke(fixture, assume(role, sessionPolicy.replace("2100-01-01", "2000-01-01")), 403);
        assertEquals(1, fixture.hits().get());
    }

    private static String conditionedPolicy(String arn, String conditions) {
        return policy(arn).replace("}}", ",\"Condition\":{" + conditions + "}}}");
    }

    private void cleanupIam(String action, Map<String, String> parameters) {
        try {
            SignedIamUsers.iam(endpoint, action, parameters).then().statusCode(200);
        } catch (Exception failure) {
            throw new AssertionError("IAM cleanup failed for " + action, failure);
        }
    }

    private Key assume(String role, String policy) throws Exception {
        String body = "Action=AssumeRole&Version=2011-06-15&RoleSessionName=route-session&RoleArn="
                + URLEncoder.encode(role, StandardCharsets.UTF_8);
        if (policy != null) {
            body += "&Policy=" + URLEncoder.encode(policy, StandardCharsets.UTF_8);
        }
        Response response = given().headers(AwsRequestSigner.signedAs("test", "test", "sts")
                        .headersFor("POST", endpoint, Map.of(), body.getBytes(StandardCharsets.UTF_8)))
                .contentType("application/x-www-form-urlencoded").body(body).post(endpoint)
                .then().statusCode(200).extract().response();
        String prefix = "AssumeRoleResponse.AssumeRoleResult.Credentials.";
        return new Key(response.xmlPath().getString(prefix + "AccessKeyId"),
                response.xmlPath().getString(prefix + "SecretAccessKey"),
                response.xmlPath().getString(prefix + "SessionToken"));
    }

    private void putPolicy(SignedIamUsers.User user, String policy) throws Exception {
        SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(), "PolicyName", "invoke",
                "PolicyDocument", policy)).then().statusCode(200);
    }

    private static String policy(String arn) {
        return "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + arn + "\"}}";
    }

    private Fixture fixture(String routeKey) throws Exception {
        HttpServer backend = HttpServer.create();
        cleanup.register(() -> backend.stop(0));
        backend.bind(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        backend.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (exchange) {
                exchange.getResponseBody().write(response);
            }
        });
        backend.start();
        LOG.infov("REST route IAM fixture listening on local port {0}", backend.getAddress().getPort());
        ApiGatewayClient api = ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        cleanup.register(api::close);
        String id = api.createRestApi(request -> request.name("rest-route-iam-" + UUID.randomUUID())).id();
        cleanup.register(() -> api.deleteRestApi(request -> request.restApiId(id)));
        String root = api.getResources(request -> request.restApiId(id)).items().getFirst().id();
        String resourceId = api.createResource(request -> request.restApiId(id).parentId(root).pathPart("items")).id();
        api.putMethod(request -> request.restApiId(id).resourceId(resourceId).httpMethod(routeKey)
                .authorizationType("AWS_IAM"));
        api.putIntegration(request -> request.restApiId(id).resourceId(resourceId).httpMethod(routeKey)
                .type("HTTP_PROXY").integrationHttpMethod("GET")
                .uri("http://127.0.0.1:" + backend.getAddress().getPort()));
        api.createDeployment(request -> request.restApiId(id).stageName("prod"));
        return new Fixture(id, "arn:aws:execute-api:us-east-1:" + OWNER + ":" + id + "/prod/GET/items", hits, api);
    }

    private void invoke(Fixture fixture, Key key, int status) throws Exception {
        invoke(fixture, key, status, Map.of());
    }

    private void invoke(Fixture fixture, Key key, int status, Map<String, String> extraHeaders) throws Exception {
        String path = "/execute-api/" + fixture.apiId() + "/prod/items";
        String host = endpoint.getHost() + ":" + endpoint.getPort();
        Map<String, String> headers = ExecuteApiRequestSigner.signedHeaders("GET", path, Map.of(), host,
                new byte[0], key.accessKey(), key.secret(), "us-east-1", Instant.now());
        RequestSpecification request = given().headers(headers).headers(extraHeaders);
        if (key.token() != null) {
            request.header("X-Amz-Security-Token", key.token());
        }
        request.get(path).then().statusCode(status);
    }

    private record Fixture(String apiId, String arn, AtomicInteger hits, ApiGatewayClient api) { }

    private record Key(String accessKey, String secret, String token) { }
}

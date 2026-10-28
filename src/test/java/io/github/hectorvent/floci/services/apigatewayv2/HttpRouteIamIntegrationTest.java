package io.github.hectorvent.floci.services.apigatewayv2;

import com.sun.net.httpserver.HttpServer;
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
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class HttpRouteIamIntegrationTest {

    Function<String, String> roleNames = prefix -> prefix + UUID.randomUUID();

    private static final Logger LOG = Logger.getLogger(HttpRouteIamIntegrationTest.class);

    private static final String OWNER = "000000000000";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"GET /items", "ANY /{proxy+}", "$default"})
    void signedCallerNeedsExactRoutePermissionBeforeHttpBackend(String routeKey) throws Exception {
        Fixture fixture = fixture(routeKey);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "http-route-", "invoke",
                "{\"Statement\":[]}")) {
            Key key = new Key(user.accessKeyId(), user.secretAccessKey(), null);
            invoke(fixture, key, 403);
            String[] wrongArns = "$default".equals(routeKey)
                    ? new String[]{fixture.arn().replace("/prod/", "/other/"),
                            fixture.arn().replace("/$default", "/GET/items")}
                    : new String[]{fixture.arn().replace("/prod/", "/other/"),
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
        Fixture fixture = fixture("GET /items");
        String name = roleNames.apply("http-route-role-");
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                + OWNER + ":root\"},\"Action\":\"sts:AssumeRole\"}}";
        String role = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", name, "Path", "/service-role/",
                "AssumeRolePolicyDocument", trust)).then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> cleanupIam("DeleteRole", Map.of("RoleName", name)));
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
        Fixture fixture = fixture("GET /items");
        invoke(fixture, new Key("test", "test", null), 200);
        assertEquals(1, fixture.hits().get());
    }

    @Test
    void rejectedRoleCreationPreservesPreexistingRole() throws Exception {
        String name = "http-existing-" + UUID.randomUUID();
        SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", name,
                "AssumeRolePolicyDocument", "{\"Statement\":[]}")).then().statusCode(200);
        cleanup.register(() -> cleanupIam("DeleteRole", Map.of("RoleName", name)));
        HttpRouteIamIntegrationTest nested = nestedFixture(name);
        try {
            assertThrows(AssertionError.class, () -> nested.issuedRoleSessionsRetainPolicyTokenAndRevocationChecks());
        } finally {
            nested.cleanup.afterEach(mock(ExtensionContext.class));
        }
        SignedIamUsers.iam(endpoint, "GetRole", Map.of("RoleName", name)).then().statusCode(200);
    }

    @Test
    void successfulRoleScenarioRemovesOwnedRoleAndPolicy() throws Exception {
        String name = "http-owned-" + UUID.randomUUID();
        HttpRouteIamIntegrationTest nested = nestedFixture(name);
        try {
            nested.issuedRoleSessionsRetainPolicyTokenAndRevocationChecks();
        } finally {
            nested.cleanup.afterEach(mock(ExtensionContext.class));
        }
        SignedIamUsers.iam(endpoint, "GetRole", Map.of("RoleName", name)).then().statusCode(404);
    }

    private HttpRouteIamIntegrationTest nestedFixture(String name) {
        HttpRouteIamIntegrationTest nested = new HttpRouteIamIntegrationTest();
        nested.endpoint = endpoint;
        nested.roleNames = prefix -> name;
        return nested;
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
        LOG.infov("HTTP route IAM fixture listening on local port {0}", backend.getAddress().getPort());
        ApiGatewayV2Client api = ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        cleanup.register(api::close);
        String id = api.createApi(request -> request.name("http-route-iam-" + UUID.randomUUID()).protocolType("HTTP")).apiId();
        cleanup.register(() -> api.deleteApi(request -> request.apiId(id)));
        String integration = api.createIntegration(request -> request.apiId(id).integrationType("HTTP_PROXY")
                .integrationMethod("ANY").integrationUri("http://127.0.0.1:" + backend.getAddress().getPort()))
                .integrationId();
        api.createRoute(request -> request.apiId(id).routeKey(routeKey).authorizationType("AWS_IAM")
                .target("integrations/" + integration));
        api.createStage(request -> request.apiId(id).stageName("prod").autoDeploy(true));
        String resource = "$default".equals(routeKey) ? "$default" : "GET/items";
        return new Fixture(id, "arn:aws:execute-api:us-east-1:" + OWNER + ":" + id + "/prod/" + resource, hits);
    }

    private void invoke(Fixture fixture, Key key, int status) throws Exception {
        String path = "/execute-api/" + fixture.apiId() + "/prod/items";
        String host = endpoint.getHost() + ":" + endpoint.getPort();
        Map<String, String> headers = ExecuteApiRequestSigner.signedHeaders("GET", path, Map.of(), host,
                new byte[0], key.accessKey(), key.secret(), "us-east-1", Instant.now());
        RequestSpecification request = given().headers(headers);
        if (key.token() != null) {
            request.header("X-Amz-Security-Token", key.token());
        }
        request.get(path).then().statusCode(status);
    }

    private record Fixture(String apiId, String arn, AtomicInteger hits) { }

    private record Key(String accessKey, String secret, String token) { }
}

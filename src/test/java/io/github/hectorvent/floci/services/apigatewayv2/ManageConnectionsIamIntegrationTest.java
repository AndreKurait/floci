package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.services.apigatewayv2.websocket.ConnectionInfo;
import io.github.hectorvent.floci.services.apigatewayv2.websocket.WebSocketConnectionManager;
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
import io.vertx.core.http.ServerWebSocket;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class ManageConnectionsIamIntegrationTest {
    Supplier<String> roleNames = () -> "connections-role-" + UUID.randomUUID();

    private static final String OWNER = "000000000000";

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    WebSocketConnectionManager connections;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void signedCallerNeedsMethodGrantAndRevocationStopsSocketAccess(String method) throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "connections-", "invoke", "{\"Statement\":[]}")) {
            Key key = new Key(user.accessKeyId(), user.secretAccessKey(), null);
            invoke(fixture, method, key, 403);
            putPolicy(user, policy(fixture.arn(method)).replace("ManageConnections", "Invoke"));
            invoke(fixture, method, key, 403);
            verifyNoInteractions(fixture.socket());
            putPolicy(user, policy(fixture.arn(method)));
            invoke(fixture, method, key, "DELETE".equals(method) ? 204 : 200);
            if ("POST".equals(method)) {
                verify(fixture.socket()).writeTextMessage("hello");
            } else if ("DELETE".equals(method)) {
                verify(fixture.socket()).close();
            }
            clearInvocations(fixture.socket());
            putPolicy(user, policy(fixture.arn(method)).replace("Allow", "Deny"));
            invoke(fixture, method, key, 403);
            verifyNoInteractions(fixture.socket());
        }
    }

    @Test
    void unsignedOrTamperedRequestCannotUseConnection() throws Exception {
        Fixture fixture = fixture();
        given().body("hello").post(fixture.path()).then().statusCode(403);
        Map<String, String> headers = signed(fixture.path(), "POST", new Key("test", "test", null));
        given().headers(headers).body("changed").post(fixture.path()).then().statusCode(403);
        verifyNoInteractions(fixture.socket());
        invoke(fixture, "POST", new Key("test", "test", null), 200);
    }

    @Test
    void issuedSessionPolicyAndTokenAreEnforced() throws Exception {
        Fixture fixture = fixture();
        String name = roleNames.get();
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                + OWNER + ":root\"},\"Action\":\"sts:AssumeRole\"}}";
        String role = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", name, "Path", "/service-role/",
                "AssumeRolePolicyDocument", trust)).then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> cleanupIam("DeleteRole", Map.of("RoleName", name)));
        cleanup.register(() -> cleanupIam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
        SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy(fixture.arn("POST")))).then().statusCode(200);
        invoke(fixture, "POST", assume(role, policy(fixture.arn("POST")).replace("Allow", "Deny")), 403);
        Key allowed = assume(role, null);
        invoke(fixture, "POST", new Key(allowed.accessKey(), allowed.secret(), null), 403);
        invoke(fixture, "POST", new Key(allowed.accessKey(), allowed.secret(), "wrong-token"), 403);
        verifyNoInteractions(fixture.socket());
        invoke(fixture, "POST", allowed, 200);
        verify(fixture.socket()).writeTextMessage("hello");
    }

    @Test
    void virtualHostRewriteRetainsSignedPathAndApiOwner() throws Exception {
        Fixture fixture = fixture();
        String host = fixture.apiId() + ".execute-api.us-east-1.amazonaws.com";
        String path = "/prod/@connections/" + fixture.connectionId();
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ExecuteApiRequestSigner.signedHeaders("POST", path.replace("@", "%40"), Map.of(), host,
                body, "test", "test", "us-east-1", Instant.now());
        given().headers(headers).header("Host", host).body(body).post(path).then().statusCode(200);
        verify(fixture.socket()).writeTextMessage("hello");
    }

    @Test
    void connectionCannotBeRetargetedThroughAnotherApiOrStage() throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();
        String wrongApi = "/execute-api/" + second.apiId() + "/prod/@connections/" + first.connectionId();
        String wrongStage = first.path().replace("/prod/", "/other/");
        for (String path : new String[]{wrongApi, wrongStage}) {
            given().headers(signed(path, "POST", new Key("test", "test", null))).body("hello")
                    .post(path).then().statusCode(410);
        }
        verifyNoInteractions(first.socket(), second.socket());
    }

    @Test
    void rejectedRoleCreationPreservesPreexistingRole() throws Exception {
        String name = "connections-existing-" + UUID.randomUUID();
        SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", name,
                "AssumeRolePolicyDocument", "{\"Statement\":[]}")).then().statusCode(200);
        cleanup.register(() -> cleanupIam("DeleteRole", Map.of("RoleName", name)));
        ManageConnectionsIamIntegrationTest fixture = nestedFixture(name);
        try {
            assertThrows(AssertionError.class,
                    fixture::issuedSessionPolicyAndTokenAreEnforced);
        } finally {
            fixture.cleanup.afterEach(mock(ExtensionContext.class));
        }
        SignedIamUsers.iam(endpoint, "GetRole", Map.of("RoleName", name)).then().statusCode(200);
    }

    @Test
    void successfulRoleScenarioRemovesOwnedRoleAndPolicy() throws Exception {
        String name = "connections-owned-" + UUID.randomUUID();
        ManageConnectionsIamIntegrationTest fixture = nestedFixture(name);
        try {
            fixture.issuedSessionPolicyAndTokenAreEnforced();
        } finally {
            fixture.cleanup.afterEach(mock(ExtensionContext.class));
        }
        SignedIamUsers.iam(endpoint, "GetRole", Map.of("RoleName", name)).then().statusCode(404);
    }

    private ManageConnectionsIamIntegrationTest nestedFixture(String name) {
        ManageConnectionsIamIntegrationTest fixture = new ManageConnectionsIamIntegrationTest();
        fixture.endpoint = endpoint;
        fixture.connections = connections;
        fixture.roleNames = () -> name;
        return fixture;
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
        return "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:ManageConnections\",\"Resource\":\"" + arn + "\"}}";
    }


    private Fixture fixture() {
        ApiGatewayV2Client api = ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        cleanup.register(api::close);
        String id = api.createApi(request -> request.name("connections-iam-" + UUID.randomUUID())
                .protocolType("WEBSOCKET").routeSelectionExpression("$request.body.action")).apiId();
        cleanup.register(() -> api.deleteApi(request -> request.apiId(id)));
        api.createStage(request -> request.apiId(id).stageName("prod"));
        String connectionId = UUID.randomUUID().toString();
        ServerWebSocket socket = mock(ServerWebSocket.class);
        cleanup.register(() -> connections.unregister(connectionId));
        connections.register(connectionId, new ConnectionInfo(connectionId, id, "prod", "us-east-1",
                0, 0, "127.0.0.1", "fixture"), socket);
        return new Fixture(id, connectionId, socket);
    }

    private Map<String, String> signed(String path, String method, Key key) throws Exception {
        byte[] body = "POST".equals(method) ? "hello".getBytes(StandardCharsets.UTF_8) : new byte[0];
        return ExecuteApiRequestSigner.signedHeaders(method, path.replace("@", "%40"), Map.of(), endpoint.getHost() + ":" + endpoint.getPort(),
                body, key.accessKey(), key.secret(), "us-east-1", Instant.now());
    }

    private void invoke(Fixture fixture, String method, Key key, int status) throws Exception {
        RequestSpecification request = given().headers(signed(fixture.path(), method, key));
        if (key.token() != null) {
            request.header("X-Amz-Security-Token", key.token());
        }
        if ("POST".equals(method)) {
            request.body("hello");
        }
        request.request(method, fixture.path()).then().statusCode(status);
    }

    private record Fixture(String apiId, String connectionId, ServerWebSocket socket) {
        String path() {
            return "/execute-api/" + apiId + "/prod/@connections/" + connectionId;
        }

        String arn(String method) {
            return "arn:aws:execute-api:us-east-1:" + OWNER + ":" + apiId + "/prod/" + method + "/@connections";
        }
    }

    private record Key(String accessKey, String secret, String token) { }
}

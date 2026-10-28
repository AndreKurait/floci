package io.github.hectorvent.floci.services.apigateway;

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
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.vertx.core.http.ServerWebSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.ApiGatewayManagementApiException;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class ManageConnectionsIamIntegrationTest {
    private static final String OWNER = "000000000000";
    @InjectSpy
    WebSocketConnectionManager connections;
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void sdkGetPostDeleteRequireGrantAndRecheckRevocation() throws Exception {
        Fixture f = fixture();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "connections-", "manage", "{\"Statement\":[]}");
             ApiGatewayManagementApiClient client = client(f, AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            denied(() -> client.getConnection(b -> b.connectionId(f.connectionId())));
            denied(() -> client.postToConnection(b -> b.connectionId(f.connectionId()).data(SdkBytes.fromUtf8String("denied"))));
            denied(() -> client.deleteConnection(b -> b.connectionId(f.connectionId())));
            assertFalse(connections.isServerInitiatedClose(f.connectionId()));
            verifyNoInteractions(f.socket());
            putPolicy(user, policy(f.arn()).replace("ManageConnections", "Invoke"));
            denied(() -> client.getConnection(b -> b.connectionId(f.connectionId())));
            putPolicy(user, policy(f.arn()));
            assertNotNull(client.getConnection(b -> b.connectionId(f.connectionId())).connectedAt());
            client.postToConnection(b -> b.connectionId(f.connectionId()).data(SdkBytes.fromUtf8String("allowed")));
            verify(f.socket()).writeTextMessage("allowed");
            putPolicy(user, policy(f.arn()).replace("Allow", "Deny"));
            denied(() -> client.deleteConnection(b -> b.connectionId(f.connectionId())));
            assertFalse(connections.isServerInitiatedClose(f.connectionId()));
            putPolicy(user, policy(f.arn()));
            client.deleteConnection(b -> b.connectionId(f.connectionId()));
            assertTrue(connections.isServerInitiatedClose(f.connectionId()));
            verify(f.socket()).close();
        }
    }

    @Test
    void unsignedMalformedAndIncorrectMethodGrantsCannotReadMetadata() throws Exception {
        Fixture f = fixture();
        String path = "/execute-api/" + f.apiId() + "/prod/@connections/" + f.connectionId();
        given().get(path).then().statusCode(403);
        given().header("Authorization", "AWS4-HMAC-SHA256 invalid").get(path).then().statusCode(403);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "connections-", "manage", policy(f.arn()).replace("/*/", "/POST/"));
             ApiGatewayManagementApiClient client = client(f, AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            denied(() -> client.getConnection(b -> b.connectionId(f.connectionId())));
            putPolicy(user, policy(f.arn()).replace("/prod/", "/other/"));
            denied(() -> client.deleteConnection(b -> b.connectionId(f.connectionId())));
            assertFalse(connections.isServerInitiatedClose(f.connectionId()));
            verifyNoInteractions(f.socket());
        }
    }

    @Test
    void issuedStsSessionUsesFullRolePathSessionPolicyTokenAndCurrentRoleGrant() throws Exception {
        Fixture f = fixture();
        String name = "connections-role-" + UUID.randomUUID();
        cleanup.register(() -> cleanupIam("DeleteRole", Map.of("RoleName", name)));
        String role = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", name, "Path", "/service-role/",
                "AssumeRolePolicyDocument", "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::" + OWNER
                        + ":root\"},\"Action\":\"sts:AssumeRole\"}}")).then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> cleanupIam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "manage")));
        rolePolicy(name, policy(f.arn()));
        AwsSessionCredentials allowed = assume(role, null);
        try (ApiGatewayManagementApiClient restricted = client(f, assume(role, policy(f.arn()).replace("Allow", "Deny")));
             ApiGatewayManagementApiClient missingToken = client(f, AwsBasicCredentials.create(allowed.accessKeyId(), allowed.secretAccessKey()));
             ApiGatewayManagementApiClient invalidToken = client(f, AwsSessionCredentials.create(allowed.accessKeyId(), allowed.secretAccessKey(), "wrong"));
             ApiGatewayManagementApiClient valid = client(f, allowed)) {
            denied(() -> restricted.getConnection(b -> b.connectionId(f.connectionId())));
            denied(() -> missingToken.getConnection(b -> b.connectionId(f.connectionId())));
            denied(() -> invalidToken.getConnection(b -> b.connectionId(f.connectionId())));
            assertNotNull(valid.getConnection(b -> b.connectionId(f.connectionId())).identity());
            rolePolicy(name, policy(f.arn()).replace("Allow", "Deny"));
            denied(() -> valid.deleteConnection(b -> b.connectionId(f.connectionId())));
            assertFalse(connections.isServerInitiatedClose(f.connectionId()));
        }
    }

    @Test
    void connectionFromDifferentApiIsNotReachableThroughPermittedApi() throws Exception {
        Fixture permitted = fixture();
        Fixture other = fixture();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "connections-", "manage", policy(permitted.arn()));
             ApiGatewayManagementApiClient client = client(permitted, AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            ApiGatewayManagementApiException failure = assertThrows(ApiGatewayManagementApiException.class,
                    () -> client.postToConnection(b -> b.connectionId(other.connectionId()).data(SdkBytes.fromUtf8String("wrong-api"))));
            assertEquals(410, failure.statusCode());
            verifyNoInteractions(other.socket());
        }
    }

    @Test
    void presignedManagementRequestStillRequiresCurrentGrant() throws Exception {
        Fixture f = fixture();
        String path = "/execute-api/" + f.apiId() + "/prod/@connections/" + f.connectionId();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "connections-", "manage", policy(f.arn()))) {
            String query = ExecuteApiRequestSigner.presignedQueryString("GET", path,
                    endpoint.getHost() + ":" + endpoint.getPort(), user.accessKeyId(), user.secretAccessKey(),
                    "us-east-1", Instant.now(), 60);
            given().urlEncodingEnabled(false).get(path + "?" + query).then().statusCode(200);
            putPolicy(user, policy(f.arn()).replace("Allow", "Deny"));
            given().urlEncodingEnabled(false).get(path + "?" + query).then().statusCode(403);
        }
    }

    @Test
    void actualSocketReceivesOnlyAuthorizedMessagesAndAuthorizedClose() throws Exception {
        Fixture f = fixture();
        CompletableFuture<String> registered = new CompletableFuture<>();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            ConnectionInfo info = invocation.getArgument(1);
            if (f.apiId().equals(info.getApiId())) {
                registered.complete(invocation.getArgument(0));
            }
            return result;
        }).when(connections).register(anyString(), any(), any());
        CompletableFuture<String> message = new CompletableFuture<>();
        CompletableFuture<Integer> closed = new CompletableFuture<>();
        try (HttpClient http = HttpClient.newHttpClient();
             SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "connections-", "manage", policy(f.arn()));
             ApiGatewayManagementApiClient client = client(f, AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            WebSocket socket = http.newWebSocketBuilder().buildAsync(
                    URI.create("ws://" + endpoint.getHost() + ":" + endpoint.getPort() + "/ws/" + f.apiId() + "/prod"),
                    new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            message.complete(data.toString());
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
                            closed.complete(status);
                            return null;
                        }
                    }).get(10, TimeUnit.SECONDS);
            try {
                String id = registered.get(10, TimeUnit.SECONDS);
                assertNotNull(client.getConnection(b -> b.connectionId(id)).identity());
                putPolicy(user, policy(f.arn()).replace("Allow", "Deny"));
                denied(() -> client.postToConnection(b -> b.connectionId(id).data(SdkBytes.fromUtf8String("denied"))));
                denied(() -> client.deleteConnection(b -> b.connectionId(id)));
                assertTrue(connections.isConnected(id));
                assertFalse(closed.isDone());
                putPolicy(user, policy(f.arn()));
                client.postToConnection(b -> b.connectionId(id).data(SdkBytes.fromUtf8String("allowed")));
                assertEquals("allowed", message.get(10, TimeUnit.SECONDS));
                client.deleteConnection(b -> b.connectionId(id));
                closed.get(10, TimeUnit.SECONDS);
            } finally {
                socket.abort();
            }
        }
    }

    private void cleanupIam(String action, Map<String, String> parameters) {
        try {
            SignedIamUsers.iam(endpoint, action, parameters).then().statusCode(200);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to clean up IAM fixture with " + action, e);
        }
    }

    private Fixture fixture() {
        ApiGatewayV2Client api = ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        cleanup.register(api::close);
        String id = api.createApi(b -> b.name("manage-connections-" + UUID.randomUUID()).protocolType("WEBSOCKET")
                .routeSelectionExpression("$request.body.action")).apiId();
        cleanup.register(() -> api.deleteApi(b -> b.apiId(id)));
        api.createStage(b -> b.apiId(id).stageName("prod"));
        String connection = UUID.randomUUID().toString();
        ServerWebSocket socket = mock(ServerWebSocket.class);
        cleanup.register(() -> connections.unregister(connection));
        connections.register(connection, new ConnectionInfo(connection, id, "prod", "us-east-1", 1, 2, "127.0.0.1", "fixture"), socket);
        return new Fixture(id, connection, "arn:aws:execute-api:us-east-1:" + OWNER + ":" + id + "/prod/*/@connections", socket);
    }

    private ApiGatewayManagementApiClient client(Fixture f, AwsCredentials credentials) {
        return ApiGatewayManagementApiClient.builder().endpointOverride(endpoint.resolve("execute-api/" + f.apiId() + "/prod"))
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
    }

    private AwsSessionCredentials assume(String role, String policy) throws Exception {
        String body = "Action=AssumeRole&Version=2011-06-15&RoleSessionName=manage-session&RoleArn=" + URLEncoder.encode(role, StandardCharsets.UTF_8);
        if (policy != null) {
            body += "&Policy=" + URLEncoder.encode(policy, StandardCharsets.UTF_8);
        }
        Response response = given().headers(AwsRequestSigner.signedAs("test", "test", "sts")
                        .headersFor("POST", endpoint, Map.of(), body.getBytes(StandardCharsets.UTF_8)))
                .contentType("application/x-www-form-urlencoded").body(body).post(endpoint).then().statusCode(200).extract().response();
        String prefix = "AssumeRoleResponse.AssumeRoleResult.Credentials.";
        return AwsSessionCredentials.create(response.xmlPath().getString(prefix + "AccessKeyId"),
                response.xmlPath().getString(prefix + "SecretAccessKey"), response.xmlPath().getString(prefix + "SessionToken"));
    }

    private void putPolicy(SignedIamUsers.User user, String policy) throws Exception {
        SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(), "PolicyName", "manage", "PolicyDocument", policy)).then().statusCode(200);
    }

    private void rolePolicy(String name, String policy) throws Exception {
        SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", name, "PolicyName", "manage", "PolicyDocument", policy)).then().statusCode(200);
    }

    private static void denied(Runnable request) {
        ApiGatewayManagementApiException failure = assertThrows(ApiGatewayManagementApiException.class, request::run);
        assertEquals(403, failure.statusCode());
    }

    private static String policy(String arn) {
        return "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:ManageConnections\",\"Resource\":\"" + arn + "\"}}";
    }

    private record Fixture(String apiId, String connectionId, String arn, ServerWebSocket socket) { }
}

package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class WebSocketRouteIamIntegrationTest {
    private static final String OWNER = "000000000000";
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedCallerNeedsConnectPermissionEvenWithoutIntegration(boolean integrated) throws Exception {
        Fixture fixture = fixture(integrated, "AWS_IAM");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ws-route-", "invoke", "{\"Statement\":[]}")) {
            Key key = new Key(user.accessKeyId(), user.secretAccessKey(), null);
            invoke(fixture, null, 403);
            invoke(fixture, key, 403);
            for (String wrong : new String[]{fixture.arn().replace("/prod/", "/other/"),
                    fixture.arn().replace("/$connect", "/GET/$connect"),
                    fixture.arn().replace("/$connect", "/$default"),
                    fixture.arn().replace("us-east-1", "us-west-2")}) {
                putPolicy(user, policy(wrong));
                invoke(fixture, key, 403);
            }
            putPolicy(user, policy(fixture.arn()));
            invoke(fixture, new Key(key.accessKey(), "wrong-secret", null), 403);
            invoke(fixture, key, 101);
            putPolicy(user, policy(fixture.arn()).replace("Allow", "Deny"));
            invoke(fixture, key, 403);
        }
    }

    @Test
    void noneRouteStillAcceptsUnsignedConnection() throws Exception {
        invoke(fixture(false, "NONE"), null, 101);
    }

    @Test
    void issuedRoleSessionsRetainPolicyTokenAndRevocationChecks() throws Exception {
        Fixture fixture = fixture(true, "AWS_IAM");
        String name = "ws-route-role-" + UUID.randomUUID();
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
        invoke(fixture, allowed, 101);
        SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy(fixture.arn()).replace("Allow", "Deny"))).then().statusCode(200);
        invoke(fixture, allowed, 403);
    }

    @Test
    void signedConnectionUsesPeerAddressAndTransportRatherThanForwardedHeaders() throws Exception {
        Fixture fixture = fixture(true, "AWS_IAM");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ws-transport-", "invoke",
                transportPolicy(fixture.arn(), "Allow"))) {
            Key key = new Key(user.accessKeyId(), user.secretAccessKey(), null);
            invoke(fixture, key, 101);
            putPolicy(user, transportPolicy(fixture.arn(), "Deny"));
            invoke(fixture, key, 403);
        }
    }

    @Test
    void issuedSessionEvaluatesConnectionTransportConditions() throws Exception {
        Fixture fixture = fixture(false, "AWS_IAM");
        String name = "ws-transport-role-" + UUID.randomUUID();
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                + OWNER + ":root\"},\"Action\":\"sts:AssumeRole\"}}";
        cleanup.register(() -> cleanupIam("DeleteRole", Map.of("RoleName", name)));
        String role = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", name, "Path", "/service-role/",
                "AssumeRolePolicyDocument", trust)).then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> cleanupIam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
        SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy(fixture.arn()))).then().statusCode(200);
        invoke(fixture, assume(role, transportPolicy(fixture.arn(), "Allow")), 101);
        invoke(fixture, assume(role, transportPolicy(fixture.arn(), "Deny")), 403);
    }

    private static String transportPolicy(String arn, String effect) {
        return "{\"Statement\":{\"Effect\":\"" + effect
                + "\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + arn + "\",\"Condition\":{"
                + "\"IpAddress\":{\"aws:SourceIp\":[\"127.0.0.0/8\",\"::1/128\"]},"
                + "\"Bool\":{\"aws:SecureTransport\":\"false\"}}}}";
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

    private Fixture fixture(boolean integrated, String authorization) throws Exception {
        ApiGatewayV2Client api = ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        cleanup.register(api::close);
        String id = api.createApi(request -> request.name("ws-route-iam-" + UUID.randomUUID())
                .protocolType("WEBSOCKET").routeSelectionExpression("$request.body.action")).apiId();
        cleanup.register(() -> api.deleteApi(request -> request.apiId(id)));
        String target = integrated ? "integrations/" + api.createIntegration(request -> request.apiId(id)
                .integrationType("MOCK").requestTemplates(Map.of("application/json", "{\"statusCode\":200}")))
                .integrationId() : null;
        api.createRoute(request -> request.apiId(id).routeKey("$connect").authorizationType(authorization).target(target));
        api.createStage(request -> request.apiId(id).stageName("prod").autoDeploy(true));
        return new Fixture(id, "arn:aws:execute-api:us-east-1:" + OWNER + ":" + id + "/prod/$connect");
    }

    private void invoke(Fixture fixture, Key key, int status) throws Exception {
        String path = "/ws/" + fixture.apiId() + "/prod";
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket.Builder builder = client.newWebSocketBuilder()
                    .header("X-Forwarded-For", "192.0.2.5").header("X-Forwarded-Proto", "https");
            if (key != null) {
                ExecuteApiRequestSigner.signedHeaders("GET", path, Map.of("tag", "a b"),
                        endpoint.getHost() + ":" + endpoint.getPort(), new byte[0], key.accessKey(), key.secret(),
                        "us-east-1", Instant.now()).forEach(builder::header);
                if (key.token() != null) {
                    builder.header("X-Amz-Security-Token", key.token());
                }
            }
            URI uri = URI.create(WebSocketTestSupport.buildWsUrl(endpoint, fixture.apiId(), "prod") + "?tag=a%20b");
            try {
                WebSocket socket = builder.buildAsync(uri, new WebSocket.Listener() {}).get(10, TimeUnit.SECONDS);
                try {
                    assertEquals(101, status);
                } finally {
                    socket.abort();
                }
            } catch (ExecutionException exception) {
                WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class, exception.getCause());
                assertEquals(status, handshake.getResponse().statusCode());
            }
        }
    }

    private record Fixture(String apiId, String arn) { }
    private record Key(String accessKey, String secret, String token) { }
}

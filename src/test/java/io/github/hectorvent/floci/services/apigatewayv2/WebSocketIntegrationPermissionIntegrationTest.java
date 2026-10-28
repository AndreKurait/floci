package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class WebSocketIntegrationPermissionIntegrationTest {

    private static final String OWNER = "000000000000";
    private static final String FOREIGN = "343434343434";
    private static final String REGION = "us-east-1";

    @InjectSpy
    LambdaService lambda;

    @InjectSpy
    CallerCallAuthorizer callerAuthorizer;

    @InjectSpy
    IamService iamService;

    @TestHTTPResource("/")
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"000000000000,us-east-1,AWS_PROXY", "343434343434,us-west-2,AWS_PROXY",
            "000000000000,us-east-1,AWS", "343434343434,us-west-2,AWS"})
    void handshakeRequiresQualifiedGrantAndRechecksAfterRevocation(String targetOwner, String targetRegion, String integrationType)
            throws Exception {
        Fixture fixture = fixture(targetOwner, targetRegion, integrationType, null);
        invoke(fixture, 500);
        String source = sourceArn(fixture, REGION);
        add(fixture, new Grant(null, "apigateway.amazonaws.com", source, OWNER));
        invoke(fixture, 500);
        remove(fixture, null);
        add(fixture, new Grant("live", "apigateway.amazonaws.com", source, FOREIGN));
        invoke(fixture, 500);
        remove(fixture, "live");
        verifyInvocations(fixture, 0);
        add(fixture, new Grant("live", "apigateway.amazonaws.com", source, OWNER));
        invoke(fixture, 101);
        remove(fixture, "live");
        invoke(fixture, 500);
        verifyInvocations(fixture, 1);
    }

    @ParameterizedTest
    @CsvSource({"000000000000,us-east-1,AWS_PROXY", "343434343434,eu-west-1,AWS_PROXY",
            "000000000000,us-east-1,AWS", "343434343434,eu-west-1,AWS"})
    void configuredRoleRequiresTrustIdentityAndQualifiedResource(String owner, String region, String type) throws Exception {
        String roleName = "integration-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + OWNER + ":role/service-role/" + roleName;
        Fixture fixture = fixture(owner, region, type, roleArn);
        invoke(fixture, 500);
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"apigateway.amazonaws.com\"},"
                + "\"Action\":\"sts:AssumeRole\"}}";
        cleanup.register(() -> iam("DeleteRole", Map.of("RoleName", roleName)));
        iam("CreateRole", Map.of("RoleName", roleName, "Path", "/service-role/", "AssumeRolePolicyDocument", trust));
        invoke(fixture, 500);
        String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\""
                + fixture.arn() + "\"}}";
        cleanup.register(() -> iam("DeleteRolePolicy", Map.of("RoleName", roleName, "PolicyName", "invoke")));
        iam("PutRolePolicy", Map.of("RoleName", roleName, "PolicyName", "invoke", "PolicyDocument", policy));
        if (!OWNER.equals(owner)) {
            invoke(fixture, 500);
            lambdaRequest(owner, region).queryParam("Qualifier", "live")
                    .body(Map.of("StatementId", "invoke", "Action", "lambda:InvokeFunction", "Principal", roleArn))
                    .post("/2015-03-31/functions/" + fixture.name() + "/policy").then().statusCode(201);
        }
        invoke(fixture, 101);
        verifyInvocations(fixture, 1);
        iam("PutRolePolicy", Map.of("RoleName", roleName, "PolicyName", "invoke",
                "PolicyDocument", policy.replace("Allow", "Deny")));
        invoke(fixture, 500);
        verifyInvocations(fixture, 1);
    }

    @ParameterizedTest
    @CsvSource({"AWS_PROXY,000000000000,000000000000,us-east-1", "AWS,565656565656,343434343434,eu-west-1"})
    void verifiedCallerPoliciesControlQualifiedLambda(String type, String callerAccount, String targetOwner,
                                                      String targetRegion) throws Exception {
        Fixture fixture = fixture(targetOwner, targetRegion, type, "arn:aws:iam::*:user/*");
        String user = "ws-caller-permission-" + UUID.randomUUID().toString().substring(0, 8);
        cleanup.register(() -> callerIam(callerAccount, "DeleteUser", Map.of("UserName", user)));
        String principal = callerIam(callerAccount, "CreateUser", Map.of("UserName", user))
                .xmlPath().getString("CreateUserResponse.CreateUserResult.User.Arn");
        Response created = callerIam(callerAccount, "CreateAccessKey", Map.of("UserName", user));
        CallerKey key = new CallerKey(created.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"),
                created.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey"), null);
        cleanup.register(() -> callerIam(callerAccount, "DeleteAccessKey", Map.of("UserName", user, "AccessKeyId", key.accessKeyId())));
        signedInvoke(fixture, key, key.secretAccessKey(), 500);
        String policy = """
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s"}}"""
                .formatted(fixture.arn());
        cleanup.register(() -> callerIam(callerAccount, "DeleteUserPolicy", Map.of("UserName", user, "PolicyName", "invoke")));
        callerIam(callerAccount, "PutUserPolicy", Map.of("UserName", user, "PolicyName", "invoke", "PolicyDocument", policy));
        if (!callerAccount.equals(targetOwner)) {
            signedInvoke(fixture, key, key.secretAccessKey(), 500);
            lambdaRequest(targetOwner, targetRegion).queryParam("Qualifier", "live")
                    .body(Map.of("StatementId", "caller", "Action", "lambda:InvokeFunction", "Principal", principal))
                    .post("/2015-03-31/functions/" + fixture.name() + "/policy").then().statusCode(201);
        }
        signedInvoke(fixture, key, "invalid-secret", 500);
        signedInvoke(fixture, new CallerKey(key.accessKeyId(), key.secretAccessKey(), ""), key.secretAccessKey(), 500);
        verifyInvocations(fixture, 0);
        signedInvoke(fixture, key, key.secretAccessKey(), 101);
        verifyInvocations(fixture, 1);
        callerIam(callerAccount, "PutUserPolicy", Map.of("UserName", user, "PolicyName", "invoke",
                "PolicyDocument", policy.replace("Allow", "Deny")));
        signedInvoke(fixture, key, key.secretAccessKey(), 500);
        verifyInvocations(fixture, 1);
    }

    @ParameterizedTest
    @CsvSource({"AWS", "AWS_PROXY"})
    void issuedSessionTokenAndSessionPolicyRemainEffective(String type) throws Exception {
        Fixture fixture = fixture(OWNER, REGION, type, "arn:aws:iam::*:user/*");
        String name = "ws-caller-role-" + UUID.randomUUID().toString().substring(0, 8);
        String trust = """
                {"Statement":{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},
                "Action":"sts:AssumeRole"}}""".formatted(OWNER);
        cleanup.register(() -> iam("DeleteRole", Map.of("RoleName", name)));
        String role = callerIam(OWNER, "CreateRole", Map.of("RoleName", name, "Path", "/caller-path/",
                "AssumeRolePolicyDocument", trust)).xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        String policy = """
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s"}}"""
                .formatted(fixture.arn());
        cleanup.register(() -> iam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
        iam("PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke", "PolicyDocument", policy));
        CallerKey denied = assume(role, policy.replace("Allow", "Deny"));
        signedInvoke(fixture, denied, denied.secretAccessKey(), 500);
        CallerKey allowed = assume(role, null);
        signedInvoke(fixture, new CallerKey(allowed.accessKeyId(), allowed.secretAccessKey(), null),
                allowed.secretAccessKey(), 500);
        signedInvoke(fixture, new CallerKey(allowed.accessKeyId(), allowed.secretAccessKey(), "invalid-token"),
                allowed.secretAccessKey(), 500);
        verifyInvocations(fixture, 0);
        signedInvoke(fixture, allowed, allowed.secretAccessKey(), 101);
        verifyInvocations(fixture, 1);
        iam("PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy.replace("Allow", "Deny")));
        signedInvoke(fixture, allowed, allowed.secretAccessKey(), 500);
        verifyInvocations(fixture, 1);
    }

    @Test
    void retainedHandshakeIdentityRechecksMessageAndDisconnectAfterRevocation() throws Exception {
        Fixture fixture = fixture(OWNER, REGION, "AWS_PROXY", "arn:aws:iam::*:user/*");
        String user = "ws-lifetime-" + UUID.randomUUID().toString().substring(0, 8);
        cleanup.register(() -> callerIam(OWNER, "DeleteUser", Map.of("UserName", user)));
        callerIam(OWNER, "CreateUser", Map.of("UserName", user));
        Response created = callerIam(OWNER, "CreateAccessKey", Map.of("UserName", user));
        CallerKey key = new CallerKey(created.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"),
                created.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey"), null);
        cleanup.register(() -> callerIam(OWNER, "DeleteAccessKey", Map.of("UserName", user, "AccessKeyId", key.accessKeyId())));
        String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\""
                + fixture.arn() + "\"}}";
        cleanup.register(() -> callerIam(OWNER, "DeleteUserPolicy", Map.of("UserName", user, "PolicyName", "invoke")));
        callerIam(OWNER, "PutUserPolicy", Map.of("UserName", user, "PolicyName", "invoke", "PolicyDocument", policy));
        try (ApiGatewayV2Client gateway = ApiGatewayV2Client.builder().endpointOverride(baseUri).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(OWNER, "test"))).build()) {
            String integration = gateway.getIntegrations(request -> request.apiId(fixture.apiId())).items().getFirst().integrationId();
            for (String route : new String[]{"$default", "$disconnect"}) {
                gateway.createRoute(request -> request.apiId(fixture.apiId()).routeKey(route).authorizationType("NONE")
                        .target("integrations/" + integration));
            }
        }
        BlockingQueue<String> invoked = new LinkedBlockingQueue<>();
        BlockingQueue<Decision> checked = new LinkedBlockingQueue<>();
        doAnswer(call -> {
            Decision decision = (Decision) call.callRealMethod();
            checked.add(decision);
            return decision;
        }).when(callerAuthorizer).authorize(eq(key.accessKeyId()), eq(OWNER), any(), any(), eq(fixture.arn()), any());
        doAnswer(call -> {
            invoked.add(new ObjectMapper().readTree((byte[]) call.getArgument(2)).path("requestContext").path("eventType").asText());
            return new InvokeResult(200, null, "{\"statusCode\":200}".getBytes(StandardCharsets.UTF_8), null, "lifetime");
        }).when(lambda).invokeTarget(eq(REGION), eq(fixture.arn()), any(byte[].class), eq(InvocationType.RequestResponse));
        String path = "/ws/" + fixture.apiId() + "/prod";
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders("GET", path, Map.of(),
                baseUri.getHost() + ":" + baseUri.getPort(), new byte[0], key.accessKeyId(), key.secretAccessKey(), REGION, Instant.now());
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket.Builder builder = client.newWebSocketBuilder();
            signed.forEach(builder::header);
            WebSocket socket = builder.buildAsync(URI.create(WebSocketTestSupport.buildWsUrl(baseUri, fixture.apiId(), "prod")),
                    new WebSocket.Listener() {}).get(10, TimeUnit.SECONDS);
            try {
                assertEquals(Decision.ALLOW, checked.poll(10, TimeUnit.SECONDS));
                assertEquals("CONNECT", invoked.poll(10, TimeUnit.SECONDS));
                socket.sendText("hello", true).get(10, TimeUnit.SECONDS);
                assertEquals(Decision.ALLOW, checked.poll(10, TimeUnit.SECONDS));
                assertEquals("MESSAGE", invoked.poll(10, TimeUnit.SECONDS));
                callerIam(OWNER, "PutUserPolicy", Map.of("UserName", user, "PolicyName", "invoke",
                        "PolicyDocument", policy.replace("Allow", "Deny")));
                socket.sendText("revoked", true).get(10, TimeUnit.SECONDS);
                assertEquals(Decision.DENY, checked.poll(10, TimeUnit.SECONDS));
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
                assertEquals(Decision.DENY, checked.poll(10, TimeUnit.SECONDS));
                verifyInvocations(fixture, 2);
            } finally {
                socket.abort();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"allow-userid,AWS_PROXY", "deny-userid,AWS", "allow-token,AWS", "deny-token,AWS_PROXY",
            "allow-tag,AWS_PROXY", "deny-tag,AWS"})
    void configuredRoleUsesRegisteredSessionMetadataAndRevokesIt(String scenario, String integrationType) throws Exception {
        String name = "ws-session-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + OWNER + ":role/team/" + name;
        Fixture fixture = fixture(OWNER, REGION, integrationType, roleArn);
        String trust = """
                {"Statement":{"Effect":"Allow","Principal":{"Service":"apigateway.amazonaws.com"},
                "Action":"sts:AssumeRole"}}""";
        cleanup.register(() -> iam("DeleteRole", Map.of("RoleName", name)));
        iam("CreateRole", Map.of("RoleName", name, "Path", "/team/", "AssumeRolePolicyDocument", trust));
        iam("TagRole", Map.of("RoleName", name, "Tags.member.1.Key", "purpose", "Tags.member.1.Value", "integration"));
        String key = switch (scenario.substring(scenario.indexOf('-') + 1)) {
            case "userid" -> "aws:userid";
            case "token" -> "aws:TokenIssueTime";
            default -> "aws:PrincipalTag/purpose";
        };
        String condition = key.equals("aws:PrincipalTag/purpose")
                ? "\"StringEquals\":{\"" + key + "\":\"integration\"}"
                : "\"Null\":{\"" + key + "\":\"false\"}";
        String policy = """
                {"Statement":[{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s"%s},
                {"Effect":"%s","Action":"lambda:InvokeFunction","Resource":"%s","Condition":{%s}}]}"""
                .formatted(fixture.arn(), scenario.startsWith("allow") ? ",\"Condition\":{" + condition + "}" : "",
                        scenario.startsWith("deny") ? "Deny" : "Allow", fixture.arn(), condition);
        cleanup.register(() -> iam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
        iam("PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke", "PolicyDocument", policy));
        invoke(fixture, scenario.startsWith("allow") ? 101 : 500);
        verifyInvocations(fixture, scenario.startsWith("allow") ? 1 : 0);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(iamService).registerSessionForAccount(eq(OWNER), keyCaptor.capture(), anyString(), anyString(),
                eq(roleArn), any(Instant.class), isNull(), anyString(), nullable(String.class));
        assertTrue(iamService.resolveCallerArns(keyCaptor.getValue()).isEmpty());
    }

    private static CallerKey assume(String role, String policy) {
        RequestSpecification request = given().header("Authorization", EnforcementFixtures.auth(OWNER, "sts"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", "AssumeRole")
                .formParam("RoleArn", role).formParam("RoleSessionName", "caller-session");
        if (policy != null) {
            request.formParam("Policy", policy);
        }
        Response response = request.post("/").then().statusCode(200).extract().response();
        String prefix = "AssumeRoleResponse.AssumeRoleResult.Credentials.";
        return new CallerKey(response.xmlPath().getString(prefix + "AccessKeyId"),
                response.xmlPath().getString(prefix + "SecretAccessKey"),
                response.xmlPath().getString(prefix + "SessionToken"));
    }

    private static Response callerIam(String account, String action, Map<String, String> parameters) {
        return given().header("Authorization", EnforcementFixtures.auth(account, "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action)
                .formParams(parameters).post("/").then().statusCode(200).extract().response();
    }

    private record CallerKey(String accessKeyId, String secretAccessKey, String sessionToken) { }

    private void signedInvoke(Fixture fixture, CallerKey key, String secret, int status) throws Exception {
        String host = baseUri.getHost() + ":" + baseUri.getPort();
        String path = "/ws/" + fixture.apiId() + "/prod";
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders("GET", path, Map.of("tag", "a b"), host,
                new byte[0], key.accessKeyId(), secret, REGION, Instant.now());
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket.Builder builder = client.newWebSocketBuilder();
            signed.forEach(builder::header);
            if (key.sessionToken() != null) {
                builder.header("X-Amz-Security-Token", key.sessionToken());
            }
            URI uri = URI.create(WebSocketTestSupport.buildWsUrl(baseUri, fixture.apiId(), "prod") + "?tag=a%20b");
            try {
                WebSocket socket = builder.buildAsync(uri, new WebSocket.Listener() {}).get(10, TimeUnit.SECONDS);
                try {
                    assertEquals(101, status);
                } finally {
                    socket.abort();
                }
            } catch (ExecutionException exception) {
                WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class,
                        exception.getCause());
                assertEquals(status, handshake.getResponse().statusCode());
            }
        }
    }

    private static void iam(String action, Map<String, String> parameters) {
        given().header("Authorization", EnforcementFixtures.auth(OWNER, "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action)
                .formParam("Version", "2010-05-08").formParams(parameters).post("/").then().statusCode(200);
    }

    private Fixture fixture(String targetOwner, String targetRegion, String integrationType, String credentials) throws Exception {
        ApiGatewayV2Client gateway = ApiGatewayV2Client.builder().endpointOverride(baseUri).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(OWNER, "test"))).build();
        cleanup.register(gateway::close);
        String name = "ws-integration-permission-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = "arn:aws:lambda:" + targetRegion + ":" + targetOwner + ":function:" + name + ":live";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => ({});".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        cleanup.register(() -> lambdaRequest(targetOwner, targetRegion)
                .delete("/2015-03-31/functions/" + name).then().statusCode(204));
        lambdaRequest(targetOwner, targetRegion).body(Map.of("FunctionName", name, "Runtime", "nodejs20.x",
                "Handler", "index.handler", "Role", "arn:aws:iam::" + targetOwner + ":role/lambda-role",
                "Code", Map.of("ZipFile", Base64.getEncoder().encodeToString(bytes.toByteArray()))))
                .post("/2015-03-31/functions").then().statusCode(201);
        lambdaRequest(targetOwner, targetRegion).body("{}")
                .post("/2015-03-31/functions/" + name + "/versions").then().statusCode(201);
        lambdaRequest(targetOwner, targetRegion).body(Map.of("Name", "live", "FunctionVersion", "1"))
                .post("/2015-03-31/functions/" + name + "/aliases").then().statusCode(201);
        String api = gateway.createApi(request -> request.name(name).protocolType("WEBSOCKET")
                .routeSelectionExpression("$request.body.action")).apiId();
        cleanup.register(() -> gateway.deleteApi(request -> request.apiId(api)));
        String integration = gateway.createIntegration(request -> request.apiId(api).integrationType(integrationType).integrationMethod("POST")
                .credentialsArn(credentials).integrationUri("arn:aws:apigateway:" + targetRegion + ":lambda:path/2015-03-31/functions/"
                        + arn + "/invocations")).integrationId();
        gateway.createRoute(request -> request.apiId(api).routeKey("$connect").authorizationType("NONE")
                .target("integrations/" + integration));
        gateway.createStage(request -> request.apiId(api).stageName("prod").autoDeploy(true));
        doReturn(new InvokeResult(200, null,
                "{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8), null, "permission-test"))
                .when(lambda).invokeTarget(eq(REGION), eq(arn), any(byte[].class), eq(InvocationType.RequestResponse));
        return new Fixture(name, api, arn, targetOwner, targetRegion);
    }

    private static void add(Fixture fixture, Grant grant) {
        RequestSpecification request = lambdaRequest(fixture.owner(), fixture.region());
        if (grant.qualifier() != null) {
            request.queryParam("Qualifier", grant.qualifier());
        }
        request.body(Map.of("StatementId", "invoke", "Action", "lambda:InvokeFunction",
                "Principal", grant.principal(), "SourceArn", grant.sourceArn(), "SourceAccount", grant.sourceAccount()))
                .post("/2015-03-31/functions/" + fixture.name() + "/policy").then().statusCode(201);
    }

    private static void remove(Fixture fixture, String qualifier) {
        RequestSpecification request = lambdaRequest(fixture.owner(), fixture.region());
        if (qualifier != null) {
            request.queryParam("Qualifier", qualifier);
        }
        request.delete("/2015-03-31/functions/" + fixture.name() + "/policy/invoke").then().statusCode(204);
    }

    private static RequestSpecification lambdaRequest(String owner, String region) {
        return given().header("Authorization", EnforcementFixtures.auth(owner, region, "lambda"))
                .contentType("application/json");
    }

    private static String sourceArn(Fixture fixture, String region) {
        return "arn:aws:execute-api:" + region + ":" + OWNER + ":" + fixture.apiId() + "/prod/$connect";
    }

    private void invoke(Fixture fixture, int status) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI uri = URI.create(WebSocketTestSupport.buildWsUrl(baseUri, fixture.apiId(), "prod"));
            try {
                WebSocket socket = client.newWebSocketBuilder().header("Authorization", "identity")
                        .buildAsync(uri, new WebSocket.Listener() {}).get(10, TimeUnit.SECONDS);
                try {
                    assertEquals(101, status);
                } finally {
                    socket.abort();
                }
            } catch (ExecutionException exception) {
                WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class,
                        exception.getCause());
                assertEquals(status, handshake.getResponse().statusCode());
            }
        }
    }

    private void verifyInvocations(Fixture fixture, int count) {
        verify(lambda, times(count)).invokeTarget(eq(REGION), eq(fixture.arn()), any(byte[].class),
                eq(InvocationType.RequestResponse));
    }

    private record Fixture(String name, String apiId, String arn, String owner, String region) { }

    private record Grant(String qualifier, String principal, String sourceArn, String sourceAccount) { }
}

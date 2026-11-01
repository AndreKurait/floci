package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.core.common.AwsException;
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
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
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
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class HttpIntegrationPermissionIntegrationTest {

    private static final String OWNER = "000000000000";
    private static final String FOREIGN = "343434343434";
    private static final String REGION = "us-east-1";

    @InjectSpy
    LambdaService lambda;

    @InjectSpy
    IamService iamService;

    @TestHTTPResource("/")
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"000000000000,us-east-1", "343434343434,us-west-2"})
    void requestRequiresQualifiedGrantAndRechecksAfterRevocation(String targetOwner, String targetRegion)
            throws Exception {
        Fixture fixture = fixture(targetOwner, targetRegion);
        invoke(fixture, 500);
        String source = sourceArn(fixture, targetRegion);
        add(fixture, new Grant(null, "apigateway.amazonaws.com", source, OWNER));
        invoke(fixture, 500);
        remove(fixture, null);
        add(fixture, new Grant("live", "apigateway.amazonaws.com", source, FOREIGN));
        invoke(fixture, 500);
        remove(fixture, "live");
        verifyInvocations(fixture, 0);
        add(fixture, new Grant("live", "apigateway.amazonaws.com", source, OWNER));
        invoke(fixture, 200);
        remove(fixture, "live");
        invoke(fixture, 500);
        verifyInvocations(fixture, 1);
    }

    @ParameterizedTest
    @CsvSource({"000000000000,us-east-1,1.0", "343434343434,us-west-2,2.0"})
    void configuredRoleRequiresTrustIdentityAndQualifiedResource(String targetOwner, String targetRegion,
                                                                String payloadVersion) throws Exception {
        String roleName = "http-role-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + OWNER + ":role/service-role/" + roleName;
        Fixture fixture = fixture(targetOwner, targetRegion, roleArn, payloadVersion);
        invoke(fixture, 500);
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"apigateway.amazonaws.com\"},"
                + "\"Action\":\"sts:AssumeRole\"}}";
        createRole(roleName, "/service-role/", trust, cleanup);
        invoke(fixture, 500);
        String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\""
                + fixture.arn() + "\"}}";
        putRolePolicy(roleName, policy, cleanup);
        if (!OWNER.equals(targetOwner)) {
            invoke(fixture, 500);
            lambdaRequest(targetOwner, targetRegion).queryParam("Qualifier", "live")
                    .body(Map.of("StatementId", "invoke", "Action", "lambda:InvokeFunction", "Principal", roleArn))
                    .post("/2015-03-31/functions/" + fixture.name() + "/policy").then().statusCode(201);
        }
        invoke(fixture, 200);
        iam("PutRolePolicy", Map.of("RoleName", roleName, "PolicyName", "invoke",
                "PolicyDocument", policy.replace("Allow", "Deny")));
        invoke(fixture, 500);
        verifyInvocations(fixture, 1);
    }

    @ParameterizedTest
    @CsvSource({"1.0,000000000000,000000000000,us-east-1", "2.0,565656565656,343434343434,eu-west-1"})
    void verifiedCallerPoliciesControlQualifiedLambda(String type, String callerAccount, String targetOwner,
                                                      String targetRegion) throws Exception {
        Fixture fixture = fixture(targetOwner, targetRegion, "arn:aws:iam::*:user/*", type);
        String user = "http-caller-permission-" + UUID.randomUUID().toString().substring(0, 8);
        String principal = createUser(callerAccount, user, cleanup)
                .xmlPath().getString("CreateUserResponse.CreateUserResult.User.Arn");
        Response created = callerIam(callerAccount, "CreateAccessKey", Map.of("UserName", user));
        CallerKey key = new CallerKey(created.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"),
                created.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey"), null);
        cleanup.register(() -> callerIam(callerAccount, "DeleteAccessKey", Map.of("UserName", user, "AccessKeyId", key.accessKeyId())));
        signedInvoke(fixture, key, key.secretAccessKey(), 500);
        String policy = """
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s"}}"""
                .formatted(fixture.arn());
        putUserPolicy(callerAccount, user, policy, cleanup);
        if (!callerAccount.equals(targetOwner)) {
            signedInvoke(fixture, key, key.secretAccessKey(), 500);
            lambdaRequest(targetOwner, targetRegion).queryParam("Qualifier", "live")
                    .body(Map.of("StatementId", "caller", "Action", "lambda:InvokeFunction", "Principal", principal))
                    .post("/2015-03-31/functions/" + fixture.name() + "/policy").then().statusCode(201);
        }
        signedInvoke(fixture, key, "invalid-secret", 500);
        verifyInvocations(fixture, 0);
        signedInvoke(fixture, key, key.secretAccessKey(), 200);
        verifyInvocations(fixture, 1);
        callerIam(callerAccount, "PutUserPolicy", Map.of("UserName", user, "PolicyName", "invoke",
                "PolicyDocument", policy.replace("Allow", "Deny")));
        signedInvoke(fixture, key, key.secretAccessKey(), 500);
        verifyInvocations(fixture, 1);
    }

    @ParameterizedTest
    @CsvSource({"1.0", "2.0"})
    void issuedSessionTokenAndSessionPolicyRemainEffective(String type) throws Exception {
        Fixture fixture = fixture(OWNER, REGION, "arn:aws:iam::*:user/*", type);
        String name = "http-caller-role-" + UUID.randomUUID().toString().substring(0, 8);
        String trust = """
                {"Statement":{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},
                "Action":"sts:AssumeRole"}}""".formatted(OWNER);
        String role = createRole(name, "/caller-path/", trust, cleanup)
                .xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        String policy = """
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s"}}"""
                .formatted(fixture.arn());
        putRolePolicy(name, policy, cleanup);
        CallerKey denied = assume(role, policy.replace("Allow", "Deny"));
        signedInvoke(fixture, denied, denied.secretAccessKey(), 500);
        CallerKey allowed = assume(role, null);
        signedInvoke(fixture, new CallerKey(allowed.accessKeyId(), allowed.secretAccessKey(), null),
                allowed.secretAccessKey(), 500);
        signedInvoke(fixture, new CallerKey(allowed.accessKeyId(), allowed.secretAccessKey(), "invalid-token"),
                allowed.secretAccessKey(), 500);
        verifyInvocations(fixture, 0);
        signedInvoke(fixture, allowed, allowed.secretAccessKey(), 200);
        verifyInvocations(fixture, 1);
        iam("PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy.replace("Allow", "Deny")));
        signedInvoke(fixture, allowed, allowed.secretAccessKey(), 500);
        verifyInvocations(fixture, 1);
    }

    @ParameterizedTest
    @CsvSource({"allow-userid,1.0", "deny-userid,2.0", "allow-token,2.0", "deny-token,1.0",
            "allow-tag,1.0", "deny-tag,2.0"})
    void configuredRoleUsesRegisteredSessionMetadataAndRevokesIt(String scenario, String payloadVersion) throws Exception {
        String name = "http-session-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + OWNER + ":role/team/" + name;
        Fixture fixture = fixture(OWNER, REGION, roleArn, payloadVersion);
        String trust = """
                {"Statement":{"Effect":"Allow","Principal":{"Service":"apigateway.amazonaws.com"},
                "Action":"sts:AssumeRole"}}""";
        createRole(name, "/team/", trust, cleanup);
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
        putRolePolicy(name, policy, cleanup);
        invoke(fixture, scenario.startsWith("allow") ? 200 : 500);
        verifyInvocations(fixture, scenario.startsWith("allow") ? 1 : 0);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(iamService).registerSessionForAccount(eq(OWNER), keyCaptor.capture(), anyString(), anyString(),
                eq(roleArn), any(Instant.class), isNull(), anyString(), nullable(String.class));
        assertTrue(iamService.resolveCallerArns(keyCaptor.getValue()).isEmpty());
    }

    private static Response createRole(String name, String path, String trust, PartitionCleanup ownedCleanup) {
        Response response = callerIam(OWNER, "CreateRole", Map.of("RoleName", name, "Path", path,
                "AssumeRolePolicyDocument", trust));
        ownedCleanup.register(() -> iam("DeleteRole", Map.of("RoleName", name)));
        return response;
    }

    private static Response createUser(String account, String name, PartitionCleanup ownedCleanup) {
        Response response = callerIam(account, "CreateUser", Map.of("UserName", name));
        ownedCleanup.register(() -> callerIam(account, "DeleteUser", Map.of("UserName", name)));
        return response;
    }

    private static void putRolePolicy(String name, String policy, PartitionCleanup ownedCleanup) {
        iam("PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke", "PolicyDocument", policy));
        ownedCleanup.register(() -> iam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
    }

    private static void putUserPolicy(String account, String name, String policy, PartitionCleanup ownedCleanup) {
        callerIam(account, "PutUserPolicy", Map.of("UserName", name, "PolicyName", "invoke", "PolicyDocument", policy));
        ownedCleanup.register(() -> callerIam(account, "DeleteUserPolicy", Map.of("UserName", name, "PolicyName", "invoke")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Role", "User"})
    void duplicateIdentityCreationDoesNotDeleteExistingIdentity(String kind) throws Exception {
        String name = "http-existing-identity-" + UUID.randomUUID();
        createIdentity(kind, name, cleanup);
        PartitionCleanup attempted = new PartitionCleanup();
        try {
            AssertionError failure = assertThrows(AssertionError.class, () -> createIdentity(kind, name, attempted));
            assertTrue(failure.getMessage().contains("409"), failure.getMessage());
        } finally {
            attempted.afterEach(mock(ExtensionContext.class));
        }
        callerIam(OWNER, "Get" + kind, Map.of(kind + "Name", name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Role", "User"})
    void successfulIdentityCreationRegistersCleanup(String kind) throws Exception {
        String name = "http-owned-identity-" + UUID.randomUUID();
        PartitionCleanup owned = new PartitionCleanup();
        try {
            createIdentity(kind, name, owned);
        } finally {
            owned.afterEach(mock(ExtensionContext.class));
        }
        assertIamAbsent("Get" + kind, Map.of(kind + "Name", name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Role", "User"})
    void rejectedInlinePolicyDoesNotDeleteExistingPolicy(String kind) throws Exception {
        String name = "http-existing-policy-" + UUID.randomUUID();
        createIdentity(kind, name, cleanup);
        String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\"*\"}}";
        putIdentityPolicy(kind, name, policy, cleanup);
        Response before = callerIam(OWNER, "Get" + kind + "Policy", Map.of(kind + "Name", name, "PolicyName", "invoke"));
        String path = "Get" + kind + "PolicyResponse.Get" + kind + "PolicyResult.PolicyDocument";
        PartitionCleanup attempted = new PartitionCleanup();
        AwsException unavailable = new AwsException("ServiceFailure", "simulated policy write failure", 500);
        if (kind.equals("Role")) {
            doThrow(unavailable).when(iamService).putRolePolicy(name, "invoke", "failed-write");
        } else {
            doThrow(unavailable).when(iamService).putUserPolicy(name, "invoke", "failed-write");
        }
        try {
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> putIdentityPolicy(kind, name, "failed-write", attempted));
            assertTrue(failure.getMessage().contains("500"), failure.getMessage());
        } finally {
            if (kind.equals("Role")) {
                doCallRealMethod().when(iamService).putRolePolicy(name, "invoke", "failed-write");
            } else {
                doCallRealMethod().when(iamService).putUserPolicy(name, "invoke", "failed-write");
            }
            attempted.afterEach(mock(ExtensionContext.class));
        }
        Response after = callerIam(OWNER, "Get" + kind + "Policy", Map.of(kind + "Name", name, "PolicyName", "invoke"));
        assertEquals(before.xmlPath().getString(path), after.xmlPath().getString(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Role", "User"})
    void successfulInlinePolicyRegistersCleanup(String kind) throws Exception {
        String name = "http-owned-policy-" + UUID.randomUUID();
        createIdentity(kind, name, cleanup);
        PartitionCleanup owned = new PartitionCleanup();
        try {
            putIdentityPolicy(kind, name,
                    "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\"*\"}}", owned);
        } finally {
            owned.afterEach(mock(ExtensionContext.class));
        }
        assertIamAbsent("Get" + kind + "Policy", Map.of(kind + "Name", name, "PolicyName", "invoke"));
        callerIam(OWNER, "Get" + kind, Map.of(kind + "Name", name));
    }

    private static void createIdentity(String kind, String name, PartitionCleanup ownedCleanup) {
        if (kind.equals("Role")) {
            createRole(name, "/fixture/", "{\"Statement\":{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"Service\":\"apigateway.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}}", ownedCleanup);
        } else {
            createUser(OWNER, name, ownedCleanup);
        }
    }

    private static void putIdentityPolicy(String kind, String name, String policy, PartitionCleanup ownedCleanup) {
        if (kind.equals("Role")) {
            putRolePolicy(name, policy, ownedCleanup);
        } else {
            putUserPolicy(OWNER, name, policy, ownedCleanup);
        }
    }

    private static void assertIamAbsent(String action, Map<String, String> parameters) {
        given().header("Authorization", EnforcementFixtures.auth(OWNER, "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action)
                .formParams(parameters).post("/").then().statusCode(404);
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
        byte[] body = "{\"request\":\"signed\"}".getBytes(StandardCharsets.UTF_8);
        String host = baseUri.getHost() + ":" + baseUri.getPort();
        String path = "/execute-api/" + fixture.apiId() + "/prod/items";
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders("POST", path, Map.of("tag", "a b"), host,
                body, key.accessKeyId(), secret, REGION, Instant.now());
        if (status == 200) {
            for (String altered : new String[]{"body", "query"}) {
                RequestSpecification invalid = given().header("Host", host).headers(signed)
                        .queryParam("tag", "query".equals(altered) ? "other" : "a b")
                        .contentType("application/json").body("body".equals(altered) ? new byte[0] : body);
                if (key.sessionToken() != null) {
                    invalid.header("X-Amz-Security-Token", key.sessionToken());
                }
                invalid.post(path).then().statusCode(500);
            }
        }
        RequestSpecification request = given().header("Host", host).headers(signed)
                .queryParam("tag", "a b").contentType("application/json").body(body);
        if (key.sessionToken() != null) {
            request.header("X-Amz-Security-Token", key.sessionToken());
        }
        request.post(path).then().statusCode(status);
    }

    private static void iam(String action, Map<String, String> parameters) {
        given().header("Authorization", EnforcementFixtures.auth(OWNER, "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action)
                .formParams(parameters).post("/").then().statusCode(200);
    }

    @Test
    void failedFunctionCreationDoesNotDeleteExistingFunction() throws Exception {
        Fixture existing = fixture(OWNER, REGION);
        PartitionCleanup failedCleanup = new PartitionCleanup();
        try {
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> fixture(OWNER, REGION, null, "2.0", existing.name(), failedCleanup));
            assertTrue(failure.getMessage().contains("409"), failure.getMessage());
        } finally {
            failedCleanup.afterEach(mock(ExtensionContext.class));
        }
        lambdaRequest(OWNER, REGION).get("/2015-03-31/functions/" + existing.name())
                .then().statusCode(200);
    }

    @Test
    void successfulCreationRegistersFunctionAndApiCleanup() throws Exception {
        PartitionCleanup ownedCleanup = new PartitionCleanup();
        Fixture owned;
        try {
            owned = fixture(OWNER, REGION, null, "2.0", "http-integration-owned-" + UUID.randomUUID(), ownedCleanup);
        } finally {
            ownedCleanup.afterEach(mock(ExtensionContext.class));
        }
        lambdaRequest(OWNER, REGION).get("/2015-03-31/functions/" + owned.name())
                .then().statusCode(404);
        given().header("Authorization", EnforcementFixtures.auth(OWNER, REGION, "apigateway"))
                .get("/v2/apis/" + owned.apiId()).then().statusCode(404);
    }

    private Fixture fixture(String targetOwner, String targetRegion) throws Exception {
        return fixture(targetOwner, targetRegion, null, "2.0");
    }

    private Fixture fixture(String targetOwner, String targetRegion, String credentials, String payloadVersion)
            throws Exception {
        return fixture(targetOwner, targetRegion, credentials, payloadVersion,
                "http-integration-permission-" + UUID.randomUUID().toString().substring(0, 8), cleanup);
    }

    private Fixture fixture(String targetOwner, String targetRegion, String credentials, String payloadVersion,
                            String name, PartitionCleanup ownedCleanup) throws Exception {
        ApiGatewayV2Client gateway = ApiGatewayV2Client.builder().endpointOverride(baseUri).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(OWNER, "test"))).build();
        ownedCleanup.register(gateway::close);
        String arn = "arn:aws:lambda:" + targetRegion + ":" + targetOwner + ":function:" + name + ":live";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => ({});".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        lambdaRequest(targetOwner, targetRegion).body(Map.of("FunctionName", name, "Runtime", "nodejs20.x",
                "Handler", "index.handler", "Role", "arn:aws:iam::" + targetOwner + ":role/lambda-role",
                "Code", Map.of("ZipFile", Base64.getEncoder().encodeToString(bytes.toByteArray()))))
                .post("/2015-03-31/functions").then().statusCode(201);
        ownedCleanup.register(() -> lambdaRequest(targetOwner, targetRegion)
                .delete("/2015-03-31/functions/" + name).then().statusCode(204));
        lambdaRequest(targetOwner, targetRegion).body("{}")
                .post("/2015-03-31/functions/" + name + "/versions").then().statusCode(201);
        lambdaRequest(targetOwner, targetRegion).body(Map.of("Name", "live", "FunctionVersion", "1"))
                .post("/2015-03-31/functions/" + name + "/aliases").then().statusCode(201);
        String api = gateway.createApi(request -> request.name(name).protocolType("HTTP")).apiId();
        ownedCleanup.register(() -> gateway.deleteApi(request -> request.apiId(api)));
        String integration = gateway.createIntegration(request -> request.apiId(api).integrationType("AWS_PROXY")
                .integrationUri(arn).credentialsArn(credentials).payloadFormatVersion(payloadVersion)).integrationId();
        gateway.createRoute(request -> request.apiId(api).routeKey("GET /items").authorizationType("NONE").target("integrations/" + integration));
        gateway.createRoute(request -> request.apiId(api).routeKey("POST /items").authorizationType("NONE")
                .target("integrations/" + integration));
        gateway.createStage(request -> request.apiId(api).stageName("prod").autoDeploy(true));
        doAnswer(invocation -> {
            String payload = "{\"statusCode\":200,\"body\":\"ok\"}";
            return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "permission-test");
        }).when(lambda).invokeTarget(eq(REGION), eq(arn), any(byte[].class), eq(InvocationType.RequestResponse));
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
        return "arn:aws:execute-api:" + region + ":" + OWNER + ":" + fixture.apiId() + "/prod/GET/items";
    }

    private void invoke(Fixture fixture, int status) {
        given().header("Authorization", "identity")
                .get("/execute-api/" + fixture.apiId() + "/prod/items").then().statusCode(status);
    }

    private void verifyInvocations(Fixture fixture, int count) {
        verify(lambda, times(count)).invokeTarget(eq(REGION), eq(fixture.arn()), any(byte[].class),
                eq(InvocationType.RequestResponse));
    }

    private record Fixture(String name, String apiId, String arn, String owner, String region) { }

    private record Grant(String qualifier, String principal, String sourceArn, String sourceAccount) { }
}

package io.github.hectorvent.floci.services.apigateway;

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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RestLambdaPermissionIntegrationTest {

    private static final String OWNER = "121212121212";
    private static final String FOREIGN = "343434343434";
    private static final String CALLER = "565656565656";
    private static final String REGION = "us-east-1";

    private Supplier<String> userNames = () -> "rest-caller-permission-" + UUID.randomUUID().toString().substring(0, 8);

    private Supplier<String> roleNames = () -> "rest-caller-role-" + UUID.randomUUID().toString().substring(0, 8);

    @InjectSpy
    LambdaService lambda;

    @TestHTTPResource("/")
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"AWS_PROXY,121212121212,us-east-1", "AWS,121212121212,us-east-1",
            "AWS_PROXY,343434343434,us-east-1", "AWS,343434343434,us-east-1",
            "AWS_PROXY,121212121212,eu-west-1", "AWS,121212121212,eu-west-1",
            "AWS_PROXY,343434343434,eu-west-1", "AWS,343434343434,eu-west-1"})
    void storedQualifiedPolicyControlsBothRestExecutionRoutes(String type, String targetOwner,
                                                              String targetRegion) throws Exception {
        Fixture fixture = fixture(type, targetOwner, targetRegion, null);
        invoke(fixture, 500);
        String source = sourceArn(fixture, targetRegion);
        List<Grant> mismatches = List.of(
                new Grant(null, "apigateway.amazonaws.com", source, OWNER),
                new Grant("live", "events.amazonaws.com", source, OWNER),
                new Grant("live", "apigateway.amazonaws.com", source, FOREIGN),
                new Grant("live", "apigateway.amazonaws.com", source.replace("/prod/", "/other/"), OWNER),
                new Grant("live", "apigateway.amazonaws.com", source.replace("/POST/", "/GET/"), OWNER),
                new Grant("live", "apigateway.amazonaws.com", source + "other", OWNER));
        for (Grant grant : mismatches) {
            add(fixture, grant);
            invoke(fixture, 500);
            remove(fixture, grant.qualifier());
        }
        if (!REGION.equals(targetRegion)) {
            add(fixture, new Grant("live", "apigateway.amazonaws.com", sourceArn(fixture, REGION), OWNER));
            invoke(fixture, 500);
            remove(fixture, "live");
        }
        verifyInvocations(fixture, 0);
        add(fixture, new Grant("live", "apigateway.amazonaws.com", source, OWNER));
        invoke(fixture, 200);
        verifyInvocations(fixture, 2);
        remove(fixture, "live");
        invoke(fixture, 500);
        verifyInvocations(fixture, 2);
    }

    @ParameterizedTest
    @CsvSource({"AWS_PROXY,arn:aws:iam::*:user/*", "AWS,arn:aws:iam::*:user/*",
            "AWS_PROXY,*", "AWS,*"})
    void unsignedDocumentedCallerRequiresIdentityButLegacyStarIsRetained(String type, String credentials) throws Exception {
        Fixture fixture = fixture(type, OWNER, REGION, credentials);
        boolean legacy = "*".equals(credentials);
        invoke(fixture, legacy ? 200 : 500);
        verifyInvocations(fixture, legacy ? 2 : 0);
    }

    @ParameterizedTest
    @CsvSource({"AWS_PROXY", "AWS"})
    void explicitPolicyDenialOverridesTheStoredGrant(String type) throws Exception {
        Fixture fixture = fixture(type, OWNER, REGION, null);
        add(fixture, new Grant("live", "apigateway.amazonaws.com", sourceArn(fixture, REGION), OWNER));
        invoke(fixture, 200);
        List<Map<String, Object>> statements = new ArrayList<>(lambda.policyStatements(OWNER, REGION, fixture.name()));
        statements.add(Map.of("Effect", "Deny", "Principal", "*", "Action", "lambda:InvokeFunction",
                "Resource", fixture.arn()));
        doReturn(statements).when(lambda).policyStatements(OWNER, REGION, fixture.name());
        try {
            invoke(fixture, 500);
            verifyInvocations(fixture, 2);
        } finally {
            doCallRealMethod().when(lambda).policyStatements(OWNER, REGION, fixture.name());
        }
    }

    @ParameterizedTest
    @CsvSource({"AWS_PROXY,121212121212,us-east-1", "AWS,121212121212,us-east-1",
            "AWS_PROXY,343434343434,eu-west-1", "AWS,343434343434,eu-west-1"})
    void configuredRoleRequiresTrustIdentityAndQualifiedResource(String type, String targetOwner,
                                                                String targetRegion) throws Exception {
        String roleName = "rest-role-permission-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + OWNER + ":role/service-role/" + roleName;
        Fixture fixture = fixture(type, targetOwner, targetRegion, roleArn);
        invoke(fixture, 500);
        String trust = """
                {"Version":"2012-10-17","Statement":{"Effect":"Allow",
                "Principal":{"Service":"apigateway.amazonaws.com"},"Action":"sts:AssumeRole"}}""";
        iam("CreateRole", Map.of("RoleName", roleName, "Path", "/service-role/",
                "AssumeRolePolicyDocument", trust.replace("apigateway.amazonaws.com", "events.amazonaws.com")));
        cleanup.register(() -> iam("DeleteRole", Map.of("RoleName", roleName)));
        String policy = """
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s"}}"""
                .formatted(fixture.arn());
        iam("PutRolePolicy", Map.of("RoleName", roleName, "PolicyName", "invoke", "PolicyDocument", policy));
        cleanup.register(() -> iam("DeleteRolePolicy", Map.of("RoleName", roleName, "PolicyName", "invoke")));
        invoke(fixture, 500);
        iam("UpdateAssumeRolePolicy", Map.of("RoleName", roleName, "PolicyDocument", trust));
        if (!OWNER.equals(targetOwner)) {
            invoke(fixture, 500);
            lambdaRequest(targetOwner, targetRegion).queryParam("Qualifier", "live")
                    .body(Map.of("StatementId", "role", "Action", "lambda:InvokeFunction", "Principal", roleArn))
                    .post("/2015-03-31/functions/" + fixture.name() + "/policy").then().statusCode(201);
        }
        invoke(fixture, 200);
        verifyInvocations(fixture, 2);
        iam("PutRolePolicy", Map.of("RoleName", roleName, "PolicyName", "invoke",
                "PolicyDocument", policy.replace(fixture.arn(), fixture.arn().replace(":live", ":other"))));
        invoke(fixture, 500);
        iam("PutRolePolicy", Map.of("RoleName", roleName, "PolicyName", "invoke", "PolicyDocument", policy));
        iam("UpdateAssumeRolePolicy", Map.of("RoleName", roleName,
                "PolicyDocument", trust.replace("Allow", "Deny")));
        invoke(fixture, 500);
        verifyInvocations(fixture, 2);
    }

    @ParameterizedTest
    @CsvSource({"AWS_PROXY,121212121212,121212121212,us-east-1", "AWS,121212121212,121212121212,us-east-1",
            "AWS_PROXY,565656565656,343434343434,eu-west-1", "AWS,565656565656,343434343434,eu-west-1"})
    void verifiedCallerPoliciesControlQualifiedLambda(String type, String callerAccount, String targetOwner,
                                                      String targetRegion) throws Exception {
        Fixture fixture = fixture(type, targetOwner, targetRegion, "arn:aws:iam::*:user/*");
        String user = userNames.get();
        String principal = callerIam(callerAccount, "CreateUser", Map.of("UserName", user))
                .xmlPath().getString("CreateUserResponse.CreateUserResult.User.Arn");
        cleanup.register(() -> callerIam(callerAccount, "DeleteUser", Map.of("UserName", user)));
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
        verifyInvocations(fixture, 0);
        signedInvoke(fixture, key, key.secretAccessKey(), 200);
        verifyInvocations(fixture, 2);
        callerIam(callerAccount, "PutUserPolicy", Map.of("UserName", user, "PolicyName", "invoke",
                "PolicyDocument", policy.replace("Allow", "Deny")));
        signedInvoke(fixture, key, key.secretAccessKey(), 500);
        verifyInvocations(fixture, 2);
    }

    @ParameterizedTest
    @CsvSource({"AWS_PROXY", "AWS"})
    void issuedSessionTokenAndSessionPolicyRemainEffective(String type) throws Exception {
        Fixture fixture = fixture(type, OWNER, REGION, "arn:aws:iam::*:user/*");
        String name = roleNames.get();
        String trust = """
                {"Statement":{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},
                "Action":"sts:AssumeRole"}}""".formatted(OWNER);
        String role = callerIam(OWNER, "CreateRole", Map.of("RoleName", name, "Path", "/caller-path/",
                "AssumeRolePolicyDocument", trust)).xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> iam("DeleteRole", Map.of("RoleName", name)));
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
        verifyInvocations(fixture, 0);
        signedInvoke(fixture, allowed, allowed.secretAccessKey(), 200);
        verifyInvocations(fixture, 2);
        iam("PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke",
                "PolicyDocument", policy.replace("Allow", "Deny")));
        signedInvoke(fixture, allowed, allowed.secretAccessKey(), 500);
        verifyInvocations(fixture, 2);
    }

    @Test
    void rejectedSessionRoleCreationPreservesPreexistingRole() throws Exception {
        String name = "rest-existing-" + UUID.randomUUID();
        iam("CreateRole", Map.of("RoleName", name, "AssumeRolePolicyDocument", "{\"Statement\":[]}"));
        cleanup.register(() -> iam("DeleteRole", Map.of("RoleName", name)));
        RestLambdaPermissionIntegrationTest fixture = nestedFixture(name);
        try {
            assertThrows(AssertionError.class, () -> fixture.issuedSessionTokenAndSessionPolicyRemainEffective("AWS_PROXY"));
        } finally {
            fixture.cleanup.afterEach(mock(ExtensionContext.class));
        }
        iam("GetRole", Map.of("RoleName", name));
    }

    @Test
    void successfulSessionScenarioRemovesOwnedRoleAndPolicy() throws Exception {
        String name = "rest-owned-" + UUID.randomUUID();
        RestLambdaPermissionIntegrationTest fixture = nestedFixture(name);
        try {
            fixture.issuedSessionTokenAndSessionPolicyRemainEffective("AWS_PROXY");
        } finally {
            fixture.cleanup.afterEach(mock(ExtensionContext.class));
        }
        given().header("Authorization", EnforcementFixtures.auth(OWNER, "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", "GetRole")
                .formParam("RoleName", name).post("/").then().statusCode(404);
    }

    @ParameterizedTest
    @CsvSource({"AWS_PROXY,121212121212,121212121212,us-east-1", "AWS,121212121212,121212121212,us-east-1",
            "AWS_PROXY,565656565656,343434343434,eu-west-1", "AWS,565656565656,343434343434,eu-west-1"})
    void rejectedCallerCreationPreservesPreexistingUser(String type, String callerAccount, String targetOwner,
                                                       String targetRegion) throws Exception {
        String name = "rest-existing-user-" + UUID.randomUUID();
        callerIam(callerAccount, "CreateUser", Map.of("UserName", name));
        cleanup.register(() -> callerIam(callerAccount, "DeleteUser", Map.of("UserName", name)));
        RestLambdaPermissionIntegrationTest fixture = nestedFixture("unused-role");
        fixture.userNames = () -> name;
        try {
            assertThrows(AssertionError.class, () -> fixture.verifiedCallerPoliciesControlQualifiedLambda(
                    type, callerAccount, targetOwner, targetRegion));
        } finally {
            fixture.cleanup.afterEach(mock(ExtensionContext.class));
        }
        callerIam(callerAccount, "GetUser", Map.of("UserName", name));
    }

    @ParameterizedTest
    @CsvSource({"AWS_PROXY,121212121212,121212121212,us-east-1", "AWS,121212121212,121212121212,us-east-1",
            "AWS_PROXY,565656565656,343434343434,eu-west-1", "AWS,565656565656,343434343434,eu-west-1"})
    void successfulCallerScenarioRemovesOwnedUserKeyAndPolicy(String type, String callerAccount, String targetOwner,
                                                              String targetRegion) throws Exception {
        String name = "rest-owned-user-" + UUID.randomUUID();
        RestLambdaPermissionIntegrationTest fixture = nestedFixture("unused-role");
        fixture.userNames = () -> name;
        try {
            fixture.verifiedCallerPoliciesControlQualifiedLambda(type, callerAccount, targetOwner, targetRegion);
        } finally {
            fixture.cleanup.afterEach(mock(ExtensionContext.class));
        }
        given().header("Authorization", EnforcementFixtures.auth(callerAccount, "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", "GetUser")
                .formParam("UserName", name).post("/").then().statusCode(404);
    }

    private RestLambdaPermissionIntegrationTest nestedFixture(String name) {
        RestLambdaPermissionIntegrationTest fixture = new RestLambdaPermissionIntegrationTest();
        fixture.baseUri = baseUri;
        fixture.lambda = lambda;
        fixture.roleNames = () -> name;
        return fixture;
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
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String host = baseUri.getHost() + ":" + baseUri.getPort();
        for (String path : List.of("/execute-api/" + fixture.apiId() + "/prod/",
                "/restapis/" + fixture.apiId() + "/prod/_user_request_/")) {
            Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders("POST", path, Map.of(), host,
                    body, key.accessKeyId(), secret, REGION, Instant.now());
            RequestSpecification request = given().header("Host", host).headers(signed)
                    .contentType("application/json").body(body);
            if (key.sessionToken() != null) {
                request.header("X-Amz-Security-Token", key.sessionToken());
            }
            request.post(path).then().statusCode(status);
        }
    }

    private static void iam(String action, Map<String, String> parameters) {
        given().header("Authorization", EnforcementFixtures.auth(OWNER, "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action)
                .formParams(parameters).post("/").then().statusCode(200);
    }

    private Fixture fixture(String type, String targetOwner, String targetRegion, String credentials) throws Exception {
        ApiGatewayClient gateway = ApiGatewayClient.builder().endpointOverride(baseUri).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(OWNER, "test"))).build();
        cleanup.register(gateway::close);
        String name = "rest-permission-" + UUID.randomUUID().toString().substring(0, 8);
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
        String api = gateway.createRestApi(request -> request.name(name)).id();
        cleanup.register(() -> gateway.deleteRestApi(request -> request.restApiId(api)));
        String root = gateway.getResources(request -> request.restApiId(api)).items().getFirst().id();
        gateway.putMethod(request -> request.restApiId(api).resourceId(root).httpMethod("POST")
                .authorizationType("NONE"));
        gateway.putIntegration(request -> request.restApiId(api).resourceId(root).httpMethod("POST")
                .type(IntegrationType.fromValue(type)).integrationHttpMethod("POST").credentials(credentials)
                .uri("arn:aws:apigateway:" + targetRegion + ":lambda:path/2015-03-31/functions/" + arn + "/invocations"));
        if ("AWS".equals(type)) {
            gateway.putMethodResponse(request -> request.restApiId(api).resourceId(root).httpMethod("POST")
                    .statusCode("200"));
            gateway.putIntegrationResponse(request -> request.restApiId(api).resourceId(root).httpMethod("POST")
                    .statusCode("200"));
        }
        gateway.createDeployment(request -> request.restApiId(api).stageName("prod"));
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
        return "arn:aws:execute-api:" + region + ":" + OWNER + ":" + fixture.apiId() + "/prod/POST/";
    }

    private void invoke(Fixture fixture, int status) {
        for (String path : List.of("/execute-api/" + fixture.apiId() + "/prod/",
                "/restapis/" + fixture.apiId() + "/prod/_user_request_/")) {
            given().header("Authorization", EnforcementFixtures.auth(CALLER, "execute-api"))
                    .contentType("application/json").body("{}").post(path).then().statusCode(status);
        }
    }

    private void verifyInvocations(Fixture fixture, int count) {
        verify(lambda, times(count)).invokeTarget(eq(REGION), eq(fixture.arn()), any(byte[].class),
                eq(InvocationType.RequestResponse));
    }

    private record Fixture(String name, String apiId, String arn, String owner, String region) { }

    private record Grant(String qualifier, String principal, String sourceArn, String sourceAccount) { }
}

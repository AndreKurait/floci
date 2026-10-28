package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RestAuthorizerPermissionIntegrationTest {

    private static final String OWNER = "121212121212";
    private static final String FOREIGN = "343434343434";
    private static final String CALLER = "565656565656";
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
    @CsvSource({"TOKEN,121212121212,us-east-1", "REQUEST,121212121212,us-east-1",
            "TOKEN,343434343434,us-east-1", "REQUEST,343434343434,us-east-1",
            "TOKEN,121212121212,eu-west-1", "REQUEST,121212121212,eu-west-1",
            "TOKEN,343434343434,eu-west-1", "REQUEST,343434343434,eu-west-1"})
    void storedQualifiedPolicyControlsAuthorizerInvocation(String type, String targetOwner,
                                                           String targetRegion) throws Exception {
        Fixture fixture = fixture(type, targetOwner, targetRegion, null);
        invoke(fixture, 500);
        String source = sourceArn(fixture, REGION);
        List<Grant> mismatches = List.of(
                new Grant(null, "apigateway.amazonaws.com", source, OWNER),
                new Grant("live", "events.amazonaws.com", source, OWNER),
                new Grant("live", "apigateway.amazonaws.com", source, FOREIGN),
                new Grant("live", "apigateway.amazonaws.com", source.replace("/authorizers/", "/other/"), OWNER),
                new Grant("live", "apigateway.amazonaws.com", source.replace(fixture.apiId(), "different-api"), OWNER),
                new Grant("live", "apigateway.amazonaws.com", source + "other", OWNER));
        for (Grant grant : mismatches) {
            add(fixture, grant);
            invoke(fixture, 500);
            remove(fixture, grant.qualifier());
        }
        if (!REGION.equals(targetRegion)) {
            add(fixture, new Grant("live", "apigateway.amazonaws.com", sourceArn(fixture, targetRegion), OWNER));
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
    @CsvSource({"TOKEN,121212121212,us-east-1", "REQUEST,121212121212,us-east-1",
            "TOKEN,343434343434,eu-west-1", "REQUEST,343434343434,eu-west-1"})
    void configuredRoleRequiresTrustIdentityAndQualifiedResource(String type, String owner, String region) throws Exception {
        String roleName = "authorizer-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + OWNER + ":role/service-role/" + roleName;
        Fixture fixture = fixture(type, owner, region, roleArn);
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
        invoke(fixture, 200);
        verifyInvocations(fixture, 2);
        iam("PutRolePolicy", Map.of("RoleName", roleName, "PolicyName", "invoke",
                "PolicyDocument", policy.replace("Allow", "Deny")));
        invoke(fixture, 500);
        verifyInvocations(fixture, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"allow-userid", "deny-userid", "allow-token", "deny-token", "allow-tag", "deny-tag"})
    void configuredRoleUsesRegisteredSessionMetadataAndRevokesIt(String scenario) throws Exception {
        String name = "rest-session-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + OWNER + ":role/team/" + name;
        Fixture fixture = fixture(scenario.contains("tag") ? "REQUEST" : "TOKEN", OWNER, REGION, roleArn);
        String trust = """
                {"Statement":{"Effect":"Allow","Principal":{"Service":"apigateway.amazonaws.com"},
                "Action":"sts:AssumeRole"}}""";
        cleanup.register(() -> iam("DeleteRole", Map.of("RoleName", name)));
        iam("CreateRole", Map.of("RoleName", name, "Path", "/team/", "AssumeRolePolicyDocument", trust));
        iam("TagRole", Map.of("RoleName", name, "Tags.member.1.Key", "purpose", "Tags.member.1.Value", "authorizer"));
        String key = switch (scenario.substring(scenario.indexOf('-') + 1)) {
            case "userid" -> "aws:userid";
            case "token" -> "aws:TokenIssueTime";
            default -> "aws:PrincipalTag/purpose";
        };
        String condition = key.equals("aws:PrincipalTag/purpose")
                ? "\"StringEquals\":{\"" + key + "\":\"authorizer\"}"
                : "\"Null\":{\"" + key + "\":\"false\"}";
        String policy = """
                {"Statement":[{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s"%s},
                {"Effect":"%s","Action":"lambda:InvokeFunction","Resource":"%s","Condition":{%s}}]}"""
                .formatted(fixture.arn(), scenario.startsWith("allow") ? ",\"Condition\":{" + condition + "}" : "",
                        scenario.startsWith("deny") ? "Deny" : "Allow", fixture.arn(), condition);
        cleanup.register(() -> iam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
        iam("PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke", "PolicyDocument", policy));
        invoke(fixture, scenario.startsWith("allow") ? 200 : 500);
        verifyInvocations(fixture, scenario.startsWith("allow") ? 2 : 0);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(iamService, times(2)).registerSessionForAccount(eq(OWNER), keyCaptor.capture(), anyString(), anyString(),
                eq(roleArn), any(Instant.class), isNull(), anyString(), nullable(String.class));
        for (String sessionAccessKey : keyCaptor.getAllValues()) {
            assertTrue(iamService.resolveCallerArns(sessionAccessKey).isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"TOKEN", "REQUEST"})
    void cachedRoleAuthorizationRechecksCurrentTagsAfterFlush(String type) throws Exception {
        String name = "rest-cache-session-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + OWNER + ":role/team/" + name;
        Fixture fixture = fixture(type, OWNER, REGION, roleArn);
        fixture.gateway().updateAuthorizer(request -> request.restApiId(fixture.apiId())
                .authorizerId(fixture.authorizerId()).patchOperations(patch -> patch.op("replace")
                        .path("/authorizerResultTtlInSeconds").value("300")));
        String trust = """
                {"Statement":{"Effect":"Allow","Principal":{"Service":"apigateway.amazonaws.com"},
                "Action":"sts:AssumeRole"}}""";
        cleanup.register(() -> iam("DeleteRole", Map.of("RoleName", name)));
        iam("CreateRole", Map.of("RoleName", name, "Path", "/team/", "AssumeRolePolicyDocument", trust));
        iam("TagRole", Map.of("RoleName", name, "Tags.member.1.Key", "purpose", "Tags.member.1.Value", "authorizer"));
        String policy = """
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s",
                "Condition":{"StringEquals":{"aws:PrincipalTag/purpose":"authorizer"}}}}""".formatted(fixture.arn());
        cleanup.register(() -> iam("DeleteRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke")));
        iam("PutRolePolicy", Map.of("RoleName", name, "PolicyName", "invoke", "PolicyDocument", policy));
        invoke(fixture, 200);
        verifyInvocations(fixture, 1);
        iam("TagRole", Map.of("RoleName", name, "Tags.member.1.Key", "purpose", "Tags.member.1.Value", "revoked"));
        invoke(fixture, 200);
        verifyInvocations(fixture, 1);
        fixture.gateway().flushStageAuthorizersCache(request -> request.restApiId(fixture.apiId()).stageName("prod"));
        invoke(fixture, 500);
        verifyInvocations(fixture, 1);
        iam("TagRole", Map.of("RoleName", name, "Tags.member.1.Key", "purpose", "Tags.member.1.Value", "authorizer"));
        invoke(fixture, 200);
        verifyInvocations(fixture, 2);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(iamService, times(4)).registerSessionForAccount(eq(OWNER), keys.capture(), anyString(), anyString(),
                eq(roleArn), any(Instant.class), isNull(), anyString(), nullable(String.class));
        for (String key : keys.getAllValues()) {
            assertTrue(iamService.resolveCallerArns(key).isEmpty());
        }
    }

    private static void iam(String action, Map<String, String> parameters) {
        given().header("Authorization", EnforcementFixtures.auth(OWNER, "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action)
                .formParam("Version", "2010-05-08").formParams(parameters).post("/").then().statusCode(200);
    }

    @ParameterizedTest
    @CsvSource({"TOKEN", "REQUEST"})
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

    @Test
    void cachedPolicyNeedsNoInvocationUntilStageCacheIsFlushed() throws Exception {
        Fixture fixture = fixture("TOKEN", OWNER, REGION, null);
        fixture.gateway().updateAuthorizer(request -> request.restApiId(fixture.apiId())
                .authorizerId(fixture.authorizerId()).patchOperations(patch -> patch.op("replace")
                        .path("/authorizerResultTtlInSeconds").value("300")));
        add(fixture, new Grant("live", "apigateway.amazonaws.com", sourceArn(fixture, REGION), OWNER));
        invoke(fixture, 200);
        verifyInvocations(fixture, 1);
        remove(fixture, "live");
        invoke(fixture, 200);
        verifyInvocations(fixture, 1);
        fixture.gateway().flushStageAuthorizersCache(request -> request.restApiId(fixture.apiId()).stageName("prod"));
        invoke(fixture, 500);
        verifyInvocations(fixture, 1);
    }

    @Test
    void denialUsesTheAuthorizerConfigurationGatewayResponse() throws Exception {
        Fixture fixture = fixture("TOKEN", OWNER, REGION, null);
        fixture.gateway().putGatewayResponse(request -> request.restApiId(fixture.apiId())
                .responseType("AUTHORIZER_CONFIGURATION_ERROR").statusCode("502"));
        invoke(fixture, 502);
        verifyInvocations(fixture, 0);
    }

    @Test
    void missingIdentityStillPrecedesLambdaPermissionCheck() throws Exception {
        Fixture fixture = fixture("TOKEN", OWNER, REGION, null);
        given().post("/execute-api/" + fixture.apiId() + "/prod/").then().statusCode(401);
        verifyInvocations(fixture, 0);
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
        String authorizer = gateway.createAuthorizer(request -> request.restApiId(api).name("permission-authorizer")
                .type(type).authorizerUri("arn:aws:apigateway:" + targetRegion
                        + ":lambda:path/2015-03-31/functions/" + arn + "/invocations")
                .authorizerCredentials(credentials).identitySource("method.request.header.Authorization")
                .authorizerResultTtlInSeconds(0)).id();
        gateway.putMethod(request -> request.restApiId(api).resourceId(root).httpMethod("POST")
                .authorizationType("CUSTOM").authorizerId(authorizer));
        gateway.putIntegration(request -> request.restApiId(api).resourceId(root).httpMethod("POST")
                .type(IntegrationType.MOCK).requestTemplates(Map.of("application/json", "{\"statusCode\":200}")));
        gateway.putMethodResponse(request -> request.restApiId(api).resourceId(root).httpMethod("POST")
                .statusCode("200"));
        gateway.putIntegrationResponse(request -> request.restApiId(api).resourceId(root).httpMethod("POST")
                .statusCode("200"));
        gateway.createDeployment(request -> request.restApiId(api).stageName("prod"));
        doReturn(new InvokeResult(200, null,
                ("{\"principalId\":\"authorized\",\"policyDocument\":{\"Version\":\"2012-10-17\","
                        + "\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\","
                        + "\"Resource\":\"*\"}]}}").getBytes(StandardCharsets.UTF_8), null, "permission-test"))
                .when(lambda).invokeTarget(eq(REGION), eq(arn), any(byte[].class), eq(InvocationType.RequestResponse));
        return new Fixture(name, api, arn, targetOwner, targetRegion, authorizer, gateway);
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
        return "arn:aws:execute-api:" + region + ":" + OWNER + ":" + fixture.apiId() + "/authorizers/" + fixture.authorizerId();
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

    private record Fixture(String name, String apiId, String arn, String owner, String region,
                           String authorizerId, ApiGatewayClient gateway) { }

    private record Grant(String qualifier, String principal, String sourceArn, String sourceAccount) { }
}

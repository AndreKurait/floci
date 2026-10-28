package io.github.hectorvent.floci.services.apigatewayv2;

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
import java.util.List;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class HttpAuthorizerPermissionIntegrationTest {

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
        Fixture fixture = fixture(targetOwner, targetRegion, null, "2.0");
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
        invoke(fixture, 200);
        remove(fixture, "live");
        invoke(fixture, 500);
        verifyInvocations(fixture, 2);
    }

    @ParameterizedTest
    @CsvSource({"1.0,000000000000,us-east-1", "2.0,000000000000,us-east-1",
            "1.0,343434343434,eu-west-1", "2.0,343434343434,eu-west-1"})
    void configuredRoleRequiresTrustIdentityAndQualifiedResource(String version, String owner, String region) throws Exception {
        String roleName = "authorizer-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + OWNER + ":role/service-role/" + roleName;
        Fixture fixture = fixture(owner, region, roleArn, version);
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
        String name = "http-session-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + OWNER + ":role/team/" + name;
        Fixture fixture = fixture(OWNER, REGION, roleArn, scenario.contains("tag") ? "1.0" : "2.0");
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
        verify(iamService).registerSessionForAccount(eq(OWNER), keyCaptor.capture(), anyString(), anyString(),
                eq(roleArn), any(Instant.class), isNull(), anyString(), nullable(String.class));
        assertTrue(iamService.resolveCallerArns(keyCaptor.getValue()).isEmpty());
    }

    private static void iam(String action, Map<String, String> parameters) {
        given().header("Authorization", EnforcementFixtures.auth(OWNER, "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action)
                .formParam("Version", "2010-05-08").formParams(parameters).post("/").then().statusCode(200);
    }

    private Fixture fixture(String targetOwner, String targetRegion, String credentials, String version) throws Exception {
        ApiGatewayV2Client gateway = ApiGatewayV2Client.builder().endpointOverride(baseUri).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(OWNER, "test"))).build();
        cleanup.register(gateway::close);
        String name = "http-permission-" + UUID.randomUUID().toString().substring(0, 8);
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
        String api = gateway.createApi(request -> request.name(name).protocolType("HTTP")).apiId();
        cleanup.register(() -> gateway.deleteApi(request -> request.apiId(api)));
        String auth = gateway.createAuthorizer(request -> request.apiId(api).name("permission")
                .authorizerType("REQUEST").authorizerUri("arn:aws:apigateway:" + targetRegion
                        + ":lambda:path/2015-03-31/functions/" + arn + "/invocations")
                .authorizerCredentialsArn(credentials).authorizerPayloadFormatVersion(version).identitySource(List.of("$request.header.Authorization"))).authorizerId();
        lambdaRequest(targetOwner, targetRegion).queryParam("Qualifier", "live")
                .body(Map.of("StatementId", "backend-integration", "Action", "lambda:InvokeFunction",
                        "Principal", "apigateway.amazonaws.com", "SourceAccount", OWNER,
                        "SourceArn", "arn:aws:execute-api:" + targetRegion + ":" + OWNER + ":" + api + "/prod/GET/items"))
                .post("/2015-03-31/functions/" + name + "/policy").then().statusCode(201);
        String integration = gateway.createIntegration(request -> request.apiId(api).integrationType("AWS_PROXY")
                .integrationUri(arn).payloadFormatVersion("2.0")).integrationId();
        gateway.createRoute(request -> request.apiId(api).routeKey("GET /items").authorizationType("CUSTOM")
                .authorizerId(auth).target("integrations/" + integration));
        gateway.createStage(request -> request.apiId(api).stageName("prod").autoDeploy(true));
        doAnswer(invocation -> {
            String event = new String((byte[]) invocation.getArgument(2), StandardCharsets.UTF_8);
            String payload = event.contains("\"type\":\"REQUEST\"")
                    ? "{\"principalId\":\"user\",\"policyDocument\":{\"Version\":\"2012-10-17\","
                    + "\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"*\"}]}}"
                    : "{\"statusCode\":200,\"body\":\"ok\"}";
            return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "permission-test");
        }).when(lambda).invokeTarget(eq(REGION), eq(arn), any(byte[].class), eq(InvocationType.RequestResponse));
        return new Fixture(name, api, arn, targetOwner, targetRegion, auth);
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
        given().header("Authorization", "identity")
                .get("/execute-api/" + fixture.apiId() + "/prod/items").then().statusCode(status);
    }

    private void verifyInvocations(Fixture fixture, int count) {
        verify(lambda, times(count)).invokeTarget(eq(REGION), eq(fixture.arn()), any(byte[].class),
                eq(InvocationType.RequestResponse));
    }

    private record Fixture(String name, String apiId, String arn, String owner, String region, String authorizerId) { }

    private record Grant(String qualifier, String principal, String sourceArn, String sourceAccount) { }
}

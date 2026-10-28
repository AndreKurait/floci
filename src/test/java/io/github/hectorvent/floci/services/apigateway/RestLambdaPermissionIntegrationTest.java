package io.github.hectorvent.floci.services.apigateway;

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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RestLambdaPermissionIntegrationTest {

    private static final String OWNER = "121212121212";
    private static final String FOREIGN = "343434343434";
    private static final String CALLER = "565656565656";
    private static final String REGION = "us-east-1";

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
    @CsvSource({"AWS_PROXY,arn:aws:iam::121212121212:role/integration",
            "AWS,arn:aws:iam::121212121212:role/integration",
            "AWS_PROXY,arn:aws:iam::*:user/*", "AWS,arn:aws:iam::*:user/*"})
    void configuredCredentialsRemainOutsideServicePrincipalEvaluation(String type, String credentials) throws Exception {
        Fixture fixture = fixture(type, OWNER, REGION, credentials);
        invoke(fixture, 200);
        verifyInvocations(fixture, 2);
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

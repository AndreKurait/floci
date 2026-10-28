package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class RoleRequestTimeIntegrationTest {

    @InjectSpy
    LambdaService lambda;

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"AWS", "AWS_PROXY", "AWS:tags", "AWS_PROXY:tags"})
    void signedManagementRolePoliciesEnforceContextBeforeLambdaInvocation(String scenario) throws Exception {
        boolean tagged = scenario.endsWith(":tags");
        String type = scenario.split(":")[0];
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        cleanup.register(iam::close);
        LambdaClient functions = LambdaClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        cleanup.register(functions::close);
        ApiGatewayClient gateway = ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        cleanup.register(gateway::close);
        String name = "role-request-time-" + UUID.randomUUID().toString().substring(0, 8);
        String trust = """
                {"Statement":{"Effect":"Allow","Principal":{"Service":"apigateway.amazonaws.com"},
                "Action":"sts:AssumeRole","Condition":{"DateLessThan":{"aws:CurrentTime":"2100-01-01T00:00:00Z"}}}}""";
        String role = iam.createRole(request -> request.roleName(name).path("/service-role/")
                .assumeRolePolicyDocument(trust)).role().arn();
        cleanup.register(() -> iam.deleteRole(request -> request.roleName(name)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => ({});".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        functions.createFunction(request -> request.functionName(name).runtime(Runtime.NODEJS20_X).handler("index.handler")
                .role(role).code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(bytes.toByteArray())).build()));
        cleanup.register(() -> functions.deleteFunction(request -> request.functionName(name)));
        functions.publishVersion(request -> request.functionName(name));
        String target = functions.createAlias(request -> request.functionName(name).name("live").functionVersion("1"))
                .aliasArn();
        if (tagged) {
            iam.tagRole(request -> request.roleName(name).tags(tag -> tag.key("team").value("orders")));
        }
        String timeAllow = """
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s",
                "Condition":{"NumericLessThan":{"aws:EpochTime":"4102444800"}}}}""".formatted(target);
        String allow = tagged ? """
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s",
                "Condition":{"StringEquals":{"aws:PrincipalTag/team":"orders"}}}}""".formatted(target) : timeAllow;
        iam.putRolePolicy(request -> request.roleName(name).policyName("invoke").policyDocument(allow));
        cleanup.register(() -> iam.deleteRolePolicy(request -> request.roleName(name).policyName("invoke")));
        String api = gateway.createRestApi(request -> request.name(name)).id();
        cleanup.register(() -> gateway.deleteRestApi(request -> request.restApiId(api)));
        String root = gateway.getResources(request -> request.restApiId(api)).items().getFirst().id();
        gateway.putMethod(request -> request.restApiId(api).resourceId(root).httpMethod("POST").authorizationType("NONE"));
        gateway.putIntegration(request -> request.restApiId(api).resourceId(root).httpMethod("POST")
                .type(IntegrationType.fromValue(type)).integrationHttpMethod("POST").credentials(role)
                .uri("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/" + target + "/invocations"));
        if ("AWS".equals(type)) {
            gateway.putMethodResponse(request -> request.restApiId(api).resourceId(root).httpMethod("POST").statusCode("200"));
            gateway.putIntegrationResponse(request -> request.restApiId(api).resourceId(root).httpMethod("POST").statusCode("200"));
        }
        gateway.createDeployment(request -> request.restApiId(api).stageName("prod"));
        doReturn(new InvokeResult(200, null, "{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8), null, "time-test"))
                .when(lambda).invokeTarget(eq("us-east-1"), eq(target), any(byte[].class), eq(InvocationType.RequestResponse));
        invoke(api, 200);
        verify(lambda, times(2)).invokeTarget(eq("us-east-1"), eq(target), any(byte[].class), eq(InvocationType.RequestResponse));
        if (tagged) {
            iam.tagRole(request -> request.roleName(name).tags(tag -> tag.key("team").value("payments")));
            invoke(api, 500);
            iam.untagRole(request -> request.roleName(name).tagKeys("team"));
            invoke(api, 500);
            iam.tagRole(request -> request.roleName(name).tags(tag -> tag.key("team").value("orders")));
            String deny = """
                    {"Statement":[{"Effect":"Allow","Action":"*","Resource":"*"},
                    {"Effect":"Deny","Action":"lambda:InvokeFunction","Resource":"%s",
                    "Condition":{"StringEquals":{"aws:PrincipalTag/team":"orders"}}}]}""".formatted(target);
            iam.putRolePolicy(request -> request.roleName(name).policyName("invoke").policyDocument(deny));
            invoke(api, 500);
        } else {
            iam.putRolePolicy(request -> request.roleName(name).policyName("invoke")
                    .policyDocument(allow.replace("4102444800", "0")));
            invoke(api, 500);
        }
        iam.putRolePolicy(request -> request.roleName(name).policyName("invoke").policyDocument(allow));
        iam.updateAssumeRolePolicy(request -> request.roleName(name)
                .policyDocument(trust.replace("2100-01-01T00:00:00Z", "2000-01-01T00:00:00Z")));
        invoke(api, 500);
        verify(lambda, times(2)).invokeTarget(eq("us-east-1"), eq(target), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    private void invoke(String api, int status) {
        for (String path : List.of("/execute-api/" + api + "/prod/", "/restapis/" + api + "/prod/_user_request_/")) {
            given().contentType("application/json").body("{}").post(path).then().statusCode(status);
        }
    }
}

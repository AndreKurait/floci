package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.iam.model.Tag;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class ServiceRoleSessionIntegrationTest {

    @InjectSpy
    LambdaService lambda;
    @InjectSpy
    IamService iamService;
    @Inject
    IamPolicyEvaluator evaluator;
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"allow-userid", "deny-userid", "allow-role", "deny-role",
            "allow-token", "deny-token", "allow-tag", "deny-tag"})
    void configuredRoleMustMatchIssuedSessionPrincipalContext(String scenario) throws Exception {
        StaticCredentialsProvider rootCredentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(rootCredentials).build();
        cleanup.register(iam::close);
        StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(rootCredentials).build();
        cleanup.register(sts::close);
        LambdaClient functions = LambdaClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(rootCredentials).build();
        cleanup.register(functions::close);
        ApiGatewayClient gateway = ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(rootCredentials).build();
        cleanup.register(gateway::close);
        String name = "session-gap-" + UUID.randomUUID().toString().substring(0, 8);
        String trust = """
                {"Statement":[{"Effect":"Allow","Principal":{"Service":"apigateway.amazonaws.com"},
                "Action":"sts:AssumeRole"},{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::000000000000:root"},
                "Action":"sts:AssumeRole"}]}""";
        Role role = iam.createRole(request -> request.roleName(name).path("/service-role/")
                .assumeRolePolicyDocument(trust)).role();
        cleanup.register(() -> {
            iam.deleteRole(request -> request.roleName(name));
            assertTrue(iamService.findRole("000000000000", name).isEmpty());
        });
        iam.tagRole(request -> request.roleName(name).tags(
                Tag.builder().key("access").value("enabled").build()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => ({});".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        functions.createFunction(request -> request.functionName(name).runtime(Runtime.NODEJS20_X).handler("index.handler")
                .role(role.arn()).code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(bytes.toByteArray())).build()));
        cleanup.register(() -> functions.deleteFunction(request -> request.functionName(name)));
        functions.publishVersion(request -> request.functionName(name));
        String target = functions.createAlias(request -> request.functionName(name).name("live").functionVersion("1")).aliasArn();
        boolean deny = scenario.startsWith("deny");
        String key = switch (scenario.substring(scenario.indexOf('-') + 1)) {
            case "userid" -> "aws:userid";
            case "token" -> "aws:TokenIssueTime";
            case "tag" -> "aws:PrincipalTag/access";
            default -> "aws:PrincipalArn";
        };
        String value = switch (key) {
            case "aws:userid" -> role.roleId() + ":*";
            case "aws:TokenIssueTime" -> "*";
            case "aws:PrincipalTag/access" -> "enabled";
            default -> role.arn();
        };
        String statement = """
                {"Effect":"%s","Action":"lambda:InvokeFunction","Resource":"%s",
                "Condition":{"StringLike":{"%s":"%s"}}}""".formatted(deny ? "Deny" : "Allow", target, key, value);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + (deny ? "{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\"" + target + "\"}," : "")
                + statement + "]}";
        iam.putRolePolicy(request -> request.roleName(name).policyName("invoke").policyDocument(policy));
        cleanup.register(() -> iam.deleteRolePolicy(request -> request.roleName(name).policyName("invoke")));
        AssumeRoleResponse assumed = sts.assumeRole(request -> request.roleArn(role.arn()).roleSessionName("diagnostic"));
        Credentials issued = assumed.credentials();
        cleanup.register(() -> {
            iamService.unregisterSession("000000000000", issued.accessKeyId());
            assertTrue(iamService.resolveCallerArns(issued.accessKeyId()).isEmpty());
        });
        StsClient sessionSts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        issued.accessKeyId(), issued.secretAccessKey(), issued.sessionToken()))).build();
        cleanup.register(sessionSts::close);
        GetCallerIdentityResponse identity = sessionSts.getCallerIdentity();
        assertEquals(role.roleId() + ":diagnostic", identity.userId());
        assertTrue(identity.arn().endsWith("/" + name + "/diagnostic"));
        IamService.CallerArns caller = iamService.resolveCallerArns(issued.accessKeyId()).orElseThrow();
        assertEquals(role.arn(), caller.principalArn());
        Map<String, List<String>> context = new HashMap<>();
        caller.putPrincipalKeys(context);
        context = IamConditionContextResolver.withGlobalContext(context, target, "us-east-1", "000000000000", "000000000000");
        assertEquals(deny ? Decision.DENY : Decision.ALLOW, evaluator.evaluate(
                iamService.resolveCallerContext(issued.accessKeyId(), Instant.now()), List.of(), "lambda:InvokeFunction", target, context),
                "Real STS-issued credential context must establish the positive control before gateway comparison");
        String api = gateway.createRestApi(request -> request.name(name)).id();
        cleanup.register(() -> gateway.deleteRestApi(request -> request.restApiId(api)));
        String root = gateway.getResources(request -> request.restApiId(api)).items().getFirst().id();
        gateway.putMethod(request -> request.restApiId(api).resourceId(root).httpMethod("POST").authorizationType("NONE"));
        gateway.putIntegration(request -> request.restApiId(api).resourceId(root).httpMethod("POST")
                .type(IntegrationType.AWS_PROXY).integrationHttpMethod("POST").credentials(role.arn())
                .uri("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/" + target + "/invocations"));
        gateway.createDeployment(request -> request.restApiId(api).stageName("prod"));
        doReturn(new InvokeResult(200, null, "{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8), null, "session-gap"))
                .when(lambda).invokeTarget(eq("us-east-1"), eq(target), any(byte[].class), eq(InvocationType.RequestResponse));
        given().contentType("application/json").body("{}").post("/execute-api/" + api + "/prod/")
                .then().statusCode(deny ? 500 : 200);
        verify(lambda, times(deny ? 0 : 1)).invokeTarget(eq("us-east-1"), eq(target), any(byte[].class), eq(InvocationType.RequestResponse));
        ArgumentCaptor<String> authorizationKey = ArgumentCaptor.forClass(String.class);
        verify(iamService).registerSessionForAccount(eq("000000000000"), authorizationKey.capture(), anyString(), anyString(),
                eq(role.arn()), any(Instant.class), isNull(), anyString(), anyString());
        assertTrue(iamService.resolveCallerArns(authorizationKey.getValue()).isEmpty(),
                "The local service authorization credential must be revoked after evaluation");

    }
}

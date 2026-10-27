package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ApiGatewayException;
import software.amazon.awssdk.services.apigateway.model.Op;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.ApiGatewayV2Exception;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Signed management requests use the shared IAM check before changing stored credentials. */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class ApiGatewayPassRoleIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void restIntegrationAssignmentAndReplacementNeedPassRoleButRemovalDoesNot() {
        try (Fixture f = new Fixture()) {
            String api = f.rest.createRestApi(b -> b.name(f.name)).id();
            cleanup.register(() -> {
                try (ApiGatewayClient admin = f.adminRest()) {
                    admin.deleteRestApi(b -> b.restApiId(api));
                }
            });
            String resource = f.rest.getResources(b -> b.restApiId(api)).items().getFirst().id();
            f.rest.putMethod(b -> b.restApiId(api).resourceId(resource).httpMethod("GET").authorizationType("NONE"));
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.putIntegration(b -> b
                    .restApiId(api).resourceId(resource).httpMethod("GET").type("AWS_PROXY")
                    .integrationHttpMethod("POST").uri(f.uri()).credentials(f.role))).statusCode());
            f.policy("lambda.amazonaws.com");
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.putIntegration(b -> b
                    .restApiId(api).resourceId(resource).httpMethod("GET").type("AWS_PROXY")
                    .integrationHttpMethod("POST").uri(f.uri()).credentials(f.role))).statusCode());
            f.policy("apigateway.amazonaws.com");
            f.rest.putIntegration(b -> b.restApiId(api).resourceId(resource).httpMethod("GET").type("AWS_PROXY")
                    .integrationHttpMethod("POST").uri(f.uri()).credentials(f.role));
            f.policy(null);
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.updateIntegration(b -> b
                    .restApiId(api).resourceId(resource).httpMethod("GET").patchOperations(PatchOperation.builder()
                            .op(Op.REPLACE).path("/credentials").value(f.role + "other").build()))).statusCode());
            assertEquals(f.role, f.rest.getIntegration(b -> b.restApiId(api).resourceId(resource).httpMethod("GET"))
                    .credentials());
            assertEquals(400, assertThrows(ApiGatewayException.class, () -> f.rest.updateIntegration(b -> b
                    .restApiId(api).resourceId(resource).httpMethod("GET").patchOperations(
                            PatchOperation.builder().op(Op.REMOVE).path("/credentials").build()))).statusCode());
        }
    }

    @Test
    void restAuthorizerAssignmentNeedsPassRoleAndDeniedPatchPreservesName() {
        try (Fixture f = new Fixture()) {
            String api = f.rest.createRestApi(b -> b.name(f.name)).id();
            cleanup.register(() -> {
                try (ApiGatewayClient admin = f.adminRest()) {
                    admin.deleteRestApi(b -> b.restApiId(api));
                }
            });
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.createAuthorizer(b -> b
                    .restApiId(api).name("guard").type("TOKEN").identitySource("method.request.header.Authorization")
                    .authorizerUri(f.uri()).authorizerCredentials(f.role))).statusCode());
            assertEquals(0, f.rest.getAuthorizers(b -> b.restApiId(api)).items().size());
            f.policy("apigateway.amazonaws.com");
            String authorizer = f.rest.createAuthorizer(b -> b.restApiId(api).name("guard").type("TOKEN")
                    .identitySource("method.request.header.Authorization").authorizerUri(f.uri())
                    .authorizerCredentials(f.role)).id();
            f.policy(null);
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.updateAuthorizer(b -> b
                    .restApiId(api).authorizerId(authorizer).patchOperations(
                            PatchOperation.builder().op(Op.REPLACE).path("/name").value("changed").build(),
                            PatchOperation.builder().op(Op.REPLACE).path("/authorizerCredentials").value(f.role).build())))
                    .statusCode());
            assertEquals("guard", f.rest.getAuthorizer(b -> b.restApiId(api).authorizerId(authorizer)).name());
        }
    }

    @Test
    void v2IntegrationCreationAndUpdateNeedPassRole() {
        try (Fixture f = new Fixture()) {
            String api = f.v2.createApi(b -> b.name(f.name).protocolType("HTTP")).apiId();
            cleanup.register(() -> {
                try (ApiGatewayV2Client admin = f.adminV2()) {
                    admin.deleteApi(b -> b.apiId(api));
                }
            });
            assertEquals(403, assertThrows(ApiGatewayV2Exception.class, () -> f.v2.createIntegration(b -> b.apiId(api)
                    .integrationType("AWS_PROXY").integrationUri(f.uri()).credentialsArn(f.role))).statusCode());
            assertEquals(0, f.v2.getIntegrations(b -> b.apiId(api)).items().size());
            f.policy("apigateway.amazonaws.com");
            String id = f.v2.createIntegration(b -> b.apiId(api).integrationType("AWS_PROXY")
                    .integrationUri(f.uri()).credentialsArn(f.role)).integrationId();
            f.policy(null);
            assertEquals(403, assertThrows(ApiGatewayV2Exception.class, () -> f.v2.updateIntegration(b -> b.apiId(api)
                    .integrationId(id).integrationUri(f.uri() + "changed").credentialsArn(f.role))).statusCode());
            assertEquals(f.uri(), f.v2.getIntegration(b -> b.apiId(api).integrationId(id)).integrationUri());
            f.v2.updateIntegration(b -> b.apiId(api).integrationId(id).integrationUri(f.uri()));
        }
    }

    @Test
    void v2AuthorizerCreationAndUpdateNeedPassRole() {
        try (Fixture f = new Fixture()) {
            String api = f.v2.createApi(b -> b.name(f.name).protocolType("WEBSOCKET")
                    .routeSelectionExpression("$request.body.action")).apiId();
            cleanup.register(() -> {
                try (ApiGatewayV2Client admin = f.adminV2()) {
                    admin.deleteApi(b -> b.apiId(api));
                }
            });
            assertEquals(403, assertThrows(ApiGatewayV2Exception.class, () -> f.v2.createAuthorizer(b -> b.apiId(api)
                    .name("guard").authorizerType("REQUEST").identitySource("route.request.header.Authorization")
                    .authorizerUri(f.uri()).authorizerCredentialsArn(f.role))).statusCode());
            assertEquals(0, f.v2.getAuthorizers(b -> b.apiId(api)).items().size());
            f.policy("apigateway.amazonaws.com");
            String id = f.v2.createAuthorizer(b -> b.apiId(api).name("guard").authorizerType("REQUEST")
                    .identitySource("route.request.header.Authorization").authorizerUri(f.uri())
                    .authorizerCredentialsArn(f.role)).authorizerId();
            f.policy(null);
            assertEquals(403, assertThrows(ApiGatewayV2Exception.class, () -> f.v2.updateAuthorizer(b -> b.apiId(api)
                    .authorizerId(id).name("changed").authorizerCredentialsArn(f.role))).statusCode());
            assertEquals("guard", f.v2.getAuthorizer(b -> b.apiId(api).authorizerId(id)).name());
        }
    }

    @Test
    void issuedRoleSessionPolicyMustAllowPassingTheIntegrationRole() {
        try (Fixture f = new Fixture(); StsClient sts = StsClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test"))).build()) {
            String api = f.rest.createRestApi(b -> b.name(f.name)).id();
            cleanup.register(() -> {
                try (ApiGatewayClient admin = f.adminRest()) {
                    admin.deleteRestApi(b -> b.restApiId(api));
                }
            });
            String resource = f.rest.getResources(b -> b.restApiId(api)).items().getFirst().id();
            f.rest.putMethod(b -> b.restApiId(api).resourceId(resource).httpMethod("GET").authorizationType("NONE"));
            String callerName = f.name + "-caller";
            cleanup.register(() -> {
                try (IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                        .build()) {
                    admin.deleteRolePolicy(b -> b.roleName(callerName).policyName("access"));
                    admin.deleteRole(b -> b.roleName(callerName));
                }
            });
            String callerRole = f.iam.createRole(b -> b.roleName(callerName).path("/callers/api/")
                    .assumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                            + "\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},\"Action\":\"sts:AssumeRole\"}]}"))
                    .role().arn();
            String all = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":[\"apigateway:*\",\"iam:PassRole\"],\"Resource\":\"*\"}]}";
            String apiOnly = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"apigateway:*\",\"Resource\":\"*\"}]}";
            f.iam.putRolePolicy(b -> b.roleName(callerName).policyName("access").policyDocument(all));
            Credentials restricted = sts.assumeRole(b -> b.roleArn(callerRole).roleSessionName("restricted")
                    .policy(apiOnly)).credentials();
            try (ApiGatewayClient client = sessionClient(restricted)) {
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> client.putIntegration(b -> b
                        .restApiId(api).resourceId(resource).httpMethod("GET").type("AWS_PROXY")
                        .integrationHttpMethod("POST").uri(f.uri()).credentials(f.role))).statusCode());
            }
            Credentials allowed = sts.assumeRole(b -> b.roleArn(callerRole).roleSessionName("allowed").policy(all))
                    .credentials();
            try (ApiGatewayClient client = sessionClient(allowed)) {
                client.putIntegration(b -> b.restApiId(api).resourceId(resource).httpMethod("GET").type("AWS_PROXY")
                        .integrationHttpMethod("POST").uri(f.uri()).credentials(f.role));
            }
        }
    }

    @Test
    void accountLoggingRoleRequiresPassRoleAndRevocationPreservesState() {
        try (Fixture f = new Fixture()) {
            String original = f.rest.getAccount().cloudwatchRoleArn();
            cleanup.register(() -> {
                try (ApiGatewayClient admin = f.adminRest()) {
                    admin.updateAccount(b -> b.patchOperations(PatchOperation.builder()
                            .op(original == null ? Op.REMOVE : Op.REPLACE).path("/cloudwatchRoleArn")
                            .value(original).build()));
                }
            });
            PatchOperation assignment = PatchOperation.builder().op(Op.REPLACE)
                    .path("/cloudwatchRoleArn").value(f.role).build();
            assertEquals(403, assertThrows(ApiGatewayException.class,
                    () -> f.rest.updateAccount(b -> b.patchOperations(assignment))).statusCode());
            assertEquals(original, f.rest.getAccount().cloudwatchRoleArn());
            f.policy("lambda.amazonaws.com");
            assertEquals(403, assertThrows(ApiGatewayException.class,
                    () -> f.rest.updateAccount(b -> b.patchOperations(assignment))).statusCode());
            f.policy("apigateway.amazonaws.com");
            assertEquals(f.role, f.rest.updateAccount(b -> b.patchOperations(assignment)).cloudwatchRoleArn());
            f.policy(null);
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.updateAccount(b -> b.patchOperations(
                    PatchOperation.builder().op(Op.REPLACE).path("/cloudwatchRoleArn").value(f.role + "other").build(),
                    assignment))).statusCode());
            assertEquals(f.role, f.rest.getAccount().cloudwatchRoleArn());
            assertEquals(null, f.rest.updateAccount(b -> b.patchOperations(PatchOperation.builder()
                    .op(Op.REMOVE).path("/cloudwatchRoleArn").build())).cloudwatchRoleArn());
        }
    }

    @Test
    void quickCreateLambdaRequiresPassRoleBeforeCreatingAnyApi() {
        try (Fixture f = new Fixture()) {
            cleanup.register(() -> {
                try (ApiGatewayV2Client admin = f.adminV2()) {
                    admin.getApis().items().stream().filter(api -> f.name.equals(api.name()))
                            .forEach(api -> admin.deleteApi(b -> b.apiId(api.apiId())));
                }
            });
            String target = "arn:aws:lambda:us-east-1:000000000000:function:management-only";
            int initial = f.v2.getApis().items().size();
            for (String requestedTarget : new String[] {target, "arn:incomplete"}) {
                assertEquals(403, assertThrows(ApiGatewayV2Exception.class, () -> f.v2.createApi(b -> b
                        .name(f.name).protocolType("HTTP").target(requestedTarget).credentialsArn(f.role))).statusCode());
                assertEquals(initial, f.v2.getApis().items().size());
            }
            f.policy("lambda.amazonaws.com");
            assertEquals(403, assertThrows(ApiGatewayV2Exception.class, () -> f.v2.createApi(b -> b
                    .name(f.name).protocolType("HTTP").target(target).credentialsArn(f.role))).statusCode());
            assertEquals(initial, f.v2.getApis().items().size());
            f.policy("apigateway.amazonaws.com");
            String api = f.v2.createApi(b -> b.name(f.name).protocolType("HTTP").target(target)
                    .credentialsArn(f.role)).apiId();
            assertEquals(target, f.v2.getIntegrations(b -> b.apiId(api)).items().getFirst().integrationUri());
            assertEquals(1, f.v2.getRoutes(b -> b.apiId(api)).items().size());
            assertEquals(1, f.v2.getStages(b -> b.apiId(api)).items().size());
            f.policy(null);
            assertEquals(403, assertThrows(ApiGatewayV2Exception.class, () -> f.v2.createApi(b -> b
                    .name(f.name).protocolType("HTTP").target(target).credentialsArn(f.role))).statusCode());
            assertEquals(initial + 1, f.v2.getApis().items().size());
        }
    }

    @Test
    void quickCreateUnusedCredentialsDoNotRequirePassRole() {
        try (Fixture f = new Fixture()) {
            cleanup.register(() -> {
                try (ApiGatewayV2Client admin = f.adminV2()) {
                    admin.getApis().items().stream().filter(api -> f.name.equals(api.name()))
                            .forEach(api -> admin.deleteApi(b -> b.apiId(api.apiId())));
                }
            });
            String plain = f.v2.createApi(b -> b.name(f.name).protocolType("HTTP").credentialsArn(f.role)).apiId();
            assertEquals(0, f.v2.getIntegrations(b -> b.apiId(plain)).items().size());
            String proxy = f.v2.createApi(b -> b.name(f.name).protocolType("HTTP")
                    .target("https://example.com").credentialsArn(f.role)).apiId();
            assertEquals("HTTP_PROXY", f.v2.getIntegrations(b -> b.apiId(proxy)).items().getFirst().integrationTypeAsString());
        }
    }

    @Test
    void importedRestIntegrationsRequirePassRoleBeforeCreation() {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            String spec = importDocument(f, f.role, "get", "3.0.1");
            int initial = f.rest.getRestApis().items().size();
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                    .body(SdkBytes.fromUtf8String(spec)))).statusCode());
            assertEquals(initial, f.rest.getRestApis().items().size());
            f.policy("lambda.amazonaws.com");
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                    .body(SdkBytes.fromUtf8String(spec)))).statusCode());
            f.policy("apigateway.amazonaws.com");
            String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec))).id();
            String resource = f.rest.getResources(b -> b.restApiId(api)).items().stream()
                    .filter(item -> "/imported".equals(item.path())).findFirst().orElseThrow().id();
            assertEquals(f.role, f.rest.getIntegration(b -> b.restApiId(api).resourceId(resource)
                    .httpMethod("GET")).credentials());
        }
    }

    @Test
    void importedSwaggerAnyIntegrationUsesTheSameRoleCheck() {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            String spec = importDocument(f, f.role, "x-amazon-apigateway-any-method", "2.0");
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                    .body(SdkBytes.fromUtf8String(spec)))).statusCode());
            f.policy("apigateway.amazonaws.com");
            String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec))).id();
            String resource = f.rest.getResources(b -> b.restApiId(api)).items().stream()
                    .filter(item -> "/imported".equals(item.path())).findFirst().orElseThrow().id();
            assertEquals(f.role, f.rest.getIntegration(b -> b.restApiId(api).resourceId(resource)
                    .httpMethod("ANY")).credentials());
        }
    }

    @Test
    void deniedRestImportMergeAndOverwritePreserveExistingState() {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            f.policy("apigateway.amazonaws.com");
            String spec = importDocument(f, f.role, "get", "3.0.1");
            String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec))).id();
            f.policy(null);
            for (String mode : new String[] {"merge", "overwrite"}) {
                String changed = spec.replace("/imported", "/replacement");
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.putRestApi(b -> b
                        .restApiId(api).mode(mode).body(SdkBytes.fromUtf8String(changed)))).statusCode());
                assertEquals(2, f.rest.getResources(b -> b.restApiId(api)).items().size());
                String resource = f.rest.getResources(b -> b.restApiId(api)).items().stream()
                        .filter(item -> "/imported".equals(item.path())).findFirst().orElseThrow().id();
                assertEquals(f.role, f.rest.getIntegration(b -> b.restApiId(api).resourceId(resource)
                        .httpMethod("GET")).credentials());
            }
            String cleared = spec.replace("\"credentials\":\"" + f.role + "\",", "");
            f.rest.putRestApi(b -> b.restApiId(api).mode("overwrite").body(SdkBytes.fromUtf8String(cleared)));
            String resource = f.rest.getResources(b -> b.restApiId(api)).items().stream()
                    .filter(item -> "/imported".equals(item.path())).findFirst().orElseThrow().id();
            assertEquals(null, f.rest.getIntegration(b -> b.restApiId(api).resourceId(resource)
                    .httpMethod("GET")).credentials());
        }
    }

    @Test
    void yamlImportResolvesAwsVariablesBeforeRoleAuthorization() {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            String spec = """
                    openapi: 3.0.1
                    info:
                      title: %s
                      version: '1'
                    paths:
                      /imported:
                        get:
                          responses:
                            '200':
                              description: OK
                          x-amazon-apigateway-integration:
                            type: aws_proxy
                            httpMethod: POST
                            uri: %s
                            credentials: '%s'
                    """.formatted(f.name, f.uri(), f.role.replace("arn:aws:iam::000000000000:",
                            "arn:${AWS::Partition}:iam::${AWS::AccountId}:"));
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                    .body(SdkBytes.fromUtf8String(spec)))).statusCode());
            f.policy("apigateway.amazonaws.com");
            String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec))).id();
            String resource = f.rest.getResources(b -> b.restApiId(api)).items().stream()
                    .filter(item -> "/imported".equals(item.path())).findFirst().orElseThrow().id();
            assertEquals(f.role, f.rest.getIntegration(b -> b.restApiId(api).resourceId(resource)
                    .httpMethod("GET")).credentials());
        }
    }

    @Test
    void importIgnoresUnconsumedRoleLookalikesAndCallerCredentials() {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            String spec = importDocument(f, "arn:aws:iam::*:user/*", "get", "3.0.1")
                    .replace("\"info\":", "\"x-unused\":{\"credentials\":\"" + f.role + "\"},\"info\":");
            f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec)));
            String unknown = importDocument(f, f.role, "get", "3.0.1").replace("aws_proxy", "unknown");
            f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(unknown)));
            assertEquals(400, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                    .body(SdkBytes.fromUtf8String("invalid")))).statusCode());
        }
    }

    @Test
    void everyImportedIntegrationRoleIsCheckedBeforeAnyResourceChanges() throws Exception {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            f.policy("apigateway.amazonaws.com");
            String spec = importDocument(f, f.role, "get", "3.0.1");
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode document = (ObjectNode) mapper.readTree(spec);
            ObjectNode paths = (ObjectNode) document.get("paths");
            ObjectNode rejected = (ObjectNode) paths.get("/imported").deepCopy();
            rejected.withObject("/get/x-amazon-apigateway-integration").put("credentials", f.role + "other");
            paths.set("/rejected", rejected);
            String multiple = mapper.writeValueAsString(document);
            int initial = f.rest.getRestApis().items().size();
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                    .body(SdkBytes.fromUtf8String(multiple)))).statusCode());
            assertEquals(initial, f.rest.getRestApis().items().size());
            String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec))).id();
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.putRestApi(b -> b
                    .restApiId(api).mode("overwrite").body(SdkBytes.fromUtf8String(multiple)))).statusCode());
            assertEquals(2, f.rest.getResources(b -> b.restApiId(api)).items().size());
        }
    }

    @Test
    void importedLambdaAuthorizersRequirePassRoleBeforeCreation() {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            for (String type : new String[] {"token", "request"}) {
                String spec = authorizerDocument(f, f.role, type, false);
                int initial = f.rest.getRestApis().items().size();
                f.policy(null);
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                        .body(SdkBytes.fromUtf8String(spec)))).statusCode());
                assertEquals(initial, f.rest.getRestApis().items().size());
                f.policy("lambda.amazonaws.com");
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                        .body(SdkBytes.fromUtf8String(spec)))).statusCode());
                f.policy("apigateway.amazonaws.com");
                String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec))).id();
                assertEquals(f.role, f.rest.getAuthorizers(b -> b.restApiId(api)).items().getFirst()
                        .authorizerCredentials());
            }
        }
    }

    @Test
    void swaggerAuthorizerAssignmentsUseNormalizedSecurityDefinitions() {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            String spec = authorizerDocument(f, f.role, "token", true);
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                    .body(SdkBytes.fromUtf8String(spec)))).statusCode());
            f.policy("apigateway.amazonaws.com");
            String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec))).id();
            assertEquals(f.role, f.rest.getAuthorizers(b -> b.restApiId(api)).items().getFirst()
                    .authorizerCredentials());
        }
    }

    @Test
    void yamlAuthorizerAssignmentResolvesAwsVariables() {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            String spec = """
                    openapi: 3.0.1
                    info:
                      title: %s
                      version: '1'
                    paths: {}
                    components:
                      securitySchemes:
                        guard:
                          type: apiKey
                          name: Authorization
                          in: header
                          x-amazon-apigateway-authorizer:
                            type: token
                            authorizerUri: '%s'
                            authorizerCredentials: '%s'
                    """.formatted(f.name, f.uri(), f.role.replace("arn:aws:iam::000000000000:",
                            "arn:${AWS::Partition}:iam::${AWS::AccountId}:"));
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                    .body(SdkBytes.fromUtf8String(spec)))).statusCode());
            f.policy("apigateway.amazonaws.com");
            String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec))).id();
            assertEquals(f.role, f.rest.getAuthorizers(b -> b.restApiId(api)).items().getFirst()
                    .authorizerCredentials());
        }
    }

    @Test
    void deniedAuthorizerMergeAndOverwritePreserveCredentialsAndClearNeedsNoPassRole() {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            f.policy("apigateway.amazonaws.com");
            String spec = authorizerDocument(f, f.role, "request", false);
            String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec))).id();
            String id = f.rest.getAuthorizers(b -> b.restApiId(api)).items().getFirst().id();
            f.policy(null);
            for (String mode : new String[] {"merge", "overwrite"}) {
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.putRestApi(b -> b
                        .restApiId(api).mode(mode).body(SdkBytes.fromUtf8String(spec)))).statusCode());
                assertEquals(id, f.rest.getAuthorizers(b -> b.restApiId(api)).items().getFirst().id());
                assertEquals(f.role, f.rest.getAuthorizer(b -> b.restApiId(api).authorizerId(id))
                        .authorizerCredentials());
            }
            String omitted = spec.replace("\"authorizerCredentials\":\"" + f.role + "\",", "");
            f.rest.putRestApi(b -> b.restApiId(api).mode("merge").body(SdkBytes.fromUtf8String(omitted)));
            assertEquals(f.role, f.rest.getAuthorizer(b -> b.restApiId(api).authorizerId(id)).authorizerCredentials());
            String cleared = spec.replace("\"authorizerCredentials\":\"" + f.role + "\"",
                    "\"authorizerCredentials\":null");
            f.rest.putRestApi(b -> b.restApiId(api).mode("merge").body(SdkBytes.fromUtf8String(cleared)));
            assertEquals(null, f.rest.getAuthorizer(b -> b.restApiId(api).authorizerId(id)).authorizerCredentials());
        }
    }

    @Test
    void everyImportedAuthorizerRoleIsCheckedBeforeAnyMutation() throws Exception {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            f.policy("apigateway.amazonaws.com");
            String spec = authorizerDocument(f, f.role, "token", false);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode document = (ObjectNode) mapper.readTree(spec);
            ObjectNode schemes = (ObjectNode) document.get("components").get("securitySchemes");
            ObjectNode rejected = schemes.get("guard").deepCopy();
            ((ObjectNode) rejected.get("x-amazon-apigateway-authorizer"))
                    .put("authorizerCredentials", f.role + "other");
            schemes.set("rejected", rejected);
            String multiple = mapper.writeValueAsString(document);
            int initial = f.rest.getRestApis().items().size();
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                    .body(SdkBytes.fromUtf8String(multiple)))).statusCode());
            assertEquals(initial, f.rest.getRestApis().items().size());
            String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(spec))).id();
            assertEquals(403, assertThrows(ApiGatewayException.class, () -> f.rest.putRestApi(b -> b
                    .restApiId(api).mode("overwrite").body(SdkBytes.fromUtf8String(multiple)))).statusCode());
            assertEquals(1, f.rest.getAuthorizers(b -> b.restApiId(api)).items().size());
            assertEquals(f.role, f.rest.getAuthorizers(b -> b.restApiId(api)).items().getFirst().authorizerCredentials());
        }
    }

    @Test
    void authorizerImportIgnoresUnusedFieldsAndRejectsMalformedCredentialsWithoutMutation() {
        try (Fixture f = new Fixture()) {
            registerImportedApis(f);
            String plain = authorizerDocument(f, f.role, "token", false)
                    .replace("authorizerCredentials", "unusedCredentials");
            String api = f.rest.importRestApi(b -> b.body(SdkBytes.fromUtf8String(plain))).id();
            assertEquals(null, f.rest.getAuthorizers(b -> b.restApiId(api)).items().getFirst().authorizerCredentials());
            String malformed = authorizerDocument(f, f.role, "token", false)
                    .replace("\"authorizerCredentials\":\"" + f.role + "\"", "\"authorizerCredentials\":42");
            int initial = f.rest.getRestApis().items().size();
            assertEquals(400, assertThrows(ApiGatewayException.class, () -> f.rest.importRestApi(b -> b
                    .body(SdkBytes.fromUtf8String(malformed)))).statusCode());
            assertEquals(initial, f.rest.getRestApis().items().size());
        }
    }

    private static String authorizerDocument(Fixture f, String role, String type, boolean swagger) {
        String schemes = """
                {"guard":{"type":"apiKey","name":"Authorization","in":"header",
                  "x-amazon-apigateway-authorizer":{"type":"%s","authorizerCredentials":"%s",
                    "identitySource":"method.request.header.Authorization","authorizerUri":"%s"}}}
                """.formatted(type, role, f.uri());
        String definitions = swagger ? "\"securityDefinitions\":" + schemes
                : "\"components\":{\"securitySchemes\":" + schemes + "}";
        return "{\"" + (swagger ? "swagger\":\"2.0" : "openapi\":\"3.0.1")
                + "\",\"info\":{\"title\":\"" + f.name + "\",\"version\":\"1\"},\"paths\":{}," + definitions + "}";
    }

    private void registerImportedApis(Fixture f) {
        cleanup.register(() -> {
            try (ApiGatewayClient admin = f.adminRest()) {
                admin.getRestApisPaginator().items().stream().filter(api -> f.name.equals(api.name()))
                        .forEach(api -> admin.deleteRestApi(b -> b.restApiId(api.id())));
            }
        });
    }

    private static String importDocument(Fixture f, String role, String method, String version) {
        String field = "2.0".equals(version) ? "swagger" : "openapi";
        return """
                {"%s":"%s","info":{"title":"%s","version":"1"},"paths":{"/imported":{
                  "%s":{"responses":{"200":{"description":"OK"}},
                    "x-amazon-apigateway-integration":{"type":"aws_proxy","httpMethod":"POST",
                      "credentials":"%s","uri":"%s"}}}}}
                """.formatted(field, version, f.name, method, role, f.uri());
    }

    private ApiGatewayClient sessionClient(Credentials credentials) {
        return ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()))).build();
    }

    @Test
    void rejectedFixtureRoleCreationDoesNotDeleteAnExistingRole() {
        UUID fixed = UUID.fromString("4a832f42-0101-4a01-8101-000000000001");
        String name = "apigw-passrole-" + fixed.toString().substring(0, 8);
        try (IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String arn = admin.createRole(b -> b.roleName(name).assumeRolePolicyDocument(
                    "{\"Version\":\"2012-10-17\",\"Statement\":[]}")).role().arn();
            try {
                assertEquals(409, assertThrows(IamException.class, () -> new Fixture(name)).statusCode());
                drainFixtureCleanup();
                assertEquals(arn, admin.getRole(b -> b.roleName(name)).role().arn());
            } finally {
                try {
                    admin.deleteRole(b -> b.roleName(name));
                } catch (IamException expected) {
                    // The old-source control may have already deleted this test-owned role.
                    assertEquals(404, expected.statusCode());
                }
            }
        }
    }

    @Test
    void successfulFixtureCreationStillDeletesItsOwnedRole() {
        String name;
        try (Fixture fixture = new Fixture()) {
            name = fixture.name;
        }
        drainFixtureCleanup();
        try (IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            assertEquals(404, assertThrows(IamException.class,
                    () -> admin.getRole(b -> b.roleName(name))).statusCode());
        }
    }

    private void drainFixtureCleanup() {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getDisplayName()).thenReturn("PassRole fixture ownership regression");
        cleanup.afterEach(context);
    }

    private final class Fixture implements AutoCloseable {
        private final String name;
        private final IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        private final ApiGatewayClient rest;
        private final ApiGatewayV2Client v2;
        private final String role;

        private Fixture() {
            this("apigw-passrole-" + UUID.randomUUID().toString().substring(0, 8));
        }

        private Fixture(String name) {
            this.name = name;
            role = iam.createRole(b -> b.roleName(name).path("/teams/api/")
                    .assumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                            + "\"Principal\":{\"Service\":\"apigateway.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}"))
                    .role().arn();
            cleanup.register(() -> {
                try (IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                        .build()) {
                    admin.deleteRole(b -> b.roleName(name));
                }
            });
            iam.createUser(b -> b.userName(name));
            cleanup.register(() -> {
                try (IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                        .build()) {
                    admin.deleteUserPolicy(b -> b.userName(name).policyName("access"));
                    for (String keyId : admin.listAccessKeys(b -> b.userName(name)).accessKeyMetadata().stream()
                            .map(key -> key.accessKeyId()).toList()) {
                        admin.deleteAccessKey(b -> b.userName(name).accessKeyId(keyId));
                    }
                    admin.deleteUser(b -> b.userName(name));
                }
            });
            AccessKey key = iam.createAccessKey(b -> b.userName(name)).accessKey();
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()));
            rest = ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(credentials).build();
            v2 = ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(credentials).build();
            policy(null);
        }

        private void policy(String service) {
            String statement = "{\"Effect\":\"Allow\",\"Action\":\"apigateway:*\",\"Resource\":\"*\"}";
            if (service != null) {
                statement += ",{\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\",\"Resource\":\"" + role
                        + "\",\"Condition\":{\"StringEquals\":{\"iam:PassedToService\":\"" + service + "\"}}}";
            }
            String document = "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + "]}";
            iam.putUserPolicy(b -> b.userName(name).policyName("access").policyDocument(document));
        }

        private String uri() {
            return "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                    + "arn:aws:lambda:us-east-1:000000000000:function:management-only/invocations";
        }

        private ApiGatewayClient adminRest() {
            return ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .build();
        }

        private ApiGatewayV2Client adminV2() {
            return ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .build();
        }

        @Override
        public void close() {
            rest.close();
            v2.close();
            iam.close();
        }
    }
}

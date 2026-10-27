package io.github.hectorvent.floci.services.iam;

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

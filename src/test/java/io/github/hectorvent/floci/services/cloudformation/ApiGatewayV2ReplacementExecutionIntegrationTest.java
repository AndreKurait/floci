package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.NotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ApiGatewayV2ReplacementExecutionIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Inject
    CloudFormationService service;

    @ParameterizedTest
    @ValueSource(strings = {"Route", "Stage", "Deployment", "Integration", "Authorizer"})
    void changedApiIdReplacesChildInNewApi(String kind) {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedApi first = new OwnedApi(api); OwnedApi second = new OwnedApi(api);
                OwnedStack stack = new OwnedStack(cfn)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template(kind, first.id, "initial")));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            String original = physical(cfn, stack.name);
            cfn.updateStack(b -> b.stackName(stack.name).templateBody(template(kind, second.id, "updated")));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack.name);
            assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
            String current = physical(cfn, stack.name);
            switch (kind) {
                case "Route" -> {
                    assertNotEquals(original, current);
                    assertEquals(current, api.getRoute(b -> b.apiId(second.id).routeId(current)).routeId());
                    assertThrows(NotFoundException.class,
                            () -> api.getRoute(b -> b.apiId(first.id).routeId(original)));
                }
                case "Stage" -> {
                    assertEquals("prod", current);
                    assertEquals("prod", api.getStage(b -> b.apiId(second.id).stageName(current)).stageName());
                    assertThrows(NotFoundException.class,
                            () -> api.getStage(b -> b.apiId(first.id).stageName(original)));
                }
                case "Deployment" -> {
                    assertNotEquals(original, current);
                    assertEquals(current, api.getDeployment(b -> b.apiId(second.id).deploymentId(current)).deploymentId());
                    assertThrows(NotFoundException.class,
                            () -> api.getDeployment(b -> b.apiId(first.id).deploymentId(original)));
                }
                case "Integration" -> {
                    assertNotEquals(original, current);
                    assertEquals(current, api.getIntegration(b -> b.apiId(second.id).integrationId(current)).integrationId());
                    assertThrows(NotFoundException.class,
                            () -> api.getIntegration(b -> b.apiId(first.id).integrationId(original)));
                }
                case "Authorizer" -> {
                    assertNotEquals(original, current);
                    assertEquals(current, api.getAuthorizer(b -> b.apiId(second.id).authorizerId(current)).authorizerId());
                    assertThrows(NotFoundException.class,
                            () -> api.getAuthorizer(b -> b.apiId(first.id).authorizerId(original)));
                }
                default -> throw new AssertionError(kind);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Route", "Stage", "Deployment", "Integration", "Authorizer"})
    void laterFailureRestoresOriginalApiChild(String kind) {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedApi first = new OwnedApi(api); OwnedApi second = new OwnedApi(api);
                OwnedStack stack = new OwnedStack(cfn)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template(kind, first.id, "initial")));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            String original = physical(cfn, stack.name);
            String next = template(kind, second.id, "updated");
            String broken = next.substring(0, next.length() - 2)
                    + ",\"Broken\":{\"Type\":\"AWS::ApiGatewayV2::VpcLink\",\"DependsOn\":\"Child\","
                    + "\"Properties\":{\"Name\":\"missing-subnets\"}}}}";
            cfn.updateStack(b -> b.stackName(stack.name).templateBody(broken));
            CfnStackWaits.awaitStatus(stack.name, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(original, physical(cfn, stack.name));
            assertEquals(1, childCount(api, kind, first.id));
            assertEquals(0, childCount(api, kind, second.id));
            cfn.updateStack(b -> b.stackName(stack.name).templateBody(next));
            CfnStackWaits.awaitStatus(stack.name, "UPDATE_COMPLETE");
            assertEquals(0, childCount(api, kind, first.id));
            assertEquals(1, childCount(api, kind, second.id));
        }
    }

    @Test
    void retainedStageUsesBothApiIdentitiesWithoutChangingRef() {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedApi first = new OwnedApi(api); OwnedApi second = new OwnedApi(api);
                OwnedStack stack = new OwnedStack(cfn)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template("Stage", first.id, "initial")));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            String retained = template("Stage", second.id, "updated")
                    .replace("\"Properties\":", "\"UpdateReplacePolicy\":\"Retain\",\"Properties\":");
            cfn.updateStack(b -> b.stackName(stack.name).templateBody(retained));
            CfnStackWaits.awaitStatus(stack.name, "UPDATE_COMPLETE");
            assertEquals("prod", physical(cfn, stack.name));
            assertEquals(1, childCount(api, "Stage", first.id));
            assertEquals(1, childCount(api, "Stage", second.id));
        }
    }

    @Test
    void changedStageNameReplacesStageWithinApi() {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedApi owner = new OwnedApi(api); OwnedStack stack = new OwnedStack(cfn)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template("Stage", owner.id, "initial")));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            cfn.updateStack(b -> b.stackName(stack.name)
                    .templateBody(template("Stage", owner.id, "updated").replace("prod", "next")));
            CfnStackWaits.awaitStatus(stack.name, "UPDATE_COMPLETE");
            assertEquals("next", physical(cfn, stack.name));
            assertThrows(NotFoundException.class, () -> api.getStage(b -> b.apiId(owner.id).stageName("prod")));
            assertEquals("next", api.getStage(b -> b.apiId(owner.id).stageName("next")).stageName());
        }
    }

    private int childCount(ApiGatewayV2Client api, String kind, String id) {
        return switch (kind) {
            case "Route" -> api.getRoutes(b -> b.apiId(id)).items().size();
            case "Stage" -> api.getStages(b -> b.apiId(id)).items().size();
            case "Deployment" -> api.getDeployments(b -> b.apiId(id)).items().size();
            case "Integration" -> api.getIntegrations(b -> b.apiId(id)).items().size();
            case "Authorizer" -> api.getAuthorizers(b -> b.apiId(id)).items().size();
            default -> throw new AssertionError(kind);
        };
    }

    @Test
    void sameApiDeploymentKeepsPhysicalIdentity() {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedApi owner = new OwnedApi(api); OwnedStack stack = new OwnedStack(cfn)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template("Deployment", owner.id, "initial")));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            String original = physical(cfn, stack.name);
            cfn.updateStack(b -> b.stackName(stack.name).templateBody(template("Deployment", owner.id, "updated")));
            CfnStackWaits.awaitStatus(stack.name, "UPDATE_COMPLETE");
            assertEquals(original, physical(cfn, stack.name));
            assertEquals(original, api.getDeployment(b -> b.apiId(owner.id).deploymentId(original)).deploymentId());
        }
    }

    private String physical(CloudFormationClient cfn, String name) {
        return cfn.describeStackResource(b -> b.stackName(name).logicalResourceId("Child"))
                .stackResourceDetail().physicalResourceId();
    }

    private String template(String kind, String apiId, String description) {
        String properties = switch (kind) {
            case "Route" -> "\"RouteKey\":\"GET /items\",";
            case "Stage" -> "\"StageName\":\"prod\",";
            case "Deployment" -> "\"Description\":\"" + description + "\",";
            case "Integration" -> "\"IntegrationType\":\"HTTP_PROXY\",\"IntegrationUri\":\"http://example.invalid\",";
            case "Authorizer" -> "\"Name\":\"auth\",\"AuthorizerType\":\"JWT\","
                    + "\"IdentitySource\":[\"$request.header.Authorization\"],"
                    + "\"JwtConfiguration\":{\"Issuer\":\"https://example.invalid\",\"Audience\":[\"test\"]},";
            default -> throw new AssertionError(kind);
        };
        return "{\"Description\":\"" + description + "\",\"Resources\":{\"Child\":{\"Type\":\"AWS::ApiGatewayV2::"
                + kind + "\",\"Properties\":{" + properties + "\"ApiId\":\"" + apiId + "\"}}}}";
    }

    private class OwnedStack implements AutoCloseable {
        private final String name = "v2-replacement-" + UUID.randomUUID();
        private final CloudFormationClient cfn;

        private OwnedStack(CloudFormationClient cfn) {
            this.cfn = cfn;
        }

        @Override
        public void close() {
            try {
                service.describeStacks(name, "us-east-1");
            } catch (AwsException expected) {
                if ("ValidationError".equals(expected.getErrorCode())
                        && ("Stack with id " + name + " does not exist").equals(expected.getMessage())) {
                    return;
                }
                throw expected;
            }
            CfnStackWaits.awaitTerminal(name);
            cfn.deleteStack(b -> b.stackName(name));
            CfnStackWaits.awaitStackDeleted(name);
        }
    }

    private static class OwnedApi implements AutoCloseable {
        private final ApiGatewayV2Client client;
        private final String id;

        private OwnedApi(ApiGatewayV2Client client) {
            this.client = client;
            id = client.createApi(b -> b.name("v2-replacement-" + UUID.randomUUID()).protocolType("HTTP")).apiId();
        }

        @Override
        public void close() {
            client.deleteApi(b -> b.apiId(id));
            assertThrows(NotFoundException.class, () -> client.getApi(b -> b.apiId(id)));
        }
    }

    private ApiGatewayV2Client api() {
        return ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }

    private CloudFormationClient cfn() {
        return CloudFormationClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }
}

package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.NotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

@QuarkusTest
class ApiGatewayV2CfnChildDeletionIntegrationTest {
    @TestHTTPResource
    URI endpoint;
    @InjectSpy
    ApiGatewayV2Service service;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionAndTemplateRemovalDeleteOnlyOwnedChildren(boolean removeInUpdate) {
        exercise(removeInUpdate, false);
    }

    @Test
    void authorizerBackendFailureReportsDeleteFailedAndRetryDeletesIt() {
        exercise(false, true);
    }

    @Test
    void cleanupPreservesFailureAfterSuccessfulStackCreation() {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        String name = "child-sdk-fault-" + UUID.randomUUID();
        AssertionError original = new AssertionError("failure after successful stack creation");
        try (ApiGatewayV2Client api = ApiGatewayV2Client.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
             CloudFormationClient cfn = AwsTestClients.create(
                     CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            String apiId = api.createApi(b -> b.name(name).protocolType("HTTP")).apiId();
            AssertionError observed = assertThrows(AssertionError.class, () -> {
                try (ChildCleanup cleanup = new ChildCleanup(api, cfn, name, apiId)) {
                    cleanup.stackAttempted = true;
                    String stackId = cfn.createStack(b -> b.stackName(name).templateBody(template(apiId))).stackId();
                    CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");
                    assertEquals(stackId, cfn.describeStacks(b -> b.stackName(name)).stacks().getFirst().stackId());
                    assertEquals(apiId, api.getApi(b -> b.apiId(apiId)).apiId());
                    assertEquals(1, api.getAuthorizers(b -> b.apiId(apiId)).items().size());
                    throw original;
                }
            });
            assertSame(original, observed);
            assertEquals(0, observed.getSuppressed().length);
            CloudFormationException absent = assertThrows(CloudFormationException.class,
                    () -> cfn.describeStacks(b -> b.stackName(name)));
            assertEquals("ValidationError", absent.awsErrorDetails().errorCode());
            assertEquals("Stack with id " + name + " does not exist", absent.awsErrorDetails().errorMessage());
            assertThrows(NotFoundException.class, () -> api.getApi(b -> b.apiId(apiId)));
        }
    }

    private void exercise(boolean removeInUpdate, boolean failDelete) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (ApiGatewayV2Client api = ApiGatewayV2Client.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
             CloudFormationClient cfn = AwsTestClients.create(
                     CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            exercise(removeInUpdate, failDelete, api, cfn, "child-delete-" + UUID.randomUUID());
        }
    }

    void exercise(boolean removeInUpdate, boolean failDelete, ApiGatewayV2Client api,
                  CloudFormationClient cfn, String name) {
        String apiId = api.createApi(b -> b.name(name).protocolType("HTTP")).apiId();
        try (ChildCleanup cleanup = new ChildCleanup(api, cfn, name, apiId)) {
            String independentRoute = api.createRoute(b -> b.apiId(apiId).routeKey("GET /independent")).routeId();
            api.createStage(b -> b.apiId(apiId).stageName("independent").autoDeploy(true));
            cleanup.stackAttempted = true;
            cfn.createStack(b -> b.stackName(name).templateBody(template(apiId)));
            CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");
            Map<String, String> ids = cfn.describeStackResources(b -> b.stackName(name)).stackResources().stream()
                    .collect(Collectors.toMap(r -> r.logicalResourceId(), r -> r.physicalResourceId()));
            cleanup.authorizer = ids.get("Authorizer");
            String authorizer = cleanup.authorizer;
            assertEquals(2, api.getRoutes(b -> b.apiId(apiId)).items().size());
            assertEquals(1, api.getIntegrations(b -> b.apiId(apiId)).items().size());
            assertEquals(1, api.getAuthorizers(b -> b.apiId(apiId)).items().size());
            assertEquals(1, api.getDeployments(b -> b.apiId(apiId)).items().size());
            assertEquals(2, api.getStages(b -> b.apiId(apiId)).items().size());
            if (removeInUpdate) {
                cfn.updateStack(b -> b.stackName(name).templateBody("{\"Resources\":{\"Anchor\":{\"Type\":\"AWS::SSM::Parameter\","
                        + "\"Properties\":{\"Name\":\"/" + name + "\",\"Type\":\"String\",\"Value\":\"retained\"}}}}"));
                CfnStackWaits.awaitStatus(name, "UPDATE_COMPLETE");
            } else {
                if (failDelete) {
                    doThrow(new AwsException("InternalFailure", "owned authorizer delete fault", 500)).when(service)
                            .deleteAuthorizer(eq("us-east-1"), eq(apiId), eq(authorizer));
                }
                cfn.deleteStack(b -> b.stackName(name));
                if (failDelete) {
                    CfnStackWaits.awaitStatus(name, "DELETE_FAILED");
                    assertEquals(authorizer, api.getAuthorizer(b -> b.apiId(apiId)
                            .authorizerId(ids.get("Authorizer"))).authorizerId());
                    restoreAuthorizer(apiId, authorizer);
                    cfn.deleteStack(b -> b.stackName(name));
                }
                CfnStackWaits.awaitStackDeleted(name);
                cleanup.stackDeleted = true;
            }
            assertAll(
                    () -> assertThrows(NotFoundException.class, () -> api.getRoute(b -> b.apiId(apiId).routeId(ids.get("Route")))),
                    () -> assertThrows(NotFoundException.class, () -> api.getIntegration(b -> b.apiId(apiId).integrationId(ids.get("Integration")))),
                    () -> assertThrows(NotFoundException.class, () -> api.getAuthorizer(b -> b.apiId(apiId).authorizerId(ids.get("Authorizer")))),
                    () -> assertThrows(NotFoundException.class, () -> api.getStage(b -> b.apiId(apiId).stageName(ids.get("Stage")))),
                    () -> assertThrows(NotFoundException.class, () -> api.getDeployment(b -> b.apiId(apiId).deploymentId(ids.get("Deployment")))));
            assertEquals(apiId, api.getApi(b -> b.apiId(apiId)).apiId());
            assertEquals(independentRoute, api.getRoute(b -> b.apiId(apiId).routeId(independentRoute)).routeId());
            assertEquals("independent", api.getStage(b -> b.apiId(apiId).stageName("independent")).stageName());
        }
    }

    void restoreAuthorizer(String apiId, String authorizer) {
        doCallRealMethod().when(service).deleteAuthorizer(eq("us-east-1"), eq(apiId), eq(authorizer));
    }

    private interface CleanupStep extends AutoCloseable {
        @Override
        void close();
    }

    private class ChildCleanup implements AutoCloseable {
        private final ApiGatewayV2Client api;
        private final CloudFormationClient cfn;
        private final String name;
        private final String apiId;
        private boolean stackAttempted;
        private boolean stackDeleted;
        private String authorizer;

        private ChildCleanup(ApiGatewayV2Client api, CloudFormationClient cfn, String name, String apiId) {
            this.api = api;
            this.cfn = cfn;
            this.name = name;
            this.apiId = apiId;
        }

        @Override
        public void close() {
            try (CleanupStep apiCleanup = () -> {
                api.deleteApi(b -> b.apiId(apiId));
                assertThrows(NotFoundException.class, () -> api.getApi(b -> b.apiId(apiId)));
            }; CleanupStep stackCleanup = this::deleteStack) {
                if (authorizer != null) {
                    restoreAuthorizer(apiId, authorizer);
                }
            }
        }

        private void deleteStack() {
            if (!stackAttempted || stackDeleted) {
                return;
            }
            try {
                cfn.describeStacks(b -> b.stackName(name));
            } catch (CloudFormationException expected) {
                if (expected.awsErrorDetails() != null
                        && "ValidationError".equals(expected.awsErrorDetails().errorCode())
                        && ("Stack with id " + name + " does not exist").equals(expected.awsErrorDetails().errorMessage())) {
                    // The create request can fail before the stack is stored.
                    return;
                }
                throw expected;
            }
            CfnStackWaits.awaitTerminal(name);
            cfn.deleteStack(b -> b.stackName(name));
            CfnStackWaits.awaitStackDeleted(name);
        }
    }

    private String template(String apiId) {
        return """
                {"Resources":{
                  "Integration":{"Type":"AWS::ApiGatewayV2::Integration","Properties":{
                    "ApiId":"%1$s","IntegrationType":"HTTP_PROXY","IntegrationMethod":"GET",
                    "IntegrationUri":"https://example.com/backend","PayloadFormatVersion":"1.0"}},
                  "Authorizer":{"Type":"AWS::ApiGatewayV2::Authorizer","Properties":{
                    "ApiId":"%1$s","Name":"owned","AuthorizerType":"REQUEST",
                    "IdentitySource":["$request.header.Authorization"],"AuthorizerPayloadFormatVersion":"2.0",
                    "AuthorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:auth"}},
                  "Route":{"Type":"AWS::ApiGatewayV2::Route","Properties":{
                    "ApiId":"%1$s","RouteKey":"GET /owned","AuthorizationType":"CUSTOM",
                    "AuthorizerId":{"Ref":"Authorizer"},"Target":{"Fn::Join":["",["integrations/",{"Ref":"Integration"}]]}}},
                  "Deployment":{"Type":"AWS::ApiGatewayV2::Deployment","DependsOn":"Route","Properties":{"ApiId":"%1$s"}},
                  "Stage":{"Type":"AWS::ApiGatewayV2::Stage","Properties":{
                    "ApiId":"%1$s","StageName":"owned","DeploymentId":{"Ref":"Deployment"}}}}}
                """.formatted(apiId);
    }
}

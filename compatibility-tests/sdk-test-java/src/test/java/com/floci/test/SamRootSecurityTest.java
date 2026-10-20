package com.floci.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.ResourceStatus;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackResourceDetail;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamRootSecurityTest {

    @Test
    void rejectedRestApiImportIsRemovedAfterStackDeletionWithoutDeletingAnUnownedApi() throws InterruptedException {
        String name = TestFixtures.uniqueName("cfn-restapi-rejected-import");
        String authorizerUri = TestFixtures.arn("apigateway", "lambda", "path/2015-03-31/functions/"
                + TestFixtures.arn("lambda", "000000000000", "function:dummy") + "/invocations");
        String template = """
                {"Resources":{"Api":{"Type":"AWS::ApiGateway::RestApi","Properties":{
                  "Name":"%s","Body":{"openapi":"3.0.1","info":{"title":"rejected import","version":"1"},
                    "components":{"securitySchemes":{
                      "iam":{"type":"apiKey","name":"Authorization","in":"header",
                        "x-amazon-apigateway-authtype":"awsSigv4"},
                      "custom":{"type":"apiKey","name":"Authorization","in":"header",
                        "x-amazon-apigateway-authorizer":{"type":"request",
                          "identitySource":"method.request.header.Authorization","authorizerUri":"%s"}}}},
                    "paths":{"/items":{"get":{"security":[{"iam":[]},{"custom":[]}],
                      "responses":{"200":{"description":"ok"}}}}}
                  }}}}}
                """.formatted(name, authorizerUri);
        try (CloudFormationClient cloudFormation = TestFixtures.cloudFormationClient();
                ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient()) {
            String foreignId = apiGateway.createRestApi(request -> request.name(name)
                    .description("outside the stack")).id();
            String failedApiId = null;
            boolean created = false;
            boolean deleted = false;
            try {
                cloudFormation.createStack(request -> request.stackName(name).templateBody(template));
                created = true;
                assertThat(TestFixtures.awaitStackTerminal(cloudFormation, name, Duration.ofMinutes(2)))
                        .isEqualTo("ROLLBACK_COMPLETE");
                StackResourceDetail failed = cloudFormation.describeStackResource(
                        request -> request.stackName(name).logicalResourceId("Api")).stackResourceDetail();
                assertThat(failed.resourceStatus()).isEqualTo(ResourceStatus.CREATE_FAILED);
                assertThat(failed.resourceStatusReason())
                        .contains("Only one Authorizer or IAM authorization");
                failedApiId = failed.physicalResourceId();
                assertThat(failedApiId).isNotBlank().isNotEqualTo(foreignId);

                cloudFormation.deleteStack(request -> request.stackName(name));
                cloudFormation.waiter().waitUntilStackDeleteComplete(
                        DescribeStacksRequest.builder().stackName(name).build(),
                        TestFixtures.waiterOverride(Duration.ofMinutes(2)));
                assertThatThrownBy(() -> cloudFormation.describeStacks(request -> request.stackName(name)))
                        .isInstanceOfSatisfying(CloudFormationException.class,
                                error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError"));
                deleted = true;
                assertThat(apiGateway.getRestApi(request -> request.restApiId(foreignId)).description())
                        .isEqualTo("outside the stack");
                String apiId = failedApiId;
                assertThatThrownBy(() -> apiGateway.getRestApi(request -> request.restApiId(apiId)))
                        .isInstanceOf(NotFoundException.class);
            } finally {
                try {
                    if (created && !deleted) {
                        cloudFormation.deleteStack(request -> request.stackName(name));
                        cloudFormation.waiter().waitUntilStackDeleteComplete(
                                DescribeStacksRequest.builder().stackName(name).build(),
                                TestFixtures.waiterOverride(Duration.ofMinutes(2)));
                    }
                } finally {
                    try {
                        if (failedApiId != null) {
                            String apiId = failedApiId;
                            try {
                                apiGateway.deleteRestApi(request -> request.restApiId(apiId));
                            } catch (NotFoundException ignored) {
                                // Rollback or stack deletion already removed the stack-owned API.
                            }
                        }
                    } finally {
                        apiGateway.deleteRestApi(request -> request.restApiId(foreignId));
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void samIamDefaultDoesNotDuplicateRootSecurity(boolean swagger) {
        String name = TestFixtures.uniqueName("sam-root-security");
        String version = swagger ? "\"swagger\":\"2.0\"" : "\"openapi\":\"3.0.2\"";
        String schemes = swagger ? "\"securityDefinitions\"" : "\"components\":{\"securitySchemes\"";
        String template = """
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{
                  "Api":{"Type":"AWS::Serverless::Api","Properties":{
                    "StageName":"test","Auth":{"DefaultAuthorizer":"AWS_IAM"},
                    "DefinitionBody":{
                      %s,"info":{"title":"SAM root security","version":"1"},
                      %s:{"sigv4":{"type":"apiKey","name":"Authorization","in":"header",
                        "x-amazon-apigateway-authtype":"awsSigv4"}}%s,
                      "security":[{"sigv4":[]}],
                      "paths":{"/items":{"get":{
                        "responses":{"200":{"description":"ok"}},
                        "x-amazon-apigateway-integration":{"type":"mock",
                          "requestTemplates":{"application/json":"{\\"statusCode\\":200}"},
                          "responses":{"default":{"statusCode":"200"}}}
                      }}}
                    }
                  }}
                },"Outputs":{"ApiId":{"Value":{"Ref":"Api"}}}}
                """.formatted(version, schemes, swagger ? "" : "}");
        try (CloudFormationClient cloudFormation = TestFixtures.cloudFormationClient();
                ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient()) {
            boolean created = false;
            String createdApiId = null;
            try {
                cloudFormation.createStack(request -> request.stackName(name).templateBody(template)
                        .capabilities(Capability.CAPABILITY_AUTO_EXPAND));
                created = true;
                Stack stack = cloudFormation.waiter().waitUntilStackCreateComplete(
                                DescribeStacksRequest.builder().stackName(name).build(),
                                TestFixtures.waiterOverride(Duration.ofMinutes(2)))
                        .matched().response().orElseThrow().stacks().get(0);
                assertThat(stack.stackStatus()).isEqualTo(StackStatus.CREATE_COMPLETE);
                String apiId = stack.outputs().get(0).outputValue();
                createdApiId = apiId;
                String resourceId = apiGateway.getResources(request -> request.restApiId(apiId)).items().stream()
                        .filter(resource -> "/items".equals(resource.path()))
                        .findFirst().orElseThrow().id();
                assertThat(apiGateway.getMethod(request -> request.restApiId(apiId)
                        .resourceId(resourceId).httpMethod("GET")).authorizationType()).isEqualTo("AWS_IAM");
            } finally {
                if (created) {
                    cloudFormation.deleteStack(request -> request.stackName(name));
                    cloudFormation.waiter().waitUntilStackDeleteComplete(
                            DescribeStacksRequest.builder().stackName(name).build(),
                            TestFixtures.waiterOverride(Duration.ofMinutes(2)));
                    assertThatThrownBy(() -> cloudFormation.describeStacks(request -> request.stackName(name)))
                            .isInstanceOfSatisfying(CloudFormationException.class,
                                    error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError"));
                    String apiId = createdApiId;
                    if (apiId != null) {
                        assertThatThrownBy(() -> apiGateway.getRestApi(request -> request.restApiId(apiId)))
                                .isInstanceOf(NotFoundException.class);
                    }
                }
            }
        }
    }
}

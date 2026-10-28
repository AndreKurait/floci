package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.ResourceStatus;
import software.amazon.awssdk.services.cloudformation.model.StackResourceDetail;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ApiGatewayRestApiImportRollbackTest {

    @Test
    void localRestApiImportFailureDoesNotDeleteAnUnownedApi() throws InterruptedException {
        assumeFalse(TestFixtures.isRealAws(),
                "Uses a local importer failure; AWS accepts this security definition");
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

}

package com.floci.test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamRootSecurityTest {

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

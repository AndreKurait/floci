package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.NotFoundException;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ApiGatewayV2CfnCredentialsIntegrationTest {
    private static final String ROLE = "arn:aws:iam::000000000000:role/gateway";
    private static final String OTHER = "arn:aws:iam::000000000000:role/replacement";

    @TestHTTPResource
    URI endpoint;

    @Inject
    CloudFormationService cloudFormation;

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "WEBSOCKET"})
    void templateCredentialsUpdateAndRemovalKeepResourceIds(String protocol) {
        try (ApiGatewayV2Client api = api()) {
            String name = "v2-credentials-" + UUID.randomUUID();
            String apiId;
            try (StackCleanup cleanup = new StackCleanup(name)) {
                stackAction("CreateStack", name, template(protocol, ROLE, ROLE, false));
                Map<String, String> ids = outputs(waitFor(name, "CREATE_COMPLETE"));
                apiId = ids.get("Api");
                assertEquals(ids.get("Integration"), ids.get("IntegrationAttr"));
                assertEquals(ids.get("Authorizer"), ids.get("AuthorizerAttr"));
                assertCredentials(api, ids, ROLE, ROLE);
                // Ordinary management updates omit credentials and must retain them.
                api.updateIntegration(b -> b.apiId(ids.get("Api")).integrationId(ids.get("Integration"))
                        .description("unchanged credentials"));
                api.updateAuthorizer(b -> b.apiId(ids.get("Api")).authorizerId(ids.get("Authorizer"))
                        .name("renamed"));
                assertCredentials(api, ids, ROLE, ROLE);
                for (String credentials : new String[]{OTHER, "arn:aws:iam::*:user/*", null}) {
                    String authorizerCredentials = credentials == null ? null : OTHER;
                    stackAction("UpdateStack", name, template(protocol, credentials, authorizerCredentials, false));
                    assertEquals(ids, outputs(waitFor(name, "UPDATE_COMPLETE")));
                    assertCredentials(api, ids, credentials, authorizerCredentials);
                }
            }
            assertThrows(NotFoundException.class, () -> api.getApi(b -> b.apiId(apiId)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "WEBSOCKET"})
    void failedLaterResourceRestoresIntegrationCredentials(String protocol) {
        try (ApiGatewayV2Client api = api()) {
            String name = "v2-credentials-rollback-" + UUID.randomUUID();
            try (StackCleanup cleanup = new StackCleanup(name)) {
                stackAction("CreateStack", name, template(protocol, ROLE, ROLE, false));
                Map<String, String> ids = outputs(waitFor(name, "CREATE_COMPLETE"));
                stackAction("UpdateStack", name, template(protocol, null, ROLE, true));
                waitFor(name, "UPDATE_ROLLBACK_COMPLETE");
                assertCredentials(api, ids, ROLE, ROLE);
            }
        }
    }

    @Test
    void cleanupPreservesFailureBeforeCreation() {
        String name = "v2-cleanup-absent-" + UUID.randomUUID();
        AssertionError original = new AssertionError("before creation");
        AssertionError actual = assertThrows(AssertionError.class, () -> {
            try (StackCleanup cleanup = new StackCleanup(name)) {
                throw original;
            }
        });
        assertSame(original, actual);
        assertEquals(0, actual.getSuppressed().length);
    }

    @Test
    void cleanupPreservesFailureAfterCreationAndDeletesResources() {
        String name = "v2-cleanup-created-" + UUID.randomUUID();
        AssertionError original = new AssertionError("after creation");
        String[] apiId = new String[1];
        AssertionError actual = assertThrows(AssertionError.class, () -> {
            try (StackCleanup cleanup = new StackCleanup(name)) {
                stackAction("CreateStack", name, template("HTTP", ROLE, ROLE, false));
                apiId[0] = outputs(waitFor(name, "CREATE_COMPLETE")).get("Api");
                throw original;
            }
        });
        assertSame(original, actual);
        assertEquals(0, actual.getSuppressed().length);
        try (ApiGatewayV2Client api = api()) {
            assertThrows(NotFoundException.class, () -> api.getApi(b -> b.apiId(apiId[0])));
        }
    }

    private class StackCleanup implements AutoCloseable {
        private final String name;

        private StackCleanup(String name) {
            this.name = name;
        }

        @Override
        public void close() {
            try {
                cloudFormation.describeStacks(name, "us-east-1");
            } catch (AwsException expected) {
                if ("ValidationError".equals(expected.getErrorCode())
                        && ("Stack with id " + name + " does not exist").equals(expected.getMessage())) {
                    // Creation may have failed before a stack was stored.
                    return;
                }
                throw expected;
            }
            CfnStackWaits.awaitTerminal(name);
            stackAction("DeleteStack", name, null);
            CfnStackWaits.awaitStackDeleted(name);
        }
    }

    private void assertCredentials(ApiGatewayV2Client api, Map<String, String> ids,
                                   String integration, String authorizer) {
        assertEquals(integration, api.getIntegration(b -> b.apiId(ids.get("Api"))
                .integrationId(ids.get("Integration"))).credentialsArn());
        assertEquals(authorizer, api.getAuthorizer(b -> b.apiId(ids.get("Api"))
                .authorizerId(ids.get("Authorizer"))).authorizerCredentialsArn());
    }

    private String template(String protocol, String credentials, String authorizerCredentials, boolean broken) {
        String integrationProperty = credentials == null ? "" : ",\"CredentialsArn\":\"" + credentials + "\"";
        String authorizerProperty = authorizerCredentials == null ? ""
                : ",\"AuthorizerCredentialsArn\":\"" + authorizerCredentials + "\"";
        String failingResource = broken ? """
                ,"Broken":{"Type":"AWS::ApiGatewayV2::VpcLink","DependsOn":"Integration",
                            "Properties":{"Name":"invalid"}}
                """ : "";
        return """
                {"Resources":{
                  "Api":{"Type":"AWS::ApiGatewayV2::Api","Properties":{
                    "Name":"credentials","ProtocolType":"%s","RouteSelectionExpression":"$request.body.action"}},
                  "Integration":{"Type":"AWS::ApiGatewayV2::Integration","Properties":{
                    "ApiId":{"Ref":"Api"},"IntegrationType":"AWS_PROXY",
                    "IntegrationUri":"arn:aws:lambda:us-east-1:000000000000:function:backend",
                    "PayloadFormatVersion":"2.0"%s}},
                  "Authorizer":{"Type":"AWS::ApiGatewayV2::Authorizer","Properties":{
                    "ApiId":{"Ref":"Api"},"Name":"auth","AuthorizerType":"REQUEST",
                    "AuthorizerUri":"arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:auth/invocations",
                    "AuthorizerPayloadFormatVersion":"2.0","IdentitySource":["$request.header.Authorization"]%s}}%s},
                 "Outputs":{"Api":{"Value":{"Ref":"Api"}},
                   "Integration":{"Value":{"Ref":"Integration"}},
                   "IntegrationAttr":{"Value":{"Fn::GetAtt":["Integration","IntegrationId"]}},
                   "Authorizer":{"Value":{"Ref":"Authorizer"}},
                   "AuthorizerAttr":{"Value":{"Fn::GetAtt":["Authorizer","AuthorizerId"]}}}}
                """.formatted(protocol, integrationProperty, authorizerProperty, failingResource);
    }

    private Stack waitFor(String name, String status) {
        CfnStackWaits.awaitStatus(name, status);
        return cloudFormation.describeStacks(name, "us-east-1").getFirst();
    }

    private Map<String, String> outputs(Stack stack) {
        return stack.getOutputs();
    }

    private void stackAction(String action, String name, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action).formParam("StackName", name);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private ApiGatewayV2Client api() {
        return ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }
}

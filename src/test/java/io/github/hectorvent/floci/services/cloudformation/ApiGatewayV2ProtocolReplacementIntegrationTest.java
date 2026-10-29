package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ApiGatewayV2ProtocolReplacementIntegrationTest {
    private static final Logger LOG = Logger.getLogger(ApiGatewayV2ProtocolReplacementIntegrationTest.class);

    @TestHTTPResource
    URI endpoint;

    @Inject
    CloudFormationService service;

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "WEBSOCKET"})
    void protocolChangeReplacesApiAndItsReferencedStage(String originalProtocol) {
        String replacementProtocol = "HTTP".equals(originalProtocol) ? "WEBSOCKET" : "HTTP";
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedStack stack = new OwnedStack(cfn, api)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template(originalProtocol, "initial")));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            String original = stack.apiId();
            assertEquals(originalProtocol, api.getApi(b -> b.apiId(original)).protocolTypeAsString());
            cfn.updateStack(b -> b.stackName(stack.name).templateBody(template(replacementProtocol, "updated")));
            CfnStackWaits.awaitStatus(stack.name, "UPDATE_COMPLETE");
            String current = stack.apiId();
            assertAll(
                    () -> assertNotEquals(original, current, "ProtocolType is a replacement property"),
                    () -> assertEquals(replacementProtocol,
                            api.getApi(b -> b.apiId(current)).protocolTypeAsString()),
                    () -> assertEquals("prod", api.getStage(b -> b.apiId(current).stageName("prod")).stageName()),
                    () -> assertThrows(NotFoundException.class, () -> api.getApi(b -> b.apiId(original)))
            );
        }
    }

    @Test
    void nameChangeKeepsApiAndReferencedStage() {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedStack stack = new OwnedStack(cfn, api)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template("HTTP", "initial")));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            String original = stack.apiId();
            cfn.updateStack(b -> b.stackName(stack.name).templateBody(template("HTTP", "updated")));
            CfnStackWaits.awaitStatus(stack.name, "UPDATE_COMPLETE");
            assertEquals(original, stack.apiId());
            assertEquals("updated", api.getApi(b -> b.apiId(original)).name());
            assertEquals("prod", api.getStage(b -> b.apiId(original).stageName("prod")).stageName());
        }
    }

    @Test
    void laterResourceFailureRollsBackApiAndReferencedStage() {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedStack stack = new OwnedStack(cfn, api)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template("HTTP", stack.name)));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            String original = stack.apiId();
            String replacementName = stack.name + "-replacement";
            String update = template("WEBSOCKET", replacementName).replace(
                    "\"Resources\":{", """
                    "Resources":{
                      "Broken":{"Type":"AWS::ApiGatewayV2::Stage","DependsOn":"Stage",
                        "Properties":{"ApiId":"missing-protocol-test-api","StageName":"broken"}},
                    """);
            cfn.updateStack(b -> b.stackName(stack.name).templateBody(update));
            CfnStackWaits.awaitStatus(stack.name, "UPDATE_ROLLBACK_COMPLETE");
            Set<String> leaked = new LinkedHashSet<>();
            api.getApis().items().stream().filter(item -> replacementName.equals(item.name())).forEach(item -> {
                stack.apiIds.add(item.apiId());
                leaked.add(item.apiId());
            });
            assertTrue(leaked.isEmpty(), "Rollback must delete the owned replacement API");
            assertEquals(original, stack.apiId());
            assertEquals("HTTP", api.getApi(b -> b.apiId(original)).protocolTypeAsString());
            assertEquals("prod", api.getStage(b -> b.apiId(original).stageName("prod")).stageName());
        }
    }

    @Test
    void retainedOriginalApiSurvivesCommittedReplacement() {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedStack stack = new OwnedStack(cfn, api)) {
            String originalTemplate = template("HTTP", "initial").replace(
                    "\"Api\":{", "\"Api\":{\"UpdateReplacePolicy\":\"Retain\",");
            cfn.createStack(b -> b.stackName(stack.name).templateBody(originalTemplate));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            String original = stack.apiId();
            String update = template("WEBSOCKET", "updated").replace(
                    "\"Api\":{", "\"Api\":{\"UpdateReplacePolicy\":\"Retain\",");
            cfn.updateStack(b -> b.stackName(stack.name).templateBody(update));
            CfnStackWaits.awaitStatus(stack.name, "UPDATE_COMPLETE");
            String current = stack.apiId();
            assertNotEquals(original, current);
            assertEquals("HTTP", api.getApi(b -> b.apiId(original)).protocolTypeAsString());
            assertEquals("WEBSOCKET", api.getApi(b -> b.apiId(current)).protocolTypeAsString());
            assertEquals("prod", api.getStage(b -> b.apiId(current).stageName("prod")).stageName());
        }
    }

    @Test
    void changeSetClassifiesProtocolChangeAsReplacement() {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedStack stack = new OwnedStack(cfn, api)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template("HTTP", "initial")));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            stack.apiId();
            cfn.createChangeSet(b -> b.stackName(stack.name).changeSetName("protocol-change")
                    .changeSetType("UPDATE").templateBody(template("WEBSOCKET", "updated")));
            assertEquals("True", cfn.describeChangeSet(b -> b.stackName(stack.name)
                    .changeSetName("protocol-change")).changes().stream()
                    .filter(change -> "Api".equals(change.resourceChange().logicalResourceId()))
                    .findFirst().orElseThrow().resourceChange().replacementAsString());
        }
    }

    private String template(String protocol, String name) {
        return """
                {"Resources":{
                  "Api":{"Type":"AWS::ApiGatewayV2::Api","Properties":{
                    "Name":"%s","ProtocolType":"%s","RouteSelectionExpression":"$request.body.action"}},
                  "Stage":{"Type":"AWS::ApiGatewayV2::Stage","Properties":{
                    "ApiId":{"Ref":"Api"},"StageName":"prod"}}
                }}
                """.formatted(name, protocol);
    }

    private class OwnedStack implements AutoCloseable {
        private final String name = "v2-protocol-replacement-" + UUID.randomUUID();
        private final CloudFormationClient cfn;
        private final ApiGatewayV2Client api;
        private final Set<String> apiIds = new LinkedHashSet<>();

        private OwnedStack(CloudFormationClient cfn, ApiGatewayV2Client api) {
            this.cfn = cfn;
            this.api = api;
            LOG.infov("Owned protocol diagnostic stack {0}", name);
        }

        private String apiId() {
            String id = cfn.describeStackResource(b -> b.stackName(name).logicalResourceId("Api"))
                    .stackResourceDetail().physicalResourceId();
            apiIds.add(id);
            LOG.infov("Owned protocol diagnostic API {0} in stack {1}", id, name);
            return id;
        }

        @Override
        public void close() {
            try (AutoCloseable apiCleanup = this::deleteApis) {
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
                LOG.infov("Deleted protocol diagnostic stack {0}", name);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Protocol diagnostic cleanup failed", failure);
            }
        }

        private void deleteApis() {
            for (String id : apiIds) {
                try {
                    api.deleteApi(b -> b.apiId(id));
                } catch (NotFoundException expected) {
                    // Stack deletion normally already removed this exact owned API.
                }
                assertThrows(NotFoundException.class, () -> api.getApi(b -> b.apiId(id)));
                LOG.infov("Verified absent protocol diagnostic API {0}", id);
            }
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

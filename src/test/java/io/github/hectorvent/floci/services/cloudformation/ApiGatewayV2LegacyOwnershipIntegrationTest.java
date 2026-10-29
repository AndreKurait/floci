package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.NotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ApiGatewayV2LegacyOwnershipIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Inject
    CloudFormationService service;

    @Inject
    ObjectMapper mapper;

    @TempDir
    Path storageDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"Route", "Stage", "Deployment", "Integration", "Authorizer"})
    void childWithoutPersistedOwnershipDeletesUsingItsStackOwnership(String kind) throws Exception {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedApi owner = new OwnedApi(api); OwnedApi foreign = new OwnedApi(api);
                OwnedStack stack = new OwnedStack(cfn)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template(kind, owner.id, "initial")));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            Stack persisted = service.describeStacks(stack.name, "us-east-1").getFirst();
            StackResource original = persisted.getResources().get("Child");
            StackResource legacy = mapper.readValue(mapper.writeValueAsBytes(original), StackResource.class);
            legacy.getAttributes().remove("ApiId");
            persisted.getResources().put("Child", legacy);
            service.updateTerminationProtection(stack.name, false, "us-east-1");
            service.loadPersistedState();
            assertFalse(service.describeStacks(stack.name, "us-east-1").getFirst()
                    .getResources().get("Child").getAttributes().containsKey("ApiId"));
            api.createStage(b -> b.apiId(foreign.id).stageName("prod"));
            try {
                cfn.deleteStack(b -> b.stackName(stack.name));
                CfnStackWaits.awaitStackDeleted(stack.name);
                assertEquals(0, childCount(api, kind, owner.id));
                assertEquals("prod", api.getStage(b -> b.apiId(foreign.id).stageName("prod")).stageName());
            } finally {
                // Restore only this fixture's known owner so cleanup can retry after the negative control.
                restoreFixtureOwner(stack.name, owner.id);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Route", "Stage", "Deployment", "Integration", "Authorizer"})
    void currentChildDeletesWithoutTouchingOtherApi(String kind) {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedApi owner = new OwnedApi(api); OwnedApi foreign = new OwnedApi(api);
                OwnedStack stack = new OwnedStack(cfn)) {
            cfn.createStack(b -> b.stackName(stack.name).templateBody(template(kind, owner.id, "initial")));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            api.createStage(b -> b.apiId(foreign.id).stageName("prod"));
            cfn.deleteStack(b -> b.stackName(stack.name));
            CfnStackWaits.awaitStackDeleted(stack.name);
            assertEquals(0, childCount(api, kind, owner.id));
            assertEquals("prod", api.getStage(b -> b.apiId(foreign.id).stageName("prod")).stageName());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Route", "Stage", "Deployment", "Integration", "Authorizer"})
    void priorParameterOwnerSurvivesReplacementRollbackAndReload(String kind) throws Exception {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedApi first = new OwnedApi(api); OwnedApi second = new OwnedApi(api);
                OwnedStack stack = new OwnedStack(cfn)) {
            String initial = parameterTemplate(kind, "initial");
            cfn.createStack(b -> b.stackName(stack.name).templateBody(initial)
                    .parameters(p -> p.parameterKey("Owner").parameterValue(first.id)));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            makeLegacy(stack.name);
            String next = parameterTemplate(kind, "updated");
            String broken = next.substring(0, next.length() - 2)
                    + ",\"Broken\":{\"Type\":\"AWS::ApiGatewayV2::VpcLink\",\"DependsOn\":\"Child\","
                    + "\"Properties\":{\"Name\":\"missing-subnets\"}}}}";
            try {
                cfn.updateStack(b -> b.stackName(stack.name).templateBody(broken)
                        .parameters(p -> p.parameterKey("Owner").parameterValue(second.id)));
                CfnStackWaits.awaitStatus(stack.name, "UPDATE_ROLLBACK_COMPLETE");
                service.loadPersistedState();
                assertEquals(first.id, diskRoundTrip(stored(stack.name)).getResources()
                        .get("Child").getAttributes().get("ApiId"));
                assertEquals(1, childCount(api, kind, first.id));
                assertEquals(0, childCount(api, kind, second.id));
                cfn.updateStack(b -> b.stackName(stack.name).templateBody(next)
                        .parameters(p -> p.parameterKey("Owner").parameterValue(second.id)));
                CfnStackWaits.awaitStatus(stack.name, "UPDATE_COMPLETE");
                service.loadPersistedState();
                assertEquals(second.id, diskRoundTrip(stored(stack.name)).getResources()
                        .get("Child").getAttributes().get("ApiId"));
                assertEquals(0, childCount(api, kind, first.id));
                assertEquals(1, childCount(api, kind, second.id));
            } finally {
                Stack current = stored(stack.name);
                if (!current.getResources().get("Child").getAttributes().containsKey("ApiId")) {
                    restoreFixtureOwner(stack.name, first.id);
                }
            }
        }
    }

    @Test
    void priorResourceRefUsesPreviousTemplateDuringApiMove() throws Exception {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedStack stack = new OwnedStack(cfn)) {
            String initial = template("Stage", "owner-placeholder", "initial")
                    .replace("\"owner-placeholder\"", "{\"Ref\":\"Owner\"}")
                    .replace("\"Resources\":{", "\"Resources\":{\"Owner\":{\"Type\":\"AWS::ApiGatewayV2::Api\","
                            + "\"Properties\":{\"Name\":\"legacy-parent\",\"ProtocolType\":\"HTTP\"}},"
                            + "\"NextOwner\":{\"Type\":\"AWS::ApiGatewayV2::Api\","
                            + "\"Properties\":{\"Name\":\"next-parent\",\"ProtocolType\":\"HTTP\"}},");
            cfn.createStack(b -> b.stackName(stack.name).templateBody(initial));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            String owner = stored(stack.name).getResources().get("Owner").getPhysicalId();
            String replacement = stored(stack.name).getResources().get("NextOwner").getPhysicalId();
            makeLegacy(stack.name);
            try {
                cfn.updateStack(b -> b.stackName(stack.name)
                        .templateBody(initial.replace("{\"Ref\":\"Owner\"}", "{\"Ref\":\"NextOwner\"}")));
                CfnStackWaits.awaitStatus(stack.name, "UPDATE_COMPLETE");
                assertEquals(replacement, stored(stack.name).getResources().get("Child").getAttributes().get("ApiId"));
                assertEquals("prod", api.getStage(b -> b.apiId(replacement).stageName("prod")).stageName());
                assertThrows(NotFoundException.class, () -> api.getStage(b -> b.apiId(owner).stageName("prod")));
            } finally {
                if (!stored(stack.name).getResources().get("Child").getAttributes().containsKey("ApiId")) {
                    restoreFixtureOwner(stack.name, owner);
                }
            }
        }
    }

    @Test
    void unresolvedPriorOwnerRefusesDeletionWithoutGuessingOrMutatingChildren() throws Exception {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedApi owner = new OwnedApi(api); OwnedStack stack = new OwnedStack(cfn)) {
            String initial = template("Stage", owner.id, "initial");
            cfn.createStack(b -> b.stackName(stack.name).templateBody(initial));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            makeLegacy(stack.name);
            Stack persisted = stored(stack.name);
            persisted.setTemplateBody(initial.replace("\"" + owner.id + "\"", "{\"Ref\":\"UnknownOwner\"}"));
            service.updateTerminationProtection(stack.name, false, "us-east-1");
            try {
                cfn.deleteStack(b -> b.stackName(stack.name));
                CfnStackWaits.awaitStatus(stack.name, "DELETE_FAILED");
                assertEquals("prod", api.getStage(b -> b.apiId(owner.id).stageName("prod")).stageName());
                assertFalse(stored(stack.name).getResources().get("Child").getAttributes().containsKey("ApiId"));
                assertTrue(stored(stack.name).getStatusReason().contains("UnknownOwner"));
            } finally {
                stored(stack.name).setTemplateBody(initial);
                restoreFixtureOwner(stack.name, owner.id);
            }
        }
    }

    @Test
    void rejectedRecoveryLeavesAllLegacyMetadataAndChildrenIntact() throws Exception {
        try (ApiGatewayV2Client api = api(); CloudFormationClient cfn = cfn();
                OwnedApi owner = new OwnedApi(api); OwnedStack stack = new OwnedStack(cfn)) {
            ObjectNode template = (ObjectNode) mapper.readTree(template("Stage", owner.id, "initial"));
            ObjectNode resources = (ObjectNode) template.path("Resources");
            ObjectNode other = resources.path("Child").deepCopy();
            ((ObjectNode) other.path("Properties")).put("StageName", "other");
            resources.set("Other", other);
            String original = template.toString();
            cfn.createStack(b -> b.stackName(stack.name).templateBody(original));
            CfnStackWaits.awaitStatus(stack.name, "CREATE_COMPLETE");
            makeLegacy(stack.name);
            Stack persisted = stored(stack.name);
            persisted.getResources().get("Other").getAttributes().remove("ApiId");
            ((ObjectNode) other.path("Properties")).putObject("ApiId").put("Ref", "Missing");
            persisted.setTemplateBody(template.toString());
            service.updateTerminationProtection(stack.name, false, "us-east-1");
            try {
                cfn.deleteStack(b -> b.stackName(stack.name));
                CfnStackWaits.awaitStatus(stack.name, "DELETE_FAILED");
                service.loadPersistedState();
                for (StackResource resource : stored(stack.name).getResources().values()) {
                    assertFalse(resource.getAttributes().containsKey("ApiId"));
                }
                assertEquals(2, api.getStages(b -> b.apiId(owner.id)).items().size());
            } finally {
                stored(stack.name).setTemplateBody(original);
                stored(stack.name).getResources().get("Other").getAttributes().put("ApiId", owner.id);
                restoreFixtureOwner(stack.name, owner.id);
            }
        }
    }

    private Stack diskRoundTrip(Stack stack) {
        Path path = storageDirectory.resolve(UUID.randomUUID() + ".json");
        PersistentStorage<String, Stack> writer = new PersistentStorage<>(path,
                new TypeReference<Map<String, Stack>>() {});
        writer.put(stack.getStackId(), stack);
        PersistentStorage<String, Stack> reader = new PersistentStorage<>(path,
                new TypeReference<Map<String, Stack>>() {});
        reader.load();
        return reader.get(stack.getStackId()).orElseThrow();
    }

    private String parameterTemplate(String kind, String description) {
        return template(kind, "owner-placeholder", description)
                .replace("\"owner-placeholder\"", "{\"Fn::If\":[\"UseOwner\",{\"Ref\":\"Owner\"},\"unused\"]}")
                .replace("\"Resources\":", "\"Parameters\":{\"Owner\":{\"Type\":\"String\"}},"
                        + "\"Conditions\":{\"UseOwner\":{\"Fn::Equals\":[\"yes\",\"yes\"]}},\"Resources\":");
    }

    private Stack stored(String name) {
        return service.describeStacks(name, "us-east-1").getFirst();
    }

    private void makeLegacy(String name) throws Exception {
        Stack persisted = stored(name);
        StackResource legacy = mapper.readValue(mapper.writeValueAsBytes(persisted.getResources().get("Child")),
                StackResource.class);
        legacy.getAttributes().remove("ApiId");
        persisted.getResources().put("Child", legacy);
        service.updateTerminationProtection(name, false, "us-east-1");
        service.loadPersistedState();
    }

    private void restoreFixtureOwner(String name, String apiId) {
        try {
            Stack stack = service.describeStacks(name, "us-east-1").getFirst();
            stack.getResources().get("Child").getAttributes().put("ApiId", apiId);
            service.updateTerminationProtection(name, false, "us-east-1");
        } catch (AwsException expected) {
            if (!"ValidationError".equals(expected.getErrorCode())
                    || !("Stack with id " + name + " does not exist").equals(expected.getMessage())) {
                throw expected;
            }
            // Successful deletion has no stored resource to restore for teardown.
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

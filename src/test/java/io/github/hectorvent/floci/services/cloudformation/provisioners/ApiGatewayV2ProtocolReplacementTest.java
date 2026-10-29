package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiGatewayV2ProtocolReplacementTest {
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/path/original";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ApiGatewayV2Service service;
    private ApiGatewayV2CfnProvisioner provisioner;
    private StackResource resource;
    private ProvisionContext context;
    private ObjectNode original;
    private String apiId;
    private Api previousApi;
    private Route previousRoute;
    private Integration previousIntegration;
    private Authorizer previousAuthorizer;
    private String independentRoute;

    @BeforeEach
    void setUp() throws Exception {
        StorageFactory factory = mock(StorageFactory.class);
        doAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"))
                .when(factory).create(anyString(), anyString(), any());
        service = spy(new ApiGatewayV2Service(factory, mock(EmulatorConfig.class), mock(RegionResolver.class)));
        provisioner = new ApiGatewayV2CfnProvisioner(service, mock(S3Service.class), mapper);
        resource = new StackResource();
        resource.setResourceType("AWS::ApiGatewayV2::Api");
        resource.setLogicalId("Api");
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any(JsonNode.class))).thenAnswer(invocation -> ((JsonNode) invocation.getArgument(0)).asText());
        when(engine.resolveNode(any(JsonNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        original = (ObjectNode) mapper.readTree("""
                {"Name":"original","ProtocolType":"HTTP","Body":{"openapi":"3.0.1",
                  "components":{"securitySchemes":{"auth":{"type":"apiKey",
                    "x-amazon-apigateway-authorizer":{"type":"request",
                    "identitySource":"$request.header.Authorization",
                    "authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:auth:live",
                    "authorizerPayloadFormatVersion":"2.0"}}}},
                  "security":[{"auth":[]}],"paths":{"/items":{"get":{
                    "x-amazon-apigateway-integration":{"type":"http_proxy","httpMethod":"GET",
                    "uri":"https://example.com/original","payloadFormatVersion":"1.0"}}}}}}
                """);
        provisioner.provision(resource, original,
                new ProvisionContext(engine, REGION, "000000000000", "stack", null));
        apiId = resource.getPhysicalId();
        previousRoute = service.getRoutes(REGION, apiId).getFirst();
        previousIntegration = service.getIntegrations(REGION, apiId).getFirst();
        previousAuthorizer = service.getAuthorizers(REGION, apiId).getFirst();
        service.updateIntegration(REGION, apiId, previousIntegration.getIntegrationId(),
                Map.of("credentialsArn", ROLE, "timeoutInMillis", 1234));
        service.updateAuthorizer(REGION, apiId, previousAuthorizer.getAuthorizerId(),
                Map.of("authorizerCredentialsArn", ROLE));
        previousApi = mapper.readValue(mapper.writeValueAsString(service.getApi(REGION, apiId)), Api.class);
        independentRoute = service.createRoute(REGION, apiId, Map.of("routeKey", "GET /independent")).getRouteId();
        service.createStage(REGION, apiId, Map.of("stageName", "independent", "autoDeploy", false));
        context = new ProvisionContext(engine, REGION, "000000000000", "stack", apiId, event -> {}, original);
    }

    @Test
    void protocolReplacementRestoresBodyOwnershipAfterSerializedRollback() throws Exception {
        Map<String, String> originalAttributes = Map.copyOf(resource.getAttributes());
        provisioner.provision(resource, replacementProperties(), context);
        String replacement = resource.getPhysicalId();
        assertNotEquals(apiId, replacement);
        assertOriginal();
        StackResource reloaded = mapper.readValue(mapper.writeValueAsString(resource), StackResource.class);
        assertTrue(provisioner.rollbackUpdate(reloaded));
        assertEquals(apiId, reloaded.getPhysicalId());
        for (String key : originalAttributes.keySet()) {
            assertEquals(originalAttributes.get(key), reloaded.getAttributes().get(key), key);
        }
        assertThrows(AwsException.class, () -> service.getApi(REGION, replacement));
        assertOriginal();
    }

    @Test
    void protocolReplacementOwnBodyFailureRestoresOriginalAndDeletesNewApi() {
        StackResource websocket = createWebsocket();
        String originalId = websocket.getPhysicalId();
        IllegalStateException failure = new IllegalStateException("replacement body write");
        doThrow(failure).when(service).createIntegration(eq(REGION), anyString(), anyMap());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> provisioner.provision(websocket, changed(), websocketUpdate(originalId))));
        assertEquals(originalId, websocket.getPhysicalId());
        assertEquals("WEBSOCKET", service.getApi(REGION, originalId).getProtocolType());
        assertEquals(2, service.getApis(REGION).size());
        assertOriginal();
    }

    @Test
    void protocolReplacementRollbackDeleteFailureKeepsRetryAndOriginalBodyIds() {
        String originalBodyIds = resource.getAttributes().get("__FlociApiGatewayV2BodyRouteIds");
        provisioner.provision(resource, replacementProperties(), context);
        String replacement = resource.getPhysicalId();
        IllegalStateException failure = new IllegalStateException("replacement API delete");
        doThrow(failure).when(service).deleteApi(REGION, replacement);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource)));
        assertEquals(apiId, resource.getPhysicalId());
        assertEquals(originalBodyIds, resource.getAttributes().get("__FlociApiGatewayV2BodyRouteIds"));
        assertOriginal();
        doCallRealMethod().when(service).deleteApi(REGION, replacement);
        assertTrue(provisioner.rollbackUpdate(resource));
        assertThrows(AwsException.class, () -> service.getApi(REGION, replacement));
        assertOriginal();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protocolReplacementCommitHonorsRetain(boolean retain) {
        if (retain) {
            resource.setUpdateReplacePolicy("Retain");
        }
        provisioner.provision(resource, replacementProperties(), context);
        String replacement = resource.getPhysicalId();
        assertNotEquals(apiId, replacement);
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
        assertFalse(provisioner.rollbackUpdate(resource));
        assertEquals("WEBSOCKET", service.getApi(REGION, replacement).getProtocolType());
        if (retain) {
            assertOriginal();
        } else {
            assertThrows(AwsException.class, () -> service.getApi(REGION, apiId));
        }
    }

    @Test
    void protocolReplacementCommitDeleteFailureCanRetryWithoutDeletingCurrent() {
        provisioner.provision(resource, replacementProperties(), context);
        String replacement = resource.getPhysicalId();
        doThrow(new IllegalStateException("old API delete")).when(service).deleteApi(REGION, apiId);
        assertFalse(provisioner.completeUpdate(resource).complete());
        assertEquals("WEBSOCKET", service.getApi(REGION, replacement).getProtocolType());
        assertOriginal();
        doCallRealMethod().when(service).deleteApi(REGION, apiId);
        assertTrue(provisioner.completeUpdate(resource).complete());
        assertThrows(AwsException.class, () -> service.getApi(REGION, apiId));
        assertEquals(replacement, resource.getPhysicalId());
    }

    @Test
    void protocolReplacementOwnFailureRetainsCleanupFailureForRetry() {
        StackResource websocket = createWebsocket();
        String originalId = websocket.getPhysicalId();
        IllegalStateException failure = new IllegalStateException("replacement body write");
        IllegalStateException cleanupFailure = new IllegalStateException("replacement delete");
        doThrow(failure).when(service).createIntegration(eq(REGION), anyString(), anyMap());
        doThrow(cleanupFailure).when(service).deleteApi(eq(REGION), anyString());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> provisioner.provision(websocket, changed(), websocketUpdate(originalId))));
        assertArrayEquals(new Throwable[]{cleanupFailure}, failure.getSuppressed());
        assertEquals(originalId, websocket.getPhysicalId());
        assertEquals("WEBSOCKET", service.getApi(REGION, originalId).getProtocolType());
        assertOriginal();
        assertEquals(3, service.getApis(REGION).size());
        doCallRealMethod().when(service).deleteApi(eq(REGION), anyString());
        assertTrue(provisioner.rollbackUpdate(websocket));
        assertEquals(2, service.getApis(REGION).size());
        assertOriginal();
    }

    @Test
    void protocolReplacementFailedRollbackIsCleanedByStackDeletion() {
        provisioner.provision(resource, replacementProperties(), context);
        String replacement = resource.getPhysicalId();
        doThrow(new IllegalStateException("replacement delete")).when(service).deleteApi(REGION, replacement);
        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        doCallRealMethod().when(service).deleteApi(REGION, replacement);
        provisioner.delete(resource, REGION);
        assertTrue(service.getApis(REGION).isEmpty());
    }

    @Test
    void protocolReplacementCreateFailurePreservesOriginalAndAttributes() {
        Map<String, String> attributes = Map.copyOf(resource.getAttributes());
        IllegalStateException failure = new IllegalStateException("replacement create");
        doThrow(failure).when(service).createApi(eq(REGION), anyMap());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> provisioner.provision(resource, replacementProperties(), context)));
        assertEquals(apiId, resource.getPhysicalId());
        assertEquals(attributes, resource.getAttributes());
        assertEquals(1, service.getApis(REGION).size());
        assertOriginal();
    }

    @Test
    void protocolReplacementRollbackPreservesExistingQuickCreateChildren() {
        Api quickApi = service.createApi(REGION, Map.of("name", "quick-original",
                "target", "https://example.com/quick-original"));
        String originalId = quickApi.getApiId();
        service.createRoute(REGION, originalId, Map.of("routeKey", "GET /independent"));
        JsonNode originalApi = mapper.valueToTree(service.getApi(REGION, originalId));
        JsonNode originalRoutes = mapper.valueToTree(service.getRoutes(REGION, originalId));
        JsonNode originalIntegrations = mapper.valueToTree(service.getIntegrations(REGION, originalId));
        JsonNode originalStages = mapper.valueToTree(service.getStages(REGION, originalId));
        StackResource quickResource = new StackResource();
        quickResource.setResourceType("AWS::ApiGatewayV2::Api");
        quickResource.setLogicalId("QuickApi");
        quickResource.setPhysicalId(originalId);
        ProvisionContext update = new ProvisionContext(context.engine(), REGION, "000000000000", "stack",
                originalId, event -> {}, mapper.createObjectNode().put("ProtocolType", "HTTP"));
        provisioner.provision(quickResource, replacementProperties(), update);
        String replacement = quickResource.getPhysicalId();
        assertNotEquals(originalId, replacement);
        assertTrue(provisioner.rollbackUpdate(quickResource));
        assertEquals(originalId, quickResource.getPhysicalId());
        assertEquals(originalApi, mapper.valueToTree(service.getApi(REGION, originalId)));
        assertEquals(originalRoutes, mapper.valueToTree(service.getRoutes(REGION, originalId)));
        assertEquals(originalIntegrations, mapper.valueToTree(service.getIntegrations(REGION, originalId)));
        assertEquals(originalStages, mapper.valueToTree(service.getStages(REGION, originalId)));
        assertThrows(AwsException.class, () -> service.getApi(REGION, replacement));
        assertOriginal();
    }

    private ObjectNode replacementProperties() {
        ObjectNode properties = changed().put("ProtocolType", "WEBSOCKET")
                .put("RouteSelectionExpression", "$request.body.action");
        properties.remove("Body");
        return properties;
    }

    private StackResource createWebsocket() {
        StackResource websocket = new StackResource();
        websocket.setResourceType("AWS::ApiGatewayV2::Api");
        websocket.setLogicalId("Websocket");
        provisioner.provision(websocket, replacementProperties(),
                new ProvisionContext(context.engine(), REGION, "000000000000", "stack", null));
        return websocket;
    }

    private ProvisionContext websocketUpdate(String id) {
        return new ProvisionContext(context.engine(), REGION, "000000000000", "stack", id,
                event -> {}, replacementProperties());
    }

    private ObjectNode changed() {
        ObjectNode changed = original.deepCopy();
        changed.put("Name", "changed");
        ObjectNode integration = (ObjectNode) changed.path("Body").path("paths").path("/items")
                .path("get").path("x-amazon-apigateway-integration");
        integration.put("uri", "https://example.com/changed");
        return changed;
    }

    private void assertOriginal() {
        assertEquals(mapper.valueToTree(previousApi), mapper.valueToTree(service.getApi(REGION, apiId)));
        assertEquals(mapper.valueToTree(previousRoute),
                mapper.valueToTree(service.getRoute(REGION, apiId, previousRoute.getRouteId())));
        assertEquals(mapper.valueToTree(previousIntegration),
                mapper.valueToTree(service.getIntegration(REGION, apiId, previousIntegration.getIntegrationId())));
        assertEquals(mapper.valueToTree(previousAuthorizer),
                mapper.valueToTree(service.getAuthorizer(REGION, apiId, previousAuthorizer.getAuthorizerId())));
        assertEquals(ROLE, service.getIntegration(REGION, apiId, previousIntegration.getIntegrationId()).getCredentialsArn());
        assertEquals(ROLE, service.getAuthorizer(REGION, apiId, previousAuthorizer.getAuthorizerId()).getAuthorizerCredentialsArn());
        assertNotNull(service.getRoute(REGION, apiId, independentRoute));
        assertNotNull(service.getStage(REGION, apiId, "independent"));
        assertEquals(2, service.getRoutes(REGION, apiId).size());
        assertEquals(1, service.getIntegrations(REGION, apiId).size());
        assertEquals(1, service.getAuthorizers(REGION, apiId).size());
    }
}

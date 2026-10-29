package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiGatewayV2AuthorizerRollbackTest {
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/path/original";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ApiGatewayV2Service service;
    private ApiGatewayV2CfnProvisioner provisioner;
    private StackResource resource;
    private ProvisionContext context;
    private String apiId;
    private String authorizerId;
    private JsonNode original;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        doAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"))
                .when(factory).create(anyString(), anyString(), any());
        service = spy(new ApiGatewayV2Service(factory, mock(EmulatorConfig.class), mock(RegionResolver.class)));
        apiId = service.createApi(REGION, Map.of("name", "api", "protocolType", "HTTP")).getApiId();
        Authorizer authorizer = service.createAuthorizer(REGION, apiId, Map.of(
                "name", "original", "authorizerType", "REQUEST", "authorizerCredentialsArn", ROLE,
                "authorizerUri", "original-uri", "identitySource", List.of("$request.header.Authorization"),
                "authorizerPayloadFormatVersion", "2.0", "authorizerResultTtlInSeconds", 123,
                "enableSimpleResponses", true));
        authorizerId = authorizer.getAuthorizerId();
        original = mapper.valueToTree(authorizer);
        resource = new StackResource();
        resource.setResourceType("AWS::ApiGatewayV2::Authorizer");
        resource.setLogicalId("Authorizer");
        resource.setPhysicalId(authorizerId);
        resource.getAttributes().put("ApiId", apiId);
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any(JsonNode.class))).thenAnswer(invocation -> ((JsonNode) invocation.getArgument(0)).asText());
        context = new ProvisionContext(engine, REGION, "000000000000", "stack", authorizerId);
        provisioner = new ApiGatewayV2CfnProvisioner(service, mock(S3Service.class), mapper);
    }

    @Test
    void laterFailureRestoresEveryFieldAndConsumesSnapshot() throws Exception {
        update();
        assertNull(service.getAuthorizer(REGION, apiId, authorizerId).getAuthorizerCredentialsArn());
        assertTrue(provisioner.rollbackUpdate(resource));
        assertOriginal();
        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void serializedSnapshotSurvivesChangesToTheLiveObject() throws Exception {
        update();
        StackResource restored = mapper.readValue(mapper.writeValueAsBytes(resource), StackResource.class);
        service.getAuthorizer(REGION, apiId, authorizerId).setName("another change");
        assertTrue(provisioner.rollbackUpdate(restored));
        assertOriginal();
    }

    @Test
    void ownFailureAfterCredentialRemovalRestoresOriginal() {
        IllegalStateException failure = new IllegalStateException("after credential removal");
        doAnswer(invocation -> { invocation.callRealMethod(); throw failure; }).when(service)
                .replaceAuthorizerCredentials(eq(REGION), eq(apiId), eq(authorizerId), isNull());
        assertSame(failure, assertThrows(IllegalStateException.class, this::update));
        assertOriginal();
        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void failedRestoreKeepsSnapshotForRetry() throws Exception {
        update();
        IllegalStateException failure = new IllegalStateException("restore write");
        doThrow(failure).when(service).restoreAuthorizer(eq(REGION), eq(apiId), any());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource)));
        doCallRealMethod().when(service).restoreAuthorizer(eq(REGION), eq(apiId), any());
        assertTrue(provisioner.rollbackUpdate(resource));
        assertOriginal();
    }

    @Test
    void ownFailureCarriesUnrestoredSnapshotToCommittedResource() {
        IllegalStateException failure = new IllegalStateException("write");
        IllegalStateException restore = new IllegalStateException("restore");
        doAnswer(invocation -> { invocation.callRealMethod(); throw failure; }).when(service)
                .updateAuthorizer(eq(REGION), eq(apiId), eq(authorizerId), anyMap());
        doThrow(restore).when(service).restoreAuthorizer(eq(REGION), eq(apiId), any());
        assertSame(failure, assertThrows(IllegalStateException.class, this::update));
        assertArrayEquals(new Throwable[]{restore}, failure.getSuppressed());
        assertTrue(resource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        StackResource previous = new StackResource();
        previous.setResourceType(resource.getResourceType());
        previous.setPhysicalId(authorizerId);
        provisioner.mergeFailedUpdateResourceTracking(previous, resource);
        doCallRealMethod().when(service).restoreAuthorizer(eq(REGION), eq(apiId), any());
        assertTrue(provisioner.rollbackUpdate(previous));
        assertOriginal();
    }

    @Test
    void sameFailureObjectDoesNotMaskTheOriginal() {
        IllegalStateException failure = new IllegalStateException("same backend failure");
        doThrow(failure).when(service).updateAuthorizer(eq(REGION), eq(apiId), eq(authorizerId), anyMap());
        doThrow(failure).when(service).restoreAuthorizer(eq(REGION), eq(apiId), any());
        assertSame(failure, assertThrows(IllegalStateException.class, this::update));
        assertEquals(0, failure.getSuppressed().length);
        assertTrue(resource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void committedUpdateDoesNotUndoNewState() throws Exception {
        update();
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
        assertFalse(provisioner.rollbackUpdate(resource));
        assertEquals("changed", service.getAuthorizer(REGION, apiId, authorizerId).getName());
        assertNull(service.getAuthorizer(REGION, apiId, authorizerId).getAuthorizerCredentialsArn());
    }

    @Test
    void nextUpdateSnapshotsLatestCommittedState() throws Exception {
        update();
        JsonNode committed = mapper.valueToTree(service.getAuthorizer(REGION, apiId, authorizerId));
        provisioner.clearUpdate(resource);
        ObjectNode props = properties();
        props.put("Name", "newer");
        props.put("AuthorizerCredentialsArn", ROLE);
        provisioner.provision(resource, props, context);
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(committed, mapper.valueToTree(service.getAuthorizer(REGION, apiId, authorizerId)));
    }

    @Test
    void noUpdateHasNoRollback() {
        assertFalse(provisioner.rollbackUpdate(resource));
        assertOriginal();
    }

    private void update() throws Exception {
        provisioner.provision(resource, properties(), context);
    }

    private ObjectNode properties() {
        ObjectNode props = mapper.createObjectNode();
        props.put("ApiId", apiId);
        props.put("Name", "changed");
        props.put("AuthorizerType", "REQUEST");
        props.put("AuthorizerUri", "changed-uri");
        props.put("AuthorizerPayloadFormatVersion", "1.0");
        props.put("AuthorizerResultTtlInSeconds", 42);
        props.put("EnableSimpleResponses", false);
        props.putArray("IdentitySource").add("$request.header.Other");
        return props;
    }

    private void assertOriginal() {
        assertEquals(original, mapper.valueToTree(service.getAuthorizer(REGION, apiId, authorizerId)));
        assertEquals(authorizerId, resource.getPhysicalId());
    }
}

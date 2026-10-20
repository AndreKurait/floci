package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.Connection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventsConnectionCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ARN = "arn:aws:events:us-east-1:000000000000:connection/auth/id";
    private static final String AUTH = "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"X-Key\",\"ApiKeyValue\":\"synthetic\"}}";
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventBridgeService events = mock(EventBridgeService.class);
    private final EventsCfnProvisioner provisioner = new EventsCfnProvisioner(events);

    private ProvisionContext context(String prior) {
        return new ProvisionContext(CloudFormationTemplateEngine.standalone(
                "000000000000", REGION, "stack", mapper, null), REGION, "000000000000", "stack", prior);
    }

    private StackResource resource(String name) {
        StackResource resource = new StackResource();
        resource.setLogicalId("Connection");
        resource.setResourceType("AWS::Events::Connection");
        resource.setPhysicalId(name);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private Connection connection() {
        Connection connection = new Connection();
        connection.setName("auth");
        connection.setConnectionArn(ARN);
        connection.setSecretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:events!connection/auth/id");
        connection.setAuthorizationType("API_KEY");
        connection.setAuthParameters(AUTH);
        return connection;
    }

    private ObjectNode properties(String name) throws Exception {
        ObjectNode properties = (ObjectNode) mapper.readTree("""
                {"AuthorizationType":"API_KEY","AuthParameters":{
                  "ApiKeyAuthParameters":{"ApiKeyName":"X-Key","ApiKeyValue":"synthetic"}}}
                """);
        if (name != null) {
            properties.put("Name", name);
        }
        return properties;
    }

    @Test
    void createSetsTheNameRefAndEveryPublicAttribute() throws Exception {
        Connection connection = connection();
        when(events.createConnection(any(), any(), any(), any(), any(), any(), any())).thenReturn(connection);
        StackResource resource = resource(null);

        provisioner.provision(resource, properties("auth"), context(null));

        assertEquals("auth", resource.getPhysicalId());
        assertEquals(ARN, resource.getAttributes().get("Arn"));
        assertEquals(ARN.substring(0, ARN.lastIndexOf('/')), resource.getAttributes().get("ArnForPolicy"));
        assertEquals(connection.getSecretArn(), resource.getAttributes().get("SecretArn"));
        verify(events).createConnection("auth", "", "API_KEY", AUTH, null, null, REGION);
        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void omittedNameReusesThePriorGeneratedName() throws Exception {
        when(events.describeConnection("stack-Connection-ab12", REGION)).thenReturn(connection());
        when(events.updateConnection(any(), any(), any(), any(), any(), any(), any())).thenReturn(connection());
        StackResource resource = resource("stack-Connection-ab12");

        provisioner.provision(resource, properties(null), context("stack-Connection-ab12"));

        assertEquals("stack-Connection-ab12", resource.getPhysicalId());
        verify(events, never()).createConnection(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void readerConvertsBooleansAndOmitsNoValueBeforeTheApiCall() throws Exception {
        when(events.createConnection(any(), any(), any(), any(), any(), any(), any())).thenReturn(connection());
        ObjectNode properties = properties("auth");
        properties.set("Description", mapper.readTree("{\"Ref\":\"AWS::NoValue\"}"));
        ((ObjectNode) properties.get("AuthParameters")).set("InvocationHttpParameters", mapper.readTree("""
                {"HeaderParameters":[{"Key":"X-Private","Value":"value","IsValueSecret":"true"}]}
                """));

        provisioner.provision(resource(null), properties, context(null));

        ArgumentCaptor<String> auth = ArgumentCaptor.forClass(String.class);
        verify(events).createConnection(eq("auth"), eq(""), eq("API_KEY"), auth.capture(), isNull(), isNull(), eq(REGION));
        JsonNode sent = mapper.readTree(auth.getValue());
        assertTrue(sent.at("/InvocationHttpParameters/HeaderParameters/0/IsValueSecret").isBoolean());
    }

    @Test
    void readerRejectsMalformedNestedPropertiesBeforeCreatingAnything() throws Exception {
        ObjectNode properties = properties("auth");
        ((ObjectNode) properties.get("AuthParameters")).put("ApiKeyAuthParameters", "wrong");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(null), properties, context(null)));

        assertEquals("ValidationError", failure.getErrorCode());
        verifyNoInteractions(events);
    }

    @Test
    void requiredPropertiesCannotBeRemovedByAnUpdate() throws Exception {
        ObjectNode properties = properties("auth");
        properties.remove("AuthParameters");

        assertThrows(AwsException.class,
                () -> provisioner.provision(resource("auth"), properties, context("auth")));

        verifyNoInteractions(events);
    }

    @Test
    void mutableUpdateRestoresCredentialsAndAbsentOptionalValues() throws Exception {
        Connection current = connection();
        when(events.describeConnection("auth", REGION)).thenReturn(current);
        when(events.updateConnection(any(), any(), any(), any(), any(), any(), any())).thenReturn(current);
        StackResource resource = resource("auth");
        ObjectNode properties = properties("auth");
        properties.put("Description", "changed");
        properties.put("KmsKeyIdentifier", "synthetic-key");
        provisioner.provision(resource, properties, context("auth"));

        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(events).restoreConnection("auth", null, "API_KEY", AUTH, null, null, null, null, null, null, REGION);
        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void aFailedMutableUpdateKeepsItsSnapshotForRollbackAndRetriesFailedRestoration() throws Exception {
        when(events.describeConnection("auth", REGION)).thenReturn(connection());
        when(events.updateConnection(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AwsException("ValidationException", "invalid update", 400));
        StackResource resource = resource("auth");
        ObjectNode properties = properties("auth");
        assertThrows(AwsException.class, () -> provisioner.provision(resource, properties, context("auth")));
        when(events.restoreConnection(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AwsException("InternalFailure", "retry", 500)).thenReturn(connection());

        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void aCommittedUpdateSpendsItsCredentialSnapshot() throws Exception {
        when(events.describeConnection("auth", REGION)).thenReturn(connection());
        when(events.updateConnection(any(), any(), any(), any(), any(), any(), any())).thenReturn(connection());
        StackResource resource = resource("auth");
        provisioner.provision(resource, properties("auth"), context("auth"));

        provisioner.completeUpdate(resource);

        assertFalse(resource.getAttributes().containsKey(CfnRollback.CONNECTION_UPDATE_SNAPSHOT_ATTR));
        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void renameRollbackRestoresThePriorIdentityAndDeletesOnlyTheReplacement() throws Exception {
        when(events.createConnection(any(), any(), any(), any(), any(), any(), any())).thenReturn(connection());
        StackResource resource = resource("prior");
        resource.getAttributes().putAll(Map.of("Arn", "prior-arn", "SecretArn", "prior-secret"));
        provisioner.provision(resource, properties("replacement"), context("prior"));
        verify(events, never()).deleteConnection(any(), any());

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("prior", resource.getPhysicalId());
        assertEquals("prior-arn", resource.getAttributes().get("Arn"));
        assertEquals("prior-secret", resource.getAttributes().get("SecretArn"));
        verify(events).deleteConnection("replacement", REGION);
        verify(events, never()).deleteConnection("prior", REGION);
    }

    @Test
    void renameCommitHonorsRetainAndNormalDeletionPropagatesRealFailures() throws Exception {
        when(events.createConnection(any(), any(), any(), any(), any(), any(), any())).thenReturn(connection());
        StackResource resource = resource("prior");
        resource.setUpdateReplacePolicy("Retain");
        provisioner.provision(resource, properties("replacement"), context("prior"));

        assertNull(provisioner.updateCleanupPhysicalId(resource));
        provisioner.completeUpdate(resource);
        verify(events, never()).deleteConnection(any(), any());
        when(events.deleteConnection("replacement", REGION))
                .thenThrow(new AwsException("InternalFailure", "real failure", 500));
        assertThrows(AwsException.class, () -> provisioner.delete(resource, REGION));
    }

    @Test
    void deleteToleratesOnlyAnAlreadyDeletedConnection() {
        when(events.deleteConnection("gone", REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 400));
        assertDoesNotThrow(() -> provisioner.delete("AWS::Events::Connection", "gone", REGION));
    }
}

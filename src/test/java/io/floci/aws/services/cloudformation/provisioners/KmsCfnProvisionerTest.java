package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KmsCfnProvisionerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final KmsService kms = mock(KmsService.class);
    private final KmsCfnProvisioner provisioner = new KmsCfnProvisioner(kms);

    @Test
    void failedRestoreRetainsSnapshotForRetry() {
        StackResource resource = updateDisabledKey();
        KmsKey disabled = new KmsKey();
        disabled.setKeyId("key");
        disabled.setEnabled(false);
        when(kms.describeKey("key", "us-east-1")).thenReturn(disabled);
        doThrow(new AwsException("KMSInvalidStateException", "unavailable", 400))
                .doNothing().when(kms).enableKey("key", "us-east-1");
        String snapshot = resource.getAttributes().get(CfnRollback.KMS_KEY_UPDATE_SNAPSHOT_ATTR);
        assertNotNull(snapshot);
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        assertEquals(snapshot, resource.getAttributes().get(CfnRollback.KMS_KEY_UPDATE_SNAPSHOT_ATTR));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        verify(kms, times(2)).enableKey("key", "us-east-1");
        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void committedUpdateDropsSnapshot() {
        StackResource resource = updateDisabledKey();
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        provisioner.completeUpdate(resource);
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        assertFalse(provisioner.rollbackUpdate(resource));
        verify(kms, never()).enableKey(anyString(), anyString());
    }

    private StackResource updateDisabledKey() {
        KmsKey key = new KmsKey();
        key.setKeyId("key");
        when(kms.describeKey("key", "us-east-1")).thenReturn(key);
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return node == null || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        StackResource resource = new StackResource();
        resource.setLogicalId("Key");
        resource.setResourceType("AWS::KMS::Key");
        resource.setPhysicalId("key");
        ObjectNode properties = mapper.createObjectNode().put("Enabled", false);
        provisioner.provision(resource, properties,
                new ProvisionContext(engine, "us-east-1", "000000000000", "stack", "key"));
        verify(kms).disableKey("key", "us-east-1");
        return resource;
    }
}

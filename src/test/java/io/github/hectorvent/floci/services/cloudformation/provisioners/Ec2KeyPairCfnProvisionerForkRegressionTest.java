package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Ec2KeyPairCfnProvisionerForkRegressionTest {
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final SsmService ssm = mock(SsmService.class);
    private final Ec2KeyPairCfnProvisioner owner = new Ec2KeyPairCfnProvisioner(ec2, ssm);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyArrayInputsKeepAnUnchangedKey() throws Exception {
        StackResource resource = resource();
        resource.setPhysicalId("key");
        resource.getAttributes().put("KeyPairId", "key-legacy");
        resource.getAttributes().put("__FlociKeyPairInputs", "[\"key\",\"\",\"rsa\",\"pem\",{}]");
        owner.provision(resource, props("{\"KeyName\":\"key\"}"), context("key"));
        assertEquals("key-legacy", resource.getAttributes().get("KeyPairId"));
        verifyNoInteractions(ec2, ssm);
    }

    @Test
    void generatedKeyPublishesRealAttributesAndSecureParameter() throws Exception {
        when(ec2.createKeyPair("us-east-1", "key", null, null)).thenReturn(key("key", "key-123", "private"));
        StackResource resource = resource();
        owner.provision(resource, props("{\"KeyName\":\"key\"}"), context(null));
        assertEquals("key", resource.getPhysicalId());
        assertEquals("key-123", resource.getAttributes().get("KeyPairId"));
        assertEquals("fingerprint", resource.getAttributes().get("KeyFingerprint"));
        verify(ssm).putParameter("/ec2/keypair/key-123", "private", "SecureString",
                null, false, "us-east-1");
    }

    @Test
    void importDoesNotCreateOrDeletePrivateParameter() throws Exception {
        KeyPair imported = key("key", "key-import", null);
        when(ec2.importKeyPair("us-east-1", "key", "ssh-rsa public")).thenReturn(imported);
        when(ec2.describeKeyPairs("us-east-1", List.of("key"), List.of())).thenReturn(List.of(imported));
        owner.provision(resource(), props("{\"KeyName\":\"key\",\"PublicKeyMaterial\":\"ssh-rsa public\",\"KeyType\":\"ed25519\"}"), context(null));
        owner.delete("AWS::EC2::KeyPair", "key", "us-east-1");
        verifyNoInteractions(ssm);
        verify(ec2).deleteKeyPair("us-east-1", null, "key-import");
    }

    @Test
    void failedParameterWriteRemovesOnlyNewKeyAndPreservesPrimaryError() {
        when(ec2.createKeyPair("us-east-1", "key", null, null)).thenReturn(key("key", "key-new", "private"));
        AwsException original = new AwsException("ParameterAlreadyExists", "collision", 400);
        when(ssm.putParameter(anyString(), anyString(), anyString(), any(), anyBoolean(), anyString()))
                .thenThrow(original);
        assertSame(original, assertThrows(AwsException.class,
                () -> owner.provision(resource(), props("{\"KeyName\":\"key\"}"), context(null))));
        verify(ec2).deleteKeyPair("us-east-1", null, "key-new");
        verify(ssm, never()).deleteParameter(anyString(), anyString());
    }

    @Test
    void failedCompensationKeepsTheOwnedKeyWithoutClaimingAnExistingParameter() {
        when(ec2.createKeyPair("us-east-1", "key", null, null)).thenReturn(key("key", "key-new", "private"));
        AwsException original = new AwsException("ParameterAlreadyExists", "collision", 400);
        when(ssm.putParameter(anyString(), anyString(), anyString(), any(), anyBoolean(), anyString()))
                .thenThrow(original);
        when(ec2.deleteKeyPair("us-east-1", null, "key-new"))
                .thenThrow(new AwsException("AccessDenied", "cleanup denied", 403)).thenReturn((KeyPair) null);
        StackResource resource = resource();

        assertSame(original, assertThrows(AwsException.class,
                () -> owner.provision(resource, props("{\"KeyName\":\"key\"}"), context(null))));
        assertEquals(1, original.getSuppressed().length);
        assertEquals("key", resource.getPhysicalId());
        assertEquals("key-new", resource.getAttributes().get("KeyPairId"));
        assertEquals("true", resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        when(ec2.describeKeyPairs("us-east-1", List.of("key"), List.of()))
                .thenReturn(List.of(key("key", "key-new", "private")));
        owner.delete(resource, "us-east-1");

        verify(ec2, times(2)).deleteKeyPair("us-east-1", null, "key-new");
        verify(ssm, never()).deleteParameter(anyString(), anyString());
    }

    @Test
    void failedParameterCleanupKeepsTheKeyAndParameterAddressableForRetry() {
        when(ec2.createKeyPair("us-east-1", "key", null, null)).thenReturn(key("key", "key-new", "private"));
        AwsException original = new AwsException("InternalError", "parameter write response failed", 500);
        when(ssm.putParameter(eq("/ec2/keypair/key-new"), anyString(), anyString(), any(),
                anyBoolean(), anyString())).thenThrow(original);
        doThrow(new AwsException("AccessDeniedException", "cleanup denied", 403))
                .doNothing().when(ssm).deleteParameter("/ec2/keypair/key-new", "us-east-1");
        StackResource resource = resource();

        assertSame(original, assertThrows(AwsException.class, () -> owner.provision(resource,
                props("{\"KeyName\":\"key\",\"Tags\":[{\"Key\":\"Name\",\"Value\":\"owned\"}]}"), context(null))));
        assertEquals(1, original.getSuppressed().length);
        assertEquals("true", resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        verify(ec2, never()).deleteKeyPair(anyString(), any(), any());
        when(ec2.describeKeyPairs("us-east-1", List.of("key"), List.of()))
                .thenReturn(List.of(key("key", "key-new", "private")));
        owner.delete(resource, "us-east-1");

        verify(ssm, times(2)).deleteParameter("/ec2/keypair/key-new", "us-east-1");
        verify(ec2).deleteKeyPair("us-east-1", null, "key-new");
    }

    @Test
    void invalidTagsFailBeforeCreatingAKeyOrItsParameter() {
        assertThrows(AwsException.class, () -> owner.provision(resource(),
                props("{\"KeyName\":\"key\",\"Tags\":[{\"Key\":\"aws:reserved\",\"Value\":\"invalid\"}]}"), context(null)));
        verifyNoInteractions(ec2, ssm);
    }

    @Test
    void failedReplacementKeepsItsOwnershipWhenThePriorResourceIsRestored() throws Exception {
        when(ec2.createKeyPair("us-east-1", "old", null, null)).thenReturn(key("old", "key-old", "private"));
        when(ec2.createKeyPair("us-east-1", "next", null, null)).thenReturn(key("next", "key-next", "private"));
        StackResource previous = resource();
        owner.provision(previous, props("{\"KeyName\":\"old\"}"), context(null));
        StackResource attempted = resource();
        attempted.setPhysicalId(previous.getPhysicalId());
        attempted.getAttributes().putAll(previous.getAttributes());
        AwsException original = new AwsException("ParameterAlreadyExists", "collision", 400);
        when(ssm.putParameter(eq("/ec2/keypair/key-next"), anyString(), anyString(), any(),
                anyBoolean(), anyString())).thenThrow(original);
        when(ec2.deleteKeyPair("us-east-1", null, "key-next"))
                .thenThrow(new AwsException("AccessDenied", "cleanup denied", 403)).thenReturn((KeyPair) null);

        assertSame(original, assertThrows(AwsException.class,
                () -> owner.provision(attempted, props("{\"KeyName\":\"next\"}"), context("old"))));
        ReplacementCleanup.mergeDisplaced(previous, attempted);
        owner.mergeFailedUpdateResourceTracking(previous, attempted);
        when(ec2.describeKeyPairs("us-east-1", List.of("next"), List.of()))
                .thenReturn(List.of(key("next", "key-next", "private")));
        assertTrue(owner.completeUpdate(previous).complete());

        assertEquals("old", previous.getPhysicalId());
        assertEquals("key-old", previous.getAttributes().get("KeyPairId"));
        verify(ec2, times(2)).deleteKeyPair("us-east-1", null, "key-next");
        verify(ec2, never()).deleteKeyPair("us-east-1", null, "key-old");
        verify(ssm, never()).deleteParameter(anyString(), anyString());
        verify(ec2).describeKeyPairs("us-east-1", List.of("next"), List.of());
        verify(ec2, never()).describeKeyPairs("us-east-1", List.of("old"), List.of());
    }

    @Test
    void unchangedUpdateReusesKeyAndPrivateParameter() throws Exception {
        KeyPair key = key("key", "key-123", "private");
        when(ec2.createKeyPair("us-east-1", "key", null, null)).thenReturn(key);
        when(ec2.describeKeyPairs("us-east-1", List.of("key"), List.of())).thenReturn(List.of(key));
        StackResource resource = resource();
        owner.provision(resource, props("{\"KeyName\":\"key\"}"), context(null));
        owner.provision(resource, props("{\"KeyName\":\"key\"}"), context("key"));
        verify(ec2, times(1)).createKeyPair("us-east-1", "key", null, null);
        verify(ssm, times(1)).putParameter(anyString(), anyString(), anyString(), any(), anyBoolean(), anyString());
        assertFalse(owner.hasReplacementUpdate(resource));
    }

    @Test
    void renameRollbackDeletesReplacementParameterAndKeepsOriginal() throws Exception {
        KeyPair old = key("old", "key-old", "old-private");
        KeyPair next = key("next", "key-next", "next-private");
        when(ec2.createKeyPair("us-east-1", "old", null, null)).thenReturn(old);
        when(ec2.createKeyPair("us-east-1", "next", null, null)).thenReturn(next);
        when(ec2.describeKeyPairs("us-east-1", List.of("next"), List.of())).thenReturn(List.of(next));
        StackResource resource = resource();
        owner.provision(resource, props("{\"KeyName\":\"old\"}"), context(null));
        owner.provision(resource, props("{\"KeyName\":\"next\"}"), context("old"));
        assertTrue(owner.hasReplacementUpdate(resource));
        assertTrue(owner.rollbackUpdate(resource));
        assertEquals("old", resource.getPhysicalId());
        verify(ssm).deleteParameter("/ec2/keypair/key-next", "us-east-1");
        verify(ec2).deleteKeyPair("us-east-1", null, "key-next");
        verify(ec2, never()).deleteKeyPair("us-east-1", null, "key-old");
    }

    @Test
    void deleteFailureKeepsKeyForRetry() {
        when(ec2.describeKeyPairs("us-east-1", List.of("key"), List.of()))
                .thenReturn(List.of(key("key", "key-123", "private")));
        doThrow(new AwsException("AccessDeniedException", "denied", 403)).when(ssm)
                .deleteParameter("/ec2/keypair/key-123", "us-east-1");
        assertThrows(AwsException.class, () -> owner.delete("AWS::EC2::KeyPair", "key", "us-east-1"));
        verify(ec2, never()).deleteKeyPair(anyString(), any(), any());
    }

    @Test
    void unsupportedGenerationAndMissingNameRefuseBeforeWrites() {
        for (String json : List.of("{}", "{\"KeyName\":\"key\",\"KeyType\":\"unsupported\"}",
                "{\"KeyName\":\"key\",\"KeyFormat\":\"unsupported\"}")) {
            assertThrows(AwsException.class, () -> owner.provision(resource(), props(json), context(null)));
        }
        verifyNoInteractions(ec2, ssm);
    }

    @Test
    void legacyStackDeleteUsesRecordedIdEvenIfTheNameWasReused() {
        StackResource resource = resource();
        resource.setPhysicalId("key");
        resource.getAttributes().put("KeyPairId", "key-owned");
        resource.getAttributes().put("__FlociKeyPairOwnedKey/key", "key-owned");
        resource.getAttributes().put("__FlociKeyPairOwnedParameter/key", "true");
        owner.delete(resource, "us-east-1");
        verify(ssm).deleteParameter("/ec2/keypair/key-owned", "us-east-1");
        verify(ec2).deleteKeyPair("us-east-1", null, "key-owned");
        verify(ec2, never()).describeKeyPairs(anyString(), anyList(), anyList());
    }

    @Test
    void rollbackRestoresGeneratedOwnershipAndNoOpUpdateIdentity() throws Exception {
        KeyPair old = key("old", "key-old", "private");
        when(ec2.createKeyPair("us-east-1", "old", null, null)).thenReturn(old);
        KeyPair imported = key("next", "key-next", null);
        when(ec2.importKeyPair("us-east-1", "next", "ssh-rsa public")).thenReturn(imported);
        when(ec2.describeKeyPairs("us-east-1", List.of("next"), List.of())).thenReturn(List.of(imported));
        when(ec2.describeKeyPairs("us-east-1", List.of("old"), List.of())).thenReturn(List.of(old));
        StackResource resource = resource();
        owner.provision(resource, props("{\"KeyName\":\"old\"}"), context(null));
        owner.provision(resource, props("{\"KeyName\":\"next\",\"PublicKeyMaterial\":\"ssh-rsa public\"}"), context("old"));
        assertTrue(owner.rollbackUpdate(resource));
        owner.provision(resource, props("{\"KeyName\":\"old\"}"), context("old"));
        verify(ec2, times(1)).createKeyPair("us-east-1", "old", null, null);
        owner.delete(resource, "us-east-1");
        verify(ssm).deleteParameter("/ec2/keypair/key-old", "us-east-1");
        verify(ssm, never()).deleteParameter("/ec2/keypair/key-next", "us-east-1");
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = CloudFormationTemplateEngine.standalone(
                "000000000000", "us-east-1", "stack", mapper, value -> value);
        return new ProvisionContext(engine, "us-east-1", "000000000000", "stack", prior);
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::EC2::KeyPair");
        resource.setLogicalId("Key");
        return resource;
    }

    private static KeyPair key(String name, String id, String privateKey) {
        KeyPair key = new KeyPair();
        key.setKeyName(name);
        key.setKeyPairId(id);
        key.setKeyFingerprint("fingerprint");
        key.setKeyMaterial(privateKey);
        return key;
    }
}

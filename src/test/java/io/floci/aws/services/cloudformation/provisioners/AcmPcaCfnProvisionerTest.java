package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.acmpca.AcmPcaService;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcmPcaCfnProvisionerTest {

    private static final String TYPE = "AWS::ACMPCA::CertificateAuthority";
    private final ObjectMapper mapper = new ObjectMapper();
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final AcmPcaService service = mock(AcmPcaService.class);
    private final AcmPcaCfnProvisioner provisioner = new AcmPcaCfnProvisioner(service, config);
    private final CloudFormationResourceRegistry registry = new CloudFormationResourceRegistry(List.of(provisioner));
    private final CfnResourceDispatcher dispatcher = new CfnResourceDispatcher(mapper, registry,
            mock(CfnDynamicReferences.class), config);

    @Test
    void enabledAuthorityRemainsRoutedToItsProvisioner() {
        when(config.services().acmPca().enabled()).thenReturn(true);
        assertFalse(provisioner.serviceDisabled());
        assertSame(provisioner, registry.forType(TYPE).orElseThrow());
    }

    @Test
    void disabledAuthorityUsesTheConfiguredStubPath() {
        when(config.services().cloudformation().allowStubUnsupportedResourceTypes()).thenReturn(true);
        StackResource resource = create();
        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals("true", resource.getAttributes().get(CloudFormationTemplateEngine.SERVICE_DISABLED_ATTR));
        assertTrue(resource.getStatusReason().contains("floci.services.acm-pca.enabled=true"));
        verifyNoInteractions(service);
    }

    @Test
    void disabledAuthorityFailsWhenStubsAreForbidden() {
        StackResource resource = create();
        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("floci.services.acm-pca.enabled=true"));
        verifyNoInteractions(service);
    }

    @Test
    void disabledAuthorityCannotUpdateAnExistingResource() {
        StackResource resource = dispatcher.provision("Authority", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "test-stack", "existing-authority", Map.of());
        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals("existing-authority", resource.getPhysicalId());
        assertTrue(resource.getStatusReason().contains("cannot be updated"));
        verifyNoInteractions(service);
    }

    @Test
    void failedTagRestoreRetainsSnapshotForRetry() throws Exception {
        StackResource resource = updatedAuthority();
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        AwsException failure = new AwsException("InternalFailure", "tag storage unavailable", 500);
        when(service.tag(any(), eq("us-east-1"))).thenThrow(failure).thenReturn(mapper.createObjectNode());
        assertSame(failure, assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource)));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        verify(service, times(2)).tag(argThat(request -> "original".equals(
                request.path("Tags").path(0).path("Value").asText())), eq("us-east-1"));
    }

    @Test
    void committedTagUpdateDiscardsSnapshot() throws Exception {
        StackResource resource = updatedAuthority();
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        provisioner.completeUpdate(resource);
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void ownUpdateFailureKeepsTagSnapshotForRollback() throws Exception {
        when(service.csr(any(), anyString())).thenThrow(new AwsException("InternalFailure", "CSR read failed", 500));
        StackResource resource = new StackResource();
        assertThrows(AwsException.class, () -> updateAuthority(resource, false));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        when(service.listTags(any(), anyString())).thenReturn((ObjectNode) mapper.readTree("""
                {"Tags":[{"Key":"team","Value":"updated"}]}
                """));
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(service).tag(argThat(request -> "original".equals(
                request.path("Tags").path(0).path("Value").asText())), eq("us-east-1"));
    }

    @Test
    void replacementDefersPriorDeletionUntilCommitAndRetriesFailures() throws Exception {
        StackResource resource = replacedAuthority();
        verify(service, never()).delete(any(), anyString());
        assertEquals("prior", provisioner.updateCleanupPhysicalId(resource));
        AwsException failure = new AwsException("InternalFailure", "delete unavailable", 500);
        when(service.delete(any(), anyString())).thenThrow(failure).thenReturn(mapper.createObjectNode());
        assertFalse(provisioner.completeUpdate(resource).complete());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(service, times(2)).delete(argThat(request -> "prior".equals(
                request.path("CertificateAuthorityArn").asText())), eq("us-east-1"));
    }

    @Test
    void retainedPriorSurvivesCommitButFailedReplacementIsStillRemoved() throws Exception {
        StackResource resource = replacedAuthority();
        resource.setUpdateReplacePolicy("Retain");
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(service, never()).delete(any(), anyString());
        resource = replacedAuthority();
        resource.setUpdateReplacePolicy("Retain");
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals("prior", resource.getPhysicalId());
        assertEquals("prior-csr", resource.getAttributes().get("CertificateSigningRequest"));
        verify(service).delete(argThat(request -> "replacement".equals(
                request.path("CertificateAuthorityArn").asText())), eq("us-east-1"));
    }

    @Test
    void failedReplacementRollbackKeepsDeleteForRetry() throws Exception {
        StackResource resource = replacedAuthority();
        AwsException failure = new AwsException("InternalFailure", "delete unavailable", 500);
        when(service.delete(any(), anyString())).thenThrow(failure).thenReturn(mapper.createObjectNode());
        assertSame(failure, assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource)));
        assertEquals("prior", resource.getPhysicalId());
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.hasReplacementUpdate(resource));
        verify(service, times(2)).delete(argThat(request -> "replacement".equals(
                request.path("CertificateAuthorityArn").asText())), eq("us-east-1"));
    }

    private StackResource replacedAuthority() throws Exception {
        StackResource resource = new StackResource();
        resource.setResourceType(TYPE);
        resource.setPhysicalId("prior");
        resource.getAttributes().put("Arn", "prior");
        resource.getAttributes().put("CertificateSigningRequest", "prior-csr");
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(call -> ((JsonNode) call.getArgument(0)).asText());
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"Type":"SUBORDINATE","KeyAlgorithm":"RSA_2048","SigningAlgorithm":"SHA256WITHRSA",
                 "Subject":{"CommonName":"Replacement"}}
                """);
        when(service.describe(any(), anyString())).thenReturn(mapper.createObjectNode()
                .set("CertificateAuthority", mapper.createObjectNode().put("Arn", "prior")
                        .put("Status", "PENDING_CERTIFICATE")));
        when(service.create(any(), anyString())).thenReturn(mapper.createObjectNode()
                .put("CertificateAuthorityArn", "replacement"));
        when(service.csr(any(), anyString())).thenReturn(mapper.createObjectNode().put("Csr", "replacement-csr"));
        provisioner.provision(resource, props, new ProvisionContext(engine, "us-east-1", "000000000000",
                "stack", "prior"));
        return resource;
    }

    private StackResource updatedAuthority() throws Exception {
        StackResource resource = new StackResource();
        updateAuthority(resource, true);
        return resource;
    }

    private void updateAuthority(StackResource resource, boolean csrSucceeds) throws Exception {
        String arn = "arn:aws:acm-pca:us-east-1:000000000000:certificate-authority/test";
        resource.setResourceType(TYPE);
        resource.setPhysicalId(arn);
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(call -> ((JsonNode) call.getArgument(0)).asText());
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"Type":"SUBORDINATE","KeyAlgorithm":"RSA_2048","SigningAlgorithm":"SHA256WITHRSA",
                 "Subject":{"CommonName":"Test"},"Tags":[{"Key":"team","Value":"updated"}]}
                """);
        ObjectNode authority = props.deepCopy().put("Arn", arn).put("Status", "PENDING_CERTIFICATE")
                .put("KeyStorageSecurityStandard", "FIPS_140_2_LEVEL_3_OR_HIGHER").put("UsageMode", "GENERAL_PURPOSE");
        authority.set("CertificateAuthorityConfiguration", props.deepCopy());
        when(service.describe(any(), anyString())).thenReturn(mapper.createObjectNode()
                .set("CertificateAuthority", authority));
        when(service.listTags(any(), anyString())).thenReturn((ObjectNode) mapper.readTree("""
                {"Tags":[{"Key":"team","Value":"original"}]}
                """));
        if (csrSucceeds) {
            when(service.csr(any(), anyString())).thenReturn(mapper.createObjectNode().put("Csr", "csr"));
        }
        provisioner.provision(resource, props, new ProvisionContext(engine, "us-east-1", "000000000000",
                "stack", arn));
        when(service.listTags(any(), anyString())).thenReturn((ObjectNode) mapper.readTree("""
                {"Tags":[{"Key":"team","Value":"updated"},{"Key":"added","Value":"remove"}]}
                """));
    }

    private StackResource create() {
        return dispatcher.provision("Authority", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "test-stack");
    }
}

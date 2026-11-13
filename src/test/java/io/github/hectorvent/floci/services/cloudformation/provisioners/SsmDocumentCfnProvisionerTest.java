package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsmDocumentCfnProvisionerTest {

    private static final String NAME = "managed-document";
    private static final String REGION = "us-east-1";
    private static final String ORIGINAL = "{\"description\":\"original\"}";
    private static final String SECOND = "{\"description\":\"second\"}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SsmService ssm = mock(SsmService.class);
    private final SsmDocumentCfnProvisioner provisioner = new SsmDocumentCfnProvisioner(ssm);

    @Test
    void failedDefaultSelectionKeepsTheSnapshotUntilRollbackSucceeds() {
        SsmDocument original = new SsmDocument(NAME, ORIGINAL, "Command");
        SsmDocument second = secondVersion();
        when(ssm.getDocument(NAME, REGION)).thenReturn(original);
        when(ssm.updateDocument(NAME, SECOND, REGION)).thenReturn(second);
        when(ssm.updateDocumentDefaultVersion(NAME, "2", REGION)).thenThrow(unavailable());
        when(ssm.updateDocumentDefaultVersion(NAME, "1", REGION))
                .thenThrow(unavailable()).thenReturn(original);
        StackResource resource = resource(NAME);

        assertThrows(AwsException.class, () -> provisioner.provision(resource, properties(), context(NAME)));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        verify(ssm, never()).deleteDocument(anyString(), anyString());
    }

    @Test
    void retrySelectsTheAlreadyPublishedVersionWithoutPublishingAgain() {
        when(ssm.getDocument(NAME, REGION)).thenReturn(secondVersion());
        StackResource resource = resource(NAME);

        provisioner.provision(resource, properties(), context(NAME));

        verify(ssm, never()).updateDocument(anyString(), anyString(), anyString());
        verify(ssm).updateDocumentDefaultVersion(NAME, "2", REGION);
        provisioner.completeUpdate(resource);
        assertFalse(provisioner.retainsFailedUpdateState(resource));
    }

    @Test
    void updateDoesNotRecreateADocumentDeletedOutsideTheStack() {
        when(ssm.getDocument(NAME, REGION))
                .thenThrow(new AwsException("InvalidDocument", "gone", 400));
        StackResource resource = resource(NAME);

        assertThrows(AwsException.class, () -> provisioner.provision(resource, properties(), context(NAME)));

        assertFalse(provisioner.retainsFailedUpdateState(resource));
        verify(ssm, never()).createDocument(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void collisionDuringCreateLeavesNoOwnedIdentityOrDelete() {
        when(ssm.createDocument(NAME, SECOND, "Command", REGION))
                .thenThrow(new AwsException("DocumentAlreadyExists", "foreign", 400));
        StackResource resource = resource(null);

        assertThrows(AwsException.class, () -> provisioner.provision(resource, properties(), context(null)));

        assertNull(resource.getPhysicalId());
        verify(ssm, never()).getDocument(anyString(), anyString());
        verify(ssm, never()).deleteDocument(anyString(), anyString());
    }

    @Test
    void replacementDeletesTheOldDocumentOnlyAfterTheUpdateCommits() {
        StackResource resource = resource(NAME);
        ObjectNode properties = properties().put("Name", NAME + "-new");

        provisioner.provision(resource, properties, context(NAME));

        verify(ssm).createDocument(NAME + "-new", SECOND, "Command", REGION);
        verify(ssm, never()).deleteDocument(anyString(), anyString());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        provisioner.completeUpdate(resource);
        verify(ssm).deleteDocument(NAME, REGION);
    }

    @Test
    void deletionToleratesOnlyAnAlreadyMissingDocument() {
        doThrow(new AwsException("InvalidDocument", "gone", 400))
                .when(ssm).deleteDocument(NAME, REGION);
        provisioner.delete("AWS::SSM::Document", NAME, REGION);
        doThrow(unavailable()).when(ssm).deleteDocument(NAME, REGION);

        assertThrows(AwsException.class, () -> provisioner.delete("AWS::SSM::Document", NAME, REGION));
    }

    @Test
    void migrationFromAStubCreatesARealDocumentAndCanRollBackItsOwnership() {
        String stub = "Inventory-12345678";
        StackResource resource = resource(stub);
        resource.setAttributes(new HashMap<>(Map.of("Arn", "arn:aws:stub:::Inventory")));

        provisioner.provision(resource, properties(), context(stub));

        verify(ssm).createDocument(NAME, SECOND, "Command", REGION);
        verify(ssm, never()).getDocument(stub, REGION);
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(ssm).deleteDocument(NAME, REGION);
        verify(ssm, never()).deleteDocument(stub, REGION);
    }

    private static SsmDocument secondVersion() {
        SsmDocument document = new SsmDocument(NAME, ORIGINAL, "Command");
        document.setContent(SECOND);
        document.setDocumentVersion(2);
        document.getVersions().put("2", SECOND);
        return document;
    }

    private static ObjectNode properties() {
        ObjectNode properties = MAPPER.createObjectNode()
                .put("Name", NAME).put("DocumentType", "Command").put("UpdateMethod", "NewVersion");
        properties.putObject("Content").put("description", "second");
        return properties;
    }

    private static StackResource resource(String name) {
        StackResource resource = new StackResource();
        resource.setLogicalId("Inventory");
        resource.setResourceType("AWS::SSM::Document");
        resource.setPhysicalId(name);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private static ProvisionContext context(String priorName) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(call -> call.getArgument(0, JsonNode.class));
        return new ProvisionContext(engine, REGION, "000000000000", "stack", priorName);
    }

    private static AwsException unavailable() {
        return new AwsException("InternalServerError", "unavailable", 500);
    }
}

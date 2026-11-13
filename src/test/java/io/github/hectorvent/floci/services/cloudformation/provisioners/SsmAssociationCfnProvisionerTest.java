package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.SsmAssociation;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SsmAssociationCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ID = "9381a922-8299-4f94-9434-bb5272026d19";
    private final SsmService ssm = mock(SsmService.class);
    private final SsmAssociationCfnProvisioner provisioner = new SsmAssociationCfnProvisioner(ssm);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createSetsBothReferenceMechanisms() throws Exception {
        document();
        SsmAssociation association = association();
        when(ssm.createAssociation(eq("Inventory"), isNull(), eq("$DEFAULT"), isNull(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(REGION))).thenReturn(association);
        StackResource resource = resource();

        provisioner.provision(resource, properties(), context(null));

        assertEquals(ID, resource.getPhysicalId());
        assertEquals(ID, resource.getAttributes().get("AssociationId"));
    }

    @Test
    void executionWaitIsRefusedBeforeAnyBackingMutation() throws Exception {
        JsonNode props = mapper.readTree("""
                {"Name":"Inventory","Targets":[{"Key":"InstanceIds","Values":["*"]}],
                 "WaitForSuccessTimeoutSeconds":15}
                """);
        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, context(null)));
        verifyNoInteractions(ssm);
    }

    @Test
    void staleUpdateDoesNotRetainARollbackThatCouldOverwriteTheWinner() throws Exception {
        document();
        when(ssm.describeAssociation(ID, null, null, REGION)).thenReturn(association());
        when(ssm.updateAssociation(eq(ID), eq("Inventory"), isNull(), eq("$DEFAULT"), any(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq("1"), eq(REGION)))
                .thenThrow(new AwsException("InvalidAssociationVersion", "A concurrent update won.", 400));
        StackResource resource = resource();
        resource.setPhysicalId(ID);
        resource.getAttributes().put("AssociationId", ID);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties(), context(ID)));

        assertEquals("InvalidAssociationVersion", failure.getErrorCode());
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        assertFalse(provisioner.rollbackUpdate(resource));
        verify(ssm, times(1)).updateAssociation(eq(ID), eq("Inventory"), isNull(), eq("$DEFAULT"), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq("1"), eq(REGION));
    }

    @Test
    void deleteToleratesOnlyAnAlreadyMissingAssociation() {
        doThrow(new AwsException("AssociationDoesNotExist", "Gone", 400))
                .when(ssm).deleteAssociation(ID, null, null, REGION);
        assertDoesNotThrow(() -> provisioner.delete("AWS::SSM::Association", ID, REGION));

        doThrow(new AwsException("AccessDeniedException", "Denied", 403))
                .when(ssm).deleteAssociation(ID, null, null, REGION);
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::SSM::Association", ID, REGION));
        assertEquals("AccessDeniedException", failure.getErrorCode());
    }

    private void document() {
        SsmDocument document = new SsmDocument();
        document.setDocumentType("Command");
        when(ssm.getDocument("Inventory", REGION)).thenReturn(document);
    }

    private static SsmAssociation association() {
        SsmAssociation association = new SsmAssociation();
        association.setAssociationId(ID);
        association.setAssociationVersion("1");
        association.setName("Inventory");
        association.setDocumentVersion("$DEFAULT");
        association.setTargets(List.of(new SsmAssociation.Target("InstanceIds", List.of("*"))));
        return association;
    }

    private JsonNode properties() throws Exception {
        return mapper.readTree("""
                {"Name":"Inventory","Targets":[{"Key":"InstanceIds","Values":["*"]}]}
                """);
    }

    private ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ProvisionContext(engine, REGION, "840011110010", "inventory-stack", prior);
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::SSM::Association");
        resource.setLogicalId("Registration");
        resource.setAttributes(new HashMap<>());
        return resource;
    }
}

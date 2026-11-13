package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StepFunctionsActivityCfnProvisionerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-west-2";
    private static final String ACCOUNT = "867530900003";
    private static final String TYPE = "AWS::StepFunctions::Activity";
    private static final String OLD_ARN = "arn:aws:states:us-west-2:867530900003:activity:old";
    private static final String NEW_ARN = "arn:aws:states:us-west-2:867530900003:activity:new";

    private StepFunctionsService service;
    private StepFunctionsActivityCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;
    private StackResource resource;

    @BeforeEach
    void setUp() {
        service = mock(StepFunctionsService.class);
        provisioner = new StepFunctionsActivityCfnProvisioner(service);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(call -> {
            JsonNode node = call.getArgument(0);
            return node == null || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        resource = new StackResource();
        resource.setResourceType(TYPE);
        resource.setLogicalId("Worker");
    }

    @Test
    void createPublishesBothReferenceMechanisms() throws Exception {
        when(service.createActivity(eq("new"), eq(REGION), anyMap())).thenReturn(activity("new", NEW_ARN));
        provisioner.provision(resource, props("new"), context(null));
        assertEquals(NEW_ARN, resource.getPhysicalId());
        assertEquals(NEW_ARN, resource.getAttributes().get("Arn"));
        assertEquals("new", resource.getAttributes().get("Name"));
        verify(service).createActivity("new", REGION, Map.of("marker", "new"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"Name\":\"\"}", "{\"Name\":\" \"}",
            "{\"Name\":\"new\",\"EncryptionConfiguration\":{\"Type\":\"CUSTOMER_MANAGED_KMS_KEY\"}}"})
    void invalidOrUnsupportedConfigurationDoesNotCreateAnything(String properties) throws Exception {
        assertThrows(AwsException.class, () ->
                provisioner.provision(resource, MAPPER.readTree(properties), context(null)));
        verifyNoInteractions(service);
    }

    @Test
    void tagUpdateAndRollbackKeepTheOriginalActivity() throws Exception {
        existing();
        provisioner.provision(resource, props("old"), context(OLD_ARN));
        assertEquals(OLD_ARN, resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        verify(service).replaceActivityTags(OLD_ARN, Map.of("marker", "new"));
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(service).replaceActivityTags(OLD_ARN, Map.of("marker", "old"));
        verify(service, never()).deleteActivity(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void renameDefersOldActivityDeletionUntilCommitAndHonorsRetain(boolean retain) throws Exception {
        existing();
        resource.setUpdateReplacePolicy(retain ? "Retain" : "Delete");
        when(service.createActivity(eq("new"), eq(REGION), anyMap())).thenReturn(activity("new", NEW_ARN));
        provisioner.provision(resource, props("new"), context(OLD_ARN));
        assertEquals(NEW_ARN, resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        verify(service, never()).deleteActivity(OLD_ARN);
        provisioner.completeUpdate(resource);
        if (retain) {
            verify(service, never()).deleteActivity(OLD_ARN);
        } else {
            verify(service).deleteActivity(OLD_ARN);
        }
    }

    @Test
    void replacementRollbackRestoresReferencesAndRemovesOnlyTheNewActivity() throws Exception {
        existing();
        when(service.createActivity(eq("new"), eq(REGION), anyMap())).thenReturn(activity("new", NEW_ARN));
        provisioner.provision(resource, props("new"), context(OLD_ARN));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(OLD_ARN, resource.getPhysicalId());
        assertEquals(OLD_ARN, resource.getAttributes().get("Arn"));
        assertEquals("old", resource.getAttributes().get("Name"));
        verify(service).deleteActivity(NEW_ARN);
        verify(service, never()).deleteActivity(OLD_ARN);
    }

    @Test
    void collidingNameDoesNotDeleteOrAdoptAnotherActivity() throws Exception {
        existing();
        AwsException collision = new AwsException("ActivityAlreadyExists", "already owned", 400);
        when(service.createActivity(eq("new"), eq(REGION), anyMap())).thenThrow(collision);
        assertSame(collision, assertThrows(AwsException.class,
                () -> provisioner.provision(resource, props("new"), context(OLD_ARN))));
        assertEquals(OLD_ARN, resource.getPhysicalId());
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(service, never()).deleteActivity(any());
    }

    @Test
    void deleteOnlyToleratesAnAlreadyMissingActivity() {
        doThrow(new AwsException("ActivityDoesNotExist", "gone", 400)).when(service).deleteActivity(OLD_ARN);
        provisioner.delete(TYPE, OLD_ARN, REGION);
        AwsException denied = new AwsException("AccessDeniedException", "denied", 403);
        doThrow(denied).when(service).deleteActivity(NEW_ARN);
        assertSame(denied, assertThrows(AwsException.class, () -> provisioner.delete(TYPE, NEW_ARN, REGION)));
    }

    private void existing() {
        resource.setPhysicalId(OLD_ARN);
        resource.getAttributes().put("Arn", OLD_ARN);
        resource.getAttributes().put("Name", "old");
        when(service.describeActivity(OLD_ARN)).thenReturn(activity("old", OLD_ARN));
    }

    private static Activity activity(String name, String arn) {
        Activity activity = new Activity();
        activity.setName(name);
        activity.setActivityArn(arn);
        activity.setTags(Map.of("marker", "old"));
        return activity;
    }

    private static JsonNode props(String name) throws Exception {
        return MAPPER.readTree("""
                {"Name":"%s","Tags":[{"Key":"marker","Value":"new"}]}
                """.formatted(name));
    }

    private ProvisionContext context(String priorArn) {
        return new ProvisionContext(engine, REGION, ACCOUNT, "stack", priorArn);
    }
}

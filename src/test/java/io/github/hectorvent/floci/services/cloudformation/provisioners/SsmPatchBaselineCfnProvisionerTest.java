package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmPatchBaselineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SsmPatchBaselineCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ID = "pb-00000000000000001";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SsmPatchBaselineService baselines = mock(SsmPatchBaselineService.class);
    private final SsmPatchBaselineCfnProvisioner provisioner = new SsmPatchBaselineCfnProvisioner(baselines);

    @Test
    void convertsSchemaTypesAndSetsBothReferenceMechanisms() throws Exception {
        when(baselines.create(any(), eq(REGION))).thenReturn(address());
        ObjectNode props = (ObjectNode) MAPPER.readTree("""
                {
                  "Name": "example",
                  "DefaultBaseline": "true",
                  "ApprovedPatchesEnableNonSecurity": "false",
                  "PatchGroups": ["workers"],
                  "ApprovalRules": {"PatchRules": [{
                    "ApproveAfterDays": "7",
                    "PatchFilterGroup": {"PatchFilters": [{"Key": "CLASSIFICATION", "Values": ["Security"]}]}
                  }]}
                }
                """);
        StackResource resource = resource();

        provisioner.provision(resource, props, context(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(baselines).create(request.capture(), eq(REGION));
        assertTrue(request.getValue().path("ApprovedPatchesEnableNonSecurity").isBoolean());
        assertFalse(request.getValue().path("ApprovedPatchesEnableNonSecurity").asBoolean());
        assertEquals(7, request.getValue().path("ApprovalRules").path("PatchRules")
                .get(0).path("ApproveAfterDays").intValue());
        assertFalse(request.getValue().has("PatchGroups"));
        assertFalse(request.getValue().has("DefaultBaseline"));
        verify(baselines).registerGroup(address().put("PatchGroup", "workers"), REGION);
        verify(baselines).registerDefault(address(), REGION);
        assertEquals(ID, resource.getPhysicalId());
        assertEquals(ID, resource.getAttributes().get("Id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1"})
    void invalidBooleansCannotSilentlyBecomeFalse(String value) {
        ObjectNode props = MAPPER.createObjectNode().put("Name", "example").put("DefaultBaseline", value);

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, context(null)));

        assertEquals("InvalidRequest", error.getErrorCode());
        assertTrue(error.getMessage().contains("#/DefaultBaseline"));
        verifyNoInteractions(baselines);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Key", "Value"})
    void tagsRequireBothFieldsBeforeAnyBackingResourceIsCreated(String missing) {
        ObjectNode props = MAPPER.createObjectNode().put("Name", "example");
        ObjectNode tag = props.putArray("Tags").addObject().put("Key", "purpose").put("Value", "example");
        tag.remove(missing);

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, context(null)));

        assertEquals("InvalidRequest", error.getErrorCode());
        verifyNoInteractions(baselines);
    }

    @Test
    void deleteDeregistersEveryCurrentGroupBeforeDeletingTheBaseline() {
        ObjectNode current = address();
        current.putArray("PatchGroups").add("one").add("two");
        when(baselines.get(address(), REGION)).thenReturn(current);

        provisioner.delete("AWS::SSM::PatchBaseline", ID, REGION);

        InOrder calls = inOrder(baselines);
        calls.verify(baselines).get(address(), REGION);
        calls.verify(baselines).deregisterGroup(address().put("PatchGroup", "one"), REGION);
        calls.verify(baselines).deregisterGroup(address().put("PatchGroup", "two"), REGION);
        calls.verify(baselines).delete(address(), REGION);
    }

    @Test
    void deleteAcceptsOnlyAnAlreadyMissingBaseline() {
        when(baselines.get(address(), REGION)).thenThrow(new AwsException("DoesNotExistException", "gone", 400));

        provisioner.delete("AWS::SSM::PatchBaseline", ID, REGION);

        verify(baselines, never()).delete(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AccessDeniedException", "InternalServerError", "ResourceInUseException"})
    void deletePropagatesFailuresThatDoNotMeanTheBaselineIsGone(String code) {
        AwsException failure = new AwsException(code, "still present", 400);
        when(baselines.get(address(), REGION)).thenThrow(failure);

        assertSame(failure, assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::SSM::PatchBaseline", ID, REGION)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedGroupRegistrationCleansUpOrRetainsOwnershipForRetry(boolean cleanupFails) {
        when(baselines.create(any(), eq(REGION))).thenReturn(address());
        ObjectNode current = address();
        current.putArray("PatchGroups").add("one");
        when(baselines.get(address(), REGION)).thenReturn(current);
        AwsException conflict = new AwsException("AlreadyExistsException", "two is occupied", 400);
        when(baselines.registerGroup(argThat(value -> "two".equals(value.path("PatchGroup").asText())), eq(REGION)))
                .thenThrow(conflict);
        if (cleanupFails) {
            when(baselines.delete(address(), REGION))
                    .thenThrow(new AwsException("InternalServerError", "retry deletion", 500));
        }
        ObjectNode props = MAPPER.createObjectNode().put("Name", "example");
        props.putArray("PatchGroups").add("one").add("two");
        StackResource resource = resource();

        assertSame(conflict, assertThrows(AwsException.class,
                () -> provisioner.provision(resource, props, context(null))));

        verify(baselines).deregisterGroup(address().put("PatchGroup", "one"), REGION);
        verify(baselines).delete(address(), REGION);
        if (cleanupFails) {
            assertEquals("true", resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
            assertEquals(1, conflict.getSuppressed().length);
        } else {
            assertNull(resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
            assertEquals(0, conflict.getSuppressed().length);
        }
    }

    @Test
    void failedRollbackKeepsItsSnapshotUntilARetrySucceeds() {
        when(baselines.create(any(), eq(REGION))).thenReturn(address());
        ObjectNode current = address().put("Name", "example").put("OperatingSystem", "WINDOWS");
        current.putArray("PatchGroups");
        when(baselines.get(address(), REGION)).thenReturn(current);
        when(baselines.resourceTags(eq("ListTagsForResource"), any(), eq(REGION)))
                .thenReturn(MAPPER.createObjectNode());
        AwsException failure = new AwsException("InternalServerError", "retry restoration", 500);
        when(baselines.update(any(), eq(REGION))).thenReturn(current).thenThrow(failure).thenReturn(current);
        StackResource resource = resource();
        ObjectNode props = MAPPER.createObjectNode().put("Name", "example");
        provisioner.provision(resource, props, context(null));
        props.put("Description", "changed");
        provisioner.provision(resource, props, context(ID));

        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertSame(failure, assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource)));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertTrue(provisioner.requiresUpdateRecovery(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        assertFalse(provisioner.requiresUpdateRecovery(resource));
        assertEquals(ID, resource.getAttributes().get("Id"));
        verify(baselines, never()).delete(any(), any());
    }

    private static ObjectNode address() {
        return MAPPER.createObjectNode().put("BaselineId", ID);
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::SSM::PatchBaseline");
        resource.setLogicalId("Baseline");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private static ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.omitNoValue(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(invocation -> strings(invocation.getArgument(0)));
        when(engine.resolveLiteralList(any())).thenAnswer(invocation -> strings(invocation.getArgument(0)));
        return new ProvisionContext(engine, REGION, "830011110046", "baseline-stack", prior);
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}

package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmResourceDataSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SsmResourceDataSyncCfnProvisionerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TYPE = "AWS::SSM::ResourceDataSync";
    private static final String REGION = "us-east-1";
    private final SsmResourceDataSyncService syncs = mock(SsmResourceDataSyncService.class);
    private final List<Long> callbacks = new ArrayList<>();
    private final SsmResourceDataSyncCfnProvisioner provisioner =
            new SsmResourceDataSyncCfnProvisioner(syncs, callbacks::add);

    @ParameterizedTest
    @ValueSource(strings = {"Successful", "InProgress"})
    void translatesDestinationFieldsAndAcceptsTheProvidersSupportedStatuses(String status) {
        when(syncs.list(any(), eq(REGION))).thenReturn(list("inventory", status));
        StackResource resource = resource();

        provisioner.provision(resource, properties(), context(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(syncs).create(request.capture(), eq(REGION));
        assertEquals("us-east-1", request.getValue().path("S3Destination").path("Region").asText());
        assertEquals("reports", request.getValue().path("S3Destination").path("Prefix").asText());
        assertFalse(request.getValue().has("BucketRegion"));
        assertEquals("inventory", resource.getPhysicalId());
        assertEquals(List.of(2L), callbacks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SyncSource", "Tags", "Unexpected"})
    void unsupportedPropertiesFailBeforeCreatingAnything(String field) {
        ObjectNode props = properties();
        if ("SyncSource".equals(field)) {
            props.putObject(field).put("SourceType", "AwsRegions").putArray("SourceRegions").add(REGION);
        } else {
            props.putArray(field);
        }

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, context(null)));

        assertEquals("InvalidRequest", error.getErrorCode());
        verifyNoInteractions(syncs);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedAdmissionDeletesOnlyItsNewSyncAndRetainsFailedCleanupForRetry(boolean cleanupFails) {
        when(syncs.list(any(), eq(REGION))).thenReturn(list("inventory", "Failed"));
        if (cleanupFails) {
            doThrow(new AwsException("InternalServerError", "retry deletion", 500))
                    .when(syncs).delete(any(), eq(REGION));
        }
        StackResource resource = resource();

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties(), context(null)));

        assertEquals("NotStabilized", error.getErrorCode());
        assertTrue(error.getMessage().contains("denied destination"));
        verify(syncs).delete(argThat(request -> "inventory".equals(request.path("SyncName").asText())), eq(REGION));
        if (cleanupFails) {
            assertEquals("true", resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
            assertEquals(1, error.getSuppressed().length);
        } else {
            assertNull(resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        }
    }

    @Test
    void missingAdmissionStatusHasBoundedCallbacksAndRemovesTheCreatedSync() {
        ObjectNode empty = JSON.createObjectNode();
        empty.putArray("ResourceDataSyncItems");
        when(syncs.list(any(), eq(REGION))).thenReturn(empty);

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), properties(), context(null)));

        assertEquals("NotStabilized", error.getErrorCode());
        assertEquals(List.of(2L, 10L, 10L, 10L, 10L, 10L, 10L), callbacks);
        verify(syncs, times(6)).list(any(), eq(REGION));
        verify(syncs).delete(any(), eq(REGION));
    }

    @Test
    void interruptedAdmissionPreservesCancellationAndCleansUp() {
        SsmResourceDataSyncCfnProvisioner waiting = new SsmResourceDataSyncCfnProvisioner(syncs);
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class,
                    () -> waiting.provision(resource(), properties(), context(null)));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(syncs).delete(any(), eq(REGION));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void duplicateCreationDoesNotDeleteTheExistingSync() {
        AwsException conflict = new AwsException("ResourceDataSyncAlreadyExistsException", "already exists", 400);
        doThrow(conflict).when(syncs).create(any(), eq(REGION));

        assertSame(conflict, assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), properties(), context(null))));

        verify(syncs, never()).delete(any(), any());
        assertTrue(callbacks.isEmpty());
    }

    @Test
    void unchangedUpdateDoesNotRecreateAnExternallyRemovedSync() {
        when(syncs.list(any(), eq(REGION))).thenReturn(list("inventory", "Successful"))
                .thenReturn(JSON.createObjectNode());
        StackResource resource = resource();
        provisioner.provision(resource, properties(), context(null));

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties(), context("inventory")));

        assertEquals("ResourceDataSyncNotFoundException", error.getErrorCode());
        verify(syncs, times(1)).create(any(), eq(REGION));
        verify(syncs, never()).delete(any(), any());
    }

    @Test
    void failedReplacementCleanupRestoresOriginalInputsBeforeRetrying() {
        when(syncs.list(any(), eq(REGION))).thenReturn(list("inventory", "Successful"),
                list("replacement", "Successful"), list("inventory", "Successful"));
        StackResource resource = resource();
        provisioner.provision(resource, properties(), context(null));
        provisioner.provision(resource, properties().put("SyncName", "replacement")
                .put("BucketPrefix", "replacement"), context("inventory"));
        AwsException failure = new AwsException("InternalServerError", "retry cleanup", 500);
        doThrow(failure).doNothing().when(syncs).delete(
                argThat(request -> "replacement".equals(request.path("SyncName").asText())), eq(REGION));

        assertSame(failure, assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource)));
        assertEquals("inventory", resource.getPhysicalId());
        assertTrue(provisioner.rollbackUpdate(resource));
        provisioner.provision(resource, properties(), context("inventory"));

        assertEquals("inventory", resource.getPhysicalId());
        verify(syncs, times(2)).create(any(), eq(REGION));
        verify(syncs, times(2)).delete(any(), eq(REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AccessDeniedException", "InternalServerError"})
    void deletionPropagatesFailuresOtherThanAnAlreadyMissingSync(String code) {
        AwsException failure = new AwsException(code, "cannot delete", 500);
        doThrow(failure).when(syncs).delete(any(), eq(REGION));

        assertSame(failure, assertThrows(AwsException.class,
                () -> provisioner.delete(TYPE, "inventory", REGION)));
    }

    @Test
    void deletionAcceptsAnAlreadyMissingSync() {
        doThrow(new AwsException("ResourceDataSyncNotFoundException", "gone", 400))
                .when(syncs).delete(any(), eq(REGION));

        provisioner.delete(TYPE, "inventory", REGION);
    }

    private static ObjectNode properties() {
        return JSON.createObjectNode().put("SyncName", "inventory").put("BucketName", "inventory-bucket")
                .put("BucketRegion", REGION).put("SyncFormat", "JsonSerDe").put("BucketPrefix", "reports");
    }

    private static ObjectNode list(String name, String status) {
        ObjectNode result = JSON.createObjectNode();
        result.putArray("ResourceDataSyncItems").addObject().put("SyncName", name).put("LastStatus", status)
                .put("LastSyncStatusMessage", "denied destination");
        return result;
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setResourceType(TYPE);
        resource.setLogicalId("Sync");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private static ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.omitNoValue(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.resolveLiteralList(any())).thenAnswer(invocation -> List.of(REGION));
        return new ProvisionContext(engine, REGION, "830033330000", "inventory-stack", prior);
    }
}

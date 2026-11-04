package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WaitConditionCfnProvisionerTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String BUCKET = "floci-internal-cfn-wait-owned";
    private static final String HANDLE = "http://localhost:4566/" + BUCKET + "/signals?signature=owned";
    private final ObjectMapper mapper = new ObjectMapper();
    private final S3Service s3 = mock(S3Service.class);
    private final ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
    private final WaitConditionCfnProvisioner provisioner = new WaitConditionCfnProvisioner(s3, mapper, endpoint);

    @BeforeEach
    void ownHandle() {
        when(endpoint.baseUrl(false)).thenReturn("http://localhost:4566");
        when(s3.isServiceOwnedBucket(BUCKET)).thenReturn(true);
        when(s3.getBucketOwnerAccountId(BUCKET)).thenReturn(ACCOUNT);
        when(s3.getBucketRegion(BUCKET)).thenReturn(REGION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"Handle\":\"x\"}", "{\"Count\":-1,\"Timeout\":\"1\"}",
            "{\"Timeout\":\"0\"}", "{\"Timeout\":\"43201\"}", "{\"Timeout\":\"1.5\"}"})
    void invalidCountsAndDeadlinesNeverReadSignalStorage(String json) throws Exception {
        assertThrows(AwsException.class, () -> provisioner.provision(resource(), mapper.readTree(json), context(null)));
        verifyNoInteractions(s3, endpoint);
    }

    @Test
    void zeroCountCompletesWithoutReadingSignals() {
        StackResource resource = resource();
        provisioner.provision(resource, properties(0), context(null));
        assertEquals("{}", resource.getAttributes().get("Data"));
        assertEquals(resource.getPhysicalId(), resource.getAttributes().get("Id"));
        assertTrue(provisioner.attributeValue(resource.getResourceType(), "Data", "{}").isTextual());
        verify(s3, never()).listObjectVersions(BUCKET, "signals", null, 1000, null, null);
    }

    @Test
    void versionsAreDeduplicatedBySignalIdInPersistedWriteOrderAcrossPages() throws Exception {
        S3Object first = signalVersion("first", "f", "same", "old");
        S3Object updated = signalVersion("updated", "10", "same", "new");
        S3Object second = signalVersion("second", "11", "other", "other");
        when(s3.listObjectVersions(BUCKET, "signals", null, 1000, null, null))
                .thenReturn(new S3Service.ListVersionsResult(List.of(second, updated), List.of(),
                        true, "signals", "updated"));
        when(s3.listObjectVersions(BUCKET, "signals", null, 1000, "signals", "updated"))
                .thenReturn(new S3Service.ListVersionsResult(List.of(first), List.of(), false, null, null));
        StackResource resource = resource();
        provisioner.provision(resource, properties(2), context(null));
        assertEquals(mapper.valueToTree(Map.of("same", "old", "other", "other")),
                mapper.readTree(resource.getAttributes().get("Data")));
    }

    @Test
    void requiredCountCompletesBeforeLaterSignalsCanChangeTheResult() throws Exception {
        S3Object first = signalVersion("first", "f", "first", "ready");
        S3Object later = signalVersion("later", "10", "later", "too late", "FAILURE");
        when(s3.listObjectVersions(BUCKET, "signals", null, 1000, null, null))
                .thenReturn(new S3Service.ListVersionsResult(List.of(later, first), List.of(), false, null, null));
        StackResource resource = resource();
        provisioner.provision(resource, properties(1), context(null));
        assertEquals(mapper.valueToTree(Map.of("first", "ready")),
                mapper.readTree(resource.getAttributes().get("Data")));
        verify(s3, never()).getObject(BUCKET, "signals", "later");
    }

    @Test
    void foreignEndpointAndAccountHandlesCannotReadStorage() {
        ObjectNode foreignEndpoint = properties(1).put("Handle",
                "http://metadata.invalid/" + BUCKET + "/signals?signature=owned");
        assertThrows(AwsException.class, () -> provisioner.provision(resource(), foreignEndpoint, context(null)));
        verifyNoInteractions(s3);
        when(s3.getBucketOwnerAccountId(BUCKET)).thenReturn("222222222222");
        assertThrows(AwsException.class, () -> provisioner.provision(resource(), properties(1), context(null)));
        verify(s3, never()).getObject(anyString(), anyString(), anyString());
    }

    @Test
    void failedUpdateLeavesThePriorResultUntouched() {
        StackResource resource = resource();
        provisioner.provision(resource, properties(0), context(null));
        String id = resource.getPhysicalId();
        Map<String, String> attributes = new HashMap<>(resource.getAttributes());
        clearInvocations(s3, endpoint);
        assertThrows(AwsException.class, () -> provisioner.provision(resource(), properties(1), context(id)));
        assertEquals(id, resource.getPhysicalId());
        assertEquals(attributes, resource.getAttributes());
        assertTrue(provisioner.rollbackUpdate(resource));
        verifyNoInteractions(s3, endpoint);
    }

    @Test
    void interruptionStopsAWaitingResourceAndPreservesTheInterrupt() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(s3.listObjectVersions(BUCKET, "signals", null, 1000, null, null)).thenAnswer(invocation -> {
            reading.countDown();
            return new S3Service.ListVersionsResult(List.of(), List.of(), false, null, null);
        });
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                provisioner.provision(resource(), properties(1).put("Timeout", "43200"), context(null));
            } catch (IllegalStateException failure) {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertTrue(reading.await(3, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertTrue(interrupted.get());
        } finally {
            worker.interrupt();
            worker.join(3000);
        }
    }

    private ObjectNode properties(int count) {
        return mapper.createObjectNode().put("Handle", HANDLE).put("Count", count).put("Timeout", "1");
    }

    private S3Object signalVersion(String version, String sequence, String id, String data) {
        return signalVersion(version, sequence, id, data, "SUCCESS");
    }

    private S3Object signalVersion(String version, String sequence, String id, String data, String status) {
        byte[] body = mapper.createObjectNode().put("Status", status).put("UniqueId", id)
                .put("Data", data).put("Reason", "configured").toString().getBytes(StandardCharsets.UTF_8);
        S3Object object = new S3Object(BUCKET, "signals", body, "application/json");
        object.setVersionId(version);
        object.setEventSequencer(sequence);
        when(s3.getObject(BUCKET, "signals", version)).thenReturn(object);
        return object;
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Wait");
        resource.setResourceType("AWS::CloudFormation::WaitCondition");
        return resource;
    }

    private ProvisionContext context(String prior) {
        return new ProvisionContext(CloudFormationTemplateEngine.standalone(
                ACCOUNT, REGION, "stack", mapper, UnaryOperator.identity()), REGION, ACCOUNT, "stack", prior);
    }
}

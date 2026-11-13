package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.PreSignedUrlGenerator;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WaitConditionHandleCfnProvisionerTest {
    private final S3Service s3 = mock(S3Service.class);
    private final PreSignedUrlGenerator presigner = mock(PreSignedUrlGenerator.class);
    private final ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
    private final WaitConditionHandleCfnProvisioner provisioner =
            new WaitConditionHandleCfnProvisioner(s3, presigner, endpoint);

    @Test
    void unsupportedPropertiesCreateNothing() {
        assertThrows(AwsException.class, () -> provisioner.provision(resource(),
                new ObjectMapper().createObjectNode().put("Timeout", 1), context(null)));
        verifyNoInteractions(s3, presigner, endpoint);
    }

    @Test
    void unchangedHandleKeepsItsUrlAndSignalsDuringUpdateAndRollback() {
        StackResource resource = resource();
        provisioner.provision(resource, null, context("http://localhost/old?signature=old"));
        assertEquals("http://localhost/old?signature=old", resource.getPhysicalId());
        assertEquals(resource.getPhysicalId(), resource.getAttributes().get("Id"));
        assertTrue(provisioner.rollbackUpdate(resource));
        verifyNoInteractions(s3, presigner, endpoint);
    }

    @Test
    void failedSigningUnwindsOnlyTheBucketItCreated() {
        when(endpoint.baseUrl(false)).thenReturn("http://localhost:4566");
        RuntimeException failure = new IllegalStateException("signing failed");
        when(presigner.generatePresignedUrl(anyString(), anyString(), eq("signals"),
                eq("PUT"), anyInt(), eq("us-east-1"), eq("111111111111"))).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> provisioner.provision(resource(), null, context(null))));
        verify(s3).deleteBucket(anyString());
    }

    @Test
    void aFailedCreateDoesNotDeleteAnExistingBucket() {
        when(s3.createServiceOwnedBucket(anyString(), anyString()))
                .thenThrow(new AwsException("BucketAlreadyOwnedByYou", "existing", 409));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(), null, context(null)));
        verify(s3, never()).deleteBucket(anyString());
        verifyNoInteractions(presigner, endpoint);
    }

    @Test
    void deletionReadsEveryVersionPageAndPreservesUnrelatedObjects() {
        StackResource resource = backedResource();
        when(s3.isServiceOwnedBucket("owned")).thenReturn(true);
        when(s3.listObjectVersions("owned", "signals", null, 1000, null, null))
                .thenReturn(new S3Service.ListVersionsResult(List.of(version("signals", "v2")),
                        List.of(), true, "signals", "v2"));
        when(s3.listObjectVersions("owned", "signals", null, 1000, "signals", "v2"))
                .thenReturn(new S3Service.ListVersionsResult(List.of(version("signals", "v1"),
                        version("signals-other", "foreign")), List.of(), false, null, null));
        AwsException notEmpty = new AwsException("BucketNotEmpty", "unrelated object", 409);
        doThrow(notEmpty).when(s3).deleteBucket("owned");
        assertSame(notEmpty, assertThrows(AwsException.class, () -> provisioner.delete(resource, "us-east-1")));
        verify(s3).deleteObject("owned", "signals", "v1");
        verify(s3).deleteObject("owned", "signals", "v2");
        verify(s3, never()).deleteObject("owned", "signals-other", "foreign");
    }

    @Test
    void deletingAMissingOrCustomerRecreatedBucketIsSafe() {
        provisioner.delete(backedResource(), "us-east-1");
        verify(s3, never()).deleteBucket(anyString());
    }

    private static S3Object version(String key, String version) {
        S3Object object = new S3Object();
        object.setKey(key);
        object.setVersionId(version);
        return object;
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Handle");
        resource.setResourceType("AWS::CloudFormation::WaitConditionHandle");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private static StackResource backedResource() {
        StackResource resource = resource();
        resource.getAttributes().put(WaitConditionHandleCfnProvisioner.BUCKET_ATTRIBUTE, "owned");
        resource.getAttributes().put(WaitConditionHandleCfnProvisioner.KEY_ATTRIBUTE, "signals");
        return resource;
    }

    private static ProvisionContext context(String prior) {
        return new ProvisionContext(null, "us-east-1", "111111111111", "stack", prior);
    }
}

package io.floci.aws.services.elb;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.elb.model.ClassicListener;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.floci.aws.testing.FileSystemAssumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.*;

class ElbClassicDiskFailureTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @TempDir
    Path directory;

    @Test
    void failedUpdateRetainsDurableAndLiveDeclaration() throws IOException {
        verifyFailedWrite(service -> service.registerInstances(REGION, "web", List.of("i-new")));
    }

    @Test
    void failedDeleteRetainsDurableAndLiveDeclaration() throws IOException {
        verifyFailedWrite(service -> service.deleteLoadBalancer(REGION, "web"));
    }

    @Test
    void failedCreateDoesNotPublishUndurableDeclaration() throws IOException {
        verifyFailedWrite(service -> create(service, "new-web"));
    }

    @Test
    void successfulUpdatesAndDeletionSurviveDiskReload() {
        ElbClassicService original = service();
        create(original, "web");
        original.registerInstances(REGION, "web", List.of("i-new"));
        ElbClassicService reloaded = service();
        assertEquals(List.of("i-new"), reloaded.requireLoadBalancer(REGION, "web").getInstanceIds());
        reloaded.deleteLoadBalancer(REGION, "web");
        assertTrue(service().describeLoadBalancers(REGION, null).isEmpty());
    }

    @Test
    void failedFrontendStartAndRollbackDoNotRestoreRejectedCreate() throws IOException {
        FileSystemAssumptions.assumePosixPermissions(directory);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
        ElbClassicFrontendManager frontends = mock(ElbClassicFrontendManager.class);
        IllegalStateException startupFailure = new IllegalStateException("frontend startup failed");
        doAnswer(invocation -> {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
            assumeFalse(Files.isWritable(directory), "root bypasses the write failure");
            throw startupFailure;
        }).when(frontends).reconcile(any());
        ElbClassicService original = service(frontends);
        try {
            assertSame(startupFailure, assertThrows(IllegalStateException.class, () -> create(original, "web")));
            assertEquals(0, startupFailure.getSuppressed().length, "startup failure needs no disk compensation");
            assertTrue(original.describeLoadBalancers(REGION, null).isEmpty());
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
        assertTrue(service().describeLoadBalancers(REGION, null).isEmpty(),
                "fresh storage must not restore a create rejected after frontend startup failure");
    }

    @Test
    void failedFrontendStartWithSuccessfulRollbackStaysAbsentAfterReload() {
        ElbClassicFrontendManager frontends = mock(ElbClassicFrontendManager.class);
        IllegalStateException startupFailure = new IllegalStateException("frontend startup failed");
        doThrow(startupFailure).when(frontends).reconcile(any());
        ElbClassicService original = service(frontends);
        assertSame(startupFailure, assertThrows(IllegalStateException.class, () -> create(original, "web")));
        assertEquals(0, startupFailure.getSuppressed().length);
        assertTrue(original.describeLoadBalancers(REGION, null).isEmpty());
        assertTrue(service().describeLoadBalancers(REGION, null).isEmpty());
    }

    @Test
    void failedCommitRemovesStartedFrontendAndRemainsAbsentOnDisk() throws IOException {
        verifyFailedFrontendCommit(false);
    }

    @Test
    void failedCommitRetainsFrontendCleanupFailureAndRemainsAbsentOnDisk() throws IOException {
        verifyFailedFrontendCommit(true);
    }

    private void verifyFailedFrontendCommit(boolean cleanupFails) throws IOException {
        FileSystemAssumptions.assumePosixPermissions(directory);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
        ElbClassicFrontendManager frontends = mock(ElbClassicFrontendManager.class);
        IllegalStateException cleanupFailure = new IllegalStateException("frontend cleanup failed");
        if (cleanupFails) {
            doThrow(cleanupFailure).when(frontends).remove(any());
        }
        ElbClassicService original = service(frontends);
        doAnswer(invocation -> {
            assertTrue(original.describeLoadBalancers(REGION, null).isEmpty());
            assertTrue(service().describeLoadBalancers(REGION, null).isEmpty());
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
            assumeFalse(Files.isWritable(directory), "root bypasses the write failure");
            return true;
        }).when(frontends).reconcile(any());
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> create(original, "web"));
            verify(frontends).remove(any());
            assertEquals(cleanupFails, List.of(failure.getSuppressed()).contains(cleanupFailure));
            assertTrue(original.describeLoadBalancers(REGION, null).isEmpty());
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
        assertTrue(service().describeLoadBalancers(REGION, null).isEmpty());
    }

    private void verifyFailedWrite(Consumer<ElbClassicService> mutation) throws IOException {
        FileSystemAssumptions.assumePosixPermissions(directory);
        ElbClassicService original = service();
        create(original, "web");
        Path snapshot = directory.resolve("elb-load-balancers.json");
        byte[] committed = Files.readAllBytes(snapshot);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
        RuntimeException failure = null;
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
            assumeFalse(Files.isWritable(directory), "root bypasses the write failure");
            try {
                mutation.accept(original);
            } catch (RuntimeException expected) {
                // Retain the real storage error for assertions after permissions are restored.
                failure = expected;
            }
            assertArrayEquals(committed, Files.readAllBytes(snapshot));
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
        ElbClassicService reloaded = service();
        RuntimeException observedFailure = failure;
        assertAll(
                () -> assertNotNull(observedFailure, "a failed persistent write must reach service compensation"),
                () -> assertEquals(List.of("web"), original.describeLoadBalancers(REGION, null).stream()
                        .map(ClassicLoadBalancer::getLoadBalancerName).toList()),
                () -> assertTrue(original.requireLoadBalancer(REGION, "web").getInstanceIds().isEmpty()),
                () -> assertEquals(List.of("web"), reloaded.describeLoadBalancers(REGION, null).stream()
                        .map(ClassicLoadBalancer::getLoadBalancerName).toList()),
                () -> assertTrue(reloaded.requireLoadBalancer(REGION, "web").getInstanceIds().isEmpty()));
    }

    private ElbClassicService service() {
        return service(null);
    }

    private ElbClassicService service(ElbClassicFrontendManager frontends) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess services = mock(ServiceConfigAccess.class);
        when(services.storageMode("elb")).thenReturn("persistent");
        StorageFactory factory = new StorageFactory(config, services);
        ElbClassicService service = new ElbClassicService(mock(Ec2Service.class),
                mock(ElbClassicHealthChecker.class), factory, null, null, null, null, frontends);
        service.initializeStorage();
        return service;
    }

    private static ClassicLoadBalancer create(ElbClassicService service, String name) {
        ClassicListener listener = new ClassicListener();
        listener.setProtocol("HTTP");
        listener.setLoadBalancerPort(80);
        listener.setInstanceProtocol("HTTP");
        listener.setInstancePort(8080);
        return service.createLoadBalancer(REGION, name, List.of(listener), List.of(),
                List.of(), List.of(), "internet-facing", Map.of());
    }
}

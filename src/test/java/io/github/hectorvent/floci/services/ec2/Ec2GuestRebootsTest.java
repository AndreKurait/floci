package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2GuestRebootsTest {
    private static final String CONTAINER = "owned-container";
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final InspectContainerResponse inspection = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final List<Runnable> queued = new ArrayList<>();
    private final List<Instance> restarted = new ArrayList<>();
    private final Instance instance = new Instance();
    private final Map<String, String> labels = new HashMap<>();
    private Ec2GuestReboots reboots;

    @BeforeEach
    void setup() {
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("owner"));
        instance.setInstanceId("i-owned");
        instance.setOwnerId("123456789012");
        instance.setRegion("us-gov-east-1");
        instance.setDockerContainerId(CONTAINER);
        instance.setState(InstanceState.running());
        labels.put(ContainerStorageHelper.OWNER_LABEL, "owner/4566");
        labels.put(ContainerStorageHelper.SERVICE_LABEL, "ec2");
        labels.put(ContainerStorageHelper.ACCOUNT_LABEL, instance.getOwnerId());
        labels.put(ContainerStorageHelper.RESOURCE_ID_LABEL, instance.getInstanceId());
        labels.put(ContainerStorageHelper.REGION_LABEL, instance.getRegion());
        labels.put(Ec2SystemdV1Runtime.RUNTIME_LABEL, ResolvedAmiImage.SYSTEMD_V1_RUNTIME);
        when(docker.inspectContainerCmd(CONTAINER).exec()).thenReturn(inspection);
        when(inspection.getId()).thenReturn(CONTAINER);
        when(inspection.getConfig().getLabels()).thenReturn(labels);
        exited(129);
        reboots = new Ec2GuestReboots(docker, config, (container, task) -> {
            assertEquals(CONTAINER, container);
            queued.add(task);
            return CompletableFuture.completedFuture(null);
        }, guest -> {
            restarted.add(guest);
            when(inspection.getState().getRunning()).thenReturn(true);
            when(inspection.getState().getStatus()).thenReturn("running");
        }, false);
    }

    @AfterEach
    void close() {
        reboots.close();
    }

    @Test
    void pollingAndHealthChecksQueueOneRebootAndPreserveTheInstance() {
        reboots.watch(instance);
        reboots.poll();
        assertTrue(reboots.isRebooting(CONTAINER));
        reboots.poll();
        reboots.watch(instance);
        assertEquals(1, queued.size());
        assertTrue(restarted.isEmpty());
        queued.removeFirst().run();
        assertEquals(List.of(instance), restarted);
        assertEquals(CONTAINER, instance.getDockerContainerId());
        assertEquals("running", instance.getState().getName());
        assertFalse(reboots.isRebooting(CONTAINER));
        reboots.poll();
        assertTrue(queued.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 130, 137, 143})
    void doesNotRestartPoweroffOrUnrelatedProcessFailures(int code) {
        exited(code);
        reboots.watch(instance);
        reboots.poll();
        assertFalse(reboots.isRebooting(CONTAINER));
        assertTrue(queued.isEmpty());
        assertTrue(restarted.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "io.floci.owner", "io.floci.service", "io.floci.account",
            "io.floci.resource-id", "io.floci.region", "io.floci.ec2.init-runtime"
    })
    void refusesRebootWhenAnyOwnershipDimensionChanges(String key) {
        reboots.watch(instance);
        labels.put(key, "foreign");
        assertFalse(reboots.isRebooting(CONTAINER));
        reboots.poll();
        assertTrue(queued.isEmpty());
    }

    @Test
    void verifiesOwnershipAgainAfterTheRebootWasQueued() {
        reboots.watch(instance);
        assertTrue(reboots.isRebooting(CONTAINER));
        labels.put(ContainerStorageHelper.ACCOUNT_LABEL, "210987654321");
        queued.removeFirst().run();
        assertTrue(restarted.isEmpty());
    }

    @Test
    void forgettingForStopOrTerminationCancelsAnAlreadyQueuedReboot() {
        reboots.watch(instance);
        assertTrue(reboots.isRebooting(CONTAINER));
        reboots.forget(instance);
        queued.removeFirst().run();
        assertFalse(reboots.isRebooting(CONTAINER));
        assertTrue(restarted.isEmpty());
    }

    @Test
    void aNewLifecycleStateWinsEvenBeforeItsStopTaskExecutes() {
        reboots.watch(instance);
        assertTrue(reboots.isRebooting(CONTAINER));
        instance.setState(InstanceState.stopping());
        queued.removeFirst().run();
        assertFalse(reboots.isRebooting(CONTAINER));
        assertTrue(restarted.isEmpty());
    }

    @Test
    void replacementContainerCannotBeRestartedThroughAnOldLease() {
        reboots.watch(instance);
        assertTrue(reboots.isRebooting(CONTAINER));
        instance.setDockerContainerId("replacement");
        queued.removeFirst().run();
        assertTrue(restarted.isEmpty());
    }

    @Test
    void anAlreadyRestartedGuestDoesNotReceiveAnotherRestart() {
        reboots.watch(instance);
        assertTrue(reboots.isRebooting(CONTAINER));
        when(inspection.getState().getRunning()).thenReturn(true);
        when(inspection.getState().getStatus()).thenReturn("running");
        queued.removeFirst().run();
        assertTrue(restarted.isEmpty());
    }

    @Test
    void aFailedRestartIsNotReportedAsHealthyOrRetriedInAnEndlessLoop() {
        reboots.close();
        reboots = new Ec2GuestReboots(docker, config, (container, task) -> {
            queued.add(task);
            return CompletableFuture.completedFuture(null);
        },
                guest -> { throw new IllegalStateException("Cannot start"); }, false);
        reboots.watch(instance);
        assertTrue(reboots.isRebooting(CONTAINER));
        queued.removeFirst().run();
        assertFalse(reboots.isRebooting(CONTAINER));
        reboots.poll();
        assertTrue(queued.isEmpty());
    }

    @Test
    void transientIdleInspectionFailureRetainsObservationForTheNextPoll() {
        reboots.watch(instance);
        when(docker.inspectContainerCmd(CONTAINER).exec())
                .thenThrow(new IllegalStateException("Daemon temporarily unavailable"))
                .thenReturn(inspection);

        assertFalse(reboots.isRebooting(CONTAINER));
        assertTrue(queued.isEmpty());
        reboots.poll();
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertEquals(List.of(instance), restarted);
        assertFalse(reboots.isRebooting(CONTAINER));
    }

    @Test
    void recoveredIdleInspectionStillRejectsChangedGuestOwnership() {
        reboots.watch(instance);
        when(docker.inspectContainerCmd(CONTAINER).exec())
                .thenThrow(new IllegalStateException("Daemon temporarily unavailable"))
                .thenReturn(inspection);

        assertFalse(reboots.isRebooting(CONTAINER));
        labels.put(ContainerStorageHelper.ACCOUNT_LABEL, "210987654321");
        assertFalse(reboots.isRebooting(CONTAINER));
        labels.put(ContainerStorageHelper.ACCOUNT_LABEL, instance.getOwnerId());
        reboots.poll();
        assertTrue(queued.isEmpty());
        assertTrue(restarted.isEmpty());
    }

    @Test
    void synchronousSchedulingFailureRemainsTerminal() {
        reboots.close();
        reboots = new Ec2GuestReboots(docker, config, (container, task) -> {
            throw new RejectedExecutionException("Executor stopped");
        }, guest -> fail("Rejected task must not run"), false);
        reboots.watch(instance);

        assertFalse(reboots.isRebooting(CONTAINER));
        clearInvocations(docker);
        reboots.poll();
        verifyNoInteractions(docker);
        assertTrue(restarted.isEmpty());
    }

    @Test
    void inspectionFailureAfterSchedulingRemainsTerminal() {
        reboots.watch(instance);
        assertTrue(reboots.isRebooting(CONTAINER));
        when(docker.inspectContainerCmd(CONTAINER).exec())
                .thenThrow(new IllegalStateException("Cannot inspect the queued guest"));
        queued.removeFirst().run();

        assertFalse(reboots.isRebooting(CONTAINER));
        clearInvocations(docker);
        reboots.poll();
        verifyNoInteractions(docker);
        assertTrue(restarted.isEmpty());
    }

    @Test
    void aRemovedContainerRetiresItsObservation() {
        reboots.watch(instance);
        when(docker.inspectContainerCmd(CONTAINER).exec()).thenThrow(new NotFoundException("gone"));
        assertFalse(reboots.isRebooting(CONTAINER));
        clearInvocations(docker);
        reboots.poll();
        verifyNoInteractions(docker);
    }

    @Test
    void aRejectedLifecycleTaskIsNotReportedAsHealthy() {
        reboots.close();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        reboots = new Ec2GuestReboots(docker, config, (container, task) -> completion,
                guest -> fail("Rejected task must not run"), false);
        reboots.watch(instance);
        assertTrue(reboots.isRebooting(CONTAINER));
        completion.completeExceptionally(new RejectedExecutionException("Executor stopped"));
        assertFalse(reboots.isRebooting(CONTAINER));
    }

    @Test
    void pendingLaunchIsObservedOnceItBecomesRunning() {
        instance.setState(InstanceState.pending());
        reboots.watch(instance);
        reboots.poll();
        assertTrue(queued.isEmpty());
        instance.setState(InstanceState.running());
        assertTrue(reboots.isRebooting(CONTAINER));
        assertEquals(1, queued.size());
    }

    @Test
    void aTerminalLifecycleStateRetiresTheOldObservation() {
        reboots.watch(instance);
        instance.setState(InstanceState.terminated());
        reboots.poll();
        instance.setState(InstanceState.running());
        assertFalse(reboots.isRebooting(CONTAINER));
        assertTrue(queued.isEmpty());
    }

    @Test
    void closingTheRuntimePreventsQueuedWorkFromRestartingGuests() {
        reboots.watch(instance);
        assertTrue(reboots.isRebooting(CONTAINER));
        reboots.close();
        queued.removeFirst().run();
        reboots.watch(instance);
        assertFalse(reboots.isRebooting(CONTAINER));
        assertTrue(restarted.isEmpty());
    }

    private void exited(long code) {
        when(inspection.getState().getRunning()).thenReturn(false);
        when(inspection.getState().getStatus()).thenReturn("exited");
        when(inspection.getState().getExitCodeLong()).thenReturn(code);
    }
}

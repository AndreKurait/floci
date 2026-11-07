package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerExecStubs;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2NativeRecoveryHealthTest {
    private static final String CONTAINER = "owned-guest";
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final Ec2MetadataServer metadata = mock(Ec2MetadataServer.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final InspectContainerResponse inspection = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final ExecutorService retries = mock(ExecutorService.class);
    private final List<Runnable> tasks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Instance instance = new Instance();
    private final Map<String, String> labels = new HashMap<>();
    private final Ec2ContainerManager manager;

    Ec2NativeRecoveryHealthTest() {
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("owner"));
        instance.setInstanceId("i-owned");
        instance.setOwnerId("123456789012");
        instance.setRegion("us-gov-east-1");
        instance.setState(InstanceState.running());
        instance.setDockerContainerId(CONTAINER);
        instance.setContainerBridgeIp("172.18.0.8");
        labels.put(ContainerStorageHelper.OWNER_LABEL, "owner/4566");
        labels.put(ContainerStorageHelper.SERVICE_LABEL, "ec2");
        labels.put(ContainerStorageHelper.ACCOUNT_LABEL, instance.getOwnerId());
        labels.put(ContainerStorageHelper.RESOURCE_ID_LABEL, instance.getInstanceId());
        labels.put(ContainerStorageHelper.REGION_LABEL, instance.getRegion());
        labels.put(Ec2SystemdV1Runtime.RUNTIME_LABEL, ResolvedAmiImage.SYSTEMD_V1_RUNTIME);
        when(inspection.getId()).thenReturn(CONTAINER);
        when(inspection.getConfig().getLabels()).thenReturn(labels);
        when(inspection.getHostConfig().getNetworkMode()).thenReturn("bridge");
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn("172.18.0.8");
        when(inspection.getNetworkSettings().getNetworks()).thenReturn(Map.of("bridge", network));
        when(inspection.getState().getRunning()).thenAnswer(ignored -> running.get());
        when(inspection.getState().getStatus()).thenAnswer(ignored -> running.get() ? "running" : "exited");
        when(inspection.getState().getExitCodeLong()).thenReturn(129L);
        when(inspection.getState().getPidLong()).thenReturn(81L);
        when(docker.inspectContainerCmd(CONTAINER).exec()).thenReturn(inspection);
        when(lifecycle.isContainerRunning(CONTAINER)).thenAnswer(ignored -> running.get());
        InspectContainerResponse helper = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(helper.getId()).thenReturn("owned-init-helper");
        when(helper.getState().getRunning()).thenReturn(true);
        Map<String, String> helperLabels = new HashMap<>(labels);
        helperLabels.put(ContainerStorageHelper.SERVICE_LABEL, Ec2SystemdV1Runtime.SERVICE);
        helperLabels.put(Ec2SystemdV1Runtime.SCOPE_LABEL, "floci-" + "a".repeat(32));
        when(helper.getConfig().getLabels()).thenReturn(helperLabels);
        when(docker.inspectContainerCmd("owned-init-helper").exec()).thenReturn(helper);
        when(docker.inspectContainerCmd(ContainerStorageHelper.resourceName(
                config, Ec2SystemdV1Runtime.SERVICE, null, instance.getInstanceId())).exec()).thenReturn(helper);
        ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        doAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            return null;
        }).when(retries).submit(any(Runnable.class));
        manager = new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                docker, mock(PortAllocator.class), config, metadata, mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class), mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class), new Ec2InstanceTypeCatalog(),
                executor, retries, Duration.ofSeconds(1));
    }

    @AfterEach
    void stopObserving() {
        manager.stop();
    }

    @Test
    void startupRestoresMetadataAndRecoveryForARebootWhileFlociWasDown() {
        assertTrue(manager.restoreMetadataRegistration(instance));
        verify(metadata, atLeastOnce()).registerContainer("172.18.0.8", instance.getInstanceId(), instance);
        assertTrue(manager.isContainerRunning(instance));
        assertEquals(1, tasks.size());
        assertEquals("running", instance.getState().getName());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 137, 143})
    void startupDoesNotRestartOtherGuestExits(long exitCode) {
        when(inspection.getState().getExitCodeLong()).thenReturn(exitCode);
        assertFalse(manager.restoreMetadataRegistration(instance));
        assertFalse(manager.isContainerRunning(instance));
        assertTrue(tasks.isEmpty());
        verifyNoInteractions(metadata);
    }

    @Test
    void startupDoesNotRestartAnInstanceTheCustomerStopped() {
        instance.setState(InstanceState.stopped());
        assertFalse(manager.restoreMetadataRegistration(instance));
        assertFalse(manager.isContainerRunning(instance));
        assertTrue(tasks.isEmpty());
        verifyNoInteractions(metadata);
    }

    @Test
    void startupDoesNotAdoptAnOrdinaryContainerThatExitsWithTheSameCode() {
        labels.remove(Ec2SystemdV1Runtime.RUNTIME_LABEL);
        assertFalse(manager.restoreMetadataRegistration(instance));
        assertFalse(manager.isContainerRunning(instance));
        assertTrue(tasks.isEmpty());
        verifyNoInteractions(metadata);
    }

    @Test
    void failedNativeRecoveryIsUnhealthyEvenWhenItsBootstrapShellIsRunning() {
        running.set(true);
        assertTrue(manager.restoreMetadataRegistration(instance));
        running.set(false);
        assertTrue(manager.isContainerRunning(instance));
        assertEquals(1, tasks.size());
        StartContainerCmd start = docker.startContainerCmd(CONTAINER);
        doAnswer(ignored -> {
            running.set(true);
            return null;
        }).when(start).exec();
        ContainerExecStubs.completeEveryExec(docker, CONTAINER, 2, "", "bootstrap gate unavailable");

        tasks.getFirst().run();

        assertTrue(running.get(), "Docker is alive although native boot failed");
        assertFalse(manager.isContainerRunning(instance));
        assertEquals(1, tasks.size(), "failed recovery is not rescheduled endlessly");
    }
}

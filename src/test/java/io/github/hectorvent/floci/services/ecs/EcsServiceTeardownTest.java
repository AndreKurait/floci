package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskLaunchException;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Closeable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies emulator-shutdown teardown stops the Docker containers of running tasks
 * exactly once. Without it, task containers outlive the process as orphans (task
 * state is transient, so nothing reclaims them on the next start).
 */
class EcsServiceTeardownTest {

    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownStopsTasksInBoundedParallelAndWaitsBeforeReleasingNetworks(boolean interrupted) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager manager = mock(EcsContainerManager.class);
        when(manager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", task.getTaskArn()), Map.of());
        });
        CountDownLatch firstWave = new CountDownLatch(8);
        CountDownLatch finishStops = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Set<String> stopped = ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            EcsTaskHandle handle = invocation.getArgument(0);
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            firstWave.countDown();
            try {
                assertTrue(finishStops.await(10, TimeUnit.SECONDS), "task stops were not released");
                assertTrue(stopped.add(handle.getTaskArn()), "each task must be stopped once");
            } finally {
                active.decrementAndGet();
            }
            return null;
        }).when(manager).stopTask(any());
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), manager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("busybox:stable");
        service.registerTaskDefinition("parallel-shutdown", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        service.runTask(null, "parallel-shutdown", 9, LaunchType.FARGATE, null, null,
                List.of(), null, REGION);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptionPreserved = new AtomicBoolean();
        Thread shutdown = new Thread(() -> {
            try {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                service.stopManagedContainers();
                interruptionPreserved.set(Thread.currentThread().isInterrupted());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        shutdown.start();
        try {
            assertTrue(firstWave.await(5, TimeUnit.SECONDS),
                    "slow ECS tasks must enter shutdown together, within the existing eight-task bound");
            assertTrue(shutdown.isAlive(), "shutdown must wait for the task stops");
            assertEquals(8, peak.get());
            verify(manager, never()).releaseTaskNetwork(any(), anyString());
            finishStops.countDown();
            shutdown.join(5000);
            assertFalse(shutdown.isAlive());
            assertNull(failure.get());
            assertEquals(interrupted, interruptionPreserved.get());
            assertEquals(9, stopped.size());
            assertEquals(8, peak.get(), "queued tasks must not increase the existing stop concurrency");
            verify(manager, times(9)).stopTask(any());
            verify(manager, times(9)).releaseTaskNetwork(any(), anyString());
        } finally {
            finishStops.countDown();
            shutdown.join(5000);
            assertFalse(shutdown.isAlive(), "the test must release its shutdown thread");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stoppedTaskKeepsItsHandleUntilEphemeralVolumeCleanupSucceeds(boolean exitsNaturally) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager manager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of(),
                null, null, REGION, Map.of(), Set.of("task-volume"));
        when(manager.startTask(any(), any(), any(), anyString())).thenReturn(handle);
        when(manager.getExitCodeIfStopped("docker-id")).thenReturn(0);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(ignored -> {
            attempts.incrementAndGet();
            handle.recordContainerRemoved("app");
            return null;
        }).when(manager).cleanupStoppedTask(handle);
        when(manager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            handle.recordContainerRemoved("app");
            if (attempts.incrementAndGet() > 1) {
                handle.recordTaskVolumeRemoved("task-volume");
            }
            return Map.of("app", 0);
        });
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), manager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("app:latest");
        service.registerTaskDefinition("volume-retry", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "volume-retry", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        if (exitsNaturally) {
            service.reconcile();
        } else {
            service.stopTask(null, taskArn, null, REGION);
            service.awaitPendingStops();
        }
        assertEquals("STOPPED", service.describeTasks(null, List.of(taskArn), REGION).getFirst().getLastStatus());
        assertTrue(handle.hasTaskVolumes());

        service.reconcile();
        assertFalse(handle.hasTaskVolumes());
        service.reconcile();
        assertEquals(2, attempts.get(), "cleanup stops retrying after the volume is removed");
    }

    @Test
    void failedLaunchCleanupIsRetriedByStoppedTaskReconciliation() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager manager = mock(EcsContainerManager.class);
        EcsTaskHandle partial = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of(),
                null, null, REGION, Map.of(), Set.of("task-volume"));
        when(manager.startTask(any(), any(), any(), anyString()))
                .thenThrow(new EcsTaskLaunchException(new IllegalStateException("launch failed"), partial));
        when(manager.stopTaskAndCollectExitCodes(partial)).thenAnswer(ignored -> {
            partial.recordContainerRemoved("app");
            partial.recordTaskVolumeRemoved("task-volume");
            return Map.of("app", 137);
        });
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), manager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("busybox:stable");
        service.registerTaskDefinition("failed-volume-launch", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        EcsTask task = service.runTask(null, "failed-volume-launch", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst();
        assertEquals("STOPPED", task.getLastStatus());
        assertEquals("Failed to start: launch failed", task.getStoppedReason());
        assertTrue(partial.hasTaskVolumes());
        service.reconcile();
        assertFalse(partial.hasTaskVolumes());
        service.reconcile();
        verify(manager, times(1)).stopTaskAndCollectExitCodes(partial);
        service.stopManagedContainers();
    }

    @Test
    void shutdownRetriesVolumeRemovalAndRetainsPendingCleanupAfterReset() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager manager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of(),
                null, null, REGION, Map.of(), Set.of("task-volume"));
        when(manager.startTask(any(), any(), any(), anyString())).thenReturn(handle);
        doAnswer(ignored -> {
            handle.recordContainerRemoved("app");
            return null;
        }).when(manager).stopTask(handle);
        when(manager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            handle.recordTaskVolumeRemoved("task-volume");
            return Map.of("app", 0);
        });
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), manager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("busybox:stable");
        service.registerTaskDefinition("shutdown-volume", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        service.runTask(null, "shutdown-volume", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION);
        service.stopManagedContainers();
        verify(manager, times(2)).cleanupStoppedTask(handle);
        assertTrue(handle.hasTaskVolumes());
        service.clear();
        service.afterReset();
        service.reconcile();
        assertFalse(handle.hasTaskVolumes());
        service.stopManagedContainers();
    }

    @Test
    void startupTransitionWaitsForTheTaskLock() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch finishStart = new CountDownLatch(1);
        AtomicReference<EcsTask> startingTask = new AtomicReference<>();
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            startingTask.set(task);
            startEntered.countDown();
            if (!finishStart.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("container startup was not released");
            }
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", "docker-id"), Map.of());
        });
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"),
                containerManager, config, mock(EcsLoadBalancerRegistrar.class),
                new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("locked-start", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        AtomicReference<Throwable> launchFailure = new AtomicReference<>();
        Thread launch = new Thread(() -> {
            try {
                service.runTask(null, "locked-start", 1, LaunchType.FARGATE, null, null,
                        List.of(), null, REGION);
            } catch (Throwable failure) {
                launchFailure.set(failure);
            }
        });
        launch.start();
        try {
            assertTrue(startEntered.await(5, TimeUnit.SECONDS));
            synchronized (startingTask.get()) {
                finishStart.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (launch.getState() != Thread.State.BLOCKED) {
                    assertTrue(launch.isAlive(), "startup finished without acquiring the task lock");
                    assertTrue(System.nanoTime() < deadline, "startup did not wait for the task lock");
                    Thread.onSpinWait();
                }
            }
            launch.join(5000);
            assertFalse(launch.isAlive());
            assertNull(launchFailure.get());
            assertEquals("RUNNING", startingTask.get().getLastStatus());
        } finally {
            finishStart.countDown();
            service.stopManagedContainers();
        }
    }

    @Test
    void stopDuringContainerStartupCompletesAfterTheHandleArrives() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch finishStart = new CountDownLatch(1);
        AtomicReference<String> taskArn = new AtomicReference<>();
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            taskArn.set(task.getTaskArn());
            startEntered.countDown();
            if (!finishStart.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("container startup was not released");
            }
            Container container = new Container();
            container.setName("app");
            container.setRuntimeId("docker-id");
            task.setContainers(List.of(container));
            return handle;
        });
        when(containerManager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            handle.recordContainerRemoved("app");
            return Map.of("app", 0);
        });

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"), containerManager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("startup-stop", List.of(definition), null, null, null,
                null, null, List.of(), REGION);

        CompletableFuture<List<EcsTask>> launch = CompletableFuture.supplyAsync(() ->
                service.runTask(null, "startup-stop", 1, LaunchType.FARGATE, null, null,
                        List.of(), null, REGION));
        try {
            assertTrue(startEntered.await(5, TimeUnit.SECONDS));
            assertEquals("STOPPING", service.stopTask(null, taskArn.get(), null, REGION).getLastStatus());
        } finally {
            finishStart.countDown();
        }

        launch.get(5, TimeUnit.SECONDS);
        assertEquals("STOPPED", service.describeTasks(null, List.of(taskArn.get()), REGION).getFirst().getLastStatus());
        verify(containerManager).stopTaskAndCollectExitCodes(handle);
        service.stopManagedContainers();
    }

    @Test
    void stopTaskRecoversContainerIdsWhenTheRuntimeHandleIsMissing() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle original = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            Container container = new Container();
            container.setName("app");
            container.setDockerId("docker-id");
            task.setContainers(List.of(container));
            return original;
        });
        when(containerManager.taskVolumeNames(any(), any())).thenReturn(Set.of("recovered-volume"));
        when(containerManager.stopTaskAndCollectExitCodes(any())).thenAnswer(invocation -> {
            EcsTaskHandle stoppingHandle = invocation.getArgument(0);
            assertEquals(Set.of("recovered-volume"), stoppingHandle.getTaskVolumeNames());
            stoppingHandle.recordContainerRemoved("app");
            stoppingHandle.recordTaskVolumeRemoved("recovered-volume");
            return Map.of("app", 0);
        });

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"), containerManager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("recover-handle", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "recover-handle", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        // A no-op Docker test double leaves the container alive while shutdown drops its handle.
        service.stopManagedContainers();
        service.afterReset();
        service.stopTask(null, taskArn, null, REGION);
        service.awaitPendingStops();

        verify(containerManager).stopTaskAndCollectExitCodes(argThat(
                handle -> "docker-id".equals(handle.getContainerIds().get("app"))));
        verify(containerManager).taskVolumeNames(any(), any());
        assertEquals("STOPPED", service.describeTasks(null, List.of(taskArn), REGION).getFirst().getLastStatus());
        service.stopManagedContainers();
    }

    @Test
    void failedContainerRemovalKeepsTaskStoppingUntilReconciliationRetries() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        when(containerManager.startTask(any(), any(), any(), anyString())).thenReturn(handle);
        Map<String, Integer> failed = new HashMap<>();
        failed.put("app", null);
        AtomicInteger attempts = new AtomicInteger();
        when(containerManager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            if (attempts.incrementAndGet() == 1) {
                return failed;
            }
            handle.recordContainerRemoved("app");
            return Map.of("app", 0);
        });

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"), containerManager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("retry-removal", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "retry-removal", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        service.stopTask(null, taskArn, null, REGION);
        service.awaitPendingStops();
        assertEquals("STOPPING", service.describeTasks(null, List.of(taskArn), REGION).getFirst().getLastStatus());

        // The tick's retry runs off the reconciler thread and lands the task once Docker is done.
        service.reconcile();
        service.awaitPendingStops();
        assertEquals("STOPPED", service.describeTasks(null, List.of(taskArn), REGION).getFirst().getLastStatus());
        verify(containerManager, times(2)).stopTaskAndCollectExitCodes(handle);
    }

    @Test
    void removedContainerWithUnknownExitCodeStopsWithoutInventingSuccess() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            Container container = new Container();
            container.setName("app");
            task.setContainers(List.of(container));
            return handle;
        });
        when(containerManager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            handle.recordContainerRemoved("app");
            Map<String, Integer> codes = new HashMap<>();
            codes.put("app", null);
            return codes;
        });

        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"),
                containerManager, config, mock(EcsLoadBalancerRegistrar.class),
                new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("unknown-exit", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "unknown-exit", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        // StopTask answers STOPPING, as on ECS; the teardown finishes afterwards.
        assertEquals("STOPPING", service.stopTask(null, taskArn, null, REGION).getLastStatus());
        service.awaitPendingStops();
        EcsTask stopped = service.describeTasks(null, List.of(taskArn), REGION).getFirst();
        assertEquals("STOPPED", stopped.getLastStatus());
        assertNull(stopped.getContainers().getFirst().getExitCode());
        service.reconcile();
        verify(containerManager, times(1)).stopTaskAndCollectExitCodes(handle);
        service.stopManagedContainers();
    }

    @Test
    void concurrentStopsTeardownTheTaskOnlyOnce() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        when(containerManager.startTask(any(), any(), any(), anyString())).thenReturn(handle);
        CountDownLatch teardownEntered = new CountDownLatch(1);
        CountDownLatch finishTeardown = new CountDownLatch(1);
        when(containerManager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            teardownEntered.countDown();
            if (!finishTeardown.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("teardown was not released");
            }
            handle.recordContainerRemoved("app");
            return Map.of("app", 0);
        });

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"), containerManager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("concurrent-stop", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "concurrent-stop", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        EcsTask first = service.stopTask(null, taskArn, null, REGION);
        try {
            assertEquals("STOPPING", first.getLastStatus());
            assertTrue(teardownEntered.await(5, TimeUnit.SECONDS));
            // A second StopTask while the first teardown runs answers at once and starts no second one.
            EcsTask second = CompletableFuture.supplyAsync(() -> service.stopTask(null, taskArn, null, REGION))
                    .get(5, TimeUnit.SECONDS);
            assertEquals("STOPPING", second.getLastStatus());

            finishTeardown.countDown();
            service.awaitPendingStops();
            verify(containerManager, times(1)).stopTaskAndCollectExitCodes(handle);
            assertEquals("STOPPED", service.describeTasks(null, List.of(taskArn), REGION).getFirst().getLastStatus());
        } finally {
            finishTeardown.countDown();
        }
    }

    @Test
    void stopManagedContainersStopsEachRunningTaskOnce() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false); // docker mode
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = mock(EcsTaskHandle.class);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenReturn(handle);

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                containerManager,
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new SingleUseStorageFactory(),
                null);
        service.initializeStorage();

        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage("nginx:alpine");
        service.registerTaskDefinition("teardown-fam", List.of(cd), null, null, null,
                null, null, List.of(), REGION);
        service.runTask(null, "teardown-fam", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION);

        service.stopManagedContainers();
        verify(containerManager, times(1)).stopTask(handle);

        // The reconciler must be stopped before handles are drained, or a tick could
        // restart the drained tasks between teardown and the final storage flush.
        assertTrue(service.isReconcilerShutdown());

        // Handles are claimed on the first pass; a second invocation must be a no-op.
        service.stopManagedContainers();
        verify(containerManager, times(1)).stopTask(handle);
    }

    @Test
    void afterResetRestartsTheReconcilerThatAResetTeardownStopped() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false); // docker mode
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new SingleUseStorageFactory(),
                null);
        service.initializeStorage();

        // A reset runs the teardown, then clear(), and afterReset() last, even when the wipe or
        // a clear() threw; shutdown runs only the teardown. afterReset() alone must bring the
        // reconciler back.
        service.stopManagedContainers();
        assertTrue(service.isReconcilerShutdown());

        service.afterReset();
        assertFalse(service.isReconcilerShutdown());
        service.afterReset();
        assertFalse(service.isReconcilerShutdown());

        service.stopManagedContainers();
        assertTrue(service.isReconcilerShutdown());
    }

    @Test
    void stoppedTaskRetriesTeardownUntilItsRemainingLogStreamIsReleased() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false); // docker mode
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"),
                Map.of("docker-id", mock(Closeable.class)));
        when(containerManager.startTask(any(), any(), any(), anyString())).thenReturn(handle);
        AtomicInteger teardownAttempts = new AtomicInteger();
        when(containerManager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            handle.recordContainerRemoved("app");
            if (teardownAttempts.incrementAndGet() == 2) {
                handle.removeLogStream("docker-id");
            }
            return Map.of("app", 0);
        });

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                containerManager,
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new SingleUseStorageFactory(),
                null);
        service.initializeStorage();

        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage("nginx:alpine");
        service.registerTaskDefinition("retry-fam", List.of(cd), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "retry-fam", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        service.stopTask(null, taskArn, null, REGION);
        service.awaitPendingStops();
        verify(containerManager, times(1)).stopTaskAndCollectExitCodes(handle);

        service.reconcile();
        verify(containerManager, times(2)).stopTaskAndCollectExitCodes(handle);

        service.reconcile();
        verify(containerManager, times(2)).stopTaskAndCollectExitCodes(handle);
    }

    private static final class SingleUseStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SingleUseStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}

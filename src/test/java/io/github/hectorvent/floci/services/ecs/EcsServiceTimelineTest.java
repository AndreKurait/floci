package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.exec.EcsExecSessionRegistry;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ManualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every instant ECS stamps or compares is read from the one injected clock, so a test that owns the
 * clock owns the whole timeline: nothing here is compared with the wall clock.
 */
class EcsServiceTimelineTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final Instant START = Instant.parse("2031-03-04T05:06:07.008Z");

    private final ManualClock clock = new ManualClock(START);
    private final List<EcsService> created = new ArrayList<>();

    @AfterEach
    void stopReconcilers() {
        created.forEach(EcsService::stopManagedContainers);
    }

    @Test
    void taskDefinition_registeredAndDeregisteredAt_areReadFromTheClock() {
        EcsService service = mockModeService();

        TaskDefinition registered = registerTaskDef(service, "tl-td-fam");
        assertEquals(START, registered.getRegisteredAt());
        assertNull(registered.getDeregisteredAt());

        clock.advance(Duration.ofHours(3));
        TaskDefinition deregistered = service.deregisterTaskDefinition(registered.getTaskDefinitionArn(), REGION);

        assertEquals(START, deregistered.getRegisteredAt());
        assertEquals(START.plus(Duration.ofHours(3)), deregistered.getDeregisteredAt());
    }

    @Test
    void task_everyLifecycleStamp_isReadFromTheClock() {
        EcsService service = mockModeService();
        service.createCluster("tl-task-cluster", REGION);
        registerTaskDef(service, "tl-task-fam");

        EcsTask task = service.runTask("tl-task-cluster", "tl-task-fam", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst();
        assertEquals(START, task.getCreatedAt());
        assertEquals(START, task.getStartedAt());
        assertEquals(START, task.getConnectivityAt());
        assertNull(task.getStoppedAt());

        clock.advance(Duration.ofSeconds(90));
        EcsTask stopped = service.stopTask("tl-task-cluster", task.getTaskArn(), "done", REGION);

        Instant stoppedAt = START.plusSeconds(90);
        assertEquals(TaskStatus.STOPPED.name(), stopped.getLastStatus());
        assertEquals(START, stopped.getCreatedAt());
        assertEquals(stoppedAt, stopped.getStoppingAt());
        assertEquals(stoppedAt, stopped.getStoppedAt());
        assertEquals(stoppedAt, stopped.getExecutionStoppedAt());
    }

    @Test
    void serviceDeployment_finishesAtTheInstantTheReconcilerConverges_notWhenItWasCreated() {
        EcsService service = mockModeService();
        service.createCluster("tl-dep-cluster", REGION);
        registerTaskDef(service, "tl-dep-fam");
        service.createService("tl-dep-cluster", "tl-dep-svc", "tl-dep-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        ServiceDeployment started = onlyDeployment(service, "tl-dep-svc", "tl-dep-cluster");
        assertEquals("IN_PROGRESS", started.getStatus());
        assertEquals(START, started.getCreatedAt());
        assertNull(started.getFinishedAt());

        clock.advance(Duration.ofSeconds(5));
        service.reconcile();

        ServiceDeployment finished = onlyDeployment(service, "tl-dep-svc", "tl-dep-cluster");
        assertEquals("SUCCESSFUL", finished.getStatus());
        assertEquals(START, finished.getCreatedAt());
        assertEquals(START.plusSeconds(5), finished.getFinishedAt());
        assertEquals(START.plusSeconds(5), finished.getUpdatedAt());
    }

    /** The wait only blames a failed task launched since it began; one tick earlier is history. */
    @Test
    void awaitServiceStable_taskThatFailedOneMillisecondBeforeTheWaitBegan_doesNotEndTheWait() {
        EcsService service = dockerModeServiceWhoseFirstLaunchFails();
        EcsServiceModel svc = serviceWithOneFailedLaunch(service, "tl-before");

        clock.advance(Duration.ofMillis(1));
        service.awaitServiceStable(svc, Duration.ofSeconds(30));

        assertEquals(1, service.describeServices("tl-before-cluster", List.of("tl-before-svc"), REGION)
                .getFirst().getRunningCount());
    }

    @Test
    void awaitServiceStable_taskThatFailedAtTheInstantTheWaitBegan_endsTheWaitWithItsStoppedReason() {
        EcsService service = dockerModeServiceWhoseFirstLaunchFails();
        EcsServiceModel svc = serviceWithOneFailedLaunch(service, "tl-at");

        AwsException error = assertThrows(AwsException.class,
                () -> service.awaitServiceStable(svc, Duration.ofSeconds(30)));

        assertEquals("NotStabilized", error.getErrorCode());
        assertThat(error.getMessage(), containsString("stopped: Failed to start: first launch fails"));
    }

    private EcsServiceModel serviceWithOneFailedLaunch(EcsService service, String prefix) {
        service.createCluster(prefix + "-cluster", REGION);
        registerTaskDef(service, prefix + "-fam");
        EcsServiceModel svc = service.createService(prefix + "-cluster", prefix + "-svc", prefix + "-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcile();
        List<EcsTask> tasks = service.describeTasks(prefix + "-cluster", service.listTasks(prefix + "-cluster",
                null, TaskStatus.STOPPED.name(), null, REGION), REGION);
        assertEquals(1, tasks.size(), "the first reconcile pass launched the task that fails to start");
        assertEquals(START, tasks.getFirst().getCreatedAt());
        return svc;
    }

    private EcsService mockModeService() {
        return newService(mock(EcsContainerManager.class), true);
    }

    private EcsService dockerModeServiceWhoseFirstLaunchFails() {
        AtomicInteger launches = new AtomicInteger();
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        // Mockito answers 0 for an Integer, which reads as "every container exited".
        when(containerManager.getExitCodeIfStopped(anyString())).thenReturn(null);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            if (launches.getAndIncrement() == 0) {
                throw new IllegalStateException("first launch fails");
            }
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", "docker-" + task.getTaskArn().hashCode()),
                    Map.of());
        });
        return newService(containerManager, false);
    }

    private EcsService newService(EcsContainerManager containerManager, boolean mockMode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(mockMode);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(new RegionResolver(REGION, ACCOUNT), containerManager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory(ACCOUNT), null,
                new EcsExecSessionRegistry(), null, null, clock);
        service.initializeStorage();
        created.add(service);
        return service;
    }

    private static TaskDefinition registerTaskDef(EcsService service, String family) {
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("app:1");
        container.setEssential(true);
        return service.registerTaskDefinition(family, List.of(container), null, null, null,
                null, null, List.of(), REGION);
    }

    private static ServiceDeployment onlyDeployment(EcsService service, String serviceName, String cluster) {
        List<ServiceDeployment> deployments =
                service.listServiceDeploymentsDetailed(serviceName, cluster, null, REGION);
        assertEquals(1, deployments.size());
        return deployments.getFirst();
    }
}

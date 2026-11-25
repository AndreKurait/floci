package io.floci.aws.services.ecs;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.container.EcsTaskHandle;
import io.floci.aws.services.ecs.exec.EcsExecSessionRegistry;
import io.floci.aws.services.ecs.model.Container;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.ContainerInstance;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EcsServiceEcrPullIdentityTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String NATIVE_IMAGE = "111111111111.dkr.ecr.us-east-1.amazonaws.com/image:latest";
    private final List<EcsService> services = new ArrayList<>();
    private EcsContainerManager manager;
    private ContainerInstance instance;

    @AfterEach
    void stopOwnedExecutorsAndMockTasks() {
        services.forEach(EcsService::stopManagedContainers);
    }

    @Test
    void enforcedEc2RunTaskSelectsItsRegisteredInstanceBeforeLaunching() throws Exception {
        EcsService service = service(true, NATIVE_IMAGE);
        CountDownLatch launched = new CountDownLatch(1);
        when(manager.startTask(any(), any(), any(), eq(REGION), eq("i-owned"))).thenAnswer(invocation -> {
            launched.countDown();
            return started(invocation.getArgument(0));
        });

        EcsTask response = service.runTask("cluster", "family", 1, LaunchType.EC2,
                null, null, List.of(), null, REGION).getFirst();

        assertEquals(instance.getContainerInstanceArn(), response.getContainerInstanceArn());
        assertTrue(launched.await(5, TimeUnit.SECONDS));
        verify(manager, never()).startTask(any(), any(), any(), anyString());
    }

    @Test
    void iamOffEc2RunTaskKeepsItsExistingPlacementAndManagerPath() throws Exception {
        EcsService service = service(false, NATIVE_IMAGE);
        CountDownLatch launched = new CountDownLatch(1);
        when(manager.startTask(any(), any(), any(), eq(REGION))).thenAnswer(invocation -> {
            launched.countDown();
            return started(invocation.getArgument(0));
        });

        EcsTask response = service.runTask("cluster", "family", 1, LaunchType.EC2,
                null, null, List.of(), null, REGION).getFirst();

        assertNull(response.getContainerInstanceArn());
        assertTrue(launched.await(5, TimeUnit.SECONDS));
        verify(manager, never()).startTask(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void enforcedFargateRunTaskKeepsItsExecutionRolePath() throws Exception {
        EcsService service = service(true, NATIVE_IMAGE);
        CountDownLatch launched = new CountDownLatch(1);
        when(manager.startTask(any(), any(), any(), eq(REGION))).thenAnswer(invocation -> {
            launched.countDown();
            return started(invocation.getArgument(0));
        });

        EcsTask response = service.runTask("cluster", "family", 1, LaunchType.FARGATE,
                null, null, List.of(), null, REGION).getFirst();

        assertNull(response.getContainerInstanceArn());
        assertTrue(launched.await(5, TimeUnit.SECONDS));
        verify(manager, never()).startTask(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void enforcedNativeEcrLaunchPassesTheRegisteredEc2InstanceToTheManager() {
        EcsService service = service(true, NATIVE_IMAGE);
        createAndReconcile(service);

        verify(manager).startTask(any(), any(), any(), eq(REGION), eq("i-owned"));
        verify(manager, never()).startTask(any(), any(), any(), anyString());
        List<EcsTask> tasks = service.describeTasks("cluster",
                service.listTasks("cluster", null, "RUNNING", null, REGION), REGION);
        assertEquals(1, tasks.size());
        assertEquals("RUNNING", tasks.getFirst().getLastStatus());
    }

    @Test
    void missingContainerInstanceIdentityStopsLaunchBeforeCallingTheManager() {
        EcsService service = service(true, NATIVE_IMAGE);
        instance.setEc2InstanceId(null);
        createAndReconcile(service);

        verify(manager, never()).startTask(any(), any(), any(), anyString(), anyString());
        verify(manager, never()).startTask(any(), any(), any(), anyString());
        List<EcsTask> tasks = service.describeTasks("cluster",
                service.listTasks("cluster", null, "STOPPED", null, REGION), REGION);
        assertFalse(tasks.isEmpty());
        assertTrue(tasks.stream().allMatch(task -> "STOPPED".equals(task.getLastStatus())));
        assertTrue(tasks.stream().allMatch(task -> task.getStoppedReason().contains("container instance is unavailable")));
        assertEquals(0, service.describeServices("cluster", List.of("web"), REGION).getFirst().getRunningCount());
    }

    @Test
    void iamOffAndNonEcrLaunchesKeepTheExistingManagerPath() {
        EcsService disabled = service(false, NATIVE_IMAGE);
        createAndReconcile(disabled);
        verify(manager).startTask(any(), any(), any(), eq(REGION));
        verify(manager, never()).startTask(any(), any(), any(), anyString(), anyString());
        EcsService external = service(true, "example.invalid/image:1");
        createAndReconcile(external);
        verify(manager).startTask(any(), any(), any(), eq(REGION));
        verify(manager, never()).startTask(any(), any(), any(), anyString(), anyString());
    }

    private EcsService service(boolean enforced, String image) {
        manager = mock(EcsContainerManager.class);
        when(manager.getExitCodeIfStopped(anyString())).thenReturn(null);
        when(manager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation ->
                started(invocation.getArgument(0)));
        when(manager.startTask(any(), any(), any(), anyString(), anyString())).thenAnswer(invocation ->
                started(invocation.getArgument(0)));
        when(manager.stopTaskAndCollectExitCodes(any())).thenAnswer(invocation -> {
            EcsTaskHandle handle = invocation.getArgument(0);
            handle.getContainerIds().keySet().forEach(handle::recordContainerRemoved);
            return Map.of("app", 0);
        });
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.services().iam().enforcementEnabled()).thenReturn(enforced);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(new RegionResolver(REGION, ACCOUNT), manager, config,
                mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory(ACCOUNT), null,
                new EcsExecSessionRegistry(), null, null, Clock.systemUTC());
        services.add(service);
        service.initializeStorage();
        service.createCluster("cluster", REGION);
        instance = service.registerContainerInstance("cluster",
                "{\"instanceId\":\"i-owned\",\"region\":\"us-east-1\"}", List.of(), REGION);
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(image);
        service.registerTaskDefinition("family", List.of(app), null, null, null, null, null, List.of(), REGION);
        return service;
    }

    private static void createAndReconcile(EcsService service) {
        service.createService("cluster", "web", "family", 1, LaunchType.EC2, List.of(), null, REGION);
        service.reconcileServices();
    }

    private static EcsTaskHandle started(EcsTask task) {
        Container container = new Container();
        container.setName("app");
        container.setRuntimeId("docker-id");
        task.setContainers(List.of(container));
        return new EcsTaskHandle(task.getTaskArn(), Map.of("app", "docker-id"), Map.of());
    }
}

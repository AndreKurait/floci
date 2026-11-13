package io.floci.aws.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.container.EcsTaskHandle;
import io.floci.aws.services.ecs.exec.EcsExecSessionRegistry;
import io.floci.aws.services.ecs.model.Attribute;
import io.floci.aws.services.ecs.model.Container;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.services.ecs.model.NetworkMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EcsTaskWaits.awaitLastStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StopTask as ECS answers it: the task comes back {@code STOPPING} (desired {@code STOPPED}) at
 * once, and its containers are given their stop grace and torn down afterwards, the task reaching
 * {@code STOPPED} when they are gone. A caller that stops a task whose application drains on
 * SIGTERM is not held for the whole grace period.
 */
class EcsServiceAsyncStopTaskTest {

    private static final String REGION = "eu-west-1";
    private static final String ACCOUNT = "000000000000";
    private static final String DOCUMENT = "{\"instanceId\":\"i-0abc000000000001\","
            + "\"availabilityZone\":\"eu-west-1a\",\"instanceType\":\"m5.large\"}";

    @Test
    void stopTaskAnswersStoppingAtOnceAndTearsDownAfterwards() throws Exception {
        EcsContainerManager manager = manager();
        CountDownLatch draining = new CountDownLatch(1);
        CountDownLatch stopCalled = new CountDownLatch(1);
        when(manager.stopTaskAndCollectExitCodes(any())).thenAnswer(invocation -> {
            stopCalled.countDown();
            draining.await(30, TimeUnit.SECONDS);
            EcsTaskHandle handle = invocation.getArgument(0);
            handle.getContainerIds().keySet().forEach(handle::recordContainerRemoved);
            return Map.of("app", 0);
        });
        EcsService service = service(manager);
        service.createCluster("app-cluster", REGION);
        registerTaskDefinition(service);
        service.registerContainerInstance("app-cluster", DOCUMENT, null, REGION);
        EcsTask task = service.runTask("app-cluster", "app", 1, LaunchType.EC2, null, null, null, null, REGION).getFirst();

        try {
            awaitLastStatus(() -> describe(service, task), EcsTask::getLastStatus,
                    Duration.ofSeconds(5), "RUNNING");
            EcsTask answer = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> service.stopTask("app-cluster", task.getTaskArn(), "drain", REGION));

            assertEquals("STOPPING", answer.getLastStatus());
            assertEquals("STOPPED", answer.getDesiredStatus());
            assertTrue(stopCalled.await(10, TimeUnit.SECONDS), "the teardown never started");
            assertEquals("STOPPING", describe(service, task).getLastStatus());

            draining.countDown();
            service.awaitPendingStops();
            assertEquals("STOPPED", describe(service, task).getLastStatus());
            assertEquals(0, describe(service, task).getContainers().getFirst().getExitCode());
        } finally {
            draining.countDown();
            service.shutdown();
        }
    }

    private static EcsTask describe(EcsService service, EcsTask task) {
        return service.describeTasks("app-cluster", List.of(task.getTaskArn()), REGION).getFirst();
    }

    private static void registerTaskDefinition(EcsService service) {
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("busybox:stable");
        service.registerTaskDefinition("app", List.of(definition), NetworkMode.bridge, null, null,
                null, null, List.of(), REGION);
    }

    private static EcsContainerManager manager() {
        EcsContainerManager manager = mock(EcsContainerManager.class);
        when(manager.getExitCodeIfStopped(anyString())).thenReturn(null);
        when(manager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            task.setContainers(List.of(container("app")));
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", "docker-id"), Map.of());
        });
        return manager;
    }

    private static Container container(String name) {
        Container container =
                new Container();
        container.setName(name);
        container.setLastStatus("RUNNING");
        return container;
    }

    private static EcsService service(EcsContainerManager manager) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(new RegionResolver(REGION, ACCOUNT), manager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorage(), null,
                new EcsExecSessionRegistry(), null);
        service.initializeStorage();
        return service;
    }

    private static final class InMemoryStorage extends StorageFactory {
        private InMemoryStorage() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT);
        }
    }
}

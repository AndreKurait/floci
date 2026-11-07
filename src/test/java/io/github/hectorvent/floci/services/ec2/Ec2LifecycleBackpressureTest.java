package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.StopContainerCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2LifecycleBackpressureTest {
    @Test
    void fullLaunchQueueCannotDeadlockWorkersSchedulingTheirSuccessors() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 1, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), Ec2ContainerManager.BLOCKING_BACKPRESSURE);
        DockerClient docker = mock(DockerClient.class);
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                docker, mock(PortAllocator.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class), executor, Duration.ofSeconds(1));
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch finishFirst = new CountDownLatch(1);
        CountDownLatch successorsFinished = new CountDownLatch(2);
        CountDownLatch queuedLaunchesFinished = new CountDownLatch(2);
        List<Instance> instances = List.of(instance("one"), instance("two"));
        for (Instance instance : instances) {
            AtomicInteger calls = new AtomicInteger();
            StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
            when(docker.stopContainerCmd(instance.getDockerContainerId())).thenReturn(stop);
            doAnswer(ignored -> {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    assertTrue(finishFirst.await(10, TimeUnit.SECONDS));
                } else {
                    successorsFinished.countDown();
                }
                return null;
            }).when(stop).exec();
        }

        try {
            instances.forEach(manager::stop);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Both lifecycle workers must be occupied");
            instances.forEach(manager::stop);
            executor.submit(queuedLaunchesFinished::countDown);
            executor.submit(queuedLaunchesFinished::countDown);
            assertEquals(2, executor.getQueue().size(), "Launch queue must be full before completion");
            finishFirst.countDown();

            assertTrue(successorsFinished.await(5, TimeUnit.SECONDS),
                    "Workers must return to the launch queue instead of blocking while enqueueing successors");
            assertTrue(queuedLaunchesFinished.await(1, TimeUnit.SECONDS));
            for (Instance instance : instances) {
                verify(docker.stopContainerCmd(instance.getDockerContainerId()), times(2)).exec();
            }
        } finally {
            finishFirst.countDown();
            manager.stop();
        }
    }

    private static Instance instance(String suffix) {
        Instance instance = new Instance();
        instance.setInstanceId("i-" + suffix);
        instance.setDockerContainerId("container-" + suffix);
        instance.setState(InstanceState.running());
        return instance;
    }
}

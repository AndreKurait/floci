package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.TestTime;
import io.github.hectorvent.floci.testing.TimelineDeadlineWaits;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A container-backed launch is given five minutes to reach running, counted on the injected deadline source. */
class Ec2ContainerLaunchDeadlineTest {

    private static final String ACCOUNT = "000000000000";
    private static final Duration LAUNCH_TIMEOUT = Duration.ofMinutes(5);

    private final TestTime time = TestTime.create();
    private final TimelineDeadlineWaits waits = new TimelineDeadlineWaits(time);
    private final Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
    private final Ec2Service service = new Ec2Service(containerBackedConfig(), containerManager,
            mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
            new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(ACCOUNT), time.clock(), waits);

    @Test
    void aPendingLaunchIsCancelledWhenItsFiveMinutesAreUpAndNotBefore() throws Exception {
        Instance instance = pendingInstance("i-pending");
        when(containerManager.cancelLaunch(instance)).thenReturn(true);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> launch = executor.submit(() -> service.awaitContainerLaunch(instance));
            waits.awaitWaiting(1);

            time.advance(LAUNCH_TIMEOUT.minusNanos(1));
            assertFalse(launch.isDone(), "one nanosecond before the timeout the launch is still awaited");
            verify(containerManager, never()).cancelLaunch(instance);

            time.advance(Duration.ofNanos(1));
            ExecutionException failure = assertThrows(ExecutionException.class, launch::get);
            assertThat(failure.getCause(), instanceOf(AwsException.class));
            assertEquals("InternalError", ((AwsException) failure.getCause()).getErrorCode());
            assertThat(failure.getCause().getMessage(),
                    containsString("did not reach running state before the launch timeout"));
            verify(containerManager).cancelLaunch(instance);
        }
    }

    @Test
    void everyWaiterIsReleasedByTheInstanceReachingRunningWithNoTimePassing() throws Exception {
        Instance instance = pendingInstance("i-signalled");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> service.awaitContainerLaunch(instance));
            Future<?> second = executor.submit(() -> service.awaitContainerLaunch(instance));
            waits.awaitWaiting(2);

            instance.setState(InstanceState.running());

            first.get();
            second.get();
            verify(containerManager, never()).cancelLaunch(instance);
        }
    }

    @Test
    void aWaiterIsToldAtOnceThatTheContainerTerminatedDuringLaunch() throws Exception {
        Instance instance = pendingInstance("i-terminated");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> launch = executor.submit(() -> service.awaitContainerLaunch(instance));
            waits.awaitWaiting(1);

            instance.setState(InstanceState.terminated());

            ExecutionException failure = assertThrows(ExecutionException.class, launch::get);
            assertThat(failure.getCause().getMessage(), containsString("container terminated during launch"));
            verify(containerManager, never()).cancelLaunch(instance);
        }
    }

    private static Instance pendingInstance(String instanceId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        instance.setState(InstanceState.pending());
        return instance;
    }

    private static EmulatorConfig containerBackedConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.enabled()).thenReturn(true);
        when(ec2.mock()).thenReturn(false);
        return config;
    }
}

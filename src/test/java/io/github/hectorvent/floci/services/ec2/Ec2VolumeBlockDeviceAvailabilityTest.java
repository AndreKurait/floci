package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PingCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One answer of the Docker daemon is reused for five seconds of elapsed time, then asked for again. */
class Ec2VolumeBlockDeviceAvailabilityTest {

    private static final Duration ANSWER_REUSED_FOR = Duration.ofSeconds(5);

    @Test
    void theDaemonIsAskedAgainOnceItsLastAnswerIsFiveSecondsOld() {
        TestTime time = TestTime.create();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);
        DockerClient dockerClient = mock(DockerClient.class);
        PingCmd ping = mock(PingCmd.class);
        when(dockerClient.pingCmd()).thenReturn(ping);
        Ec2VolumeBlockDeviceManager manager = new Ec2VolumeBlockDeviceManager(dockerClient,
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), config, time::nanoTime);

        assertTrue(manager.isAvailable());
        doThrow(new IllegalStateException("daemon went away")).when(ping).exec();

        time.advance(ANSWER_REUSED_FOR.minusNanos(1));
        assertTrue(manager.isAvailable(), "the answer is still the one given before the daemon went away");
        verify(ping, times(1)).exec();

        time.advance(Duration.ofNanos(1));
        assertFalse(manager.isAvailable());
        verify(ping, times(2)).exec();

        time.advance(ANSWER_REUSED_FOR.minusNanos(1));
        assertFalse(manager.isAvailable(), "a refusal is reused for as long as an acceptance");
        verify(ping, times(2)).exec();
    }
}

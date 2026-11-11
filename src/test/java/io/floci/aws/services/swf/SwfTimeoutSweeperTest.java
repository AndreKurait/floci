package io.floci.aws.services.swf;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.ServicesConfig;
import io.floci.aws.config.EmulatorConfig.SwfServiceConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The sweep interval reaches {@code scheduleWithFixedDelay}, which rejects a non-positive
 * delay. Because the schedule happens in a {@link StartupEvent} observer, an
 * IllegalArgumentException there stops the whole emulator from becoming ready, so a
 * misconfigured interval must not be passed through unchecked.
 */
class SwfTimeoutSweeperTest {

    @Test
    void nonPositiveConfiguredInterval_doesNotPreventStartup() {
        for (long interval : new long[] {0L, -1L, Long.MIN_VALUE}) {
            SwfTimeoutSweeper sweeper = new SwfTimeoutSweeper(null, configWithInterval(interval));
            assertDoesNotThrow(() -> sweeper.onStart(new StartupEvent()),
                    "interval " + interval + " must not abort startup");
            sweeper.onStop(new ShutdownEvent());
        }
    }

    @Test
    void positiveConfiguredInterval_startsNormally() {
        SwfTimeoutSweeper sweeper = new SwfTimeoutSweeper(null, configWithInterval(30L));
        assertDoesNotThrow(() -> sweeper.onStart(new StartupEvent()));
        sweeper.onStop(new ShutdownEvent());
    }

    @Test
    void configFixtureRejectsOtherServicePaths() {
        EmulatorConfig config = configWithInterval(30L);

        AssertionError otherService = assertThrows(AssertionError.class, () -> config.services().ecs());
        assertTrue(otherService.getMessage().contains("ecs"));
        assertThrows(AssertionError.class, () -> config.services().rds());
    }

    private static EmulatorConfig configWithInterval(long intervalSeconds) {
        EmulatorConfig config = configView(EmulatorConfig.class);
        ServicesConfig services = configView(ServicesConfig.class);
        SwfServiceConfig swf = configView(SwfServiceConfig.class);
        doReturn(services).when(config).services();
        doReturn(swf).when(services).swf();
        doReturn(true).when(swf).enabled();
        doReturn(true).when(swf).timeoutSweepEnabled();
        doReturn(intervalSeconds).when(swf).timeoutSweepIntervalSeconds();
        return config;
    }

    private static <T> T configView(Class<T> configType) {
        return mock(configType, invocation -> {
            throw new AssertionError("Unexpected configuration access: " + invocation.getMethod());
        });
    }

    @Test
    void aSweepThatEndsInAnErrorDoesNotStopTheOnesAfterIt() {
        SwfService service = mock(SwfService.class);
        doThrow(new StackOverflowError("history too deep")).doNothing().when(service).sweep();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        new SwfTimeoutSweeper(service, configWithInterval(1L), scheduler).onStart(new StartupEvent());
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(tick.capture(), eq(1L), eq(1L), eq(TimeUnit.SECONDS));

        // A periodic task that throws is cancelled by its executor, so the Error must not leave the tick.
        assertDoesNotThrow(() -> tick.getValue().run());
        verify(service, times(1)).sweep();
        assertDoesNotThrow(() -> tick.getValue().run());
        verify(service, times(2)).sweep();
    }
}

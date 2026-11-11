package io.github.hectorvent.floci.services.swf;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.ServicesConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.SwfServiceConfig;
import io.github.hectorvent.floci.testing.TestTime;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
    void configuredSecondsIntervalIsTheSweepPeriod() {
        TestTime time = TestTime.create();
        SwfService service = mock(SwfService.class);
        new SwfTimeoutSweeper(service, configWithInterval(30L), time.scheduler()).onStart(new StartupEvent());

        time.advance(Duration.ofSeconds(30).minusMillis(1));
        verify(service, never()).sweep();
        time.advance(Duration.ofMillis(1));
        verify(service, times(1)).sweep();
        time.advance(Duration.ofSeconds(30));
        verify(service, times(2)).sweep();
    }

    @Test
    void millisecondIntervalOverridesTheSecondsInterval() {
        TestTime time = TestTime.create();
        SwfService service = mock(SwfService.class);
        new SwfTimeoutSweeper(service, configWithInterval(30L, 100L), time.scheduler()).onStart(new StartupEvent());

        time.advance(Duration.ofMillis(99));
        verify(service, never()).sweep();
        time.advance(Duration.ofMillis(1));
        verify(service, times(1)).sweep();
        time.advance(Duration.ofSeconds(30));
        verify(service, times(301)).sweep();
    }

    @Test
    void nonPositiveConfiguredInterval_fallsBackToTheDefaultPeriod() {
        for (long interval : new long[] {0L, -1L, Long.MIN_VALUE}) {
            TestTime time = TestTime.create();
            SwfService service = mock(SwfService.class);
            new SwfTimeoutSweeper(service, configWithInterval(interval), time.scheduler())
                    .onStart(new StartupEvent());

            time.advance(Duration.ofSeconds(SwfTimeoutSweeper.DEFAULT_TICK_INTERVAL_SECONDS).minusMillis(1));
            verify(service, never()).sweep();
            time.advance(Duration.ofMillis(1));
            verify(service, times(1)).sweep();
        }
    }

    @Test
    void aFailedSweepDoesNotStopTheOnesAfterIt() {
        TestTime time = TestTime.create();
        SwfService service = mock(SwfService.class);
        doThrow(new IllegalStateException("storage is briefly unavailable")).doNothing().when(service).sweep();
        new SwfTimeoutSweeper(service, configWithInterval(1L), time.scheduler()).onStart(new StartupEvent());

        time.advance(Duration.ofSeconds(1));
        verify(service, times(1)).sweep();
        time.advance(Duration.ofSeconds(1));
        verify(service, times(2)).sweep();
    }

    @Test
    void aDisabledSweeperNeverSweeps() {
        TestTime time = TestTime.create();
        SwfService service = mock(SwfService.class);
        EmulatorConfig config = configWithInterval(1L);
        SwfServiceConfig swf = config.services().swf();
        doReturn(false).when(swf).timeoutSweepEnabled();
        new SwfTimeoutSweeper(service, config, time.scheduler()).onStart(new StartupEvent());

        time.advance(Duration.ofHours(1));

        verify(service, never()).sweep();
    }

    /** The sweeper's own thread in real time; the cases above cover when it sweeps. */
    @Test
    void millisecondIntervalReachesTheSweepBeforeTheLegacyInterval() {
        SwfService service = mock(SwfService.class);
        SwfTimeoutSweeper sweeper = new SwfTimeoutSweeper(service, configWithInterval(30L, 100L));
        try {
            sweeper.onStart(new StartupEvent());
            verify(service, timeout(2000)).sweep();
        } finally {
            sweeper.onStop(new ShutdownEvent());
        }
    }

    @Test
    void configFixtureRejectsOtherServicePaths() {
        EmulatorConfig config = configWithInterval(30L);

        AssertionError otherService = assertThrows(AssertionError.class, () -> config.services().ecs());
        assertTrue(otherService.getMessage().contains("ecs"));
        assertThrows(AssertionError.class, () -> config.services().rds());
    }

    private static EmulatorConfig configWithInterval(long intervalSeconds) {
        return configWithInterval(intervalSeconds, 0L);
    }

    private static EmulatorConfig configWithInterval(long intervalSeconds, long intervalMillis) {
        EmulatorConfig config = configView(EmulatorConfig.class);
        ServicesConfig services = configView(ServicesConfig.class);
        SwfServiceConfig swf = configView(SwfServiceConfig.class);
        doReturn(services).when(config).services();
        doReturn(swf).when(services).swf();
        doReturn(true).when(swf).enabled();
        doReturn(true).when(swf).timeoutSweepEnabled();
        doReturn(intervalSeconds).when(swf).timeoutSweepIntervalSeconds();
        doReturn(intervalMillis).when(swf).timeoutSweepIntervalMillis();
        return config;
    }

    private static <T> T configView(Class<T> configType) {
        return mock(configType, invocation -> {
            throw new AssertionError("Unexpected configuration access: " + invocation.getMethod());
        });
    }


    @Test
    void aSweepThatEndsInAnErrorDoesNotStopTheOnesAfterIt() {
        TestTime time = TestTime.create();
        SwfService service = mock(SwfService.class);
        doThrow(new StackOverflowError("history too deep")).doNothing().when(service).sweep();
        new SwfTimeoutSweeper(service, configWithInterval(1L), time.scheduler()).onStart(new StartupEvent());

        time.advance(Duration.ofSeconds(1));
        verify(service, times(1)).sweep();
        time.advance(Duration.ofSeconds(1));
        verify(service, times(2)).sweep();
    }
}

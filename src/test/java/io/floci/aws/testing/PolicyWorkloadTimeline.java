package io.floci.aws.testing;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared only by the explicitly selected policy workload profile. */
@ApplicationScoped
public class PolicyWorkloadTimeline {
    private final ManualClock clock = new ManualClock(Instant.now().truncatedTo(ChronoUnit.MINUTES));
    private final Map<String, Callback> callbacks = new LinkedHashMap<>();

    public ManualClock clock() {
        return clock;
    }

    public synchronized ScheduledExecutorService scheduler(String name) {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AtomicBoolean closed = new AtomicBoolean();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    synchronized (this) {
                        assertNull(callbacks.putIfAbsent(name, new Callback(invocation.getArgument(0),
                                invocation.getArgument(1), invocation.getArgument(2),
                                invocation.getArgument(3), closed)));
                    }
                    return null;
                });
        doAnswer(invocation -> {
            closed.set(true);
            return null;
        }).when(scheduler).shutdown();
        when(scheduler.shutdownNow()).thenAnswer(invocation -> {
            closed.set(true);
            return List.of();
        });
        try {
            when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        when(scheduler.isShutdown()).thenAnswer(invocation -> closed.get());
        return scheduler;
    }

    public void advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("The workload clock cannot move backwards");
        }
        clock.advance(duration);
    }

    public void run(String name) {
        Callback callback;
        synchronized (this) {
            callback = callbacks.get(name);
            assertNotNull(callback, "The real lifecycle must register the scheduled callback");
            assertFalse(callback.closed().get());
        }
        callback.task().run();
    }

    public synchronized void assertSchedules() {
        assertEquals(List.of("evaluator", "publisher"), callbacks.keySet().stream().sorted().toList());
        assertEquals(5L, callbacks.get("evaluator").initialDelay());
        assertEquals(1L, callbacks.get("publisher").initialDelay());
        for (Callback callback : callbacks.values()) {
            assertEquals(10L, callback.period());
            assertEquals(TimeUnit.SECONDS, callback.unit());
        }
    }

    private record Callback(Runnable task, long initialDelay, long period, TimeUnit unit, AtomicBoolean closed) {}
}

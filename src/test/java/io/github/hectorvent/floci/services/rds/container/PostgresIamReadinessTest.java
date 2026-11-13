package io.github.hectorvent.floci.services.rds.container;

import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PostgresIamReadinessTest {
    private static final ContainerExec.Result READY = new ContainerExec.Result(0, "DO", "", false);
    private static final ContainerExec.Result STARTING =
            new ContainerExec.Result(2, "", "connection refused", false);

    @Test
    void successfulSqlReturnsWithoutSleeping() {
        PostgresIamReadiness.await("postgres", timeout -> {
            assertTrue(timeout.compareTo(Duration.ofSeconds(5)) <= 0);
            return READY;
        }, () -> 0, nanos -> fail("A ready database must not sleep"));
    }

    @Test
    void catchesReadinessJustAfterAOneSecondBoundary() {
        AtomicLong now = new AtomicLong();
        long readyAt = TimeUnit.MILLISECONDS.toNanos(1050);
        PostgresIamReadiness.await("postgres", timeout -> now.get() >= readyAt ? READY : STARTING,
                now::get, now::addAndGet);
        assertTrue(now.get() >= readyAt);
        assertTrue(now.get() < TimeUnit.MILLISECONDS.toNanos(1200),
                "Readiness just after one second must not wait for the two-second boundary");
    }

    @Test
    void fastPollingPreservesTheFullReadinessBudget() {
        AtomicLong now = new AtomicLong();
        long readyAt = TimeUnit.MILLISECONDS.toNanos(59_800);
        PostgresIamReadiness.await("postgres", timeout -> now.get() >= readyAt ? READY : STARTING,
                now::get, now::addAndGet);
        assertTrue(now.get() >= readyAt);
        assertTrue(now.get() < TimeUnit.SECONDS.toNanos(60));
    }

    @Test
    void expiryKeepsDiagnosticsAndBoundsEveryProbeByRemainingTime() {
        AtomicLong now = new AtomicLong();
        List<Duration> budgets = new ArrayList<>();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PostgresIamReadiness.await("postgres", timeout -> {
                    budgets.add(timeout);
                    return STARTING;
                }, now::get, now::addAndGet));
        assertEquals(TimeUnit.SECONDS.toNanos(60), now.get());
        assertTrue(failure.getMessage().contains("postgres"));
        assertTrue(failure.getMessage().contains("connection refused"));
        assertTrue(budgets.stream().allMatch(timeout -> !timeout.isNegative()
                && !timeout.isZero() && timeout.compareTo(Duration.ofSeconds(5)) <= 0));
        assertTrue(budgets.getLast().compareTo(Duration.ofSeconds(1)) < 0);
    }

    @Test
    void transientDockerFailureCanRecover() {
        AtomicLong now = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        PostgresIamReadiness.await("postgres", timeout -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("connection reset");
            }
            return READY;
        }, now::get, now::addAndGet);
        assertEquals(2, calls.get());
    }

    @Test
    void timedOutExecCannotOverlapAnotherSqlAttempt() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PostgresIamReadiness.await("postgres", timeout -> {
                    calls.incrementAndGet();
                    return new ContainerExec.Result(-1, "", "exec timed out", true);
                }, () -> 0, nanos -> fail("A timed-out exec must be cleaned up, not retried")));
        assertEquals(1, calls.get());
        assertTrue(failure.getMessage().contains("exec timed out"));
    }

    @Test
    void successAfterTheDeadlineIsRejected() {
        AtomicLong now = new AtomicLong();
        assertThrows(IllegalStateException.class, () -> PostgresIamReadiness.await("postgres", timeout -> {
            now.set(TimeUnit.SECONDS.toNanos(61));
            return READY;
        }, now::get, nanos -> fail("The expired budget must not sleep")));
    }

    @Test
    void interruptedSleepRestoresTheInterruptFlag() {
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            assertThrows(IllegalStateException.class,
                    () -> PostgresIamReadiness.await("postgres", timeout -> STARTING, () -> 0, nanos -> {
                        throw new InterruptedException("cancelled");
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preexistingInterruptPreventsSqlExecution() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class,
                    () -> PostgresIamReadiness.await("postgres", timeout -> {
                        fail("Cancelled initialization must not execute SQL");
                        return READY;
                    }, () -> 0, nanos -> fail("Cancelled initialization must not sleep")));
        } finally {
            Thread.interrupted();
        }
    }
}

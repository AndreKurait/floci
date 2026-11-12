package io.floci.aws.services.rds.container;

import io.floci.aws.core.common.docker.ContainerExec;
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
    private static final ContainerExec.Result EXEC_TIMED_OUT =
            new ContainerExec.Result(-1, "", "Timed out after 5s", true);
    private static final Duration MIN_EXEC = Duration.ofMillis(500);

    @Test
    void successfulSqlReturnsWithoutSleeping() {
        PostgresIamReadiness.await("postgres", timeout -> {
            assertTrue(timeout.compareTo(Duration.ofSeconds(5)) <= 0);
            return READY;
        }, () -> 0, nanos -> fail("A ready database must not sleep"));
    }

    @Test
    void startupNotificationWakesBeforeTheNextPollingBoundary() {
        AtomicLong now = new AtomicLong();
        long readyAt = TimeUnit.MILLISECONDS.toNanos(1050);
        PostgresIamReadiness.await("postgres", timeout -> now.get() >= readyAt ? READY : STARTING,
                now::get, nanos -> now.set(Math.min(now.get() + nanos, readyAt)));
        assertEquals(readyAt, now.get(), "The final-server log wakes readiness immediately");
    }

    @Test
    void readinessNearTheDeadlineStillReceivesARemainingExecBudget() {
        AtomicLong now = new AtomicLong();
        List<Duration> budgets = new ArrayList<>();
        long readyAt = TimeUnit.MILLISECONDS.toNanos(59_400);
        PostgresIamReadiness.await("postgres", timeout -> {
            budgets.add(timeout);
            return now.get() >= readyAt ? READY : STARTING;
        }, now::get, nanos -> now.set(Math.min(now.get() + nanos, readyAt)));
        assertEquals(readyAt, now.get());
        assertEquals(Duration.ofMillis(600), budgets.getLast(), "The last probe gets all of the remaining budget");
    }

    @Test
    void remainingBudgetTooShortForAnExecKeepsTheLastSqlError() {
        AtomicLong now = new AtomicLong();
        List<Duration> budgets = new ArrayList<>();
        long wokenAt = TimeUnit.MILLISECONDS.toNanos(59_800);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PostgresIamReadiness.await("postgres", timeout -> {
                    budgets.add(timeout);
                    return now.get() >= wokenAt
                            ? new ContainerExec.Result(-1, "", "Timed out after PT0.2S", true) : STARTING;
                }, now::get, nanos -> now.set(Math.min(now.get() + nanos, wokenAt))));
        assertEquals(wokenAt, now.get(), "No probe or wait follows the wakeup");
        assertTrue(budgets.stream().allMatch(timeout -> timeout.compareTo(MIN_EXEC) >= 0));
        assertEquals("Timed out initializing PostgreSQL IAM role in postgres: connection refused",
                failure.getMessage());
    }

    @Test
    void expiryKeepsDiagnosticsAndBoundsEveryProbeByRemainingTime() {
        AtomicLong now = new AtomicLong();
        List<Duration> budgets = new ArrayList<>();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PostgresIamReadiness.await("postgres", timeout -> {
                    budgets.add(timeout);
                    now.addAndGet(Math.min(timeout.toNanos(), TimeUnit.MILLISECONDS.toNanos(123)));
                    return STARTING;
                }, now::get, now::addAndGet));
        assertTrue(now.get() <= TimeUnit.SECONDS.toNanos(60));
        assertTrue(now.get() > TimeUnit.SECONDS.toNanos(60) - MIN_EXEC.toNanos());
        assertEquals("Timed out initializing PostgreSQL IAM role in postgres: connection refused",
                failure.getMessage());
        assertTrue(budgets.stream().allMatch(timeout -> timeout.compareTo(MIN_EXEC) >= 0
                && timeout.compareTo(Duration.ofSeconds(5)) <= 0));
        assertTrue(budgets.getLast().compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void brokenExecConnectionIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PostgresIamReadiness.await("postgres", timeout -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("connection reset");
                }, () -> 0, nanos -> fail("An exec that could not run must not be retried")));
        assertEquals(1, calls.get());
        assertEquals("connection reset", failure.getCause().getMessage());
    }

    @Test
    void slowFailedProbesConsumeTheBudgetInsteadOfMultiplyingIt() {
        AtomicLong now = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> PostgresIamReadiness.await("postgres", timeout -> {
            calls.incrementAndGet();
            now.addAndGet(timeout.toNanos());
            return STARTING;
        }, now::get, now::addAndGet));
        assertEquals(TimeUnit.SECONDS.toNanos(60), now.get());
        assertEquals(10, calls.get());
    }

    @Test
    void timedOutExecIsRetriedAfterWaiting() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        PostgresIamReadiness.await("postgres",
                timeout -> calls.incrementAndGet() == 1 ? EXEC_TIMED_OUT : READY,
                () -> 0, nanos -> waits.incrementAndGet());
        assertEquals(2, calls.get());
        assertEquals(1, waits.get());
    }

    @Test
    void execTimeoutsThatExhaustTheBudgetAreReportedWithTheLastSqlError() {
        AtomicLong now = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PostgresIamReadiness.await("postgres", timeout -> {
                    if (calls.incrementAndGet() == 1) {
                        return STARTING;
                    }
                    now.addAndGet(timeout.toNanos());
                    return EXEC_TIMED_OUT;
                }, now::get, now::addAndGet));
        // One instant failure, then ten 5 s timeouts, each preceded by a 1 s wait.
        assertEquals(11, calls.get());
        assertEquals(TimeUnit.SECONDS.toNanos(60), now.get());
        assertEquals("Timed out initializing PostgreSQL IAM role in postgres: "
                + "10 exec(s) did not finish in time; last output: connection refused", failure.getMessage());
    }

    @Test
    void missingExitStatusIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        PostgresIamReadiness.await("postgres",
                timeout -> calls.incrementAndGet() == 1 ? new ContainerExec.Result(-1, "", "", false) : READY,
                () -> 0, nanos -> waits.incrementAndGet());
        assertEquals(2, calls.get());
        assertEquals(1, waits.get());
    }

    @Test
    void successThatCompletesAfterTheDeadlineIsAccepted() {
        AtomicLong now = new AtomicLong();
        PostgresIamReadiness.await("postgres", timeout -> {
            now.set(TimeUnit.SECONDS.toNanos(61));
            return READY;
        }, now::get, nanos -> fail("A completed success must not sleep"));
    }

    @Test
    void failureThatCompletesAfterTheDeadlineExpiresWithoutSleeping() {
        AtomicLong now = new AtomicLong();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PostgresIamReadiness.await("postgres", timeout -> {
                    now.set(TimeUnit.SECONDS.toNanos(61));
                    return STARTING;
                }, now::get, nanos -> fail("The expired budget must not sleep")));
        assertTrue(failure.getMessage().contains("connection refused"));
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

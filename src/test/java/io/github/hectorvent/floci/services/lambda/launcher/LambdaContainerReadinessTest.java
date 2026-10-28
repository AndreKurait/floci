package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaContainerReadinessTest {

    private static final ContainerExec.Result READY = result(0);
    private final AtomicLong clock = new AtomicLong();

    @Test
    void noConfiguredCommandDoesNotProbeOrRequireABudget() {
        LambdaContainerReadiness.await(Optional.empty(), 0,
                (command, remaining) -> {
                    throw new AssertionError("Unexpected probe");
                }, clock::get, nanos -> {
                    throw new AssertionError("Unexpected wait");
                });
    }

    @Test
    void retriesBeforeAdmissionWithOneDecreasingBudgetAndLiteralArguments() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> budgets = new ArrayList<>();
        LambdaContainerReadiness.await(Optional.of("probe 'two words' '$(not-a-shell)'"), 10,
                (command, remaining) -> {
                    assertArrayEquals(new String[]{"probe", "two words", "$(not-a-shell)"}, command);
                    budgets.add(remaining);
                    return attempts.incrementAndGet() == 3 ? READY : result(22);
                }, clock::get, nanos -> clock.addAndGet(TimeUnit.SECONDS.toNanos(2)));
        assertEquals(3, attempts.get());
        assertEquals(List.of(10_000_000_000L, 8_000_000_000L, 6_000_000_000L), budgets);
    }

    @Test
    void oneSecondBudgetAllowsAProbeAfterClockAdvances() {
        AtomicInteger attempts = new AtomicInteger();
        LambdaContainerReadiness.await(Optional.of("probe"), 1,
                (command, remaining) -> {
                    attempts.incrementAndGet();
                    assertEquals(TimeUnit.SECONDS.toNanos(1) - 1, remaining);
                    return READY;
                }, clock::getAndIncrement, nanos -> {
                    throw new AssertionError("Ready probe must not wait");
                });
        assertEquals(1, attempts.get());
    }

    @Test
    void retryCanSucceedDuringTheLastFractionalSecond() {
        AtomicInteger attempts = new AtomicInteger();
        LambdaContainerReadiness.await(Optional.of("probe"), 2,
                (command, remaining) -> attempts.incrementAndGet() == 2 ? READY : result(1),
                clock::get, nanos -> clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1500)));
        assertEquals(2, attempts.get());
    }

    @Test
    void retrySleepUsesOnlyTheRemainingBudget() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> LambdaContainerReadiness.await(
                Optional.of("probe"), 1, (command, remaining) -> {
                    attempts.incrementAndGet();
                    clock.set(TimeUnit.MILLISECONDS.toNanos(900));
                    return result(1);
                }, clock::get, nanos -> {
                    assertEquals(TimeUnit.MILLISECONDS.toNanos(100), nanos);
                    clock.addAndGet(nanos);
                }));
        assertEquals(1, attempts.get());
    }

    @Test
    void unfinishedProbeFailsWithoutStartingAnotherExec() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> LambdaContainerReadiness.await(
                Optional.of("probe"), 10, (command, remaining) -> {
                    attempts.incrementAndGet();
                    return new ContainerExec.Result(-1, "", "", true);
                }, clock::get, nanos -> {
                    throw new AssertionError("Timed-out exec must not be retried");
                }));
        assertEquals(1, attempts.get());
    }

    @Test
    void aSuccessAfterTheDeadlineDoesNotAdmitTheContainer() {
        assertThrows(IllegalStateException.class, () -> LambdaContainerReadiness.await(
                Optional.of("probe"), 10, (command, remaining) -> {
                    clock.set(TimeUnit.SECONDS.toNanos(10));
                    return READY;
                }, clock::get, nanos -> {
                    throw new AssertionError("Expired probe must not be retried");
                }));
    }

    @Test
    void exhaustedBudgetFailsBeforeStartingAnotherProbe() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> LambdaContainerReadiness.await(
                Optional.of("probe"), 2, (command, remaining) -> {
                    attempts.incrementAndGet();
                    return result(1);
                }, clock::get, nanos -> clock.addAndGet(TimeUnit.SECONDS.toNanos(2))));
        assertEquals(1, attempts.get());
    }

    @Test
    void missingOrNonExecutableProbeFailsWithoutRetry() {
        for (long code : List.of(-1L, 126L, 127L)) {
            assertThrows(IllegalStateException.class, () -> LambdaContainerReadiness.await(
                    Optional.of("probe"), 10, (command, remaining) -> result(code),
                    clock::get, nanos -> {
                        throw new AssertionError("Invalid probe must not be retried");
                    }));
        }
    }

    @Test
    void invalidConfigurationFailsBeforeExec() {
        for (int budget : List.of(0, -1, 901)) {
            assertThrows(IllegalArgumentException.class, () -> LambdaContainerReadiness.await(
                    Optional.of("probe"), budget, (command, remaining) -> {
                        throw new AssertionError("Invalid config must not execute");
                    }, clock::get, nanos -> { }));
        }
    }

    @Test
    void interruptionPreventsAdmissionAndPreservesTheInterruptFlag() {
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            assertThrows(IllegalStateException.class, () -> LambdaContainerReadiness.await(
                    Optional.of("probe"), 10, (command, remaining) -> result(1),
                    clock::get, nanos -> {
                        throw new InterruptedException("cancel");
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static ContainerExec.Result result(long code) {
        return new ContainerExec.Result(code, "", "", false);
    }

    @Test
    void interruptionDuringSuccessfulProbeStillPreventsAdmission() {
        try {
            assertThrows(IllegalStateException.class, () -> LambdaContainerReadiness.await(
                    Optional.of("probe"), 10, (command, remaining) -> {
                        Thread.currentThread().interrupt();
                        return READY;
                    }, clock::get, nanos -> { }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}

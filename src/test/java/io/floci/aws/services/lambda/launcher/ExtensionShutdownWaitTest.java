package io.floci.aws.services.lambda.launcher;

import io.floci.aws.services.lambda.launcher.ExtensionShutdownWait.ExtensionExec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionShutdownWaitTest {

    /** Long enough that only a completed wait returns before the hang guard. */
    private static final long PATIENT_NANOS = Duration.ofMinutes(10).toNanos();
    private static final Duration HANG_GUARD = Duration.ofSeconds(20);
    private static final Predicate<String> NEVER_ASKED = execId -> {
        throw new AssertionError("Unexpected daemon inspection of " + execId);
    };

    @Test
    void awaitReturnsOnceTheLastOpenStreamEnds() throws Exception {
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();
        ExtensionShutdownWait wait = new ExtensionShutdownWait(
                Map.of("first", true, "second", true),
                Map.of("first", new ExtensionExec("exec-first", first),
                        "second", new ExtensionExec("exec-second", second)),
                NEVER_ASKED);

        try (ExecutorService stopper = Executors.newSingleThreadExecutor()) {
            Future<Boolean> completed = stopper.submit(() -> wait.await(PATIENT_NANOS));
            try {
                first.complete(null);
                assertFalse(wait.isComplete());
                second.complete(null);
                assertTrue(completed.get(HANG_GUARD.toSeconds(), TimeUnit.SECONDS));
            } finally {
                // A failed assertion must not leave the worker waiting out the budget.
                stopper.shutdownNow();
            }
        }
    }

    @Test
    void lostStreamIsPolledUntilTheDaemonReportsTheProcessGone() {
        AtomicInteger inspections = new AtomicInteger();
        ExtensionShutdownWait wait = new ExtensionShutdownWait(
                Map.of("shutdown", true),
                Map.of("shutdown", lost("shutdown")),
                execId -> inspections.incrementAndGet() >= 3);

        assertTrue(assertTimeoutPreemptively(HANG_GUARD, () -> wait.await(PATIENT_NANOS)));

        assertEquals(3, inspections.get());
        // A confirmed exit is remembered: the daemon is not asked again.
        assertTrue(wait.isComplete());
        assertEquals(3, inspections.get());
    }

    @Test
    void streamLostDuringTheWaitIsResolvedByTheDaemon() throws Exception {
        CompletableFuture<Void> output = new CompletableFuture<>();
        ExtensionShutdownWait wait = new ExtensionShutdownWait(
                Map.of("shutdown", true),
                Map.of("shutdown", new ExtensionExec("exec-shutdown", output)),
                "exec-shutdown"::equals);

        try (ExecutorService stopper = Executors.newSingleThreadExecutor()) {
            Future<Boolean> completed = stopper.submit(() -> wait.await(PATIENT_NANOS));
            try {
                output.completeExceptionally(new IOException("stream reset"));
                assertTrue(completed.get(HANG_GUARD.toSeconds(), TimeUnit.SECONDS));
            } finally {
                stopper.shutdownNow();
            }
        }
    }

    @Test
    void lostStreamOfARunningProcessHoldsUntilTheBudgetEnds() throws Exception {
        AtomicInteger inspections = new AtomicInteger();
        ExtensionShutdownWait wait = new ExtensionShutdownWait(
                Map.of("shutdown", true),
                Map.of("shutdown", lost("shutdown")),
                execId -> {
                    inspections.incrementAndGet();
                    return false;
                });

        assertFalse(wait.await(ExtensionShutdownWait.LOST_STREAM_POLL.toNanos() * 3));

        assertTrue(inspections.get() >= 2, "The daemon is asked again while the budget lasts");
    }

    @Test
    void oneUnmatchedExitSatisfiesOneAliasSubscriber() {
        CompletableFuture<Void> exit = new CompletableFuture<>();
        ExtensionShutdownWait wait = new ExtensionShutdownWait(
                Map.of("custom-name", true),
                Map.of("binary-name", new ExtensionExec("exec-binary-name", exit),
                        "never-registered", running("never-registered")),
                NEVER_ASKED);

        assertFalse(wait.isComplete());
        exit.complete(null);

        // The process that never registered and never exits no longer costs the budget.
        assertTrue(wait.isComplete());
    }

    @Test
    void eachAliasSubscriberNeedsItsOwnUnmatchedExit() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();
        ExtensionShutdownWait wait = new ExtensionShutdownWait(
                Map.of("alias-one", true, "alias-two", true),
                Map.of("binary-one", new ExtensionExec("exec-binary-one", first),
                        "binary-two", new ExtensionExec("exec-binary-two", second),
                        "never-registered", running("never-registered")),
                NEVER_ASKED);

        first.complete(null);
        assertFalse(wait.isComplete());
        second.complete(null);
        assertTrue(wait.isComplete());
    }

    @Test
    void unmatchedProcessThatEndedBeforeShutdownDoesNotStandInForTheSubscriber() {
        CompletableFuture<Void> exit = new CompletableFuture<>();
        ExtensionShutdownWait wait = new ExtensionShutdownWait(
                Map.of("custom-name", true),
                Map.of("crashed-earlier", new ExtensionExec("exec-crashed-earlier",
                                CompletableFuture.completedFuture(null)),
                        "binary-name", new ExtensionExec("exec-binary-name", exit)),
                NEVER_ASKED);

        assertFalse(wait.isComplete());
        exit.complete(null);
        assertTrue(wait.isComplete());
    }

    @Test
    void aliasSubscriberWithoutARunningUnmatchedProcessDoesNotWait() {
        ExtensionShutdownWait wait = new ExtensionShutdownWait(
                Map.of("internal", true, "shutdown", true),
                Map.of("shutdown", new ExtensionExec("exec-shutdown", CompletableFuture.completedFuture(null)),
                        "lost-and-gone", lost("lost-and-gone")),
                "exec-lost-and-gone"::equals);

        assertTrue(wait.isComplete());
    }

    @Test
    void unmatchedProcessesAreIgnoredWithoutAnAliasSubscriber() {
        ExtensionShutdownWait wait = new ExtensionShutdownWait(
                Map.of("invoke-only", false),
                Map.of("invoke-only", running("invoke-only"),
                        "never-registered", running("never-registered"),
                        "lost", lost("lost")),
                NEVER_ASKED);

        assertTrue(wait.isComplete());
    }

    private static ExtensionExec running(String name) {
        return new ExtensionExec("exec-" + name, new CompletableFuture<>());
    }

    private static ExtensionExec lost(String name) {
        return new ExtensionExec("exec-" + name, CompletableFuture.failedFuture(new IOException("stream reset")));
    }
}

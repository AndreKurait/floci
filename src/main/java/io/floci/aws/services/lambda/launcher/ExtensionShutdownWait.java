package io.floci.aws.services.lambda.launcher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The extension processes one container stop waits for.
 *
 * <p>A process has exited once its exec output stream completes, which also means its final logs
 * were delivered. A stream that failed (a daemon restart, a reset connection) says nothing about
 * the process and reports nothing further, so the daemon is asked instead, again and again until
 * the process is gone or the budget ends.
 */
final class ExtensionShutdownWait {

    /** How often the daemon is asked about a process whose output stream failed. */
    static final Duration LOST_STREAM_POLL = Duration.ofMillis(100);

    /** A launched external extension: its exec, and the future its output stream settles. */
    record ExtensionExec(String execId, CompletableFuture<Void> output) { }

    private final Predicate<String> execExited;
    private final Set<String> confirmedExited = ConcurrentHashMap.newKeySet();
    private final List<ExtensionExec> subscribed = new ArrayList<>();
    private final List<ExtensionExec> aliasCandidates = new ArrayList<>();
    private final int aliasQuota;

    /**
     * Selects what to wait for. Call it before SHUTDOWN is sent, so that a process which had
     * already ended is not taken for one answering the event.
     *
     * @param subscriptions registration names and whether each receives SHUTDOWN
     * @param execs launched extension processes by executable name
     * @param execExited whether the daemon reports an exec id as no longer running
     */
    ExtensionShutdownWait(Map<String, Boolean> subscriptions, Map<String, ExtensionExec> execs,
                          Predicate<String> execExited) {
        this.execExited = execExited;
        long aliasSubscribers = subscriptions.entrySet().stream()
                .filter(entry -> entry.getValue() && !execs.containsKey(entry.getKey()))
                .count();
        execs.forEach((name, exec) -> {
            Boolean receivesShutdown = subscriptions.get(name);
            if (Boolean.TRUE.equals(receivesShutdown)) {
                subscribed.add(exec);
            } else if (receivesShutdown == null && aliasSubscribers > 0 && !exited(exec)) {
                aliasCandidates.add(exec);
            }
        });
        // Floci permits registration under a name different from the executable, so a SHUTDOWN
        // subscriber without an exec of its name is one of the unmatched running processes,
        // unknown which. Waiting for as many of them to exit as there are such subscribers keeps
        // an unmatched process that never exits (INVOKE-only, or never registered) from costing
        // the whole budget. It still does when fewer exit than subscribed, for example when a
        // subscriber is not a launched process or had ended earlier.
        this.aliasQuota = (int) Math.min(aliasSubscribers, aliasCandidates.size());
    }

    /** True when every process this stop waits for has exited. */
    boolean isComplete() {
        for (ExtensionExec exec : subscribed) {
            if (!exited(exec)) {
                return false;
            }
        }
        int exits = 0;
        for (ExtensionExec exec : aliasCandidates) {
            if (exits == aliasQuota) {
                break;
            }
            if (exited(exec)) {
                exits++;
            }
        }
        return exits == aliasQuota;
    }

    /** Waits until complete or until {@code budgetNanos} have passed; returns whether it completed. */
    boolean await(long budgetNanos) throws InterruptedException {
        long deadline = System.nanoTime() + budgetNanos;
        while (true) {
            // Taken before the check: a stream ending in between is then seen by one or the other.
            CompletableFuture<?>[] open = required().map(ExtensionExec::output)
                    .filter(output -> !output.isDone())
                    .toArray(CompletableFuture[]::new);
            if (isComplete()) {
                return true;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            boolean lostStream = required().anyMatch(exec -> exec.output().isCompletedExceptionally()
                    && !confirmedExited.contains(exec.execId()));
            long slice = lostStream ? Math.min(remaining, LOST_STREAM_POLL.toNanos()) : remaining;
            try {
                CompletableFuture.anyOf(open).get(slice, TimeUnit.NANOSECONDS);
            } catch (ExecutionException | TimeoutException | CancellationException expected) {
                // A stream failed or the slice ended. Either way the loop looks again.
            }
        }
    }

    private Stream<ExtensionExec> required() {
        return Stream.concat(subscribed.stream(), aliasCandidates.stream());
    }

    private boolean exited(ExtensionExec exec) {
        CompletableFuture<Void> output = exec.output();
        if (!output.isDone()) {
            return false;
        }
        if (!output.isCompletedExceptionally() || confirmedExited.contains(exec.execId())) {
            return true;
        }
        if (!execExited.test(exec.execId())) {
            return false;
        }
        confirmedExited.add(exec.execId());
        return true;
    }
}

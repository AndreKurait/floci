package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.lambda.model.InvocationTimer;
import io.github.hectorvent.floci.testing.TestTime;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An {@link InvocationTimer} on a {@link TestTime} timeline. Timeouts and delayed work become
 * scheduled tasks, which run on whichever thread advances the timeline. A blocking wait advances the
 * timeline itself, task by task, until the future it waits for completes or its timeout has passed:
 * the test thread is the one waiting, so nobody else could.
 */
public final class TimelineInvocationTimer implements InvocationTimer {

    private final TestTime time;

    public TimelineInvocationTimer(TestTime time) {
        this.time = time;
    }

    @Override
    public long currentTimeMillis() {
        return time.currentTimeMillis();
    }

    @Override
    public void failAfter(CompletableFuture<?> future, long timeoutMs) {
        time.scheduler().schedule(() -> {
            future.completeExceptionally(new TimeoutException());
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void runAfter(long delayMs, Executor executor, Runnable task) {
        time.scheduler().schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public <T> T await(CompletableFuture<T> future) throws InterruptedException, ExecutionException {
        while (!future.isDone()) {
            Instant next = time.nextTaskDue().orElseThrow(() -> new AssertionError(
                    "nothing left on the timeline could complete this wait"));
            advanceTo(next);
        }
        return future.get();
    }

    @Override
    public <T> T await(CompletableFuture<T> future, long timeoutMs)
            throws InterruptedException, ExecutionException, TimeoutException {
        Instant deadline = time.now().plusMillis(timeoutMs);
        while (!future.isDone() && time.now().isBefore(deadline)) {
            Optional<Instant> next = time.nextTaskDue().filter(due -> due.isBefore(deadline));
            advanceTo(next.orElse(deadline));
        }
        if (!future.isDone()) {
            throw new TimeoutException();
        }
        return future.get();
    }

    private void advanceTo(Instant instant) {
        if (instant.isAfter(time.now())) {
            time.advanceTo(instant);
        } else {
            time.runDue();
        }
    }
}

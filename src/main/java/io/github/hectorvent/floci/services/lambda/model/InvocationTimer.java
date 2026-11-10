package io.github.hectorvent.floci.services.lambda.model;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The time an invocation is measured on and the waits made on its behalf. Everything outside tests
 * uses {@link #SYSTEM}; the interface exists so that a test can run function timeouts and retry
 * delays on a timeline it controls. It is only ever passed through constructors.
 */
public interface InvocationTimer {

    /** The system clock, the JDK's delay thread and plain blocking waits. */
    InvocationTimer SYSTEM = new InvocationTimer() {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void failAfter(CompletableFuture<?> future, long timeoutMs) {
            future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void runAfter(long delayMs, Executor executor, Runnable task) {
            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, executor).execute(task);
        }

        @Override
        public <T> T await(CompletableFuture<T> future) throws InterruptedException, ExecutionException {
            return future.get();
        }

        @Override
        public <T> T await(CompletableFuture<T> future, long timeoutMs)
                throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
    };

    /** Milliseconds since the epoch. */
    long currentTimeMillis();

    /** Fails {@code future} with a {@link TimeoutException} unless it completes within {@code timeoutMs}. */
    void failAfter(CompletableFuture<?> future, long timeoutMs);

    /** Runs {@code task} on {@code executor} once {@code delayMs} have passed. */
    void runAfter(long delayMs, Executor executor, Runnable task);

    /** Blocks until {@code future} completes and returns its result. */
    <T> T await(CompletableFuture<T> future) throws InterruptedException, ExecutionException;

    /** Blocks until {@code future} completes, for at most {@code timeoutMs}, and returns its result. */
    <T> T await(CompletableFuture<T> future, long timeoutMs)
            throws InterruptedException, ExecutionException, TimeoutException;
}

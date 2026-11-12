package io.floci.aws.core.common.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The elapsed-time source and bounded wait behind a deadline on work another thread finishes.
 * Production reads the JVM's monotonic clock and blocks on the future itself. A test substitutes a
 * timeline it moves, so a deadline is reached by advancing that timeline instead of waiting it out.
 * A method or constructor parameter only: nothing in the configuration selects an implementation.
 */
public interface DeadlineWaits {

    /** {@link System#nanoTime()} and the JDK's own timed waits. */
    DeadlineWaits SYSTEM = new DeadlineWaits() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public <T> T get(Future<T> future, long timeoutNanos)
                throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public boolean await(CountDownLatch latch, long timeoutNanos) throws InterruptedException {
            return latch.await(timeoutNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public void waitOn(Object monitor, long timeoutNanos) throws InterruptedException {
            TimeUnit.NANOSECONDS.timedWait(monitor, timeoutNanos);
        }
    };

    /** A monotonic reading in nanoseconds; only differences between two readings mean anything. */
    long nanoTime();

    /** The result of {@code future}, or {@link TimeoutException} when it has none within the timeout. */
    <T> T get(Future<T> future, long timeoutNanos)
            throws InterruptedException, ExecutionException, TimeoutException;

    /** Whether {@code latch} reached zero within the timeout. */
    boolean await(CountDownLatch latch, long timeoutNanos) throws InterruptedException;

    /**
     * Waits on {@code monitor}, which the caller has locked, until it is notified or the timeout
     * passes. Like {@link Object#wait(long)} it can return early, so the caller rechecks its
     * condition and the time left.
     */
    void waitOn(Object monitor, long timeoutNanos) throws InterruptedException;
}

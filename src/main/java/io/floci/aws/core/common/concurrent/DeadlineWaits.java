package io.floci.aws.core.common.concurrent;

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

    /** {@link System#nanoTime()} and a real timed {@link Future#get(long, TimeUnit)}. */
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
    };

    /** A monotonic reading in nanoseconds; only differences between two readings mean anything. */
    long nanoTime();

    /** The result of {@code future}, or {@link TimeoutException} when it has none within the timeout. */
    <T> T get(Future<T> future, long timeoutNanos)
            throws InterruptedException, ExecutionException, TimeoutException;
}

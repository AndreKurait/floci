package io.floci.aws.services.stepfunctions;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The bounded waits a Step Functions worker makes on work another thread finishes: a task token's
 * callback, a Parallel branch or Map iteration completing, an activity task being scheduled.
 * Production blocks on the primitive itself. A test substitutes waits that the clock it controls
 * brings to an end, so a timeout is reached by moving that clock instead of by waiting it out.
 * A constructor parameter only: nothing in the configuration selects an implementation.
 */
interface BoundedWaits {

    /** Blocks on the JDK primitive for the real duration. */
    BoundedWaits BLOCKING = new BoundedWaits() {
        @Override
        public <T> T get(Future<T> future, long timeoutNanos)
                throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public <T> T poll(BlockingQueue<T> queue, long timeoutNanos) throws InterruptedException {
            return queue.poll(timeoutNanos, TimeUnit.NANOSECONDS);
        }
    };

    /** The result of {@code future}, or {@link TimeoutException} when it has none within the timeout. */
    <T> T get(Future<T> future, long timeoutNanos)
            throws InterruptedException, ExecutionException, TimeoutException;

    /** The head of {@code queue}, or null when nothing arrives within the timeout. */
    <T> T poll(BlockingQueue<T> queue, long timeoutNanos) throws InterruptedException;
}

package io.github.hectorvent.floci.services.codedeploy;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * How deployment workers measure elapsed time and wait. Production uses {@link #SYSTEM}; tests pass a
 * controlled timeline through the package-private constructors, so configured intervals and deadlines
 * keep their real values.
 */
interface CodeDeployTimer {

    CodeDeployTimer SYSTEM = new CodeDeployTimer() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void sleepNanos(long nanos) throws InterruptedException {
            TimeUnit.NANOSECONDS.sleep(nanos);
        }

        @Override
        public boolean awaitDone(Future<?> future, long nanos) throws InterruptedException {
            try {
                future.get(nanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException | CancellationException expected) {
                // Safe to drop: the caller reads the outcome from the future itself.
            }
            return future.isDone();
        }
    };

    /** A monotonic reading in nanoseconds, like {@link System#nanoTime()}. */
    long nanoTime();

    void sleepNanos(long nanos) throws InterruptedException;

    /** Waits up to {@code nanos} for {@code future} to complete in any way; true once it has. */
    boolean awaitDone(Future<?> future, long nanos) throws InterruptedException;
}

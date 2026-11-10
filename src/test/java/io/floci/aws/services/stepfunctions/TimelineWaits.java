package io.floci.aws.services.stepfunctions;

import io.floci.aws.services.stepfunctions.model.ExecutionStatusSubscription;
import io.floci.aws.testing.TestTime;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The executor's bounded waits on a {@link TestTime} timeline: a waiter blocks with no real timeout
 * and is ended by the timeline reaching the instant its timeout names, or by the work it waits on.
 * Nothing but an {@code advance} can therefore make a wait time out, which is what makes
 * "one nanosecond earlier it is still waiting" a deterministic assertion.
 *
 * <p>An advance that ends a wait holds the timeline still until the woken thread has stopped
 * running again (in its next wait or sleep, or done), the way {@link TestTime} does for the sleepers
 * it wakes, so what the test reads after {@code advance} returns is what that instant left behind.
 */
final class TimelineWaits implements BoundedWaits, AslExecutor.NestedWaiter {

    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(10);

    private final TestTime time;
    private final Object monitor = new Object();
    private int waiting;

    TimelineWaits(TestTime time) {
        this.time = time;
    }

    @Override
    public <T> T get(Future<T> future, long timeoutNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (timeoutNanos <= 0) {
            return time.get(future, Duration.ZERO);
        }
        return time.within(Duration.ofNanos(timeoutNanos), () -> {
            count(1);
            try {
                return future.get();
            } finally {
                count(-1);
            }
        });
    }

    @Override
    public <T> T poll(BlockingQueue<T> queue, long timeoutNanos) throws InterruptedException {
        if (timeoutNanos <= 0) {
            return queue.poll();
        }
        try {
            return time.within(Duration.ofNanos(timeoutNanos), () -> {
                count(1);
                try {
                    return queue.take();
                } finally {
                    count(-1);
                }
            });
        } catch (TimeoutException expired) {
            return null;
        }
    }

    @Override
    public void awaitChange(ExecutionStatusSubscription subscription, long observed, long timeoutNanos)
            throws InterruptedException {
        if (timeoutNanos <= 0) {
            return;
        }
        try {
            time.within(Duration.ofNanos(timeoutNanos), () -> {
                count(1);
                try {
                    subscription.awaitChange(observed, Long.MAX_VALUE);
                    return null;
                } finally {
                    count(-1);
                }
            });
        } catch (TimeoutException expired) {
            // The wait is over either way; the caller reads the status again.
        }
    }

    /** How many threads are inside one of these waits right now. */
    int waiting() {
        synchronized (monitor) {
            return waiting;
        }
    }

    /**
     * Blocks until exactly {@code count} threads are inside one of these waits, each with its expiry
     * already on the timeline, so the advance that follows cannot run ahead of a worker.
     */
    void awaitWaiting(int count) {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        synchronized (monitor) {
            while (waiting != count) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("expected " + count + " thread(s) in a bounded wait, found "
                            + waiting + " after " + SETTLE_TIMEOUT);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted waiting for " + count + " bounded wait(s)", e);
                }
            }
        }
    }

    private void count(int delta) {
        synchronized (monitor) {
            waiting += delta;
            monitor.notifyAll();
        }
    }
}

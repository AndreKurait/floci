package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.services.stepfunctions.model.ExecutionStatusSubscription;
import io.github.hectorvent.floci.testing.TestTime;
import io.github.hectorvent.floci.testing.ThreadStates;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

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
    private static final int SETTLED_SAMPLES = 2;

    private final ScheduledExecutorService timers;
    private final Object monitor = new Object();
    private final Set<Thread> waiters = ConcurrentHashMap.newKeySet();
    private int waiting;

    TimelineWaits(TestTime time) {
        this.timers = time.newScheduler();
    }

    @Override
    public <T> T get(Future<T> future, long timeoutNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (timeoutNanos <= 0) {
            if (future.isDone()) {
                return future.get();
            }
            throw new TimeoutException();
        }
        Expiry expiry = new Expiry(timeoutNanos);
        boolean interrupted = false;
        try {
            return future.get();
        } catch (InterruptedException e) {
            interrupted = true;
            if (expiry.fired()) {
                throw new TimeoutException();
            }
            throw e;
        } finally {
            expiry.end(interrupted);
        }
    }

    @Override
    public <T> T poll(BlockingQueue<T> queue, long timeoutNanos) throws InterruptedException {
        if (timeoutNanos <= 0) {
            return queue.poll();
        }
        Expiry expiry = new Expiry(timeoutNanos);
        boolean interrupted = false;
        try {
            return queue.take();
        } catch (InterruptedException e) {
            interrupted = true;
            if (expiry.fired()) {
                return null;
            }
            throw e;
        } finally {
            expiry.end(interrupted);
        }
    }

    @Override
    public void awaitChange(ExecutionStatusSubscription subscription, long observed, long timeoutNanos)
            throws InterruptedException {
        if (timeoutNanos <= 0) {
            return;
        }
        Expiry expiry = new Expiry(timeoutNanos);
        boolean interrupted = false;
        try {
            subscription.awaitChange(observed, Long.MAX_VALUE);
        } catch (InterruptedException e) {
            interrupted = true;
            if (!expiry.fired()) {
                throw e;
            }
        } finally {
            expiry.end(interrupted);
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

    /** The timeline instant one wait ends at, delivered to its thread as an interrupt. */
    private final class Expiry {

        private final Thread waiter = Thread.currentThread();
        private final ScheduledFuture<?> timer;
        private boolean fired;
        private boolean ended;

        Expiry(long timeoutNanos) {
            this.timer = timers.schedule(this::fire, timeoutNanos, TimeUnit.NANOSECONDS);
            waiters.add(waiter);
            count(1);
        }

        /** Runs inside the advance that reached this instant, and returns once the waiter is at rest. */
        private void fire() {
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            synchronized (this) {
                if (ended) {
                    return;
                }
                fired = true;
                waiter.interrupt();
                while (!ended) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw stillRunning();
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted ending the wait of " + waiter.getName(), e);
                    }
                }
            }
            int parkedSamples = 0;
            while (waiter.isAlive() && !waiters.contains(waiter)) {
                parkedSamples = ThreadStates.PARKED_OR_DONE.contains(waiter.getState()) ? parkedSamples + 1 : 0;
                if (parkedSamples >= SETTLED_SAMPLES) {
                    return;
                }
                if (System.nanoTime() - deadline > 0) {
                    throw stillRunning();
                }
                LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(parkedSamples == 0 ? 50 : 500));
            }
        }

        private AssertionError stillRunning() {
            AssertionError error = new AssertionError(waiter.getName() + " was still running " + SETTLE_TIMEOUT
                    + " after its wait timed out; it must wait again, sleep or finish");
            error.setStackTrace(waiter.getStackTrace());
            return error;
        }

        synchronized boolean fired() {
            return fired;
        }

        /**
         * Ends the wait. An expiry that fired while the wait was returning on its own left an
         * interrupt nobody consumed; it belongs to this wait, so it is cleared here.
         */
        void end(boolean interrupted) {
            timer.cancel(false);
            waiters.remove(waiter);
            synchronized (this) {
                ended = true;
                if (fired && !interrupted) {
                    Thread.interrupted();
                }
                notifyAll();
            }
            count(-1);
        }
    }
}

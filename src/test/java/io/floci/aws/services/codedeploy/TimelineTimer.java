package io.floci.aws.services.codedeploy;

import io.floci.aws.testing.TestTime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link CodeDeployTimer} on a {@link TestTime} timeline. A deployment worker that waits parks until
 * the test advances past the wait, so the test sees each poll interval and deadline at its exact instant.
 * A wait for a future also ends the moment the future completes, as a timed {@code Future.get} does.
 */
final class TimelineTimer implements CodeDeployTimer {

    private final TestTime time;
    private final boolean selfAdvancing;
    private final Semaphore monitorWaits = new Semaphore(0);

    TimelineTimer(TestTime time) {
        this(time, false);
    }

    private TimelineTimer(TestTime time, boolean selfAdvancing) {
        this.time = time;
        this.selfAdvancing = selfAdvancing;
    }

    /** For code the test calls on its own thread: each sleep moves the timeline on instead of parking. */
    static TimelineTimer selfAdvancing(TestTime time) {
        return new TimelineTimer(time, true);
    }

    @Override
    public long nanoTime() {
        return time.nanoTime();
    }

    @Override
    public void sleepNanos(long nanos) throws InterruptedException {
        if (selfAdvancing) {
            time.sleepAdvancing(Duration.ofNanos(nanos));
        } else {
            time.sleepNanos(nanos);
        }
    }

    @Override
    public boolean awaitDone(Future<?> future, long nanos) throws InterruptedException {
        if (future.isDone()) {
            return true;
        }
        Thread waiter = Thread.currentThread();
        AtomicBoolean waiting = new AtomicBoolean(true);
        if (future instanceof CompletableFuture<?> completable) {
            // Completion interrupts the parked waiter, but only while it is still inside this wait.
            completable.whenComplete((result, failure) -> {
                synchronized (waiting) {
                    if (waiting.get()) {
                        waiter.interrupt();
                    }
                }
            });
        }
        try {
            time.sleepNanos(nanos);
        } catch (InterruptedException woken) {
            if (!future.isDone()) {
                throw woken;
            }
        } finally {
            synchronized (waiting) {
                waiting.set(false);
                if (future.isDone()) {
                    Thread.interrupted();
                }
            }
        }
        return future.isDone();
    }

    /**
     * {@code monitor.wait(timeout)} with the timeout on the timeline: the waiter wakes only when
     * notified or when the test advances to its deadline.
     */
    @Override
    public void waitOn(Object monitor, long nanos) throws InterruptedException {
        try {
            time.within(Duration.ofNanos(nanos), () -> {
                monitorWaits.release();
                monitor.wait();
                return null;
            });
        } catch (TimeoutException expired) {
            // A timed wait returns normally at its deadline; the caller rechecks its condition.
        }
    }

    /** Forgets the monitor waits seen so far, so the next {@link #awaitWaitOn} sees only a new one. */
    void forgetWaits() {
        monitorWaits.drainPermits();
    }

    /** Returns once a thread has started a {@link #waitOn} of {@code monitor} and has let go of it. */
    void awaitWaitOn(Object monitor) throws InterruptedException {
        assertTrue(monitorWaits.tryAcquire(10, TimeUnit.SECONDS), "a thread waits on the monitor");
        synchronized (monitor) {
            // Entering the monitor proves the waiter released it, which only its wait does.
            monitor.hashCode();
        }
    }
}

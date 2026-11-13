package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.testing.TestTime;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link SesResetWait} on a {@link TestTime} timeline. A reset draining its workers, or a create
 * waiting for the reset to finish, parks here on its own thread and is released by what releases
 * it in production (a notify on the monitor) or by the test advancing the timeline to the end of
 * the wait. Real time passing releases nothing; a wait nobody ends fails after
 * {@link #STUCK_AFTER_SECONDS}.
 *
 * <p>Each wait is reported to the test before it parks, so the test knows the thread is waiting
 * and for how long without sleeping to find out: {@link #awaitPark()}.
 */
final class TimelineResetWait implements SesResetWait {

    private static final int STUCK_AFTER_SECONDS = 30;

    private final TestTime time;
    private final LinkedBlockingQueue<Duration> parks = new LinkedBlockingQueue<>();

    TimelineResetWait(TestTime time) {
        this.time = time;
    }

    @Override
    public long nanoTime() {
        return time.nanoTime();
    }

    @Override
    public void timedWait(Object monitor, long timeoutNanos) throws InterruptedException {
        // The caller holds the monitor, so the wake cannot run before the wait below has released it.
        ScheduledFuture<?> due = time.scheduler().schedule(() -> {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }, timeoutNanos, TimeUnit.NANOSECONDS);
        parks.add(Duration.ofNanos(timeoutNanos));
        long parkedAt = System.nanoTime();
        try {
            monitor.wait(TimeUnit.SECONDS.toMillis(STUCK_AFTER_SECONDS));
            if (System.nanoTime() - parkedAt >= TimeUnit.SECONDS.toNanos(STUCK_AFTER_SECONDS)) {
                throw new AssertionError("a wait is still parked: its monitor was not notified and the timeline"
                        + " was not advanced by its " + Duration.ofNanos(timeoutNanos) + " timeout");
            }
        } finally {
            due.cancel(false);
        }
    }

    /** Waits for a thread to start its next wait and returns how long that wait is. */
    Duration awaitPark() {
        try {
            Duration park = parks.poll(STUCK_AFTER_SECONDS, TimeUnit.SECONDS);
            if (park == null) {
                throw new AssertionError("no thread parked on the timeline");
            }
            return park;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for a thread to park", e);
        }
    }

    /** How many waits have started that {@link #awaitPark()} has not yet returned. */
    int unreportedParks() {
        return parks.size();
    }
}

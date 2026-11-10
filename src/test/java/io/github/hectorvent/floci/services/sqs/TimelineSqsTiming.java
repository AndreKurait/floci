package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.TestTime;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link SqsTiming} on a {@link TestTime} timeline. A long poll or a throttled move task parks
 * here on its own thread and is released by what releases it in production (the queue changing,
 * the cancel latch opening) or by the test advancing the timeline to the end of the wait. Real
 * time passing releases nothing; a wait nobody ends fails after {@link #STUCK_AFTER_SECONDS}.
 *
 * <p>Each wait is reported to the test before it parks, so the test knows the thread is waiting
 * and for how long without sleeping to find out: {@link #awaitPark()}.
 */
final class TimelineSqsTiming implements SqsTiming {

    private static final int STUCK_AFTER_SECONDS = 30;

    private final TestTime time;
    private final LinkedBlockingQueue<Duration> parks = new LinkedBlockingQueue<>();

    TimelineSqsTiming(TestTime time) {
        this.time = time;
    }

    @Override
    public Clock clock() {
        return time.clock();
    }

    @Override
    public long nanoTime() {
        return time.nanoTime();
    }

    @Override
    public void awaitChange(GuardedMessageQueue queue, long observed, long timeoutNanos) throws InterruptedException {
        long pause = queue.pollPause(timeoutNanos);
        ScheduledFuture<?> due = time.scheduler().schedule(queue::signalChange, pause, TimeUnit.NANOSECONDS);
        parks.add(Duration.ofNanos(pause));
        try {
            for (int seconds = 0; queue.changeVersion() == observed; seconds++) {
                if (seconds == STUCK_AFTER_SECONDS) {
                    throw new AssertionError("a long poll is still parked: nothing changed the queue and the"
                            + " timeline was not advanced by its " + Duration.ofNanos(pause) + " wait");
                }
                queue.awaitChange(observed, TimeUnit.SECONDS.toNanos(1));
            }
        } finally {
            due.cancel(false);
        }
    }

    @Override
    public boolean await(CountDownLatch latch, long timeoutMillis) throws InterruptedException {
        CompletableFuture<Boolean> opened = new CompletableFuture<>();
        ScheduledFuture<?> due = time.scheduler().schedule(() -> opened.complete(false),
                timeoutMillis, TimeUnit.MILLISECONDS);
        Thread watcher = Thread.ofVirtual().start(() -> {
            try {
                latch.await();
                opened.complete(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        parks.add(Duration.ofMillis(timeoutMillis));
        try {
            return opened.get(STUCK_AFTER_SECONDS, TimeUnit.SECONDS) || latch.getCount() == 0;
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("a wait is still parked: the latch did not open and the timeline was not"
                    + " advanced by its " + Duration.ofMillis(timeoutMillis) + " timeout", e);
        } finally {
            due.cancel(false);
            watcher.interrupt();
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

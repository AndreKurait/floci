package io.floci.aws.services.elasticache.proxy;

import io.floci.aws.testing.TestTime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A proxy's timed wait for a relay thread, on a {@link TestTime} timeline. The wait ends when the
 * relay thread ends, as it does in production, or when the test advances the timeline to the end
 * of the timeout. Real time passing ends nothing; a wait nobody ends fails after
 * {@link #STUCK_AFTER_SECONDS}.
 *
 * <p>Each wait is reported to the test before it parks, so the test knows the proxy is waiting
 * and for how long without sleeping to find out: {@link #awaitJoin()}.
 */
public final class TimelineRelayJoin {

    private static final int STUCK_AFTER_SECONDS = 30;

    private final TestTime time;
    private final LinkedBlockingQueue<Duration> joins = new LinkedBlockingQueue<>();
    private final AtomicInteger started = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();

    public TimelineRelayJoin(TestTime time) {
        this.time = time;
    }

    /** What a proxy's {@code joinRelay} override calls. */
    public void join(Thread relay, long timeoutMillis) throws InterruptedException {
        CompletableFuture<Void> over = new CompletableFuture<>();
        ScheduledFuture<?> due = time.scheduler().schedule(() -> over.complete(null),
                timeoutMillis, TimeUnit.MILLISECONDS);
        Thread watcher = Thread.ofVirtual().start(() -> {
            try {
                relay.join();
                over.complete(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        started.incrementAndGet();
        waiting.incrementAndGet();
        joins.add(Duration.ofMillis(timeoutMillis));
        try {
            over.get(STUCK_AFTER_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("a relay join is still parked: the relay did not end and the timeline"
                    + " was not advanced by its " + Duration.ofMillis(timeoutMillis) + " timeout", e);
        } finally {
            waiting.decrementAndGet();
            due.cancel(false);
            watcher.interrupt();
        }
    }

    /** Waits for the proxy to start its next relay wait and returns how long that wait is. */
    public Duration awaitJoin() {
        try {
            Duration join = joins.poll(STUCK_AFTER_SECONDS, TimeUnit.SECONDS);
            if (join == null) {
                throw new AssertionError("the proxy never waited for a relay");
            }
            return join;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the proxy to wait for a relay", e);
        }
    }

    /** How many relay waits have started so far. */
    public int started() {
        return started.get();
    }

    /** How many relay waits are in progress. */
    public int waiting() {
        return waiting.get();
    }
}

package io.github.hectorvent.floci.services.sqs;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Where message delivery reads time and waits for it: visibility timeouts, delivery delays,
 * retention, long polls and the move-task rate. Production always runs on {@link #SYSTEM}. The
 * only way to supply another one is a package-private constructor, which tests use to put the
 * service on a timeline they move.
 */
interface SqsTiming {

    SqsTiming SYSTEM = new SqsTiming() {
        private final Clock clock = Clock.systemUTC();

        @Override
        public Clock clock() {
            return clock;
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void awaitChange(GuardedMessageQueue queue, long observed, long timeoutNanos)
                throws InterruptedException {
            queue.awaitChange(observed, timeoutNanos);
        }

        @Override
        public boolean await(CountDownLatch latch, long timeoutMillis) throws InterruptedException {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    };

    /** The clock a message's sent time, visibility deadline and retention are measured on. */
    Clock clock();

    /** Elapsed time for long-poll deadlines, with the meaning of {@link System#nanoTime()}. */
    long nanoTime();

    /**
     * Parks a long poll until {@code queue} changes from the {@code observed} version or its next
     * rescan is due, {@link GuardedMessageQueue#pollPause} at the latest.
     */
    void awaitChange(GuardedMessageQueue queue, long observed, long timeoutNanos) throws InterruptedException;

    /** {@link CountDownLatch#await(long, TimeUnit)}: true as soon as the latch opens, false after the timeout. */
    boolean await(CountDownLatch latch, long timeoutMillis) throws InterruptedException;
}

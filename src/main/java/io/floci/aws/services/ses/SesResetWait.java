package io.floci.aws.services.ses;

import java.util.concurrent.TimeUnit;

/**
 * The elapsed time and the monitor wait behind the bounded waits of a reset: the drain that waits
 * for running import workers, and a create that waits for the reset to finish. Production always
 * runs on {@link #SYSTEM}. The only way to supply another one is a package-private constructor,
 * which tests use to put those waits on a timeline they move.
 */
interface SesResetWait {

    SesResetWait SYSTEM = new SesResetWait() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void timedWait(Object monitor, long timeoutNanos) throws InterruptedException {
            TimeUnit.NANOSECONDS.timedWait(monitor, timeoutNanos);
        }
    };

    /** Elapsed time for the wait deadlines, with the meaning of {@link System#nanoTime()}. */
    long nanoTime();

    /** {@link Object#wait(long, int)} on a monitor the caller holds, for at most {@code timeoutNanos}. */
    void timedWait(Object monitor, long timeoutNanos) throws InterruptedException;
}

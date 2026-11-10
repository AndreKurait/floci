package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.testing.TestTime;

import java.util.concurrent.Future;

/**
 * A {@link CodeDeployTimer} on a {@link TestTime} timeline. A deployment worker that waits parks until
 * the test advances past the wait, so the test sees each poll interval and deadline at its exact instant.
 * A future completed while the worker is parked is noticed at the end of that wait, as one more poll.
 */
final class TimelineTimer implements CodeDeployTimer {

    private final TestTime time;

    TimelineTimer(TestTime time) {
        this.time = time;
    }

    @Override
    public long nanoTime() {
        return time.nanoTime();
    }

    @Override
    public void sleepNanos(long nanos) throws InterruptedException {
        time.sleepNanos(nanos);
    }

    @Override
    public boolean awaitDone(Future<?> future, long nanos) throws InterruptedException {
        if (!future.isDone()) {
            time.sleepNanos(nanos);
        }
        return future.isDone();
    }
}

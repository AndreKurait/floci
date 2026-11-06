package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.concurrent.TimeUnit;

/** Tracks complete operations, including their metadata and FIFO writes, without holding a lock over I/O. */
final class SqsResetAdmission {
    private static final long DRAIN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private final Runnable recovery;
    private volatile boolean paused;
    private int active;

    SqsResetAdmission(Runnable recovery) {
        this.recovery = recovery;
    }

    @FunctionalInterface
    interface Operation extends AutoCloseable {
        @Override
        void close();
    }

    Operation enter() {
        Operation operation = tryEnter();
        if (operation == null) {
            throw new AwsException("ServiceUnavailable", "SQS is resetting. Retry the request after reset.", 503);
        }
        return operation;
    }

    Operation tryEnter() {
        Operation operation = acquire();
        if (operation != null) {
            try {
                recovery.run();
            } catch (RuntimeException | Error failure) {
                operation.close();
                throw failure;
            }
        }
        return operation;
    }

    private synchronized Operation acquire() {
        int nesting = depth.get();
        if (paused && nesting == 0) {
            depth.remove();
            return null;
        }
        if (nesting == 0) {
            active++;
        }
        depth.set(nesting + 1);
        return this::leave;
    }

    private synchronized void leave() {
        int nesting = depth.get();
        if (nesting == 1) {
            depth.remove();
            active--;
            if (active == 0) {
                notifyAll();
            }
        } else {
            depth.set(nesting - 1);
        }
    }

    synchronized void pause() {
        paused = true;
    }

    boolean isPaused() {
        return paused;
    }

    synchronized void awaitDrained() {
        long deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS;
        while (active != 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("Timed out draining SQS operations before reset");
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while draining SQS operations before reset", exception);
            }
        }
    }

    synchronized void resume() {
        paused = false;
    }
}

package io.github.hectorvent.floci.core.common.concurrent;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One deadline for socket operations, including peers that continually send incomplete responses. */
public final class SocketDeadline {

    private SocketDeadline() {}

    public static <T> T call(int timeoutSeconds, Callable<T> operation) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        FutureTask<T> result = new FutureTask<>(operation);
        // Interrupting a virtual thread closes its blocking java.net sockets.
        Thread.ofVirtual().name("health-probe").start(result);
        try {
            return result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            throw new SocketTimeoutException("Health check exceeded " + timeoutSeconds + " seconds");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Health check interrupted");
            failure.initCause(interrupted);
            throw failure;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Health check failed", cause);
        } finally {
            result.cancel(true);
        }
    }
}

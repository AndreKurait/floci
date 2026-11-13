package io.github.hectorvent.floci.services.lambda;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The poll executor an event source poller test hands to the poller, so the test can tell when the
 * polls it started have finished instead of sleeping until they probably have.
 */
public final class PollExecutor extends AbstractExecutorService {

    private static final long IDLE_TIMEOUT_SECONDS = 5;

    /** Null when polls run on the submitting thread. */
    private final ExecutorService threads;
    private final Queue<Future<?>> started = new ConcurrentLinkedQueue<>();
    private volatile boolean shutdown;

    private PollExecutor(ExecutorService threads) {
        this.threads = threads;
    }

    /** Runs each poll on its own daemon thread, as the production executor does. */
    public static PollExecutor onThreads() {
        return new PollExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "test-esm-poller");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** Runs each poll on the thread that submits it: a timer tick returns once its poll is done. */
    public static PollExecutor inline() {
        return new PollExecutor(null);
    }

    /** Returns once every poll submitted so far has finished. Fails if one is still running after five seconds. */
    public void awaitIdle() {
        Future<?> poll;
        while ((poll = started.poll()) != null) {
            try {
                poll.get(IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for a poll", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError("poll did not complete", e);
            }
        }
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        RunnableFuture<T> task = super.newTaskFor(runnable, value);
        started.add(task);
        return task;
    }

    @Override
    public void execute(Runnable command) {
        if (threads == null) {
            command.run();
        } else {
            threads.execute(command);
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
        if (threads != null) {
            threads.shutdown();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return threads == null ? List.of() : threads.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return threads == null ? shutdown : threads.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return threads == null ? shutdown : threads.awaitTermination(timeout, unit);
    }
}

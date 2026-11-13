package io.github.hectorvent.floci.services.sqs;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The move-task executor production uses, with a way for a test to wait until every worker it
 * started has returned: {@link #join()}. A worker has written its task's terminal status by then,
 * so the test reads the status once instead of polling for it.
 */
final class JoinableMoveTasks extends AbstractExecutorService {

    private static final int STUCK_AFTER_SECONDS = 30;

    private final ExecutorService workers = SqsService.newMoveTaskExecutor();
    private final Queue<Future<?>> started = new ConcurrentLinkedQueue<>();

    @Override
    public void execute(Runnable command) {
        FutureTask<Void> worker = new FutureTask<>(command, null);
        started.add(worker);
        workers.execute(worker);
    }

    /** Returns once every worker started so far has finished. */
    void join() {
        for (Future<?> worker = started.poll(); worker != null; worker = started.poll()) {
            try {
                worker.get(STUCK_AFTER_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for a move task worker", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError("a move task worker did not finish: it is throttled and the"
                        + " timeline was not advanced, or it failed", e);
            }
        }
    }

    @Override
    public void shutdown() {
        workers.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return workers.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return workers.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return workers.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return workers.awaitTermination(timeout, unit);
    }
}

package io.floci.aws.core.common;

import io.floci.aws.core.common.concurrent.DeadlineWaits;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Runs every {@link ContainerTeardown} implementation, tolerating a failure in one so it
 * doesn't block the others or whatever cleanup the caller runs next. {@code EmulatorLifecycle}
 * (on {@code ShutdownEvent}) and {@code EmulatorInfoController} (on {@code /state/reset} and
 * {@code /state/nuke}) each independently ran this identical loop before this class existed.
 */
public final class ContainerTeardowns {

    private ContainerTeardowns() {
    }

    public static void stopAll(Instance<ContainerTeardown> teardowns, Logger log) {
        List<ContainerTeardown> ordered = new ArrayList<>();
        for (ContainerTeardown teardown : teardowns) {
            ordered.add(teardown);
        }
        ordered.sort(Comparator.comparingInt(ContainerTeardown::teardownOrder));
        for (ContainerTeardown teardown : ordered) {
            try {
                teardown.stopManagedContainers();
            } catch (Exception e) {
                log.warnv("Container teardown failed for {0}: {1}",
                        teardown.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    public static <T> void stopInParallel(List<T> resources, Consumer<T> stop, Logger log) {
        stopInParallel(resources, stop, log, DeadlineWaits.SYSTEM);
    }

    /** As {@link #stopInParallel(List, Consumer, Logger)}, with its fifteen seconds counted by {@code waits}. */
    public static <T> void stopInParallel(List<T> resources, Consumer<T> stop, Logger log, DeadlineWaits waits) {
        stopInParallel(resources, stop, log, waits.nanoTime() + TimeUnit.SECONDS.toNanos(15), waits);
    }

    public static <T> void stopInParallel(List<T> resources, Consumer<T> stop, Logger log,
                                         long deadlineNanos) {
        stopInParallel(resources, stop, log, deadlineNanos, DeadlineWaits.SYSTEM);
    }

    /**
     * @param deadlineNanos the shared deadline, as a reading of {@code waits.nanoTime()}
     * @param waits the time source the deadline is measured on and the bounded wait for the stops
     */
    public static <T> void stopInParallel(List<T> resources, Consumer<T> stop, Logger log,
                                         long deadlineNanos, DeadlineWaits waits) {
        if (resources.isEmpty()) {
            return;
        }
        long remaining = Math.max(0, deadlineNanos - waits.nanoTime());
        if (remaining == 0) {
            log.warnv("Container drain deadline expired with {0} resource(s) pending", resources.size());
            return;
        }
        // Every owned container must receive its stop within the same deadline. A fixed worker
        // queue lets slow Docker calls consume that budget before later containers can even stop.
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("container-teardown-", 0).factory());
        try {
            List<Future<Void>> drains = new ArrayList<>(resources.size());
            try {
                for (T resource : resources) {
                    Callable<Void> drain = () -> {
                        stop.accept(resource);
                        return null;
                    };
                    drains.add(executor.submit(drain));
                }
                awaitUntil(drains, deadlineNanos, waits);
            } finally {
                // A stop still running at the deadline is interrupted; a finished one is unaffected.
                for (Future<Void> drain : drains) {
                    drain.cancel(true);
                }
            }
            for (Future<Void> task : drains) {
                if (task.isCancelled()) {
                    log.warn("Container drain did not finish before the shared deadline");
                } else {
                    try {
                        task.get();
                    } catch (ExecutionException failure) {
                        log.warnv(failure.getCause(), "Container drain failed");
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Container drain interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    /** Returns once every drain is done or the deadline has passed, whichever is first. */
    private static void awaitUntil(List<Future<Void>> drains, long deadlineNanos, DeadlineWaits waits)
            throws InterruptedException {
        for (Future<Void> drain : drains) {
            if (drain.isDone()) {
                continue;
            }
            try {
                waits.get(drain, Math.max(0, deadlineNanos - waits.nanoTime()));
            } catch (ExecutionException | CancellationException expected) {
                // Reported with the other outcomes once the wait is over.
            } catch (TimeoutException expired) {
                return;
            }
        }
    }
}

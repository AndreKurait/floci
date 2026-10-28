package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.WaitContainerCondition;
import com.github.dockerjava.api.model.WaitResponse;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Reports when a Lambda container's main process exits, through Docker's wait-for-exit stream.
 *
 * <p>The stream sends nothing until the container exits, so on a quiet container it ends with a read
 * timeout or a transport failure while the container still runs. A watch that simply
 * stopped there would miss every later crash, turning a {@code Runtime.ExitError} into a
 * {@code Function.TimedOut}. When the stream ends with an error, the container is inspected instead:
 * still running re-arms the watch, already exited reports the inspected exit code, and gone reports
 * an unknown exit status. Intentional teardown cancels the captured watcher after quiescing its server.
 *
 * <p>A stream that fails soon after it was armed, or an inspect that fails, usually means the daemon
 * itself is unreachable (a dropped connection, a restart). Those re-arm after a delay that doubles each
 * time, so the watch outlasts a short outage instead of spending its attempts within milliseconds; after
 * {@link #MAX_QUICK_FAILURES} in a row it stops. A stream that lived longer than {@link #QUICK_FAILURE}
 * resets the count and re-arms at once.
 */
final class LambdaExitWatcher {

    private static final Logger LOG = Logger.getLogger(LambdaExitWatcher.class);

    /** A stream that fails sooner than this after it was armed counts as a quick failure. */
    static final Duration QUICK_FAILURE = Duration.ofSeconds(5);
    /** Consecutive quick failures tolerated before the watch gives up. */
    static final int MAX_QUICK_FAILURES = 5;
    /** Delay before re-arming after the first quick failure; it doubles with each further one. */
    static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);

    private final DockerClient dockerClient;
    private final String containerId;
    private final IntConsumer onExit;
    private final BiConsumer<Duration, Runnable> scheduler;
    private final LongSupplier nanoClock;
    private final AtomicBoolean reported = new AtomicBoolean();
    private final CompletableFuture<Void> readiness = new CompletableFuture<>();
    private volatile boolean awaitingStart;
    private volatile Throwable beforeStartFailure;
    private volatile WaitContainerResultCallback currentCallback;
    private int quickFailures;

    LambdaExitWatcher(DockerClient dockerClient, String containerId, IntConsumer onExit,
                      BiConsumer<Duration, Runnable> scheduler, LongSupplier nanoClock) {
        this.dockerClient = dockerClient;
        this.containerId = containerId;
        this.onExit = onExit;
        this.scheduler = scheduler;
        this.nanoClock = nanoClock;
    }

    /** Arms a watch that calls {@code onExit} with the exit status, at most once per exit. */
    static void watch(DockerClient dockerClient, String containerId, IntConsumer onExit) {
        new LambdaExitWatcher(dockerClient, containerId, onExit, LambdaExitWatcher::schedule, System::nanoTime)
                .arm();
    }

    static LambdaExitWatcher watchBeforeStart(
            DockerClient dockerClient, String containerId, IntConsumer onExit, Duration timeout) {
        LambdaExitWatcher watcher = new LambdaExitWatcher(
                dockerClient, containerId, onExit, LambdaExitWatcher::schedule, System::nanoTime);
        watcher.awaitingStart = true;
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Interrupted while registering Lambda exit watcher");
            }
            watcher.currentCallback = watcher.callback(watcher.nanoClock.getAsLong());
            dockerClient.waitContainerCmd(containerId).withCondition(WaitContainerCondition.NEXT_EXIT)
                    .exec(watcher.currentCallback);
            watcher.readiness.get(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
            watcher.requireHealthy();
            return watcher;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            watcher.cancel();
            throw new IllegalStateException("Interrupted while registering Lambda exit watcher", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            watcher.cancel();
            throw new IllegalStateException("Lambda exit watcher was not ready before start", e);
        }
    }

    void requireHealthy() {
        if (beforeStartFailure != null) {
            throw new IllegalStateException("Lambda exit watcher failed before start", beforeStartFailure);
        }
        if (reported.get()) {
            throw new IllegalStateException("Lambda container exited before start");
        }
    }

    void containerStarted() {
        Throwable failure;
        synchronized (this) {
            awaitingStart = false;
            failure = beforeStartFailure;
        }
        if (failure != null && !reported.get()) {
            streamFailed(failure, nanoClock.getAsLong());
        }
    }

    void cancel() {
        reported.set(true);
        if (currentCallback != null) {
            try {
                currentCallback.close();
            } catch (IOException e) {
                LOG.debugv(e, "Could not close exit watcher for container {0}", containerId);
            }
        }
    }

    private static void schedule(Duration delay, Runnable task) {
        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS).execute(task);
    }

    void arm() {
        if (reported.get()) {
            return;
        }
        long armedAt = nanoClock.getAsLong();
        try {
            WaitContainerResultCallback callback = callback(armedAt);
            currentCallback = callback;
            dockerClient.waitContainerCmd(containerId).exec(callback);
            if (reported.get()) {
                cancel();
            }
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not arm exit watcher for container {0}", containerId);
            reArm(e, armedAt);
        }
    }

    private WaitContainerResultCallback callback(long armedAt) {
        return new WaitContainerResultCallback() {
            @Override
            public void onStart(Closeable stream) {
                super.onStart(stream);
                if (reported.get()) {
                    cancel();
                    return;
                }
                readiness.complete(null);
            }

            @Override
            public void onNext(WaitResponse response) {
                super.onNext(response);
                Integer statusCode = response.getStatusCode();
                reportExit(statusCode != null ? statusCode : -1);
            }

            @Override
            public void onError(Throwable throwable) {
                boolean deferred = deferBeforeStartFailure(throwable);
                super.onError(throwable);
                if (!deferred && !reported.get()) {
                    streamFailed(throwable, armedAt);
                }
            }

            @Override
            public void onComplete() {
                Throwable failure = new IllegalStateException("Lambda exit watcher ended without an exit status");
                boolean deferred = deferBeforeStartFailure(failure);
                super.onComplete();
                if (!deferred && !reported.get()) {
                    streamFailed(failure, armedAt);
                }
            }
        };
    }

    private boolean deferBeforeStartFailure(Throwable cause) {
        synchronized (this) {
            if (awaitingStart && !reported.get()) {
                beforeStartFailure = cause;
                readiness.completeExceptionally(cause);
                return true;
            }
            return false;
        }
    }

    private void reportExit(int statusCode) {
        if (reported.compareAndSet(false, true)) {
            onExit.accept(statusCode);
        }
    }

    private void streamFailed(Throwable cause, long armedAt) {
        InspectContainerResponse.ContainerState state;
        try {
            state = dockerClient.inspectContainerCmd(containerId).exec().getState();
        } catch (NotFoundException e) {
            LOG.debugv("Exit watcher for container {0} lost its exit status: the container is gone", containerId);
            // An auto-removed runtime cannot be inspected. Its owning server ignores this after
            // intentional quiesce, but an unexpected disappearance must not become an invoke timeout.
            reportExit(-1);
            return;
        } catch (RuntimeException e) {
            LOG.debugv(e, "Exit watcher for container {0} could not inspect the container", containerId);
            reArm(e, armedAt);
            return;
        }
        if (state == null || !Boolean.TRUE.equals(state.getRunning())) {
            Long exitCode = state == null ? null : state.getExitCodeLong();
            reportExit(exitCode != null ? exitCode.intValue() : -1);
            return;
        }
        reArm(cause, armedAt);
    }

    private void reArm(Throwable cause, long armedAt) {
        boolean quick = nanoClock.getAsLong() - armedAt < QUICK_FAILURE.toNanos();
        quickFailures = quick ? quickFailures + 1 : 0;
        if (quickFailures > MAX_QUICK_FAILURES) {
            LOG.warnv(cause, "Exit watcher for container {0} gave up after {1} quick failures; a later exit is unreported",
                    containerId, quickFailures);
            return;
        }
        if (quickFailures == 0) {
            LOG.debugv("Exit watcher stream for running container {0} ended ({1}); re-arming",
                    containerId, cause.toString());
            arm();
            return;
        }
        Duration delay = FIRST_RETRY_DELAY.multipliedBy(1L << (quickFailures - 1));
        LOG.debugv("Exit watcher for container {0} failed quickly ({1}); re-arming in {2} ms",
                containerId, cause.toString(), delay.toMillis());
        scheduler.accept(delay, this::arm);
    }
}

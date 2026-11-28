package io.floci.aws.core.common;

import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Runs a periodic sweep on its own daemon thread, one sweep at a time. A service owns one worker and
 * forwards its startup, shutdown and reset events to it; the worker has no CDI wiring of its own.
 */
public final class SweepWorker {
    private static final Logger LOG = Logger.getLogger(SweepWorker.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final String name;
    private final Consumer<Instant> sweep;
    private final long intervalSeconds;
    private final boolean sweepAtStart;
    private final long shutdownTimeoutNanos;
    private final ScheduledExecutorService executor;
    private final Clock clock;
    private final AtomicReference<Thread> executorThread;
    private final ReentrantLock sweepLock = new ReentrantLock();
    private volatile boolean paused;

    /**
     * @param name display name used in the thread name and log messages
     * @param enabled whether a background thread exists; a disabled worker still runs {@link #tick}
     * @param intervalSeconds delay between sweeps, at least one second
     * @param sweepAtStart whether the first sweep runs at startup, not after one interval
     * @param sweep the work, run under the sweep lock and skipped while paused
     */
    public SweepWorker(String name, boolean enabled, long intervalSeconds, boolean sweepAtStart,
                       Consumer<Instant> sweep) {
        this(name, intervalSeconds, sweepAtStart, sweep, scheduler(name, enabled), Clock.systemUTC());
    }

    private SweepWorker(String name, long intervalSeconds, boolean sweepAtStart, Consumer<Instant> sweep,
                        Scheduler scheduler, Clock clock) {
        this(name, intervalSeconds, sweepAtStart, sweep, scheduler.executor(), clock, scheduler.thread(), SHUTDOWN_TIMEOUT);
    }

    /**
     * A worker whose timer and clock the caller supplies, so a test can move time itself.
     *
     * @param executor runs the sweeps and is shut down by {@link #stop}; {@code null} means no
     *                 background thread, as for a disabled worker
     * @param clock read once per scheduled sweep for the instant handed to it
     */
    public SweepWorker(String name, long intervalSeconds, boolean sweepAtStart, Consumer<Instant> sweep,
                       ScheduledExecutorService executor, Clock clock) {
        this(name, intervalSeconds, sweepAtStart, sweep, executor, clock, new AtomicReference<>(), SHUTDOWN_TIMEOUT);
    }

    SweepWorker(String name, long intervalSeconds, boolean sweepAtStart, Consumer<Instant> sweep,
                ThreadFactory threadFactory, Duration shutdownTimeout) {
        this(name, intervalSeconds, sweepAtStart, sweep, scheduler(threadFactory), shutdownTimeout);
    }

    private SweepWorker(String name, long intervalSeconds, boolean sweepAtStart, Consumer<Instant> sweep,
                        Scheduler scheduler, Duration shutdownTimeout) {
        this(name, intervalSeconds, sweepAtStart, sweep, scheduler.executor(), Clock.systemUTC(),
                scheduler.thread(), shutdownTimeout);
    }

    private SweepWorker(String name, long intervalSeconds, boolean sweepAtStart, Consumer<Instant> sweep,
                        ScheduledExecutorService executor, Clock clock, AtomicReference<Thread> executorThread,
                        Duration shutdownTimeout) {
        this.name = name;
        this.sweep = sweep;
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.sweepAtStart = sweepAtStart;
        this.shutdownTimeoutNanos = shutdownTimeout.toNanos();
        this.executor = executor;
        this.clock = clock;
        this.executorThread = executorThread;
    }

    private static Scheduler scheduler(String name, boolean enabled) {
        return scheduler(enabled ? Thread.ofPlatform().name(name).daemon(true).factory() : null);
    }

    private static Scheduler scheduler(ThreadFactory threadFactory) {
        AtomicReference<Thread> thread = new AtomicReference<>();
        ScheduledExecutorService executor = threadFactory == null ? null : Executors.newSingleThreadScheduledExecutor(task -> {
            Thread created = threadFactory.newThread(task);
            thread.set(created);
            return created;
        });
        return new Scheduler(executor, thread);
    }

    public void start() {
        if (executor != null) {
            executor.scheduleWithFixedDelay(this::tickSafely, sweepAtStart ? 0 : intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    /** Interrupts the scheduler and waits up to five seconds for it and its observed worker thread to finish. */
    public void stop() {
        if (executor == null) {
            return;
        }
        long started = System.nanoTime();
        long budget = shutdownTimeoutNanos;
        executor.shutdownNow();
        if (executorThread.get() == Thread.currentThread()) {
            return;
        }
        try {
            boolean terminated = executor.awaitTermination(
                    Math.max(0, budget - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
            Thread thread = executorThread.get();
            long remaining = budget - (System.nanoTime() - started);
            // An executor can signal termination just before its Thread.run actually returns.
            if (terminated && thread != null && thread != Thread.currentThread() && remaining > 0) {
                TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
            }
            if (!terminated || (thread != null && thread.isAlive())) {
                LOG.warnv("The {0} worker did not finish within the shutdown wait", name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debugv(e, "Interrupted while stopping the {0} worker", name);
        }
    }

    /** Whether a background thread exists, which the owner's configuration switches decide. */
    public boolean active() {
        return executor != null;
    }

    public void tick(Instant now) {
        sweepLock.lock();
        try {
            if (!paused) {
                sweep.accept(now);
            }
        } finally {
            sweepLock.unlock();
        }
    }

    /** Stops new sweeps and waits for a running one, so it cannot write into storage that reset is about to clear. */
    public void pause() {
        paused = true;
        sweepLock.lock();
        sweepLock.unlock();
    }

    public void resume() {
        paused = false;
    }

    /** Threads waiting for the running sweep, which lets tests observe blocking without timing. */
    public int queuedSweeps() {
        return sweepLock.getQueueLength();
    }

    private void tickSafely() {
        executorThread.set(Thread.currentThread());
        try {
            tick(clock.instant());
        } catch (RuntimeException e) {
            LOG.warnv(e, "The {0} sweep failed", name);
        }
    }

    private record Scheduler(ScheduledExecutorService executor, AtomicReference<Thread> thread) {}
}

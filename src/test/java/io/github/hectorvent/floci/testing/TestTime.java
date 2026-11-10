package io.github.hectorvent.floci.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Time a test controls: a clock, a scheduler and a sleep that share one timeline, which moves only
 * when the test advances it.
 *
 * <pre>{@code
 * TestTime time = TestTime.create();
 * Worker worker = new Worker(service, time.clock(), time.scheduler());
 *
 * time.advance(Duration.ofSeconds(59));
 * assertDeploymentIsInProgress();
 *
 * time.advance(Duration.ofSeconds(1));
 * assertDeploymentIsBaking();
 * }</pre>
 *
 * <p>{@link #advance} does more than move the clock. It walks the timeline in order and, at each
 * instant something is due, sets the clock to that instant and then:
 * <ul>
 *   <li>runs the scheduled task on the advancing thread, re-queueing a periodic one, so a task that
 *       reads the clock sees the time it was scheduled for;</li>
 *   <li>wakes a thread parked in {@link #sleep}, and waits for that thread to park again or finish
 *       before moving on, so a polling loop takes one turn per interval however far the test jumps.</li>
 * </ul>
 * Nothing runs between calls: work handed to the scheduler waits for the next {@code advance}, even
 * with no delay ({@link #runDue} runs just that). The production code keeps its configured
 * durations; the test decides when they elapse.
 *
 * <p>Timed waits the code makes on its own ({@code Thread.sleep}, {@code get(timeout)},
 * {@code poll(timeout)}, {@code awaitNanos}) are outside this timeline. Give that code a seam to call
 * instead and pass the stand-in:
 * <ul>
 *   <li>{@code Thread.sleep} becomes {@link #sleep};</li>
 *   <li>a delayed or periodic task goes to {@link #scheduler()};</li>
 *   <li>{@code future.get(timeout)} becomes {@link #get}, {@code queue.poll(timeout)} becomes
 *       {@link #poll}, and any other timed wait ({@code monitor.wait(timeout)},
 *       {@code condition.awaitNanos}, {@code latch.await(timeout)}) becomes {@link #within} around
 *       its untimed form.</li>
 * </ul>
 * A thread in one of those waits is released by whatever it waits for, as in production, or by the
 * {@code advance} that reaches its deadline, which then waits for the thread to come to rest again.
 *
 * <p>Code the test calls on its own thread cannot park for an {@code advance} nobody is left to
 * make: give it {@link #sleepAdvancing} and {@link #getAdvancing}, which move the timeline on
 * themselves, or step through its work with {@link #advanceUntil}.
 */
public final class TestTime {

    /** Where a timeline starts unless the test says otherwise. */
    public static final Instant DEFAULT_START = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * A blocking call with no time limit of its own, such as {@code queue::take} or
     * {@code future::get}, for {@link #within} to bound on the timeline.
     */
    @FunctionalInterface
    public interface BlockingCall<T, X extends Exception> {
        T call() throws X, InterruptedException;
    }

    private static final Duration DEFAULT_SETTLE_TIMEOUT = Duration.ofSeconds(10);
    /** Keeps {@link #nanoTime} visibly unrelated to the epoch, like {@link System#nanoTime}. */
    private static final long NANO_ORIGIN = TimeUnit.HOURS.toNanos(7);
    private static final int SETTLED_SAMPLES = 2;
    /** Earliest first, and for the same instant, whichever was queued first. */
    private static final Comparator<Entry> IN_DUE_ORDER =
            Comparator.<Entry, Instant>comparing(entry -> entry.due).thenComparingLong(entry -> entry.sequence);

    /** Held by the thread walking the timeline; a task it runs may advance further. */
    private final ReentrantLock advancing = new ReentrantLock();
    /** Guards the queue, the sequence and every entry's state. */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PriorityQueue<Entry> queue = new PriorityQueue<>(IN_DUE_ORDER);
    private final Instant start;
    private final Clock clock = new TimelineClock(ZoneOffset.UTC);
    private final ScheduledExecutorService scheduler = new TimelineScheduler();
    private volatile Instant now;
    private volatile Duration settleTimeout = DEFAULT_SETTLE_TIMEOUT;
    private long nextSequence;

    private TestTime(Instant start) {
        this.start = Objects.requireNonNull(start, "start");
        this.now = start;
    }

    /** A timeline starting at {@link #DEFAULT_START}. */
    public static TestTime create() {
        return new TestTime(DEFAULT_START);
    }

    /** A timeline starting at {@code start}. */
    public static TestTime startingAt(Instant start) {
        return new TestTime(start);
    }

    /** The clock for this timeline, in UTC. Zoned views of it move with it. */
    public Clock clock() {
        return clock;
    }

    /** The current instant on this timeline. */
    public Instant now() {
        return now;
    }

    /** Milliseconds since the epoch on this timeline, for a seam shaped like {@code System::currentTimeMillis}. */
    public long currentTimeMillis() {
        return now.toEpochMilli();
    }

    /** A monotonic reading of this timeline, for a seam shaped like {@code System::nanoTime}. */
    public long nanoTime() {
        return NANO_ORIGIN + Duration.between(start, now).toNanos();
    }

    /**
     * The scheduler for this timeline. Its tasks run on the thread that calls {@link #advance}, in
     * due order and then in the order they were submitted.
     */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /**
     * Another scheduler on the same timeline, for code that owns and shuts down its own. Shutting one
     * down leaves the others running.
     */
    public ScheduledExecutorService newScheduler() {
        return new TimelineScheduler();
    }

    /**
     * Parks the calling thread until the timeline has moved {@code duration} on, the way
     * {@code Thread.sleep} would on a real clock. Returns at once for a zero or negative duration.
     * Called from a task {@link #advance} is running, it moves the timeline itself instead of parking,
     * since nothing else could.
     */
    public void sleep(Duration duration) throws InterruptedException {
        Objects.requireNonNull(duration, "duration");
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        if (advancing.isHeldByCurrentThread()) {
            advance(duration);
            return;
        }
        lock.lockInterruptibly();
        try {
            Parked sleep = new Parked(now.plus(duration), nextSequence++, Thread.currentThread(), false);
            queue.add(sleep);
            changed.signalAll();
            try {
                while (!sleep.woken) {
                    changed.await();
                }
            } finally {
                queue.remove(sleep);
                sleep.returned = true;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** {@link #sleep(Duration)} for a seam that counts in milliseconds. */
    public void sleepMillis(long millis) throws InterruptedException {
        sleep(Duration.ofMillis(millis));
    }

    /** {@link #sleep(Duration)} for a seam that counts in nanoseconds. */
    public void sleepNanos(long nanos) throws InterruptedException {
        sleep(Duration.ofNanos(nanos));
    }

    /**
     * A sleep that passes by itself: moves the timeline {@code duration} on from the calling thread,
     * running whatever comes due on the way. For code that sleeps on the test's own thread, such as a
     * retry loop called directly, where nobody else is left to call {@link #advance}.
     */
    public void sleepAdvancing(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (!duration.isNegative()) {
            advance(duration);
        }
    }

    /**
     * Runs {@code call}, a blocking call with no time limit of its own, with {@code timeout} measured
     * on this timeline: the stand-in for any timed wait, such as
     * {@code within(timeout, () -> condition.await())} for {@code condition.awaitNanos}. Returns what
     * the call returns if it comes back by itself. If {@link #advance} reaches the deadline first, the
     * waiting thread is interrupted out of the call and this throws {@link TimeoutException}; the
     * advance goes on once the thread has parked again or finished. A call that returns just as the
     * deadline is reached wins. The waiting thread counts among the {@link #sleepers}.
     *
     * <p>Times out at once, without calling, for a zero or negative timeout. Not for a task the
     * timeline itself is running, which would stop the timeline: use {@link #getAdvancing} there.
     */
    public <T, X extends Exception> T within(Duration timeout, BlockingCall<T, X> call)
            throws X, InterruptedException, TimeoutException {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(call, "call");
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new TimeoutException();
        }
        if (advancing.isHeldByCurrentThread()) {
            throw new IllegalStateException("a blocking wait inside a task the timeline is running would stop "
                    + "the timeline; use getAdvancing or advanceUntil");
        }
        Parked wait;
        lock.lockInterruptibly();
        try {
            wait = new Parked(now.plus(timeout), nextSequence++, Thread.currentThread(), true);
            queue.add(wait);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        T result = null;
        InterruptedException interruption = null;
        boolean timedOut;
        try {
            result = call.call();
        } catch (InterruptedException e) {
            interruption = e;
        } finally {
            timedOut = leave(wait, interruption != null);
        }
        if (interruption == null) {
            return result;
        }
        if (timedOut) {
            throw new TimeoutException();
        }
        throw interruption;
    }

    /**
     * {@code future.get(timeout)} with the timeout on this timeline: returns when the future completes,
     * and throws {@link TimeoutException} when {@link #advance} reaches the deadline first. See
     * {@link #within}. Called from a task the timeline is running, it is {@link #getAdvancing}.
     */
    public <T> T get(Future<T> future, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(timeout, "timeout");
        if (future.isDone() || timeout.isZero() || timeout.isNegative()) {
            return future.get(0, TimeUnit.NANOSECONDS);
        }
        if (advancing.isHeldByCurrentThread()) {
            return getAdvancing(future, timeout);
        }
        return this.<T, ExecutionException>within(timeout, future::get);
    }

    /**
     * A {@code future.get(timeout)} that passes by itself: moves the timeline on from the calling
     * thread, one due instant at a time, until the future is done or {@code timeout} has passed, so
     * the clock stops where the future completed. For code that waits on the test's own thread for
     * something the timeline's own tasks will complete.
     */
    public <T> T getAdvancing(Future<T> future, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(timeout, "timeout");
        if (!timeout.isNegative()) {
            advanceUntil(future::isDone, timeout);
        }
        return future.get(0, TimeUnit.NANOSECONDS);
    }

    /**
     * {@code queue.poll(timeout)} with the timeout on this timeline: the next element once there is
     * one, or {@code null} when {@link #advance} reaches the deadline first. See {@link #within}.
     */
    public <T> T poll(BlockingQueue<T> queue, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(timeout, "timeout");
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        T head = queue.poll();
        if (head != null || timeout.isZero() || timeout.isNegative()) {
            return head;
        }
        if (advancing.isHeldByCurrentThread()) {
            advanceUntil(() -> !queue.isEmpty(), timeout);
            return queue.poll();
        }
        try {
            return this.<T, RuntimeException>within(timeout, queue::take);
        } catch (TimeoutException expired) {
            return null;
        }
    }

    /**
     * Moves the timeline {@code duration} on, running every scheduled task and waking every sleeper
     * that comes due on the way, each with the clock at its own due time. Work a task schedules inside
     * the window runs in the same call. Returns with the clock at the target, or later if a task
     * slept past it.
     */
    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("time only moves forward, not by " + duration);
        }
        advancing.lock();
        try {
            Instant target = now.plus(duration);
            Entry due;
            while ((due = takeDue(target)) != null) {
                due.fire();
            }
            lock.lock();
            try {
                if (target.isAfter(now)) {
                    now = target;
                }
            } finally {
                lock.unlock();
            }
        } finally {
            advancing.unlock();
        }
    }

    /** {@link #advance} up to {@code instant}, which must not be in this timeline's past. */
    public void advanceTo(Instant instant) {
        advancing.lock();
        try {
            advance(Duration.between(now, instant));
        } finally {
            advancing.unlock();
        }
    }

    /** Runs what is due now, without moving the clock: work submitted with no delay, mostly. */
    public void runDue() {
        advance(Duration.ZERO);
    }

    /**
     * Moves the timeline on one due instant at a time, running what is due there, until
     * {@code condition} holds or {@code limit} has passed, whichever comes first. Returns whether the
     * condition holds, with the clock at the instant it first did, or at the limit.
     *
     * <pre>{@code
     * assertTrue(time.advanceUntil(() -> deployment.state() == BAKING, Duration.ofMinutes(10)));
     * assertEquals(start.plusSeconds(60), time.now());
     * }</pre>
     */
    public boolean advanceUntil(BooleanSupplier condition, Duration limit) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(limit, "limit");
        if (limit.isNegative()) {
            throw new IllegalArgumentException("time only moves forward, not by " + limit);
        }
        advancing.lock();
        try {
            Instant deadline = now.plus(limit);
            while (!condition.getAsBoolean()) {
                Instant next = nextDue();
                if (next == null || next.isAfter(deadline)) {
                    advanceTo(deadline);
                    return condition.getAsBoolean();
                }
                advanceTo(next.isAfter(now) ? next : now);
            }
            return true;
        } finally {
            advancing.unlock();
        }
    }

    /** How many scheduled tasks are waiting for their time, periodic ones counted once. */
    public int pendingTasks() {
        lock.lock();
        try {
            return (int) queue.stream().filter(entry -> entry instanceof Task<?>).count();
        } finally {
            lock.unlock();
        }
    }

    /** When the next scheduled task is due, if any is waiting. */
    public Optional<Instant> nextTaskDue() {
        lock.lock();
        try {
            return queue.stream().filter(entry -> entry instanceof Task<?>).min(IN_DUE_ORDER)
                    .map(entry -> entry.due);
        } finally {
            lock.unlock();
        }
    }

    /** How many threads are parked on this timeline right now, in {@link #sleep} or a timed wait. */
    public int sleepers() {
        lock.lock();
        try {
            return sleeperCount();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits, in real time, until at least {@code count} threads are parked in {@link #sleep} or in a
     * timed wait ({@link #within}, {@link #get}, {@link #poll}). Call it before advancing past a sleep
     * or a deadline a background thread is about to take: a thread that has not reached it yet would
     * otherwise measure it from the later time. Fails after the settle timeout.
     */
    public void awaitSleepers(int count) {
        long deadline = System.nanoTime() + settleTimeout.toNanos();
        lock.lock();
        try {
            while (sleeperCount() < count) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("expected " + count + " sleeping thread(s) within " + settleTimeout
                            + ", found " + sleeperCount());
                }
                changed.awaitNanos(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + count + " sleeping thread(s)", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * How long, in real time, {@link #advance} waits for a woken thread to park again or finish, and
     * {@link #awaitSleepers} for threads to arrive, before failing the test. Ten seconds by default.
     */
    public TestTime settleTimeout(Duration timeout) {
        this.settleTimeout = Objects.requireNonNull(timeout, "timeout");
        return this;
    }

    @Override
    public String toString() {
        return "TestTime[" + now + "]";
    }

    private int sleeperCount() {
        return (int) queue.stream().filter(entry -> entry instanceof Parked).count();
    }

    /** Removes the earliest entry due by {@code target} and moves the clock up to it. */
    private Entry takeDue(Instant target) {
        lock.lock();
        try {
            Entry head = queue.peek();
            if (head == null || head.due.isAfter(target)) {
                return null;
            }
            queue.remove();
            if (head.due.isAfter(now)) {
                now = head.due;
            }
            return head;
        } finally {
            lock.unlock();
        }
    }

    /** When the earliest task or parked thread is due, or {@code null} with nothing waiting. */
    private Instant nextDue() {
        lock.lock();
        try {
            Entry head = queue.peek();
            return head == null ? null : head.due;
        } finally {
            lock.unlock();
        }
    }

    private boolean isParked(Thread thread) {
        lock.lock();
        try {
            return queue.stream().anyMatch(entry -> entry instanceof Parked parked && parked.thread == thread);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ends a timed wait and says whether its deadline was reached. A deadline that fired while the
     * call was returning on its own left an interrupt nobody consumed; it belongs to this wait, so it
     * is cleared here.
     */
    private boolean leave(Parked wait, boolean interruptConsumed) {
        lock.lock();
        try {
            queue.remove(wait);
            wait.returned = true;
            changed.signalAll();
            if (wait.woken && !interruptConsumed) {
                Thread.interrupted();
            }
            return wait.woken;
        } finally {
            lock.unlock();
        }
    }

    /** Something due at a point on the timeline: a scheduled task or a sleeping thread. */
    private abstract static class Entry {
        Instant due;
        long sequence;

        Entry(Instant due, long sequence) {
            this.due = due;
            this.sequence = sequence;
        }

        abstract void fire();
    }

    /** A thread waiting for an instant: asleep in {@link #sleep}, or blocked elsewhere in a timed wait. */
    private final class Parked extends Entry {
        final Thread thread;
        /** Whether the thread is blocked outside this class, where only an interrupt reaches it. */
        final boolean interrupts;
        boolean woken;
        boolean returned;

        Parked(Instant due, long sequence, Thread thread, boolean interrupts) {
            super(due, sequence);
            this.thread = thread;
            this.interrupts = interrupts;
        }

        /** Wakes the thread, then holds the timeline still until it has stopped running again. */
        @Override
        void fire() {
            long deadline = System.nanoTime() + settleTimeout.toNanos();
            lock.lock();
            try {
                woken = true;
                if (interrupts) {
                    thread.interrupt();
                }
                changed.signalAll();
                while (!returned) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw stillRunning();
                    }
                    changed.awaitNanos(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waking " + thread.getName(), e);
            } finally {
                lock.unlock();
            }
            int parkedSamples = 0;
            while (thread.isAlive() && !isParked(thread)) {
                parkedSamples = ThreadStates.PARKED_OR_DONE.contains(thread.getState()) ? parkedSamples + 1 : 0;
                if (parkedSamples >= SETTLED_SAMPLES) {
                    return;
                }
                if (System.nanoTime() - deadline > 0) {
                    throw stillRunning();
                }
                LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(parkedSamples == 0 ? 50 : 500));
            }
        }

        private AssertionError stillRunning() {
            AssertionError error = new AssertionError(thread.getName() + " was still running " + settleTimeout
                    + " after its " + (interrupts ? "timed wait" : "sleep") + " ended at " + due
                    + "; it must sleep again, block or finish");
            error.setStackTrace(thread.getStackTrace());
            return error;
        }
    }

    private final class Task<V> extends Entry implements ScheduledFuture<V> {
        private final TimelineScheduler owner;
        private final Callable<V> body;
        /** Zero for a one-shot, positive for a fixed rate, negative for a fixed delay. */
        private final long periodNanos;
        private final CompletableFuture<V> result = new CompletableFuture<>();

        Task(TimelineScheduler owner, Callable<V> body, Instant due, long sequence, long periodNanos) {
            super(due, sequence);
            this.owner = owner;
            this.body = body;
            this.periodNanos = periodNanos;
        }

        @Override
        void fire() {
            if (result.isDone()) {
                owner.finished(this);
                return;
            }
            try {
                V value = body.call();
                if (periodNanos == 0) {
                    result.complete(value);
                }
            } catch (Throwable failure) {
                // What a real scheduler does: the failure goes to the future and a periodic task stops.
                result.completeExceptionally(failure);
            }
            lock.lock();
            try {
                if (!result.isDone() && !owner.shutdown) {
                    due = periodNanos > 0 ? due.plusNanos(periodNanos) : now.plusNanos(-periodNanos);
                    sequence = nextSequence++;
                    queue.add(this);
                    return;
                }
            } finally {
                lock.unlock();
            }
            result.cancel(false);
            owner.finished(this);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            lock.lock();
            try {
                return unit.convert(Duration.between(now, due));
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int compareTo(Delayed other) {
            if (other instanceof Entry entry) {
                return IN_DUE_ORDER.compare(this, entry);
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = result.cancel(false);
            lock.lock();
            try {
                if (queue.remove(this)) {
                    owner.finished(this);
                }
            } finally {
                lock.unlock();
            }
            return cancelled;
        }

        @Override
        public boolean isCancelled() {
            return result.isCancelled();
        }

        @Override
        public boolean isDone() {
            return result.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return result.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return result.get(timeout, unit);
        }
    }

    private final class TimelineScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        /** Tasks queued or running; guarded by {@link TestTime#lock}. */
        private final Set<Task<?>> tasks = new HashSet<>();
        private volatile boolean shutdown;

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Objects.requireNonNull(command, "command");
            return enqueue(Executors.callable(command), unit.toNanos(delay), 0);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return enqueue(Objects.requireNonNull(callable, "callable"), unit.toNanos(delay), 0);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                      TimeUnit unit) {
            Objects.requireNonNull(command, "command");
            if (period <= 0) {
                throw new IllegalArgumentException("period must be positive");
            }
            return enqueue(Executors.callable(command), unit.toNanos(initialDelay), unit.toNanos(period));
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                         TimeUnit unit) {
            Objects.requireNonNull(command, "command");
            if (delay <= 0) {
                throw new IllegalArgumentException("delay must be positive");
            }
            return enqueue(Executors.callable(command), unit.toNanos(initialDelay), -unit.toNanos(delay));
        }

        @Override
        public void execute(Runnable command) {
            schedule(command, 0, TimeUnit.NANOSECONDS);
        }

        private <V> Task<V> enqueue(Callable<V> body, long delayNanos, long periodNanos) {
            lock.lock();
            try {
                if (shutdown) {
                    throw new RejectedExecutionException("scheduler is shut down");
                }
                Task<V> task = new Task<>(this, body, now.plusNanos(Math.max(delayNanos, 0)), nextSequence++,
                        periodNanos);
                tasks.add(task);
                queue.add(task);
                return task;
            } finally {
                lock.unlock();
            }
        }

        void finished(Task<?> task) {
            lock.lock();
            try {
                tasks.remove(task);
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** As a real scheduler does by default: periodic work stops, delayed one-shots still run. */
        @Override
        public void shutdown() {
            lock.lock();
            try {
                shutdown = true;
                for (Task<?> task : List.copyOf(tasks)) {
                    if (task.periodNanos != 0 && queue.remove(task)) {
                        task.result.cancel(false);
                        tasks.remove(task);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            List<Runnable> dropped = new ArrayList<>();
            lock.lock();
            try {
                shutdown = true;
                for (Task<?> task : List.copyOf(tasks)) {
                    if (queue.remove(task)) {
                        tasks.remove(task);
                        dropped.add(() -> {
                            try {
                                task.body.call();
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
                    }
                }
            } finally {
                lock.unlock();
            }
            return dropped;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            lock.lock();
            try {
                return shutdown && tasks.isEmpty();
            } finally {
                lock.unlock();
            }
        }

        /** Answers at once: waiting in real time for work that only virtual time can finish would hang. */
        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public String toString() {
            return "TestTime.scheduler[" + now + "]";
        }
    }

    private final class TimelineClock extends Clock {
        private final ZoneId zone;

        TimelineClock(ZoneId zone) {
            this.zone = Objects.requireNonNull(zone, "zone");
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new TimelineClock(zone);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public long millis() {
            return now.toEpochMilli();
        }
    }
}

package io.github.hectorvent.floci.testing;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proof that one thread is waiting for a lock another thread holds, for tests that assert a request
 * cannot proceed while another is in its critical section. Where a test used to wait a fixed time and
 * conclude from the request not having finished that it was held, this names the lock holder: a
 * thread waiting for a monitor or lock its holder keeps until the test releases it cannot finish, and
 * a request that is not serialized runs to its end, which fails here at once.
 */
public final class LockWaits {

    private LockWaits() {}

    /**
     * Returns once {@code waiter} is waiting for a monitor or lock owned by {@code holder}. Fails if
     * {@code waiter} ends instead, or is not waiting behind {@code holder} after ten seconds.
     */
    public static void awaitBlockedBehind(Thread waiter, Thread holder) {
        awaitBlockedBehind(() -> waiter, () -> holder, () -> !waiter.isAlive());
    }

    /**
     * Same as {@link #awaitBlockedBehind(Thread, Thread)} for work on pool threads, which publish
     * themselves through the references: fails as soon as {@code task} completes, since a pool thread
     * outlives its task.
     */
    public static void awaitBlockedBehind(AtomicReference<Thread> waiter, AtomicReference<Thread> holder,
                                          Future<?> task) {
        awaitBlockedBehind(waiter::get, holder::get, task::isDone);
    }

    /**
     * Returns the thread that has stopped inside {@code owner}'s {@code method} to wait: blocked on a
     * monitor, in {@code Object.wait} or parked, with that method on its stack. For a caller that must
     * hold back until others are done, this shows it is holding back there, where a fixed wait only
     * showed it had not returned yet. Sees platform threads only. Fails after ten seconds.
     */
    public static Thread awaitWaitingIn(Class<?> owner, String method) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() - deadline < 0) {
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread.State state = entry.getKey().getState();
                if (state != Thread.State.BLOCKED && state != Thread.State.WAITING
                        && state != Thread.State.TIMED_WAITING) {
                    continue;
                }
                for (StackTraceElement frame : entry.getValue()) {
                    if (frame.getClassName().equals(owner.getName()) && frame.getMethodName().equals(method)) {
                        return entry.getKey();
                    }
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return fail("no thread ever waited in " + owner.getSimpleName() + "." + method);
    }

    /**
     * Returns once at least {@code threads} threads are in a timed wait entered straight from
     * {@code owner}'s {@code method}, with none of the owner's other code in between: each is idle
     * there until it is notified or its own timeout ends. For a long poll that must stay open, this
     * shows it is held at its wait, where a fixed wait only showed it had not returned yet. Sees
     * platform threads only. Fails after ten seconds.
     */
    public static void awaitTimedWaitIn(Class<?> owner, String method, int threads) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int waiting = 0;
        while (System.nanoTime() - deadline < 0) {
            waiting = 0;
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                if (entry.getKey().getState() == Thread.State.TIMED_WAITING
                        && waitsDirectlyIn(entry.getValue(), owner, method)) {
                    waiting++;
                }
            }
            if (waiting >= threads) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail(waiting + " of " + threads + " threads waited in " + owner.getSimpleName() + "." + method);
    }

    private static boolean waitsDirectlyIn(StackTraceElement[] stack, Class<?> owner, String method) {
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().startsWith("java.") || frame.getClassName().startsWith("jdk.")) {
                continue;
            }
            return frame.getClassName().equals(owner.getName()) && frame.getMethodName().equals(method);
        }
        return false;
    }

    private static void awaitBlockedBehind(Supplier<Thread> waiter, Supplier<Thread> holder,
                                           BooleanSupplier finished) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ThreadInfo info = null;
        while (System.nanoTime() - deadline < 0) {
            if (finished.getAsBoolean()) {
                fail("the request finished instead of waiting for the lock");
            }
            Thread waiting = waiter.get();
            Thread holding = holder.get();
            if (waiting != null && holding != null) {
                info = ManagementFactory.getThreadMXBean().getThreadInfo(waiting.threadId());
                if (info != null && info.getLockOwnerId() == holding.threadId()) {
                    return;
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("the request never waited for the lock its predecessor holds, it was "
                + (info == null ? "not started" : info.getThreadState() + " on " + info.getLockName()
                        + " owned by " + info.getLockOwnerName()));
    }
}

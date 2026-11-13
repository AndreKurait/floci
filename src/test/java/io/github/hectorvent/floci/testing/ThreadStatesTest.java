package io.github.hectorvent.floci.testing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadStatesTest {

    @Test
    void awaitBlockedOrDoneFailsWhenFutureWorkerNeverStarts() {
        TestTime time = TestTime.create();
        FutureTask<Void> task = new FutureTask<>(() -> null);
        try {
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> ThreadStates.awaitBlockedOrDone(new AtomicReference<>(), task, time::nanoTime,
                            millis -> time.sleepAdvancing(Duration.ofMillis(millis))));

            assertTrue(failure.getMessage().startsWith("worker blocked or finished before timeout"));
            assertFalse(task.isDone());
            assertEquals(TestTime.DEFAULT_START.plusSeconds(5), time.now(),
                    "the wait gives up exactly when its five seconds are over");
        } finally {
            task.cancel(false);
        }
    }

    @Test
    void awaitBlockedOrDoneAcceptsCompletedFutureWithoutWorker() throws Exception {
        ThreadStates.awaitBlockedOrDone(new AtomicReference<>(), CompletableFuture.completedFuture(null));
    }

    @Test
    void awaitBlockedOrDoneAcceptsParkedFutureWorker() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            worker.set(Thread.currentThread());
            release.await();
            return null;
        });
        Thread thread = Thread.ofPlatform().start(task);
        try {
            ThreadStates.awaitBlockedOrDone(worker, task);
            assertFalse(task.isDone());
            assertEquals(Thread.State.WAITING, thread.getState());
        } finally {
            release.countDown();
            thread.join(TimeUnit.SECONDS.toMillis(10));
        }
        task.get(10, TimeUnit.SECONDS);
        assertFalse(thread.isAlive());
    }

    @Test
    void awaitStateReturnsTheStateTheThreadReached() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertEquals(Thread.State.WAITING, ThreadStates.awaitParkedOrDone(waiter));
        } finally {
            release.countDown();
            waiter.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertEquals(Thread.State.TERMINATED, ThreadStates.awaitParkedOrDone(waiter));
    }

    @Test
    void awaitStateFailsNamingTheThreadWhenItNeverGetsThere() {
        Thread unstarted = new Thread(() -> { });

        AssertionError failure = assertThrows(AssertionError.class, () -> ThreadStates.awaitState(
                unstarted, EnumSet.of(Thread.State.WAITING), Duration.ofMillis(20), "the worker"));

        assertTrue(failure.getMessage().startsWith("the worker never reached [WAITING], it was NEW"),
                failure.getMessage());
    }
}

package io.floci.aws.testing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
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
    void awaitBlockedOrDoneGivesUpAfterFiveSecondsWhenFutureWorkerNeverStarts() throws Exception {
        TestTime time = TestTime.create();
        FutureTask<Void> task = new FutureTask<>(() -> null);
        try {
            ThreadStates.awaitBlockedOrDone(new AtomicReference<>(), task, time::nanoTime,
                    millis -> time.sleepAdvancing(Duration.ofMillis(millis)));

            assertFalse(task.isDone());
            assertEquals(TestTime.DEFAULT_START.plusSeconds(5), time.now(),
                    "the wait gives up exactly when its five seconds are over");
        } finally {
            task.cancel(false);
        }
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

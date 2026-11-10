package io.github.hectorvent.floci.core.common.concurrent;

import io.github.hectorvent.floci.testing.TestTime;
import io.github.hectorvent.floci.testing.ThreadStates;
import io.github.hectorvent.floci.testing.TimelineDeadlineWaits;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SocketDeadlineTest {

    @Test
    void resultWaitsForAdmissionCleanup() throws Exception {
        assertCleanupBeforeCompletion(false);
    }

    @Test
    void failureWaitsForAdmissionCleanup() throws Exception {
        assertCleanupBeforeCompletion(true);
    }

    private void assertCleanupBeforeCompletion(boolean fail) throws Exception {
        CountDownLatch cleaning = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicInteger exits = new AtomicInteger();
        IOException failure = new IOException("probe failed");
        FutureTask<String> result = new FutureTask<>(() -> SocketDeadline.call(10, () -> {
            if (fail) {
                throw failure;
            }
            return "healthy";
        }, () -> {
            cleaning.countDown();
            release.join();
            exits.incrementAndGet();
        }));
        Thread caller = Thread.ofVirtual().start(result);
        try {
            assertTrue(cleaning.await(5, TimeUnit.SECONDS));
            ThreadStates.awaitState(caller, Set.of(Thread.State.TIMED_WAITING, Thread.State.TERMINATED),
                    Duration.ofSeconds(5), "probe caller");
            assertFalse(result.isDone(), "the next queued probe must not run before admission cleanup");
        } finally {
            release.complete(null);
            caller.join(5000);
            assertFalse(caller.isAlive());
        }
        if (fail) {
            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS)).getCause());
        } else {
            assertEquals("healthy", result.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, exits.get());
    }

    @Test
    void interruptedCallerRetainsAdmissionUntilIgnoringWorkerFinishes() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicInteger exits = new AtomicInteger();
        FutureTask<String> result = new FutureTask<>(() -> SocketDeadline.call(10, () -> {
            started.countDown();
            release.join();
            return "late";
        }, () -> {
            exits.incrementAndGet();
            exited.countDown();
        }));
        Thread caller = Thread.ofVirtual().start(result);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedIOException.class, failure.getCause());
            assertEquals(0, exits.get(), "cancellation must not release a running probe's permit");
        } finally {
            release.complete(null);
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            caller.join(5000);
            assertFalse(caller.isAlive());
        }
        assertEquals(1, exits.get());
    }

    @Test
    void expiredCallsAlwaysReleaseAdmissionExactlyOnce() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            CountDownLatch exited = new CountDownLatch(1);
            AtomicInteger exits = new AtomicInteger();
            try {
                SocketDeadline.call(0, () -> "healthy", () -> {
                    exits.incrementAndGet();
                    exited.countDown();
                });
            } catch (SocketTimeoutException expected) {
                // The deadline may expire before the callable starts; cleanup is still required.
            }
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(1, exits.get());
        }
    }

    @Test
    void cleanupFailureIsReportedOnce() {
        IllegalStateException failure = new IllegalStateException("cleanup failed");
        AtomicInteger exits = new AtomicInteger();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> SocketDeadline.call(10, () -> "healthy", () -> {
                    exits.incrementAndGet();
                    throw failure;
                })));
        assertEquals(1, exits.get());
    }

    @Test
    void anOperationStillRunningWhenItsSecondsAreUpTimesOutAndIsInterrupted() throws Exception {
        TestTime time = TestTime.create();
        TimelineDeadlineWaits waits = new TimelineDeadlineWaits(time);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger exits = new AtomicInteger();
        FutureTask<String> result = new FutureTask<>(() -> SocketDeadline.call(10, () -> {
            started.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return "late";
        }, () -> {
            exits.incrementAndGet();
            exited.countDown();
        }, waits));
        Thread caller = Thread.ofVirtual().start(result);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            waits.awaitWaiting(1);

            time.advance(Duration.ofSeconds(10).minusNanos(1));
            assertFalse(result.isDone(), "the call gave up before its ten seconds had passed");
            assertEquals(1, interrupted.getCount());
            assertEquals(1, waits.waiting());

            time.advance(Duration.ofNanos(1));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertInstanceOf(SocketTimeoutException.class, failure.getCause());
            assertEquals("Socket operation exceeded 10 seconds", failure.getCause().getMessage());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the timed out operation must be interrupted");
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(1, exits.get());
        } finally {
            never.countDown();
            caller.join(5000);
            assertFalse(caller.isAlive());
        }
    }

    @Test
    void anOperationThatFinishesOneNanosecondBeforeTheDeadlineReturnsItsResult() throws Exception {
        TestTime time = TestTime.create();
        TimelineDeadlineWaits waits = new TimelineDeadlineWaits(time);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<String> answer = new CompletableFuture<>();
        AtomicInteger exits = new AtomicInteger();
        FutureTask<String> result = new FutureTask<>(() -> SocketDeadline.call(10, () -> {
            started.countDown();
            return answer.get();
        }, exits::incrementAndGet, waits));
        Thread caller = Thread.ofVirtual().start(result);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            waits.awaitWaiting(1);
            time.advance(Duration.ofSeconds(10).minusNanos(1));
            assertFalse(result.isDone());

            answer.complete("healthy");

            assertEquals("healthy", result.get(5, TimeUnit.SECONDS));
            assertEquals(1, exits.get());
            assertEquals(0, time.pendingTasks(), "a finished call must leave no deadline behind");
        } finally {
            answer.complete("healthy");
            caller.join(5000);
            assertFalse(caller.isAlive());
        }
    }
}

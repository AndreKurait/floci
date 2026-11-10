package io.floci.aws.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class TimelineDeadlineWaitsTest {

    private final TestTime time = TestTime.create();
    private final TimelineDeadlineWaits waits = new TimelineDeadlineWaits(time);

    @Test
    void nanoTimeIsTheTimelines() {
        long before = waits.nanoTime();

        time.advance(Duration.ofSeconds(15));

        assertEquals(Duration.ofSeconds(15).toNanos(), waits.nanoTime() - before);
    }

    @Test
    void aWaitTimesOutAtItsInstantAndNotOneNanosecondEarlier() throws Exception {
        CompletableFuture<String> never = new CompletableFuture<>();
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread waiter = Thread.ofPlatform().daemon().name("waiter").start(() -> {
            try {
                waits.get(never, Duration.ofSeconds(15).toNanos());
            } catch (TimeoutException expected) {
                timedOut.set(true);
            } catch (InterruptedException | ExecutionException e) {
                throw new AssertionError(e);
            }
        });
        waits.awaitWaiting(1);

        time.advance(Duration.ofSeconds(15).minusNanos(1));
        assertFalse(timedOut.get());
        assertEquals(1, waits.waiting());

        time.advance(Duration.ofNanos(1));
        assertTrue(timedOut.get());
        assertEquals(0, waits.waiting());
        waiter.join();
    }

    @Test
    void aFinishedFutureEndsTheWaitWithoutMovingTheTimeline() throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<String> seen = new CompletableFuture<>();
        Thread waiter = Thread.ofPlatform().daemon().name("waiter").start(() -> {
            try {
                seen.complete(waits.get(result, Duration.ofSeconds(15).toNanos()));
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                seen.completeExceptionally(e);
            }
        });
        waits.awaitWaiting(1);

        result.complete("done");

        assertEquals("done", seen.get());
        waiter.join();
        assertEquals(0, waits.waiting());
        assertEquals(0, time.pendingTasks());
    }

    @Test
    void aSpentTimeoutReturnsADoneFutureAndTimesOutOnAnUnfinishedOne() throws Exception {
        assertEquals("done", waits.get(CompletableFuture.completedFuture("done"), 0));
        assertThrows(TimeoutException.class, () -> waits.get(new CompletableFuture<>(), 0));
        assertThrows(TimeoutException.class, () -> waits.bounded(-1, () -> "unreached"));
    }

    @Test
    void aLatchWaitReportsWhetherTheLatchOpenedBeforeItsInstant() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        CompletableFuture<Boolean> sawOpened = new CompletableFuture<>();
        CompletableFuture<Boolean> sawNever = new CompletableFuture<>();
        Thread first = Thread.ofPlatform().daemon().name("opened-waiter").start(
                () -> latchWait(opened, sawOpened));
        Thread second = Thread.ofPlatform().daemon().name("never-waiter").start(
                () -> latchWait(never, sawNever));
        waits.awaitWaiting(2);

        opened.countDown();
        assertTrue(sawOpened.get());
        first.join();

        time.advance(Duration.ofSeconds(15).minusNanos(1));
        assertFalse(sawNever.isDone());
        time.advance(Duration.ofNanos(1));
        assertFalse(sawNever.get());
        second.join();
        assertEquals(0, waits.waiting());
    }

    @Test
    void aSpentLatchWaitReportsTheLatchAsItIs() throws Exception {
        CountDownLatch open = new CountDownLatch(0);

        assertTrue(waits.await(open, 0));
        assertFalse(waits.await(new CountDownLatch(1), 0));
        assertEquals(0, time.pendingTasks());
    }

    @Test
    void aMonitorWaitReturnsOnANotificationOrAtItsInstantWithTheMonitorHeld() throws Exception {
        Object monitor = new Object();
        AtomicInteger returns = new AtomicInteger();
        AtomicBoolean heldOnReturn = new AtomicBoolean(true);
        CountDownLatch notified = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().daemon().name("monitor-waiter").start(() -> {
            try {
                for (int i = 0; i < 2; i++) {
                    synchronized (monitor) {
                        waits.waitOn(monitor, Duration.ofSeconds(15).toNanos());
                        heldOnReturn.compareAndSet(true, Thread.holdsLock(monitor));
                        returns.incrementAndGet();
                        notified.countDown();
                    }
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        waits.awaitWaiting(1);

        synchronized (monitor) {
            monitor.notifyAll();
        }
        notified.await();
        waits.awaitWaiting(1);
        assertEquals(1, returns.get());

        time.advance(Duration.ofSeconds(15).minusNanos(1));
        assertEquals(1, returns.get());
        time.advance(Duration.ofNanos(1));
        waiter.join();
        assertEquals(2, returns.get());
        assertTrue(heldOnReturn.get());
        assertFalse(waiter.isInterrupted());
    }

    @Test
    void aWaiterQueuedForTheTimelinesLockHasNotSettled() throws Exception {
        // Other threads reading the timeline keep its lock busy, so the waiter often has to queue
        // for it while it puts its next expiry on the timeline. Queued there it looks parked, but
        // it is still on its way into that wait: the advance must hold still for it, or the next
        // expiry is measured from wherever the advance got to.
        AtomicBoolean reading = new AtomicBoolean(true);
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            readers.add(Thread.ofPlatform().daemon().name("reader-" + i).start(() -> {
                while (reading.get()) {
                    time.pendingTasks();
                }
            }));
        }
        CompletableFuture<String> never = new CompletableFuture<>();
        AtomicInteger timeouts = new AtomicInteger();
        Thread waiter = Thread.ofPlatform().daemon().name("waiter").start(() -> {
            try {
                while (true) {
                    try {
                        waits.get(never, Duration.ofMillis(100).toNanos());
                    } catch (TimeoutException expected) {
                        timeouts.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new AssertionError(e);
            }
        });
        try {
            waits.awaitWaiting(1);

            time.advance(Duration.ofSeconds(50).minusNanos(1));

            assertEquals(499, timeouts.get());
            assertEquals(1, waits.waiting());
        } finally {
            reading.set(false);
            waiter.interrupt();
            waiter.join();
            for (Thread reader : readers) {
                reader.join();
            }
        }
    }

    private void latchWait(CountDownLatch latch, CompletableFuture<Boolean> seen) {
        try {
            seen.complete(waits.await(latch, Duration.ofSeconds(15).toNanos()));
        } catch (InterruptedException e) {
            seen.completeExceptionally(e);
        }
    }
}

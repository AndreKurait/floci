package io.floci.aws.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class TestTimeTest {

    private static final Instant START = Instant.parse("2026-10-29T00:00:00Z");

    private final TestTime time = TestTime.startingAt(START);

    @Test
    void theClockMovesOnlyWhenAdvanced() {
        Clock zoned = time.clock().withZone(ZoneOffset.ofHours(7));

        assertEquals(START, time.clock().instant());
        assertEquals(START, time.clock().instant());

        time.advance(Duration.ofSeconds(90));

        assertEquals(START.plusSeconds(90), time.now());
        assertEquals(START.plusSeconds(90), time.clock().instant());
        assertEquals(START.plusSeconds(90), zoned.instant());
        assertEquals(ZoneOffset.ofHours(7), zoned.getZone());
        assertEquals(START.plusSeconds(90).toEpochMilli(), time.currentTimeMillis());
    }

    @Test
    void theMonotonicReadingAdvancesWithTheClock() {
        long before = time.nanoTime();

        time.advance(Duration.ofMillis(1500));

        assertEquals(Duration.ofMillis(1500).toNanos(), time.nanoTime() - before);
    }

    @Test
    void timeDoesNotMoveBackwards() {
        assertThrows(IllegalArgumentException.class, () -> time.advance(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> time.advanceTo(START.minusNanos(1)));
        assertEquals(START, time.now());
    }

    @Test
    void aDelayedTaskRunsExactlyWhenItsDelayHasPassed() {
        AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> future = time.scheduler().schedule(runs::incrementAndGet, 60, TimeUnit.SECONDS);

        time.advance(Duration.ofSeconds(59));
        assertEquals(0, runs.get());
        assertFalse(future.isDone());
        assertEquals(1, future.getDelay(TimeUnit.SECONDS));

        time.advance(Duration.ofSeconds(1));
        assertEquals(1, runs.get());
        assertTrue(future.isDone());
        assertEquals(0, time.pendingTasks());
    }

    @Test
    void aTaskSeesTheClockAtItsOwnDueTimeHoweverFarTheTestJumps() {
        List<Instant> seen = new ArrayList<>();
        time.scheduler().schedule(() -> seen.add(time.now()), 10, TimeUnit.SECONDS);
        time.scheduler().schedule(() -> seen.add(time.now()), 3, TimeUnit.SECONDS);

        time.advance(Duration.ofHours(1));

        assertEquals(List.of(START.plusSeconds(3), START.plusSeconds(10)), seen);
        assertEquals(START.plus(Duration.ofHours(1)), time.now());
    }

    @Test
    void tasksDueAtTheSameInstantRunInSubmissionOrder() {
        List<String> order = new ArrayList<>();
        time.scheduler().schedule(() -> order.add("first"), 1, TimeUnit.SECONDS);
        time.newScheduler().schedule(() -> order.add("second"), 1, TimeUnit.SECONDS);
        time.scheduler().schedule(() -> order.add("third"), 1, TimeUnit.SECONDS);

        time.advance(Duration.ofSeconds(1));

        assertEquals(List.of("first", "second", "third"), order);
    }

    @Test
    void submittedWorkWaitsForTheTestToRunIt() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        Future<String> future = time.scheduler().submit(() -> {
            ran.set(true);
            return "done";
        });

        assertFalse(ran.get());

        time.runDue();

        assertTrue(ran.get());
        assertEquals("done", future.get());
        assertEquals(START, time.now());
    }

    @Test
    void aFixedRateTaskRunsOncePerPeriod() {
        List<Instant> ticks = new ArrayList<>();
        time.scheduler().scheduleAtFixedRate(() -> ticks.add(time.now()), 5, 10, TimeUnit.SECONDS);

        time.advance(Duration.ofSeconds(4));
        assertEquals(List.of(), ticks);

        time.advance(Duration.ofSeconds(31));
        assertEquals(List.of(START.plusSeconds(5), START.plusSeconds(15), START.plusSeconds(25),
                START.plusSeconds(35)), ticks);
        assertEquals(1, time.pendingTasks());
        assertEquals(START.plusSeconds(45), time.nextTaskDue().orElseThrow());
    }

    @Test
    void aFixedDelayTaskWaitsItsDelayAfterEachRunEnds() {
        List<Instant> ticks = new ArrayList<>();
        time.scheduler().scheduleWithFixedDelay(() -> {
            ticks.add(time.now());
            time.sleepAdvancing(Duration.ofSeconds(2));
        }, 1, 10, TimeUnit.SECONDS);

        time.advance(Duration.ofSeconds(30));

        assertEquals(List.of(START.plusSeconds(1), START.plusSeconds(13), START.plusSeconds(25)), ticks);
    }

    @Test
    void aTaskScheduledInsideTheWindowRunsInTheSameAdvance() {
        List<String> order = new ArrayList<>();
        ScheduledExecutorService scheduler = time.scheduler();
        scheduler.schedule(() -> {
            order.add("retry scheduled at " + time.now());
            scheduler.schedule(() -> order.add("retry ran at " + time.now()), 2, TimeUnit.SECONDS);
        }, 1, TimeUnit.SECONDS);

        time.advance(Duration.ofSeconds(3));

        assertEquals(List.of("retry scheduled at " + START.plusSeconds(1), "retry ran at " + START.plusSeconds(3)),
                order);
    }

    @Test
    void aCancelledTaskNeverRuns() {
        AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> oneShot = time.scheduler().schedule(runs::incrementAndGet, 1, TimeUnit.SECONDS);
        ScheduledFuture<?> periodic = time.scheduler().scheduleAtFixedRate(runs::incrementAndGet, 1, 1,
                TimeUnit.SECONDS);

        assertTrue(oneShot.cancel(false));
        time.advance(Duration.ofSeconds(1));
        assertEquals(1, runs.get());

        assertTrue(periodic.cancel(false));
        time.advance(Duration.ofMinutes(1));

        assertEquals(1, runs.get());
        assertTrue(oneShot.isCancelled());
        assertThrows(CancellationException.class, periodic::get);
        assertEquals(0, time.pendingTasks());
    }

    @Test
    void aPeriodicTaskThatThrowsStopsLikeARealOne() {
        AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> future = time.scheduler().scheduleAtFixedRate(() -> {
            if (runs.incrementAndGet() == 2) {
                throw new IllegalStateException("boom");
            }
        }, 1, 1, TimeUnit.SECONDS);

        time.advance(Duration.ofSeconds(10));

        assertEquals(2, runs.get());
        ExecutionException failure = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test
    void shutdownStopsPeriodicWorkAndKeepsDelayedWork() throws Exception {
        ScheduledExecutorService scheduler = time.newScheduler();
        AtomicInteger periodic = new AtomicInteger();
        AtomicInteger delayed = new AtomicInteger();
        scheduler.scheduleAtFixedRate(periodic::incrementAndGet, 1, 1, TimeUnit.SECONDS);
        scheduler.schedule(delayed::incrementAndGet, 5, TimeUnit.SECONDS);
        time.advance(Duration.ofSeconds(2));

        scheduler.shutdown();

        assertTrue(scheduler.isShutdown());
        assertFalse(scheduler.isTerminated());
        assertThrows(RejectedExecutionException.class, () -> scheduler.execute(() -> { }));
        time.advance(Duration.ofSeconds(10));
        assertEquals(2, periodic.get());
        assertEquals(1, delayed.get());
        assertTrue(scheduler.isTerminated());
        assertTrue(scheduler.awaitTermination(1, TimeUnit.HOURS));
    }

    @Test
    void shutdownNowDropsEverythingAndLeavesOtherSchedulersAlone() {
        ScheduledExecutorService stopped = time.newScheduler();
        AtomicInteger dropped = new AtomicInteger();
        AtomicInteger kept = new AtomicInteger();
        stopped.schedule(dropped::incrementAndGet, 1, TimeUnit.SECONDS);
        stopped.scheduleWithFixedDelay(dropped::incrementAndGet, 1, 1, TimeUnit.SECONDS);
        time.scheduler().schedule(kept::incrementAndGet, 1, TimeUnit.SECONDS);

        assertEquals(2, stopped.shutdownNow().size());

        assertTrue(stopped.isTerminated());
        time.advance(Duration.ofSeconds(5));
        assertEquals(0, dropped.get());
        assertEquals(1, kept.get());
    }

    @Test
    void aSleepingThreadWakesExactlyWhenItsSleepHasPassed() throws Exception {
        AtomicBoolean woke = new AtomicBoolean();
        Thread sleeper = Thread.ofPlatform().name("sleeper").start(() -> {
            try {
                time.sleep(Duration.ofSeconds(60));
                woke.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        time.awaitSleepers(1);

        time.advance(Duration.ofSeconds(59));
        assertFalse(woke.get());
        assertEquals(1, time.sleepers());

        time.advance(Duration.ofSeconds(1));
        assertTrue(woke.get());
        assertEquals(0, time.sleepers());
        sleeper.join();
    }

    @Test
    void aPollingLoopTakesOneTurnPerIntervalHoweverFarTheTestJumps() throws Exception {
        List<Instant> polls = new CopyOnWriteArrayList<>();
        Thread poller = Thread.ofVirtual().name("poller").start(() -> {
            try {
                for (int turn = 0; turn < 5; turn++) {
                    time.sleepMillis(200);
                    polls.add(time.now());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        time.awaitSleepers(1);

        time.advance(Duration.ofMillis(650));
        assertEquals(List.of(START.plusMillis(200), START.plusMillis(400), START.plusMillis(600)), polls);

        time.advance(Duration.ofMinutes(5));
        assertEquals(5, polls.size());
        assertEquals(START.plusMillis(1000), polls.get(4));
        poller.join();
    }

    @Test
    void sleepersAndTasksInterleaveInDueOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        Thread sleeper = Thread.ofPlatform().name("sleeper").start(() -> {
            try {
                time.sleep(Duration.ofSeconds(2));
                order.add("sleeper");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        time.awaitSleepers(1);
        time.scheduler().schedule(() -> order.add("before"), 1, TimeUnit.SECONDS);
        time.scheduler().schedule(() -> order.add("after"), 3, TimeUnit.SECONDS);

        time.advance(Duration.ofSeconds(3));

        sleeper.join();
        assertEquals(List.of("before", "sleeper", "after"), order);
    }

    @Test
    void anInterruptedSleeperLeavesTheTimeline() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread sleeper = Thread.ofPlatform().name("sleeper").start(() -> {
            try {
                time.sleep(Duration.ofSeconds(60));
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        time.awaitSleepers(1);

        sleeper.interrupt();
        sleeper.join();

        assertTrue(interrupted.get());
        assertEquals(0, time.sleepers());
        time.advance(Duration.ofMinutes(2));
    }

    @Test
    void aTaskThatSleepsMovesTheTimelineItself() {
        List<String> order = new ArrayList<>();
        time.scheduler().schedule(() -> {
            order.add("start " + time.now());
            try {
                time.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            order.add("end " + time.now());
        }, 1, TimeUnit.SECONDS);
        time.scheduler().schedule(() -> order.add("other " + time.now()), 3, TimeUnit.SECONDS);

        time.advance(Duration.ofSeconds(2));

        assertEquals(List.of("start " + START.plusSeconds(1), "other " + START.plusSeconds(3),
                "end " + START.plusSeconds(6)), order);
        assertEquals(START.plusSeconds(6), time.now());
    }

    @Test
    void aSleepOnTheTestThreadCanPassByItself() {
        AtomicInteger ticks = new AtomicInteger();
        time.scheduler().scheduleAtFixedRate(ticks::incrementAndGet, 1, 1, TimeUnit.SECONDS);

        time.sleepAdvancing(Duration.ofSeconds(3));

        assertEquals(3, ticks.get());
        assertEquals(START.plusSeconds(3), time.now());
    }

    @Test
    void waitingForASleeperThatNeverArrivesFails() {
        time.settleTimeout(Duration.ofMillis(50));

        AssertionError error = assertThrows(AssertionError.class, () -> time.awaitSleepers(1));

        assertTrue(error.getMessage().contains("expected 1 sleeping thread(s)"), error.getMessage());
    }

    @Test
    void aTimedGetTimesOutExactlyWhenItsTimeoutHasPassed() throws Exception {
        CompletableFuture<String> never = new CompletableFuture<>();
        AtomicReference<Object> outcome = new AtomicReference<>();
        AtomicBoolean interruptLeftBehind = new AtomicBoolean();
        Thread waiter = Thread.ofPlatform().name("waiter").start(() -> {
            try {
                outcome.set(time.get(never, Duration.ofMinutes(5)));
            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                outcome.set(e);
            }
            interruptLeftBehind.set(Thread.currentThread().isInterrupted());
        });
        time.awaitWaiters(1);

        time.advance(Duration.ofMinutes(5).minusNanos(1));
        assertNull(outcome.get());
        assertEquals(1, time.waiters());

        time.advance(Duration.ofNanos(1));
        assertInstanceOf(TimeoutException.class, outcome.get());
        assertEquals(0, time.waiters());
        waiter.join();
        assertFalse(interruptLeftBehind.get());
    }

    @Test
    void aTimedGetReturnsWhatArrivesBeforeItsDeadline() throws Exception {
        CompletableFuture<String> answer = new CompletableFuture<>();
        CompletableFuture<String> failure = new CompletableFuture<>();
        AtomicReference<Object> answered = new AtomicReference<>();
        AtomicReference<Object> failed = new AtomicReference<>();
        Thread first = Thread.ofVirtual().name("first").start(() -> answered.set(timedGet(answer)));
        Thread second = Thread.ofPlatform().name("second").start(() -> failed.set(timedGet(failure)));
        time.awaitWaiters(2);
        time.advance(Duration.ofMinutes(5).minusNanos(1));

        answer.complete("signalled");
        failure.completeExceptionally(new IllegalStateException("boom"));
        first.join();
        second.join();

        assertEquals("signalled", answered.get());
        assertInstanceOf(IllegalStateException.class, ((ExecutionException) failed.get()).getCause());
        assertEquals(0, time.waiters());
        time.advance(Duration.ofMinutes(1));
        assertEquals("signalled", answered.get());
    }

    @Test
    void aTimedPollTakesOneTurnPerTimeoutHoweverFarTheTestJumps() throws Exception {
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        List<String> polled = new CopyOnWriteArrayList<>();
        Thread poller = Thread.ofVirtual().name("poller").start(() -> {
            try {
                for (int turn = 0; turn < 4; turn++) {
                    polled.add(time.poll(queue, Duration.ofSeconds(20)) + " at " + time.now());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        time.awaitWaiters(1);

        time.advance(Duration.ofSeconds(50));
        assertEquals(List.of("null at " + START.plusSeconds(20), "null at " + START.plusSeconds(40)), polled);

        queue.add("message");
        queue.add("another");
        poller.join();
        assertEquals(List.of("message at " + START.plusSeconds(50), "another at " + START.plusSeconds(50)),
                polled.subList(2, 4));
        assertEquals(0, time.waiters());
    }

    @Test
    void aCallReturningAfterDeadlineSelectionIsNotInterruptedByItsWake() throws Exception {
        CompletableFuture<String> response = new CompletableFuture<>();
        CompletableFuture<String> result = new CompletableFuture<>();
        CountDownLatch returned = new CountDownLatch(1);
        Semaphore continueAfterWake = new Semaphore(0);
        AtomicBoolean staleInterrupt = new AtomicBoolean();
        Thread waiter = Thread.ofPlatform().name("returned-before-wake").start(() -> {
            try {
                result.complete(time.within(Duration.ofSeconds(1), response::get));
                returned.countDown();
                continueAfterWake.acquireUninterruptibly();
                staleInterrupt.set(Thread.currentThread().isInterrupted());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        try {
            time.awaitWaiters(1);
            // Split selection from firing to force the return between these two real timeline steps.
            Method takeDue = TestTime.class.getDeclaredMethod("takeDue", Instant.class);
            takeDue.setAccessible(true);
            Object due = takeDue.invoke(time, START.plusSeconds(1));
            response.complete("returned normally");
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            assertEquals("returned normally", result.get(5, TimeUnit.SECONDS));
            Method fire = due.getClass().getDeclaredMethod("fire");
            fire.setAccessible(true);
            fire.invoke(due);
            continueAfterWake.release();
            waiter.join(5000);
            assertFalse(waiter.isAlive());
            assertFalse(staleInterrupt.get(), "the selected deadline must not interrupt a call that already returned");
            assertEquals(0, time.waiters());
        } finally {
            response.complete("cleanup");
            continueAfterWake.release();
            waiter.interrupt();
            waiter.join(5000);
        }
    }

    @Test
    void anyBlockingCallCanBeBoundedOnTheTimeline() throws Exception {
        Object monitor = new Object();
        CountDownLatch latch = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        Thread onMonitor = Thread.ofPlatform().name("on-monitor").start(() -> {
            synchronized (monitor) {
                try {
                    time.within(Duration.ofSeconds(30), () -> {
                        monitor.wait();
                        return null;
                    });
                    order.add("notified");
                } catch (TimeoutException e) {
                    order.add("monitor timed out at " + time.now());
                } catch (InterruptedException e) {
                    order.add("interrupted");
                }
            }
        });
        Thread onLatch = Thread.ofPlatform().name("on-latch").start(() -> {
            try {
                time.within(Duration.ofSeconds(10), () -> {
                    latch.await();
                    return null;
                });
                order.add("released");
            } catch (TimeoutException e) {
                order.add("latch timed out at " + time.now());
            } catch (InterruptedException e) {
                order.add("interrupted");
            }
        });
        time.awaitWaiters(2);

        time.advance(Duration.ofMinutes(1));

        onMonitor.join();
        onLatch.join();
        assertEquals(List.of("latch timed out at " + START.plusSeconds(10),
                "monitor timed out at " + START.plusSeconds(30)), order);
    }

    @Test
    void aThreadInterruptedInATimedWaitSeesTheInterruptNotATimeout() throws Exception {
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().name("waiter").start(
                () -> outcome.set(timedGet(new CompletableFuture<>())));
        time.awaitWaiters(1);

        waiter.interrupt();
        waiter.join();

        assertInstanceOf(InterruptedException.class, outcome.get());
        assertEquals(0, time.waiters());
        time.advance(Duration.ofMinutes(10));
    }

    @Test
    void aTimedWaitWithNoTimeLeftNeverBlocks() throws Exception {
        CompletableFuture<String> pending = new CompletableFuture<>();
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("ready"));

        assertThrows(TimeoutException.class, () -> time.get(pending, Duration.ZERO));
        assertThrows(TimeoutException.class, () -> time.within(Duration.ofSeconds(-1), () -> pending.get()));
        assertEquals("done", time.get(CompletableFuture.completedFuture("done"), Duration.ZERO));
        assertEquals("ready", time.poll(queue, Duration.ZERO));
        assertNull(time.poll(queue, Duration.ZERO));
        assertEquals(START, time.now());
    }

    @Test
    void aTimedGetOnTheTestThreadCanPassByItself() throws Exception {
        CompletableFuture<String> response = new CompletableFuture<>();
        AtomicInteger ticks = new AtomicInteger();
        time.scheduler().scheduleAtFixedRate(ticks::incrementAndGet, 1, 1, TimeUnit.SECONDS);
        time.scheduler().schedule(() -> response.complete("late answer"), 3, TimeUnit.SECONDS);

        assertEquals("late answer", time.getAdvancing(response, Duration.ofSeconds(10)));
        assertEquals(START.plusSeconds(3), time.now());
        assertEquals(3, ticks.get());

        assertThrows(TimeoutException.class,
                () -> time.getAdvancing(new CompletableFuture<>(), Duration.ofSeconds(10)));
        assertEquals(START.plusSeconds(13), time.now());
        assertEquals(13, ticks.get());
    }

    @Test
    void aTimedGetInsideATaskMovesTheTimelineItself() {
        CompletableFuture<String> response = new CompletableFuture<>();
        List<String> order = new ArrayList<>();
        time.scheduler().schedule(() -> {
            try {
                order.add(time.get(response, Duration.ofSeconds(30)) + " at " + time.now());
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                order.add(e.toString());
            }
        }, 1, TimeUnit.SECONDS);
        time.scheduler().schedule(() -> response.complete("answer"), 5, TimeUnit.SECONDS);

        time.advance(Duration.ofSeconds(1));

        assertEquals(List.of("answer at " + START.plusSeconds(5)), order);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> {
            Future<?> task = time.scheduler().submit(() -> time.within(Duration.ofSeconds(1), () -> response.get()));
            time.runDue();
            try {
                task.get();
            } catch (ExecutionException e) {
                throw (RuntimeException) e.getCause();
            }
        });
        assertTrue(refused.getMessage().contains("would stop the timeline"), refused.getMessage());
    }

    @Test
    void advanceUntilStopsAtTheInstantTheConditionFirstHolds() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        Thread poller = Thread.ofVirtual().name("poller").start(() -> {
            try {
                while (true) {
                    time.sleepMillis(250);
                    polls.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        time.awaitSleepers(1);

        assertTrue(time.advanceUntil(() -> polls.get() == 7, Duration.ofMinutes(5)));
        assertEquals(START.plusMillis(1750), time.now());

        assertFalse(time.advanceUntil(() -> polls.get() == 12, Duration.ofSeconds(1)));
        assertEquals(START.plusMillis(2750), time.now());
        assertEquals(11, polls.get());

        assertTrue(time.advanceUntil(() -> true, Duration.ofHours(1)));
        assertEquals(START.plusMillis(2750), time.now());
        assertThrows(IllegalArgumentException.class, () -> time.advanceUntil(() -> true, Duration.ofNanos(-1)));
        poller.interrupt();
        poller.join();
    }

    /** What a five-minute timed get of {@code future} ends with: its value, or what it threw. */
    private Object timedGet(CompletableFuture<String> future) {
        try {
            return time.get(future, Duration.ofMinutes(5));
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            return e;
        }
    }

    @Test
    void sleepersAndTimedWaitersAreCountedApart() throws Exception {
        Thread sleeper = Thread.ofVirtual().name("sleeper").start(() -> {
            try {
                time.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread waiter = Thread.ofVirtual().name("waiter").start(() -> timedGet(new CompletableFuture<>()));
        time.awaitSleepers(1);
        time.awaitWaiters(1);

        assertEquals(1, time.sleepers());
        assertEquals(1, time.waiters());

        time.advance(Duration.ofMinutes(1));
        sleeper.join();
        assertEquals(0, time.sleepers());
        assertEquals(1, time.waiters());

        time.advance(Duration.ofMinutes(4));
        waiter.join();
        assertEquals(0, time.waiters());
        time.settleTimeout(Duration.ofMillis(50));
        AssertionError error = assertThrows(AssertionError.class, () -> time.awaitWaiters(1));
        assertTrue(error.getMessage().contains("expected 1 waiting thread(s)"), error.getMessage());
    }

    @Test
    void aSleeperQueuedForTheTimelinesOwnLockHasNotSettled() throws Exception {
        // Other threads reading the timeline keep its lock busy, so the poller often has to queue
        // for it on the way back into its next sleep. Queued there it looks parked, but it is still
        // on its way: the advance must wait for it, or it leaves polls behind.
        AtomicBoolean reading = new AtomicBoolean(true);
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            readers.add(Thread.ofPlatform().daemon().name("reader-" + i).start(() -> {
                while (reading.get()) {
                    time.sleepers();
                }
            }));
        }
        AtomicInteger polls = new AtomicInteger();
        Thread poller = Thread.ofPlatform().daemon().name("poller").start(() -> {
            try {
                while (true) {
                    time.sleep(Duration.ofMillis(100));
                    polls.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            time.awaitSleepers(1);

            time.advance(Duration.ofSeconds(50).minusNanos(1));

            assertEquals(499, polls.get());
            assertEquals(1, time.sleepers());
        } finally {
            reading.set(false);
            poller.interrupt();
            poller.join();
            for (Thread reader : readers) {
                reader.join();
            }
        }
    }
}

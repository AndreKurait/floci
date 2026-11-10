package io.github.hectorvent.floci.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
}

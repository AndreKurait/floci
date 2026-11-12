package io.floci.aws.core.common;

import io.floci.aws.testing.TestTime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SweepWorkerTest {
    private static final Instant NOW = Instant.parse("2030-01-07T02:00:00Z");

    private final List<Instant> swept = new CopyOnWriteArrayList<>();

    private SweepWorker worker(boolean enabled, Consumer<Instant> sweep) {
        return new SweepWorker("test-sweep", enabled, 60, false, sweep);
    }

    private static SweepWorker timed(TestTime time, long intervalSeconds, boolean sweepAtStart,
                                     Consumer<Instant> sweep) {
        return new SweepWorker("test-sweep", intervalSeconds, sweepAtStart, sweep, time.scheduler(), time.clock());
    }

    private static void awaitQueuedSweeps(SweepWorker worker, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (worker.queuedSweeps() < expected) {
            assertTrue(System.nanoTime() < deadline, "Expected " + expected + " thread(s) waiting for the sweep");
            Thread.onSpinWait();
        }
    }

    @Test
    void backgroundThreadExistsOnlyWhenEnabled() {
        assertTrue(worker(true, swept::add).active());
        assertFalse(worker(false, swept::add).active());
    }

    @Test
    void tickRunsTheSweepAtTheGivenInstantEvenWithoutABackgroundThread() {
        worker(false, swept::add).tick(NOW);
        assertEquals(List.of(NOW), swept);
    }

    @Test
    void tickIsSkippedBetweenPauseAndResume() {
        SweepWorker worker = worker(false, swept::add);
        worker.pause();
        worker.tick(NOW);
        assertTrue(swept.isEmpty());

        worker.resume();
        worker.tick(NOW);
        assertEquals(List.of(NOW), swept);
    }

    @Test
    void pauseDrainsARunningSweepAndHoldsLaterOnesUntilResume() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SweepWorker worker = worker(false, now -> {
            swept.add(now);
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> sweep = executor.submit(() -> worker.tick(NOW));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            Future<?> pause = executor.submit(worker::pause);
            awaitQueuedSweeps(worker, 1);
            assertFalse(pause.isDone());

            release.countDown();
            pause.get(5, TimeUnit.SECONDS);
            sweep.get(5, TimeUnit.SECONDS);
            worker.tick(NOW.plusSeconds(60));
            assertEquals(List.of(NOW), swept);

            worker.resume();
            worker.tick(NOW.plusSeconds(120));
            assertEquals(List.of(NOW, NOW.plusSeconds(120)), swept);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void overlappingTicksNeverRunTheSweepConcurrently() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SweepWorker worker = worker(false, now -> {
            peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
        });
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = executor.submit(() -> worker.tick(NOW));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> worker.tick(NOW));
            Future<?> third = executor.submit(() -> worker.tick(NOW));
            awaitQueuedSweeps(worker, 2);
            assertEquals(1, concurrent.get());

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            third.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, peak.get());
    }

    @Test
    void aFailedSweepReleasesTheLock() {
        List<Instant> attempts = new ArrayList<>();
        SweepWorker worker = worker(false, now -> {
            attempts.add(now);
            if (attempts.size() == 1) {
                throw new IllegalStateException("boom");
            }
        });
        assertThrows(IllegalStateException.class, () -> worker.tick(NOW));
        worker.tick(NOW.plusSeconds(60));
        assertEquals(List.of(NOW, NOW.plusSeconds(60)), attempts);
    }

    @Test
    void startRunsTheFirstSweepAtOnceWhenAskedTo() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        SweepWorker worker = new SweepWorker("test-sweep", true, 3600, true, now -> first.countDown());
        try {
            worker.start();
            assertTrue(first.await(5, TimeUnit.SECONDS));
        } finally {
            worker.stop();
        }
    }

    @Test
    void startWaitsOneIntervalBeforeTheFirstSweepByDefault() {
        TestTime time = TestTime.create();
        SweepWorker worker = timed(time, 3600, false, swept::add);
        Instant started = time.now();
        worker.start();

        time.advance(Duration.ofSeconds(3599));
        assertTrue(swept.isEmpty(), "the first sweep ran before one interval had passed");

        time.advance(Duration.ofSeconds(1));
        assertEquals(List.of(started.plusSeconds(3600)), swept);
    }

    @Test
    void aWorkerAskedToSweepAtStartStillWaitsAFullIntervalForTheSecondSweep() {
        TestTime time = TestTime.create();
        SweepWorker worker = timed(time, 3600, true, swept::add);
        Instant started = time.now();
        worker.start();

        time.runDue();
        assertEquals(List.of(started), swept);

        time.advance(Duration.ofSeconds(3599));
        assertEquals(List.of(started), swept);

        time.advance(Duration.ofSeconds(1));
        assertEquals(List.of(started, started.plusSeconds(3600)), swept);
    }

    @Test
    void sweepsRepeatEveryIntervalAtTheInstantTheClockShows() {
        TestTime time = TestTime.create();
        SweepWorker worker = timed(time, 60, false, swept::add);
        Instant started = time.now();
        worker.start();

        time.advance(Duration.ofSeconds(179));
        assertEquals(List.of(started.plusSeconds(60), started.plusSeconds(120)), swept);

        time.advance(Duration.ofSeconds(1));
        assertEquals(List.of(started.plusSeconds(60), started.plusSeconds(120), started.plusSeconds(180)), swept);
    }

    @Test
    void anIntervalBelowOneSecondIsRaisedToOneSecond() {
        TestTime time = TestTime.create();
        SweepWorker worker = timed(time, 0, false, swept::add);
        Instant started = time.now();
        worker.start();

        time.advance(Duration.ofMillis(999));
        assertTrue(swept.isEmpty(), "a zero interval must not sweep faster than once a second");

        time.advance(Duration.ofMillis(1));
        assertEquals(List.of(started.plusSeconds(1)), swept);
    }

    @Test
    void aFailedScheduledSweepDoesNotStopTheLaterOnes() {
        TestTime time = TestTime.create();
        List<Instant> attempts = new ArrayList<>();
        SweepWorker worker = timed(time, 60, false, now -> {
            attempts.add(now);
            if (attempts.size() == 1) {
                throw new IllegalStateException("boom");
            }
        });
        Instant started = time.now();
        worker.start();

        time.advance(Duration.ofSeconds(60));
        assertEquals(List.of(started.plusSeconds(60)), attempts);

        time.advance(Duration.ofSeconds(60));
        assertEquals(List.of(started.plusSeconds(60), started.plusSeconds(120)), attempts);
    }

    @Test
    void scheduledSweepsAreSkippedBetweenPauseAndResume() {
        TestTime time = TestTime.create();
        SweepWorker worker = timed(time, 60, false, swept::add);
        Instant started = time.now();
        worker.start();

        worker.pause();
        time.advance(Duration.ofSeconds(120));
        assertTrue(swept.isEmpty());

        worker.resume();
        time.advance(Duration.ofSeconds(59));
        assertTrue(swept.isEmpty(), "resuming must not run a sweep before the next interval ends");

        time.advance(Duration.ofSeconds(1));
        assertEquals(List.of(started.plusSeconds(180)), swept);
    }

    @Test
    void stopCancelsTheSweepsStillToCome() {
        TestTime time = TestTime.create();
        SweepWorker worker = timed(time, 60, false, swept::add);
        Instant started = time.now();
        worker.start();
        time.advance(Duration.ofSeconds(60));

        worker.stop();
        time.advance(Duration.ofSeconds(3600));

        assertEquals(List.of(started.plusSeconds(60)), swept);
        assertEquals(0, time.pendingTasks());
    }

    @Test
    void stopWithoutABackgroundThreadDoesNothing() {
        SweepWorker worker = worker(false, swept::add);
        worker.start();
        worker.stop();
        assertTrue(swept.isEmpty());
    }
}

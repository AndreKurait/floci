package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SweepWorkerTerminationTest {
    @Test
    void stopWaitsForTheThreadAfterTheExecutorSignalsTermination() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch tailEntered = new CountDownLatch(1);
        CountDownLatch releaseTail = new CountDownLatch(1);
        AtomicReference<Thread> actual = new AtomicReference<>();
        AtomicReference<Throwable> tailFailure = new AtomicReference<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(() -> {
                try {
                    task.run();
                } finally {
                    Thread.interrupted();
                    tailEntered.countDown();
                    try {
                        if (!releaseTail.await(10, TimeUnit.SECONDS)) {
                            tailFailure.set(new AssertionError("Worker tail was not released"));
                        }
                    } catch (InterruptedException failure) {
                        tailFailure.set(failure);
                    }
                }
            }, "controlled-sweep-tail");
            actual.set(thread);
            return thread;
        });
        SweepWorker worker = new SweepWorker("controlled-sweep-tail", 3600, true, now -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
            }
        }, scheduler, Clock.systemUTC());
        try (ExecutorService stopping = Executors.newSingleThreadExecutor()) {
            try {
                worker.start();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Future<?> stopped = stopping.submit(worker::stop);
                assertTrue(tailEntered.await(5, TimeUnit.SECONDS));
                assertEquals(0, interrupted.getCount());
                assertTrue(actual.get().isAlive());
                assertThrows(TimeoutException.class, () -> stopped.get(1, TimeUnit.SECONDS),
                        "Stop must not return while its observed worker thread is still alive");
                releaseTail.countDown();
                stopped.get(5, TimeUnit.SECONDS);
                assertFalse(actual.get().isAlive());
                assertNull(tailFailure.get());
            } finally {
                releaseTail.countDown();
                worker.stop();
                actual.get().join(5_000);
                assertFalse(actual.get().isAlive(), "The controlled worker must be cleaned up");
            }
        }
    }

    @Test
    void shutdownRemainsBoundedWhenTheCallbackIgnoresInterruption() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> actual = new AtomicReference<>();
        SweepWorker worker = new SweepWorker("uncooperative-sweep", true, 3600, true, now -> {
            actual.set(Thread.currentThread());
            entered.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Model a callback that cannot be stopped by an interrupt.
                }
            }
        });
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            worker.stop();
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 8,
                    "Shutdown must not add a second wait budget or wait indefinitely");
            assertTrue(actual.get().isAlive(), "The controlled callback is still held");
        } finally {
            release.countDown();
            worker.stop();
            actual.get().join(5_000);
            assertFalse(actual.get().isAlive());
        }
    }

    @Test
    void aSweepCanRequestItsOwnShutdownWithoutWaitingForItself() throws Exception {
        AtomicReference<SweepWorker> owner = new AtomicReference<>();
        AtomicReference<Thread> actual = new AtomicReference<>();
        CountDownLatch stopped = new CountDownLatch(1);
        SweepWorker worker = new SweepWorker("self-stopping-sweep", true, 3600, true, now -> {
            actual.set(Thread.currentThread());
            owner.get().stop();
            stopped.countDown();
        });
        owner.set(worker);
        try {
            worker.start();
            assertTrue(stopped.await(3, TimeUnit.SECONDS), "A worker must not wait for its own termination");
        } finally {
            worker.stop();
        }
        assertFalse(actual.get().isAlive());
    }

    @Test
    void theSuppliedClockStillControlsScheduledSweeps() throws Exception {
        Instant fixed = Instant.parse("2030-01-07T02:00:00Z");
        AtomicReference<Instant> observed = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        SweepWorker worker = new SweepWorker("fixed-clock-sweep", 3600, true, now -> {
            observed.set(now);
            called.countDown();
        }, scheduler, Clock.fixed(fixed, ZoneOffset.UTC));
        try {
            worker.start();
            assertTrue(called.await(5, TimeUnit.SECONDS));
        } finally {
            worker.stop();
        }
        assertEquals(fixed, observed.get());
        assertTrue(scheduler.isTerminated());
    }

    @Test
    void stoppingPreservesAnInterruptedCallersStatus() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean callerInterrupted = new AtomicBoolean();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        SweepWorker worker = new SweepWorker("interrupted-stop", true, 3600, true, now -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
            }
        });
        Thread caller = new Thread(() -> {
            try {
                Thread.currentThread().interrupt();
                worker.stop();
                callerInterrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                callerFailure.set(failure);
            } finally {
                Thread.interrupted();
            }
        }, "interrupted-stop-caller");
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            caller.start();
            caller.join(5_000);
            assertFalse(caller.isAlive());
            assertNull(callerFailure.get());
            assertTrue(callerInterrupted.get());
        } finally {
            worker.stop();
            caller.join(5_000);
        }
        assertEquals(0, interrupted.getCount());
    }
}

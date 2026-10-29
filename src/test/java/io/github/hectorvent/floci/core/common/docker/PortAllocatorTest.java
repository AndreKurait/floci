package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

class PortAllocatorTest {

    @Test
    void allocatesPortInRange() {
        PortAllocator allocator = new PortAllocator();
        // Use a high ephemeral range unlikely to conflict with real services
        int port = allocator.allocate(19900, 19999);
        assertTrue(port >= 19900 && port <= 19999, "Port should be within range");
        allocator.release(port);
    }

    @Test
    void reservedPortIsSkippedByNextCaller() {
        PortAllocator allocator = new PortAllocator();
        int first = allocator.allocate(19900, 19999);
        int second = allocator.allocate(19900, 19999);
        assertNotEquals(first, second, "Two sequential allocations must return different ports");
        allocator.release(first);
        allocator.release(second);
    }

    @Test
    void releasedPortBecomesAvailableAgain() {
        PortAllocator allocator = new PortAllocator();
        int port = allocator.allocate(19900, 19999);
        allocator.release(port);
        // After release the same port can be re-allocated
        int reused = allocator.allocate(19900, 19999);
        assertEquals(port, reused, "Released port should be the first candidate again");
        allocator.release(reused);
    }

    @Test
    void concurrentAllocationsAreUnique() throws Exception {
        PortAllocator allocator = new PortAllocator();
        int threads = 20;
        int base = 19900;
        int max = base + threads - 1;
        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        try {
            runConcurrently(Executors.newFixedThreadPool(threads),
                    Collections.nCopies(threads, () -> ports.add(allocator.allocate(base, max))),
                    TimeUnit.SECONDS.toNanos(5), System::nanoTime);
            assertEquals(threads, ports.size(),
                    "Concurrent allocations must all return unique ports; got duplicates: " + ports);
        } finally {
            ports.forEach(allocator::release);
        }
    }

    @Test
    void throwsWhenRangeExhausted() {
        PortAllocator allocator = new PortAllocator();
        // Allocate the only port in a single-port range
        int port = allocator.allocate(19900, 19900);
        // Now the range is full — next call must throw
        assertThrows(RuntimeException.class, () -> allocator.allocate(19900, 19900));
        allocator.release(port);
    }

    @Test
    void allocateAndStartMovesPastPortsDockerReportsInUseAndReleasesThem() {
        PortAllocator allocator = new HostBlindPortAllocator();
        List<Integer> tried = new ArrayList<>();

        int started = allocator.allocateAndStart(19900, 19902, port -> {
            tried.add(port);
            if (port < 19902) {
                throw new RuntimeException("Bind for 127.0.0.1:" + port + " failed: port is already allocated");
            }
            return port;
        });

        assertEquals(19902, started);
        assertEquals(List.of(19900, 19901, 19902), tried);
        assertEquals(19900, allocator.allocate(19900, 19902), "refused ports must be released");
        assertEquals(19901, allocator.allocate(19900, 19902), "refused ports must be released");
        assertThrows(RuntimeException.class, () -> allocator.allocate(19902, 19902),
                "the port the start succeeded on must stay reserved");
    }

    @Test
    void allocateAndStartNamesTheRefusedPortsWhenTheRangeRunsOut() {
        PortAllocator allocator = new HostBlindPortAllocator();

        RuntimeException e = assertThrows(RuntimeException.class, () -> allocator.allocateAndStart(19900, 19901,
                port -> {
                    throw new RuntimeException(new IOException("listen tcp4 0.0.0.0:" + port
                            + ": bind: address already in use"));
                }));

        assertEquals("Docker reports host ports [19900, 19901] already in use and no other port in 19900-19901"
                + " is free", e.getMessage());
        assertEquals(19900, allocator.allocate(19900, 19901), "refused ports must be released");
        assertEquals(19901, allocator.allocate(19900, 19901), "refused ports must be released");
    }

    @Test
    void allocateAndStartReleasesThePortAndRethrowsAnyOtherFailure() {
        PortAllocator allocator = new HostBlindPortAllocator();
        RuntimeException failure = new RuntimeException("Cannot connect to the Docker daemon");
        List<Integer> tried = new ArrayList<>();

        RuntimeException e = assertThrows(RuntimeException.class, () -> allocator.allocateAndStart(19900, 19901,
                port -> {
                    tried.add(port);
                    throw failure;
                }));

        assertSame(failure, e);
        assertEquals(List.of(19900), tried);
        assertEquals(19900, allocator.allocate(19900, 19901), "the chosen port must be released");
    }

    private final Map<ExecutorService, ExecutorService> controlPools = new IdentityHashMap<>();

    private ExecutorService recordingPool() {
        ExecutorService actual = Executors.newFixedThreadPool(2);
        ExecutorService recording = mock(ExecutorService.class, delegatesTo(actual));
        controlPools.put(recording, actual);
        return recording;
    }

    private <T> void recordFutures(ExecutorService pool, List<CompletableFuture<T>> results) {
        AtomicInteger index = new AtomicInteger();
        doAnswer(call -> {
            Callable<T> worker = call.getArgument(0);
            controlPools.get(pool).submit(worker);
            return results.get(index.getAndIncrement());
        }).when(pool).submit(ArgumentMatchers.<Callable<T>>any());
    }

    @AfterEach
    void terminateControlWorkers() throws InterruptedException {
        for (ExecutorService pool : controlPools.values()) {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void producerFailureCancelsRunningSiblingAndPreservesCause() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        IllegalStateException failure = new IllegalStateException("producer");
        Callable<String> failing = () -> {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            throw failure;
        };
        Callable<String> blocked = () -> {
            entered.countDown();
            try {
                release.await();
                return "released";
            } catch (InterruptedException cancellation) {
                interrupted.countDown();
                throw cancellation;
            }
        };
        try {
            ExecutionException result = assertThrows(ExecutionException.class,
                    () -> runConcurrently(pool, List.of(failing, blocked),
                            TimeUnit.SECONDS.toNanos(5), System::nanoTime));
            assertSame(failure, result.getCause());
            assertEquals(0, interrupted.getCount(), "running sibling observes cancellation before return");
            assertTrue(pool.isTerminated());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutCancelsActuallyRunningWorkers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(2);
        AtomicInteger reads = new AtomicInteger();
        LongSupplier clock = () -> {
            if (reads.getAndIncrement() < 2) {
                return 0;
            }
            assertTrue(assertDoesNotThrow(() -> entered.await(5, TimeUnit.SECONDS)));
            return TimeUnit.SECONDS.toNanos(30);
        };
        Callable<String> blocked = () -> {
            entered.countDown();
            try {
                release.await();
                return "released";
            } catch (InterruptedException cancellation) {
                interrupted.countDown();
                throw cancellation;
            }
        };
        try {
            assertThrows(TimeoutException.class,
                    () -> runConcurrently(pool, List.of(blocked, blocked), TimeUnit.SECONDS.toNanos(30), clock));
            assertEquals(0, entered.getCount());
            assertEquals(0, interrupted.getCount());
            assertTrue(pool.isTerminated());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void laterFutureReceivesOnlyRemainingBudget() throws Exception {
        ExecutorService pool = recordingPool();
        AtomicLong clock = new AtomicLong();
        CompletableFuture<String> first = new CompletableFuture<>() {
            @Override
            public String get(long timeout, TimeUnit unit) {
                assertEquals(30, unit.toSeconds(timeout));
                clock.set(TimeUnit.SECONDS.toNanos(20));
                return "first";
            }
        };
        CompletableFuture<String> second = new CompletableFuture<>() {
            @Override
            public String get(long timeout, TimeUnit unit) {
                assertEquals(10, unit.toSeconds(timeout));
                return "second";
            }
        };
        recordFutures(pool, List.of(first, second));
        doReturn(true).when(pool).awaitTermination(5, TimeUnit.SECONDS);
        assertEquals(List.of("first", "second"), runConcurrently(pool,
                List.of(() -> "unused", () -> "unused"), TimeUnit.SECONDS.toNanos(30), clock::get));
    }

    @Test
    void cleanupFailureDoesNotReplaceProducerFailure() throws Exception {
        ExecutorService pool = recordingPool();
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("producer"));
        recordFutures(pool, List.of(failed));
        doReturn(false).when(pool).awaitTermination(5, TimeUnit.SECONDS);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> runConcurrently(pool, List.of(() -> "unused"), TimeUnit.SECONDS.toNanos(30), () -> 0));
        assertEquals("producer", failure.getCause().getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(AssertionError.class, failure.getSuppressed()[0]);
    }

    @Test
    void cleanupOnlyFailureIsReported() throws Exception {
        ExecutorService pool = recordingPool();
        recordFutures(pool, List.of(CompletableFuture.completedFuture("done")));
        doReturn(false).when(pool).awaitTermination(5, TimeUnit.SECONDS);
        assertThrows(AssertionError.class,
                () -> runConcurrently(pool, List.of(() -> "unused"), TimeUnit.SECONDS.toNanos(30), () -> 0));
    }

    @Test
    void interruptedCleanupPreservesPrimaryAndRestoresInterrupt() throws Exception {
        ExecutorService pool = recordingPool();
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("producer"));
        recordFutures(pool, List.of(failed));
        InterruptedException cleanup = new InterruptedException("cleanup");
        doThrow(cleanup).when(pool).awaitTermination(5, TimeUnit.SECONDS);
        try {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> runConcurrently(pool, List.of(() -> "unused"), TimeUnit.SECONDS.toNanos(30), () -> 0));
            assertEquals("producer", failure.getCause().getMessage());
            assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void successfulWorkersTerminateAndReturnResults() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            assertEquals(List.of("one", "two"), runConcurrently(pool,
                    List.of(() -> "one", () -> "two"), TimeUnit.SECONDS.toNanos(5), System::nanoTime));
            assertTrue(pool.isTerminated());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedReadinessDoesNotAdmitAllocations() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicInteger allocated = new AtomicInteger();
        try {
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> runConcurrently(pool, List.of(allocated::incrementAndGet, allocated::incrementAndGet),
                            TimeUnit.MILLISECONDS.toNanos(100), System::nanoTime));
            assertTrue(failure.getMessage().contains("Allocation workers must become ready"));
            assertEquals(0, allocated.get(), "An incomplete ready gate must not admit an allocation");
            assertTrue(pool.isTerminated());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static <T> List<T> runConcurrently(ExecutorService pool, List<Callable<T>> bodies,
                          long timeoutNanos, LongSupplier clock) throws Exception {
        long started = clock.getAsLong();
        CountDownLatch ready = new CountDownLatch(bodies.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        Throwable primary = null;
        try {
            for (Callable<T> body : bodies) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return body.call();
                }));
            }
            assertTrue(ready.await(Math.max(0, timeoutNanos - (clock.getAsLong() - started)),
                    TimeUnit.NANOSECONDS), "Allocation workers must become ready");
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                long remaining = Math.max(0, timeoutNanos - (clock.getAsLong() - started));
                results.add(future.get(remaining, TimeUnit.NANOSECONDS));
            }
            return results;
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            for (Future<T> future : futures) {
                future.cancel(true);
            }
            start.countDown();
            pool.shutdownNow();
            try {
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Concurrent tasks must terminate");
            } catch (Exception | Error cleanup) {
                if (cleanup instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (primary != null) {
                    if (cleanup != primary) {
                        primary.addSuppressed(cleanup);
                    }
                } else {
                    throw cleanup;
                }
            } finally {
                if (primary instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}

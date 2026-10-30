package io.github.hectorvent.floci.core.common.port;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

class PortAllocatorTest {

    // Pool bookkeeping is asserted against a host where every port is free, so these tests do
    // not depend on what is listening on the machine running them (9200 is OpenSearch's port).
    private static final IntPredicate ALWAYS_BINDABLE = p -> true;

    @Test
    void allocatesSequentiallyFromBase() {
        PortAllocator allocator = new PortAllocator(9200, 9299, w -> { }, ALWAYS_BINDABLE);
        assertEquals(9200, allocator.allocate());
        assertEquals(9201, allocator.allocate());
        assertEquals(9202, allocator.allocate());
    }

    @Test
    void concurrentAllocationsAreUnique() throws Exception {
        PortAllocator allocator = new PortAllocator(9200, 9299, w -> { }, ALWAYS_BINDABLE);
        int threads = 50;
        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        runConcurrently(Executors.newFixedThreadPool(threads),
                Collections.nCopies(threads, () -> ports.add(allocator.allocate())),
                TimeUnit.SECONDS.toNanos(5), System::nanoTime);
        assertEquals(threads, ports.size(), "All allocated ports must be unique");
    }

    @Test
    void allocateNeverReturnsPortAlreadyHandedOut() {
        PortAllocator allocator = new PortAllocator(9200, 9209, w -> { }, ALWAYS_BINDABLE);
        Set<Integer> handed = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            assertTrue(handed.add(allocator.allocate()));
        }
        assertThrows(IllegalStateException.class, allocator::allocate);
    }

    @Test
    void exhaustionMessageNamesThePoolAndTheWideningProperty() {
        PortAllocator allocator = new PortAllocator(9200, 9200, w -> { }, ALWAYS_BINDABLE);
        allocator.allocate();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, allocator::allocate);
        String message = thrown.getMessage();

        assertTrue(message.contains("Lambda Runtime API"),
                "message must name the pool that ran dry; got: " + message);
        assertTrue(message.contains("floci.services.lambda.runtime-api-max-port"),
                "message must name the property that widens the pool; got: " + message);
        assertTrue(message.contains("9200"),
                "message must still report the exhausted range; got: " + message);
    }

    @Test
    void warnsOnceWhenPoolCrossesNinetyPercent() {
        List<String> warnings = new ArrayList<>();
        PortAllocator allocator = new PortAllocator(9200, 9209, warnings::add, ALWAYS_BINDABLE);

        for (int i = 0; i < 10; i++) {
            allocator.allocate();
        }

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("90% allocated"));
        assertTrue(warnings.getFirst().contains("9/10 ports"));
        assertTrue(warnings.getFirst().contains("runtime-api-max-port"));
    }

    @Test
    void warningRearmsAfterPressureDropsBelowThreshold() {
        List<String> warnings = new ArrayList<>();
        PortAllocator allocator = new PortAllocator(9200, 9209, warnings::add, ALWAYS_BINDABLE);
        List<Integer> ports = new ArrayList<>();

        for (int i = 0; i < 9; i++) {
            ports.add(allocator.allocate());
        }
        allocator.release(ports.getLast());
        ports.removeLast();
        ports.add(allocator.allocate());

        assertEquals(2, warnings.size());
    }

    @Test
    void releasedPortBecomesAvailableAgain() {
        PortAllocator allocator = new PortAllocator(9200, 9201, w -> { }, ALWAYS_BINDABLE);
        int first = allocator.allocate();
        allocator.allocate();
        assertThrows(IllegalStateException.class, allocator::allocate);

        allocator.release(first);
        assertEquals(first, allocator.allocate());
    }

    @Test
    void skipsPortHeldByAnotherProcessAndReusesItOnceReleased() throws IOException {
        // Holding the base port from outside the allocator stands in for a second floci (or any
        // other process) in the same network namespace. Handing it out would leave the Runtime
        // API server retrying a bind it can never win while the rest of the range sits idle.
        int held;
        PortAllocator allocator;
        try (ServerSocket holder = new ServerSocket(0)) {
            held = holder.getLocalPort();
            assumeTrue(held <= 65535 - 50, "OS picked a port too close to 65535 for the range");
            allocator = new PortAllocator(held, held + 50);
            int first = allocator.allocate();
            assertNotEquals(held, first, "allocate() must skip a port another process holds");
            assertTrue(first > held && first <= held + 50);
        }

        assertEquals(held, allocator.allocate(),
                "a port skipped while held must be handed out once its holder lets go");
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

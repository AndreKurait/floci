package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.stepfunctions.model.Execution;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Concurrency-race coverage for {@code StartExecution}: concurrent same-name starts must launch exactly
 * once (the check-and-create is serialized). The execution store's {@code get} reads the entry and then
 * holds its caller in the check→create window until every other racing thread is blocked behind the
 * lock or has made its own read, so the {@code executeAsync} count deterministically distinguishes a
 * serialized implementation (exactly one launch) from an unserialized one (every thread has read "no
 * such execution" before any creates it: one launch per thread). The test fails if the lock is removed.
 *
 * <p>CI-only: {@code mock(AslExecutor.class)} forces AslExecutor to load, which needs Vert.x (absent in
 * the offline sandbox). The idempotency DECISION runs locally in
 * {@code StepFunctionsServiceStartExecutionIdempotencyTest}.
 */
class StepFunctionsServiceStartExecutionRaceTest {

    private static final String SM_ARN = "arn:aws:states:us-east-1:000000000000:stateMachine:test-sm";
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final int N = 16;
    private static final long HOLD_TIMEOUT_SECONDS = 10;

    /** The threads of the current race, each registered once the barrier has released it. */
    private final Set<Thread> racers = ConcurrentHashMap.newKeySet();
    private final CountDownLatch released = new CountDownLatch(N);
    /** The racing threads that have read the execution store, and the ones whose start has returned. */
    private final Set<Thread> haveRead = ConcurrentHashMap.newKeySet();
    private final Set<Thread> done = ConcurrentHashMap.newKeySet();

    /**
     * Holds the calling thread in the check→create window until none of the other racing threads
     * can still get to its own read: each has made it already, or has returned, or is blocked
     * entering a monitor, which on the way to that read is the start lock.
     */
    private void holdUntilNoOtherRacerCanRead() {
        haveRead.add(Thread.currentThread());
        try {
            assertTrue(released.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "every racing thread must be released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the racing threads", e);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HOLD_TIMEOUT_SECONDS);
        for (Thread racer : racers) {
            while (!haveRead.contains(racer) && !done.contains(racer)
                    && racer.getState() != Thread.State.BLOCKED) {
                assertTrue(System.nanoTime() - deadline < 0, racer.getName() + " neither read nor blocked");
                Thread.onSpinWait();
            }
        }
    }

    /**
     * In-memory StorageBackend whose get() answers what it read only after {@code afterRead} has run,
     * keeping its caller in the check→create window to expose an unserialized create.
     */
    private static final class DelayStore<V> implements StorageBackend<String, V> {
        private final Map<String, V> map = new ConcurrentHashMap<>();
        private final Runnable afterRead;

        DelayStore(Runnable afterRead) {
            this.afterRead = afterRead;
        }

        @Override
        public void put(String key, V value) {
            map.put(key, value);
        }

        @Override
        public Optional<V> get(String key) {
            Optional<V> read = Optional.ofNullable(map.get(key));
            afterRead.run();
            return read;
        }

        @Override
        public void delete(String key) {
            map.remove(key);
        }

        @Override
        public List<V> scan(Predicate<String> keyFilter) {
            return map.entrySet().stream().filter(e -> keyFilter.test(e.getKey()))
                    .map(Map.Entry::getValue).collect(Collectors.toList());
        }

        @Override
        public Set<String> keys() {
            return map.keySet();
        }

        @Override
        public void flush() {
        }

        @Override
        public void load() {
        }

        @Override
        public void clear() {
            map.clear();
        }
    }

    private record Fixture(StepFunctionsService service, AslExecutor executor) {
    }

    private Fixture build() {
        // StorageFactory.create() is declared to return AccountAwareStorageBackend, so the delayed
        // backends must be wrapped in one: stubbing a bare DelayStore is a WrongTypeOfReturnValue that
        // fails build() before any thread starts. The wrapper is what the service gets in production too.
        AccountAwareStorageBackend<StateMachine> smStore =
                new AccountAwareStorageBackend<>(new DelayStore<>(() -> { }), null, ACCOUNT);
        AccountAwareStorageBackend<Execution> execStore = new AccountAwareStorageBackend<>(
                new DelayStore<>(this::holdUntilNoOtherRacerCanRead), null, ACCOUNT); // hold the check->create window
        AccountAwareStorageBackend<Object> actStore =
                new AccountAwareStorageBackend<>(new DelayStore<>(() -> { }), null, ACCOUNT);

        StorageFactory factory = mock(StorageFactory.class);
        doReturn(smStore).doReturn(execStore).doReturn(actStore)
                .when(factory).create(anyString(), anyString(), any());

        // A real resolver: a mock serializes its callers on a monitor of its own, and the store
        // must be able to tell a thread blocked at the start lock from one that is not there yet.
        RegionResolver region = new RegionResolver(REGION, ACCOUNT);

        AslExecutor executor = mock(AslExecutor.class);
        StepFunctionsService service =
                new StepFunctionsService(factory, region, executor, new ObjectMapper(), mock(SfnMockLoader.class),
                mock(StepFunctionsEventPublisher.class));

        StateMachine sm = new StateMachine();
        sm.setStateMachineArn(SM_ARN);
        sm.setName("test-sm");
        sm.setRoleArn("arn:aws:iam::000000000000:role/r");
        sm.setType("STANDARD");
        sm.setDefinition("{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"End\":true}}}");
        smStore.put(SM_ARN, sm);
        return new Fixture(service, executor);
    }

    private void runConcurrently(IntConsumer body) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(N);
        ExecutorService pool = Executors.newFixedThreadPool(N);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < N; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await();
                        racers.add(Thread.currentThread());
                        released.countDown();
                        body.accept(idx);
                        done.add(Thread.currentThread());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameNameSameInputLaunchesExactlyOnce() throws Exception {
        Fixture fx = build();
        List<String> arns = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        runConcurrently(i -> {
            try {
                arns.add(fx.service().startExecution(SM_ARN, "race", "{\"a\":1}", REGION).getExecutionArn());
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        assertTrue(errors.isEmpty(), "no same-input start should error: " + errors);
        assertEquals(N, arns.size());
        assertEquals(1, arns.stream().distinct().count(), "all resolve to one execution");
        // The lock's teeth: without serialization every thread would create + launch.
        verify(fx.executor(), times(1)).executeAsync(any(), any(), any(), any(), any());
    }

    @Test
    void concurrentSameNameDistinctInputsLaunchesOnceRestConflict() throws Exception {
        Fixture fx = build();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        runConcurrently(i -> {
            try {
                fx.service().startExecution(SM_ARN, "race", "{\"a\":" + i + "}", REGION);
                successes.incrementAndGet();
            } catch (AwsException e) {
                if ("ExecutionAlreadyExists".equals(e.getErrorCode())) {
                    conflicts.incrementAndGet();
                } else {
                    unexpected.add(e);
                }
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });
        assertTrue(unexpected.isEmpty(), "only ExecutionAlreadyExists expected: " + unexpected);
        assertEquals(1, successes.get(), "exactly one distinct-input start wins");
        assertEquals(N - 1, conflicts.get(), "the rest conflict");
        verify(fx.executor(), times(1)).executeAsync(any(), any(), any(), any(), any());
    }
}

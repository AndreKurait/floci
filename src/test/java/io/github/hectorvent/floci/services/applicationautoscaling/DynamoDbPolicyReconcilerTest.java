package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Timeout(30)
class DynamoDbPolicyReconcilerTest {
    @ParameterizedTest
    @CsvSource({"true,true,true", "true,false,false", "false,true,false", "false,false,false"})
    void onlyBothEnabledServicesAllocateTheBackgroundWorker(boolean aas, boolean dynamo, boolean enabled) {
        DynamoDbPolicyReconciler worker = new DynamoDbPolicyReconciler(
                mock(ApplicationAutoScalingService.class), config(aas, dynamo));
        try {
            assertEquals(enabled, worker.active());
        } finally {
            worker.onStop(null);
        }
    }

    @Test
    void inheritedResetDrainsTheCurrentCallbackAndSuppressesTicksUntilResume() throws Exception {
        ApplicationAutoScalingService service = mock(ApplicationAutoScalingService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(service).reconcileDynamoDbPolicies(any(Instant.class));
        DynamoDbPolicyReconciler worker = new DynamoDbPolicyReconciler(service, config(false, false));
        AtomicReference<Thread> resetting = new AtomicReference<>();
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> running = executor.submit(() -> worker.tick(first));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                Future<?> reset = executor.submit(() -> {
                    resetting.set(Thread.currentThread());
                    worker.beforeReset();
                });
                Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    assertNotNull(resetting.get());
                    assertEquals(Thread.State.WAITING, resetting.get().getState());
                    assertFalse(reset.isDone());
                });
                release.countDown();
                running.get(3, TimeUnit.SECONDS);
                reset.get(3, TimeUnit.SECONDS);
                worker.tick(first.plusSeconds(10));
                assertEquals(1, calls.get());
                worker.clear();
                worker.afterReset();
                worker.tick(first.plusSeconds(20));
                assertEquals(2, calls.get());
                verify(service).reconcileDynamoDbPolicies(first);
                verify(service).reconcileDynamoDbPolicies(first.plusSeconds(20));
                verify(service, never()).reconcileDynamoDbPolicies(first.plusSeconds(10));
            } finally {
                release.countDown();
                worker.onStop(null);
            }
        }
    }

    @Test
    void inheritedStartupSchedulesTheRealWorkerAndShutdownInterruptsAndDrainsIt() throws Exception {
        ApplicationAutoScalingService service = mock(ApplicationAutoScalingService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch emergencyRelease = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<Thread> actualThread = new AtomicReference<>();
        AtomicReference<Instant> observed = new AtomicReference<>();
        doAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            actualThread.set(Thread.currentThread());
            observed.set(invocation.getArgument(0));
            entered.countDown();
            try {
                emergencyRelease.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(service).reconcileDynamoDbPolicies(any(Instant.class));
        DynamoDbPolicyReconciler worker = new DynamoDbPolicyReconciler(service, config(true, true));
        Instant started = Instant.now();
        try {
            worker.onStart(null);
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            assertEquals("dynamodb-policy-reconciliation", threadName.get());
            assertNotNull(observed.get());
            assertFalse(observed.get().isBefore(started));
            worker.onStop(null);
            assertEquals(0, interrupted.getCount());
            assertNotNull(actualThread.get());
            assertFalse(actualThread.get().isAlive(), "Shutdown must return after the actual worker thread terminates");
            verify(service, times(1)).reconcileDynamoDbPolicies(any(Instant.class));
        } finally {
            emergencyRelease.countDown();
            worker.onStop(null);
        }
    }

    private static EmulatorConfig config(boolean aas, boolean dynamo) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().applicationautoscaling().enabled()).thenReturn(aas);
        when(config.services().dynamodb().enabled()).thenReturn(dynamo);
        return config;
    }
}

package io.github.hectorvent.floci.services.sqs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqsResetAdmissionTest {
    @Test
    void recoveryFailureReleasesOperationBeforeAnotherReset() {
        IllegalStateException expected = new IllegalStateException("storage unavailable");
        SqsResetAdmission admission = new SqsResetAdmission(() -> {
            throw expected;
        });
        assertSame(expected, assertThrows(IllegalStateException.class, admission::enter));
        admission.pause();
        admission.awaitDrained();
        assertNull(admission.tryEnter());
        admission.resume();
        assertSame(expected, assertThrows(IllegalStateException.class, admission::tryEnter));
        admission.pause();
        admission.awaitDrained();
    }

    @Test
    void recoveryDoesNotHoldAdmissionMonitor() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        SqsResetAdmission admission = new SqsResetAdmission(() -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        });
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<?> first = executor.submit(() -> {
            try (SqsResetAdmission.Operation operation = admission.enter()) {
                assertNotNull(operation);
            }
        });
        Future<?> second = executor.submit(() -> {
            try (SqsResetAdmission.Operation operation = admission.enter()) {
                assertNotNull(operation);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "both operations acquire admission while recovery waits");
            admission.pause();
            assertNull(admission.tryEnter());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            admission.awaitDrained();
        } finally {
            first.cancel(true);
            second.cancel(true);
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void pausedAdmissionDoesNotStartRecovery() {
        AtomicInteger calls = new AtomicInteger();
        SqsResetAdmission admission = new SqsResetAdmission(calls::incrementAndGet);
        admission.pause();
        assertNull(admission.tryEnter());
        assertEquals(0, calls.get());
        admission.resume();
        try (SqsResetAdmission.Operation operation = admission.enter()) {
            assertNotNull(operation);
            assertEquals(1, calls.get());
        }
        admission.pause();
        admission.awaitDrained();
    }
}

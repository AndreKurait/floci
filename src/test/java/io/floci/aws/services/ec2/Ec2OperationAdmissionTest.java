package io.floci.aws.services.ec2;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Ec2OperationAdmissionTest {

    @Test
    void admittedOperationCanFinishNestedWorkAfterClosure() throws Exception {
        Ec2OperationAdmission admission = new Ec2OperationAdmission();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<String> result = executor.submit(() -> admission.call(() -> {
                entered.countDown();
                try {
                    assertTrue(proceed.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return admission.call(() -> "completed");
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> admission.pauseAndDrain(Duration.ZERO));
                assertThrows(IllegalStateException.class, () -> admission.call(() -> "new work"));
                assertThrows(IllegalStateException.class, admission::resume);
            } finally {
                proceed.countDown();
            }
            assertEquals("completed", result.get(10, TimeUnit.SECONDS));
        }
        admission.pauseAndDrain(Duration.ZERO);
        admission.resume();
        assertEquals("new work", admission.call(() -> "new work"));
    }

    @Test
    void failedNestedWorkReleasesOuterOwnership() throws Exception {
        Ec2OperationAdmission admission = new Ec2OperationAdmission();
        assertThrows(IllegalArgumentException.class, () -> admission.call(() -> admission.call(() -> {
            throw new IllegalArgumentException("failed");
        })));
        admission.pauseAndDrain(Duration.ZERO);
        assertThrows(IllegalStateException.class, () -> admission.call(() -> "closed"));
        admission.resume();
        assertEquals("open", admission.call(() -> "open"));
    }

    @Test
    void operationCannotCloseAdmissionWhileWaitingForItself() throws Exception {
        Ec2OperationAdmission admission = new Ec2OperationAdmission();
        admission.call(() -> {
            assertThrows(IllegalStateException.class, () -> admission.pauseAndDrain(Duration.ZERO));
            return null;
        });
        assertEquals("open", admission.call(() -> "open"));
        admission.pauseAndDrain(Duration.ZERO);
    }

    @Test
    void invalidTimeoutDoesNotCloseAdmission() {
        Ec2OperationAdmission admission = new Ec2OperationAdmission();
        assertThrows(IllegalArgumentException.class, () -> admission.pauseAndDrain(Duration.ofNanos(-1)));
        assertEquals("open", admission.call(() -> "open"));
    }
}

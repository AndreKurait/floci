package io.floci.aws.services.ec2;

import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyApplyBatchTest {
    @Test
    void pendingInterruptionFailsEvenWithoutOutstandingWriters() throws Exception {
        AtomicBoolean restoredInterrupt = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            try {
                PolicyApplyBatch.run(List.of());
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                restoredInterrupt.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.join(10000);
        assertFalse(caller.isAlive());
        assertTrue(restoredInterrupt.get());
        IllegalStateException error = assertInstanceOf(IllegalStateException.class, failure.get());
        assertInstanceOf(InterruptedException.class, error.getCause());
    }

    @Test
    void interruptionDrainsTheWriterBeforeReturningFailure() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean written = new AtomicBoolean();
        AtomicBoolean restoredInterrupt = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                PolicyApplyBatch.run(List.of(() -> {
                    writing.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        written.set(true);
                    } catch (InterruptedException error) {
                        throw new AssertionError("caller interruption must not cancel an in-flight write", error);
                    }
                }));
            } catch (Throwable error) {
                failure.set(error);
                restoredInterrupt.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            ThreadStates.awaitState(caller, Set.of(Thread.State.WAITING), Duration.ofSeconds(5),
                    "policy batch caller joining its worker");
            caller.interrupt();
            assertTrue(caller.isAlive());
            assertFalse(written.get());
        } finally {
            release.countDown();
        }
        caller.join(10000);
        assertFalse(caller.isAlive());
        assertTrue(written.get());
        assertTrue(restoredInterrupt.get());
        assertInstanceOf(IllegalStateException.class, failure.get());
    }
}

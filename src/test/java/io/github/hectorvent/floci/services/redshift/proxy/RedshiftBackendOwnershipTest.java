package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RedshiftBackendOwnershipTest {

    @Test
    @Timeout(6)
    void retainsItsPlaceWhileAnIdlePumpFinishesReading() throws Exception {
        DelayedWaiterLock lock = new DelayedWaiterLock();
        RedshiftInterceptingBridge bridge = bridgeWithLock(lock);
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<Throwable> pumpFailure = new AtomicReference<>();
        Thread pump = Thread.ofVirtual().start(() -> {
            try {
                while (!stopped.get()) {
                    lock.lockInterruptibly();
                    try {
                        lock.readStarted.release();
                        lock.endRead.tryAcquire(350, TimeUnit.MILLISECONDS);
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                pumpFailure.set(failure);
            }
        });
        try {
            assertTrue(lock.readStarted.tryAcquire(1, TimeUnit.SECONDS));
            assertTrue(bridge.runWithBackendOwned(() -> true),
                    "an idle pump must yield the backend before the existing ownership deadline");
            assertNull(pumpFailure.get());
        } finally {
            stopped.set(true);
            pump.interrupt();
            pump.join(1000);
            assertFalse(pump.isAlive());
        }
    }

    @Test
    @Timeout(5)
    void doesNotRunAnExchangeWhenTheBackendRemainsOwned() throws Exception {
        ReentrantLock lock = new ReentrantLock(true);
        RedshiftInterceptingBridge bridge = bridgeWithLock(lock);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread pump = Thread.ofVirtual().start(() -> {
            lock.lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        try {
            assertTrue(held.await(1, TimeUnit.SECONDS));
            AtomicBoolean exchanged = new AtomicBoolean();
            assertFalse(bridge.runWithBackendOwned(() -> {
                exchanged.set(true);
                return true;
            }));
            assertFalse(exchanged.get());
        } finally {
            release.countDown();
            pump.join(1000);
            assertFalse(pump.isAlive());
        }
    }

    private static RedshiftInterceptingBridge bridgeWithLock(ReentrantLock lock) throws Exception {
        RedshiftInterceptingBridge bridge = new RedshiftInterceptingBridge(
                mock(Socket.class), mock(Socket.class), mock(S3Service.class), mock(IamService.class));
        Field field = RedshiftInterceptingBridge.class.getDeclaredField("backendLock");
        field.setAccessible(true);
        field.set(bridge, lock);
        return bridge;
    }

    private static final class DelayedWaiterLock extends ReentrantLock {
        private final Semaphore readStarted = new Semaphore(0);
        private final Semaphore endRead = new Semaphore(0);

        private DelayedWaiterLock() {
            super(true);
        }

        @Override
        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            boolean acquired = super.tryLock(timeout, unit);
            if (!acquired) {
                // Model a timed-out waiter resuming after the pump starts its next idle read.
                endRead.release();
                if (!readStarted.tryAcquire(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("pump did not resume");
                }
            }
            return acquired;
        }
    }
}

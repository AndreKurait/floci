package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripedLocksTest {

    @Test
    void equalKeysShareAMonitor() {
        StripedLocks locks = new StripedLocks(256);

        assertSame(locks.lockFor("runtime"), locks.lockFor(new String("runtime")));
    }

    @Test
    void aSingleStripeReusesItsMonitorForEveryKey() {
        StripedLocks locks = new StripedLocks(1);

        assertSame(locks.lockFor("first"), locks.lockFor("second"));
    }

    @Test
    void distinctStripesHaveDifferentMonitors() {
        StripedLocks locks = new StripedLocks(4);

        assertNotSame(locks.lockFor(0), locks.lockFor(1));
    }

    @Test
    void negativeHashesSelectANonnegativeStripe() {
        StripedLocks locks = new StripedLocks(3);

        assertSame(locks.lockFor(Integer.MIN_VALUE), locks.lockFor(1));
        assertSame(locks.lockFor(-1), locks.lockFor(2));
    }

    @Test
    void requiresAPositiveStripeCount() {
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(0));
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(-1));
    }

    @Test
    void orderedIndicesAgreeForReversedKeysAndCollisions() {
        StripedLocks locks = new StripedLocks(4);

        assertEquals(locks.stripeOf(1), locks.stripeOf(5));
        assertSame(locks.lockFor(5), locks.lockAt(locks.stripeOf(1)));
        assertSame(locks.lockAt(Math.min(locks.stripeOf(1), locks.stripeOf(2))),
                locks.lockAt(Math.min(locks.stripeOf(2), locks.stripeOf(5))));
        assertSame(locks.lockAt(Math.max(locks.stripeOf(1), locks.stripeOf(2))),
                locks.lockAt(Math.max(locks.stripeOf(2), locks.stripeOf(5))));
    }

    @Test
    void typedLocksRetainFairnessAndIndependentStripeState() {
        StripedLocks.Typed<ReentrantReadWriteLock> locks =
                new StripedLocks.Typed<>(4, () -> new ReentrantReadWriteLock(true));
        ReentrantReadWriteLock first = locks.lockFor(0);
        ReentrantReadWriteLock second = locks.lockFor(1);

        assertTrue(first.isFair());
        assertTrue(second.isFair());
        first.writeLock().lock();
        try {
            assertTrue(locks.lockFor(4).isWriteLockedByCurrentThread());
            assertFalse(second.isWriteLocked());
        } finally {
            first.writeLock().unlock();
        }
    }

    @Test
    void explicitLocksRetainReentrancy() {
        StripedLocks.Typed<ReentrantLock> locks = new StripedLocks.Typed<>(4, ReentrantLock::new);
        ReentrantLock first = locks.lockFor(0);
        first.lock();
        locks.lockFor(4).lock();
        try {
            assertEquals(2, first.getHoldCount());
            assertFalse(locks.lockFor(1).isLocked());
        } finally {
            first.unlock();
            first.unlock();
        }
    }
}

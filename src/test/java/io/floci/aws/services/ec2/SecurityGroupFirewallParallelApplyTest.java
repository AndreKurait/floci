package io.floci.aws.services.ec2;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupFirewallParallelApplyTest extends SecurityGroupFirewallPeerFixture {
    @Test
    void stablePoliciesOverlapWithAtMostFourWorkers() throws Exception {
        SecurityGroupFirewallManager manager = ready(8);
        CountDownLatch four = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        beforeInstall = (helper, script) -> {
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            four.countDown();
            awaitLatch(release);
            active.decrementAndGet();
        };
        CompletableFuture<Void> reconcile = CompletableFuture.runAsync(manager::reconcileAll);
        try {
            assertTrue(four.await(5, TimeUnit.SECONDS), "four independent policy installs must overlap");
            assertEquals(4, active.get());
            assertFalse(reconcile.isDone());
        } finally {
            release.countDown();
        }
        reconcile.get(10, TimeUnit.SECONDS);
        assertEquals(4, maximum.get());
        assertEquals(8, installs.size());
        assertFinal(8);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void unknownIdentityInstallsBeforeAnyPeerIsUpdated() throws Exception {
        SecurityGroupFirewallManager manager = ready(4);
        CountDownLatch own = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger peerInstalls = new AtomicInteger();
        beforeInstall = (helper, script) -> {
            if (helper.equals(helper(4))) {
                own.countDown();
                awaitLatch(release);
            } else {
                peerInstalls.incrementAndGet();
            }
        };
        CompletableFuture<Void> registration = CompletableFuture.runAsync(
                () -> manager.register(endpoint(4), helper(4), Map.of()));
        try {
            assertTrue(own.await(5, TimeUnit.SECONDS));
            assertEquals(0, peerInstalls.get());
            assertFalse(registration.isDone());
        } finally {
            release.countDown();
        }
        registration.get(10, TimeUnit.SECONDS);
        assertFinal(5);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void failedBatchDrainsEveryWriterBeforeQuarantine() throws Exception {
        SecurityGroupFirewallManager manager = ready(4);
        CountDownLatch writer = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicInteger quarantines = new AtomicInteger();
        beforeInstall = (helper, script) -> {
            if (!script.contains("add rule")) {
                assertEquals(0, release.getCount(), "quarantine must follow the last policy writer");
                quarantines.incrementAndGet();
            } else if (helper.equals(helper(0))) {
                writer.countDown();
                awaitLatch(release);
            } else if (helper.equals(helper(1)) && first.getAndSet(false)) {
                awaitLatch(writer);
                failed.countDown();
                throw new IllegalStateException("injected parallel install failure");
            }
        };
        CompletableFuture<Void> reconcile = CompletableFuture.runAsync(manager::reconcileAll);
        try {
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertFalse(reconcile.isDone(), "failure must wait for the outstanding writer");
            assertEquals(0, quarantines.get());
        } finally {
            release.countDown();
        }
        assertThrows(ExecutionException.class, () -> reconcile.get(10, TimeUnit.SECONDS));
        assertEquals(4, quarantines.get());
        installed.values().forEach(script -> assertFalse(script.contains("add rule")));
    }

    @Test
    void quarantinedIdentitiesAllWithdrawSeriallyBeforeParallelRecovery() {
        SecurityGroupFirewallManager manager = ready(4);
        failNextInstall.add(helper(0));
        assertThrows(IllegalStateException.class, manager::reconcileAll);
        installed.values().forEach(script -> assertFalse(script.contains("add rule")));
        List<Thread> firstWriter = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen = ConcurrentHashMap.newKeySet();
        beforeInstall = (helper, script) -> {
            if (seen.add(helper)) {
                firstWriter.add(Thread.currentThread());
            }
        };
        Thread caller = Thread.currentThread();
        manager.reconcileAll();
        assertEquals(4, firstWriter.size());
        firstWriter.forEach(writer -> assertEquals(caller, writer,
                "an identity unknown at the round boundary must not be promoted to the parallel phase"));
        assertFinal(4);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private SecurityGroupFirewallManager ready(int count) {
        SecurityGroupFirewallManager manager = manager();
        for (int index = 0; index < count; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
        }
        assertFinal(count);
        installs.clear();
        return manager;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "test latch timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}

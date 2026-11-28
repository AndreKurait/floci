package io.floci.aws.services.ec2;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

class SecurityGroupFirewallInspectionBatchTest extends SecurityGroupFirewallPeerFixture {

    @Test
    void helperReadsOverlapAndUnrelatedRefreshStillRetiresStoppedPeers() {
        SecurityGroupFirewallManager manager = readyPeers(6);
        CountDownLatch overlapping = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();
        beforeInspect = helper -> {
            reads.computeIfAbsent(helper, ignored -> new AtomicInteger()).incrementAndGet();
            int concurrent = active.incrementAndGet();
            peak.accumulateAndGet(concurrent, Math::max);
            try {
                overlapping.countDown();
                await(overlapping);
            } finally {
                active.decrementAndGet();
            }
        };
        manager.refreshPolicies("222222222222", "us-east-1", Map.of(), Map.of());

        assertTrue(peak.get() >= 2, "one blocked helper read must not serialize its peer");
        assertTrue(peak.get() <= 4, "each helper read batch stays within four workers");
        for (int index = 0; index < 6; index++) {
            assertEquals(1, reads.get(helper(index)).get(), "each original helper is observed once");
        }
        beforeInspect = helper -> {};
        stoppedHelpers.add(helper(5));
        manager.refreshPolicies("222222222222", "us-east-1", Map.of(), Map.of());
        assertFinal(5);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void failedReadDrainsItsBlockedPeerBeforeReturningWithoutRetiringAnything() throws Exception {
        SecurityGroupFirewallManager manager = readyPeers(2);
        Map<String, String> original = new LinkedHashMap<>(installed);
        stoppedHelpers.add(helper(0));
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch failureReached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        beforeInspect = helper -> {
            if (helper.equals(helper(0))) {
                blocked.countDown();
                await(release);
            } else if (helper.equals(helper(1))) {
                await(blocked);
                failureReached.countDown();
                throw new IllegalStateException("Original helper inspection failed");
            }
        };
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> refresh = executor.submit(() ->
                    manager.refreshPolicies("222222222222", "us-east-1", Map.of(), Map.of()));
            try {
                await(failureReached);
                assertFalse(refresh.isDone(), "failed inspection must drain the original blocked peer");
                assertEquals(original, installed, "unconfirmed reads cannot retire or rewrite a peer");
            } finally {
                release.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> refresh.get(5, TimeUnit.SECONDS));
            assertEquals("Original helper inspection failed", failure.getCause().getMessage());
        } finally {
            beforeInspect = helper -> {};
        }
        assertEquals(original, installed);
        assertFinal(2);
        stoppedHelpers.remove(helper(0));
        manager.reconcileAll();
        assertFinal(2);
    }

    @Test
    void sharedPhysicalHelperIsInspectedOnceAndRetiresEveryOwnedOrigin() {
        SecurityGroupFirewallManager manager = spy(manager());
        doNothing().when(manager).admit(anyString(), anyBoolean());
        SecurityGroupNftCompiler.Endpoint first = endpoint(0);
        SecurityGroupNftCompiler.Endpoint second = endpoint(1);
        manager.register(first, helper(0), Map.of());
        manager.register(second, helper(0), Map.of());
        assertTrue(manager.vpcForClient(first.transportAddress()).isPresent());
        assertTrue(manager.vpcForClient(second.transportAddress()).isPresent());
        AtomicInteger reads = new AtomicInteger();
        beforeInspect = helper -> {
            assertEquals(helper(0), helper);
            reads.incrementAndGet();
        };
        stoppedHelpers.add(helper(0));

        manager.refreshPolicies("222222222222", "us-east-1", Map.of(), Map.of());

        assertEquals(1, reads.get(), "both endpoint references use the same physical observation");
        assertTrue(manager.vpcForClient(first.transportAddress()).isEmpty());
        assertTrue(manager.vpcForClient(second.transportAddress()).isEmpty());
        manager.refreshPolicies("222222222222", "us-east-1", Map.of(), Map.of());
        assertEquals(1, reads.get(), "neither retired endpoint remains to trigger another inspection");
    }

    private SecurityGroupFirewallManager readyPeers(int count) {
        SecurityGroupFirewallManager manager = manager();
        for (int index = 0; index < count; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
        }
        assertFinal(count);
        return manager;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "original helper operation did not reach its barrier");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted awaiting original helper operation", failure);
        }
    }
}

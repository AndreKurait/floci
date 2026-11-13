package io.github.hectorvent.floci.services.codeartifact;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit coverage for the bounded-fan-out helper {@code PypiserverSidecarClient} and {@code
 * ReposiliteSidecarClient} both use to parallelize their per-version {@code listPackageVersions}
 * requests. These tests use plain in-process work, not real HTTP, to pin down the properties that
 * matter: results come back in input order regardless of completion order, more than {@code
 * MAX_CONCURRENT_REQUESTS} items never run at once against one sidecar key (even across two
 * simultaneous calls sharing that key), a failing item fails the whole call with its own real
 * exception rather than a wrapped or swallowed one, and the rest of the work is cancelled rather
 * than left running after a failure.
 */
class SidecarConcurrencyTest {

    @Test
    void mapBoundedPreservesInputOrderRegardlessOfCompletionOrder() {
        List<Integer> items = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        // Items 3 to 10 finish in strictly reverse order: each waits for the item after it, and the
        // last one waits for nobody. Eight of them fit under the cap together, so none is left
        // waiting on an item that cannot start.
        Map<Integer, CountDownLatch> finished = new ConcurrentHashMap<>();
        for (int item : items) {
            finished.put(item, new CountDownLatch(1));
        }
        List<Integer> completionOrder = new CopyOnWriteArrayList<>();
        List<String> result = SidecarConcurrency.mapBounded(new Object(), items, i -> {
            if (i >= 3 && i < 10) {
                awaitOpen(finished.get(i + 1), "item " + (i + 1) + " never finished");
            }
            if (i >= 3) {
                completionOrder.add(i);
            }
            finished.get(i).countDown();
            return "v" + i;
        });
        assertThat(result, equalTo(List.of("v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9", "v10")));
        assertThat(completionOrder, equalTo(List.of(10, 9, 8, 7, 6, 5, 4, 3)));
    }

    @Test
    void mapBoundedNeverRunsMoreThanTheConcurrencyCapAtOnce() throws Exception {
        Object key = new Object();
        int itemCount = 32;
        List<Integer> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            items.add(i);
        }
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        Semaphore hold = new Semaphore(0);
        CountDownLatch allDone = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            SidecarConcurrency.mapBounded(key, items, i -> {
                int current = concurrent.incrementAndGet();
                maxObserved.getAndUpdate(previous -> Math.max(previous, current));
                hold.acquireUninterruptibly();
                concurrent.decrementAndGet();
                return i;
            });
            allDone.countDown();
        });
        worker.start();

        // Every item is accounted for: eight are running and held, the other 24 are parked on the cap.
        awaitRunningAndQueued(key, concurrent, 8, itemCount - 8);
        assertEquals(8, maxObserved.get());

        hold.release(itemCount);
        assertTrue(allDone.await(10, TimeUnit.SECONDS), "mapBounded did not finish in time");
        assertEquals(8, maxObserved.get(), "the cap held while the queued items ran");
    }

    @Test
    void mapBoundedCapsConcurrencyAcrossSimultaneousCallsSharingTheSameSidecarKey() throws Exception {
        Object sharedKey = new Object();
        int itemsPerCall = 6;
        List<Integer> items = new ArrayList<>(itemsPerCall);
        for (int i = 0; i < itemsPerCall; i++) {
            items.add(i);
        }
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        Semaphore hold = new Semaphore(0);
        Runnable call = () -> SidecarConcurrency.mapBounded(sharedKey, items, i -> {
            int current = concurrent.incrementAndGet();
            maxObserved.getAndUpdate(previous -> Math.max(previous, current));
            hold.acquireUninterruptibly();
            concurrent.decrementAndGet();
            return i;
        });
        Thread callA = new Thread(call);
        Thread callB = new Thread(call);
        callA.start();
        callB.start();

        // Twelve items across the two calls: eight running, which is more than one call has, and
        // the other four parked on the shared cap.
        awaitRunningAndQueued(sharedKey, concurrent, 8, 2 * itemsPerCall - 8);
        assertThat("expected the two calls' work to actually overlap under the shared cap", maxObserved.get(),
                greaterThan(itemsPerCall));

        hold.release(2 * itemsPerCall);
        callA.join(10_000);
        callB.join(10_000);
        assertFalse(callA.isAlive() || callB.isAlive(), "the calls did not finish in time");
        assertEquals(8, maxObserved.get(),
                "two simultaneous calls sharing one sidecar key must not exceed the shared cap in aggregate");
    }

    /** Waits until {@code running} items are inside {@code perItem} and {@code queued} more wait for a permit. */
    private static void awaitRunningAndQueued(Object key, AtomicInteger concurrent, int running, int queued)
            throws Exception {
        await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(1))
                .until(() -> concurrent.get() == running && permitsOf(key).getQueueLength() == queued);
        assertEquals(0, permitsOf(key).availablePermits());
    }

    private static Semaphore permitsOf(Object key) throws Exception {
        Field permits = SidecarConcurrency.class.getDeclaredField("SIDECAR_PERMITS");
        permits.setAccessible(true);
        Object use = ((Map<?, ?>) permits.get(null)).get(key);
        if (use == null) {
            return new Semaphore(0);
        }
        Field semaphore = use.getClass().getDeclaredField("semaphore");
        semaphore.setAccessible(true);
        return (Semaphore) semaphore.get(use);
    }

    private static void awaitOpen(CountDownLatch latch, String otherwise) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(otherwise);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(otherwise, e);
        }
    }

    @Test
    void mapBoundedPropagatesARuntimeExceptionFromAFailingItemUnwrapped() {
        List<Integer> items = List.of(1, 2, 3);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> SidecarConcurrency.mapBounded(
                new Object(), items, i -> {
                    if (i == 2) {
                        throw new IllegalStateException("boom on " + i);
                    }
                    return i;
                }));
        assertThat(thrown.getMessage(), equalTo("boom on 2"));
    }

    @Test
    void mapBoundedCancelsTheOtherItemAfterAnEarlyFailure() throws InterruptedException {
        CountDownLatch otherItemStarted = new CountDownLatch(1);
        CountDownLatch otherItemFinished = new CountDownLatch(1);
        CountDownLatch neverOpens = new CountDownLatch(1);
        AtomicBoolean otherItemInterrupted = new AtomicBoolean(false);
        assertThrows(IllegalStateException.class, () -> SidecarConcurrency.mapBounded(new Object(), List.of(1, 2),
                i -> {
                    if (i == 1) {
                        // Only fail once item 2 is actually running and blocked, so this exercises real
                        // cancellation of in-flight work instead of racing against whether item 2's task
                        // had even been scheduled yet.
                        boolean started;
                        try {
                            started = otherItemStarted.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            started = false;
                        }
                        if (!started) {
                            throw new IllegalStateException("item 2 never started");
                        }
                        throw new IllegalStateException("boom");
                    }
                    otherItemStarted.countDown();
                    try {
                        neverOpens.await();
                    } catch (InterruptedException e) {
                        otherItemInterrupted.set(true);
                        Thread.currentThread().interrupt();
                    } finally {
                        otherItemFinished.countDown();
                    }
                    return i;
                }));
        assertTrue(otherItemFinished.await(5, TimeUnit.SECONDS), "expected mapBounded to cancel item 2 once item "
                + "1 had already failed, instead of leaving it blocked indefinitely");
        assertTrue(otherItemInterrupted.get(), "expected item 2 to be interrupted rather than complete normally "
                + "after item 1 had already failed");
    }

    @Test
    void releaseDropsTheCachedPermitForASidecarKey() throws Exception {
        Object key = new Object();
        SidecarConcurrency.mapBounded(key, List.of(1, 2), i -> i);
        assertTrue(sidecarPermitsContainsKey(key), "expected mapBounded to have cached a permit for this key");

        SidecarConcurrency.release(key);

        assertFalse(sidecarPermitsContainsKey(key), "expected release to drop the cached permit for this key, "
                + "otherwise it leaks one Semaphore per repository ever deleted for the life of the process");
    }

    @Test
    void releaseIsANoOpForAKeyThatWasNeverUsed() {
        assertDoesNotThrow(() -> SidecarConcurrency.release(new Object()));
    }

    @Test
    void releaseDuringAnActiveUseDefersRemovalUntilTheMatchingEndUse() throws Exception {
        Object key = new Object();
        SidecarConcurrency.beginUse(key);
        assertTrue(sidecarPermitsContainsKey(key), "expected beginUse to register this key");

        SidecarConcurrency.release(key);
        assertTrue(sidecarPermitsContainsKey(key), "expected release to defer removal while a call is still "
                + "using this key; otherwise a listPackageVersions call that fetched its version listing before "
                + "the repository was deleted, but had not yet reached mapBounded, would cache a fresh permit "
                + "for a container that is already gone, orphaned forever since nothing calls release twice");

        SidecarConcurrency.endUse(key);
        assertFalse(sidecarPermitsContainsKey(key), "expected the matching endUse to drop the entry once "
                + "release had already marked it for removal");
    }

    @Test
    void releaseWithNoActiveUseRemovesImmediately() throws Exception {
        Object key = new Object();
        SidecarConcurrency.release(key);
        assertFalse(sidecarPermitsContainsKey(key), "release on a key nobody is using must not leave anything "
                + "behind");
    }

    @Test
    void mapBoundedReusesThePermitABeginUseAlreadyCreated() throws Exception {
        Object key = new Object();
        SidecarConcurrency.beginUse(key);
        try {
            List<Integer> result = SidecarConcurrency.mapBounded(key, List.of(1, 2, 3), i -> i);
            assertThat(result, equalTo(List.of(1, 2, 3)));
        } finally {
            SidecarConcurrency.endUse(key);
        }
        assertTrue(sidecarPermitsContainsKey(key), "expected endUse, with no release pending, to leave the "
                + "cache entry alone instead of removing it");
    }

    private static boolean sidecarPermitsContainsKey(Object key) throws Exception {
        Field field = SidecarConcurrency.class.getDeclaredField("SIDECAR_PERMITS");
        field.setAccessible(true);
        Map<?, ?> permits = (Map<?, ?>) field.get(null);
        return permits.containsKey(key);
    }

    @Test
    void mapBoundedCallsPerItemDirectlyWhenThereIsAtMostOneItem() {
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        List<Integer> result = SidecarConcurrency.mapBounded(new Object(), List.of(42), i -> {
            threadNames.add(Thread.currentThread().getName());
            return i;
        });
        assertThat(result, equalTo(List.of(42)));
        assertThat(threadNames, equalTo(Set.of(Thread.currentThread().getName())));
    }
}

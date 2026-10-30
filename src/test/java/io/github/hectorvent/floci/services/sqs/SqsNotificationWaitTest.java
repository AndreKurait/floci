package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqsNotificationWaitTest {
    private static final String REGION = "us-east-1";

    @Test
    void aReceiveResolvedBeforeResetCannotConsumeFromTheRecreatedQueue() throws Exception {
        ParkingStore<Queue> storage = new ParkingStore<>();
        SqsService service = new SqsService(storage, 30, 1048576, "http://localhost:4566", Clock.systemUTC());
        Queue queue = service.createQueue("reset-incarnation", null, REGION);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            storage.parkNextRead();
            Future<List<Message>> receive = executor.submit(
                    () -> service.receiveMessage(queue.getQueueUrl(), 1, 30, 20, REGION));
            assertTrue(storage.awaitParked());
            storage.clear();
            service.clear();
            Queue replacement = service.createQueue("reset-incarnation", null, REGION);
            service.sendMessage(replacement.getQueueUrl(), "new incarnation", null, null, null, REGION);
            storage.release();
            assertTrue(receive.get(2, TimeUnit.SECONDS).isEmpty(),
                    "A receive holding pre-reset metadata must not claim a post-reset delivery");
            List<Message> messages = service.receiveMessage(replacement.getQueueUrl(), 1, 30, 0, REGION);
            assertEquals(1, messages.size());
            assertEquals("new incarnation", messages.getFirst().getBody());
        } finally {
            storage.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            service.stop();
        }
    }

    @Test
    void resetDetachesOldQueuesBeforeClosingAndPreservesNewIncarnations() throws Exception {
        InMemoryStorage<String, Queue> storage = new InMemoryStorage<>();
        SqsService service = new SqsService(storage, 30, 1048576, "http://localhost:4566", Clock.systemUTC());
        Queue queue = service.createQueue("reset-close", null, REGION);
        Field field = SqsService.class.getDeclaredField("messagesByQueue");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, GuardedMessageQueue> queues =
                (ConcurrentHashMap<String, GuardedMessageQueue>) field.get(service);
        String key = queues.keySet().iterator().next();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        GuardedMessageQueue old = new GuardedMessageQueue(null, null) {
            @Override
            void close() {
                closing.countDown();
                try {
                    assertTrue(releaseClose.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                super.close();
            }
        };
        queues.put(key, old).close();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            storage.clear();
            Future<?> reset = executor.submit(service::clear);
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            assertTrue(queues.isEmpty(), "Reset must detach its old incarnations before closing them");
            Queue replacement = service.createQueue("reset-close", null, REGION);
            assertEquals(queue.getQueueUrl(), replacement.getQueueUrl());
            service.sendMessage(replacement.getQueueUrl(), "post-reset delivery", null, REGION);
            releaseClose.countDown();
            reset.get(2, TimeUnit.SECONDS);
            assertTrue(old.isClosed());
            assertFalse(queues.get(key).isClosed());
            assertEquals("post-reset delivery",
                    service.receiveMessage(replacement.getQueueUrl(), 1, 30, 0, REGION).getFirst().getBody());
        } finally {
            releaseClose.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            service.stop();
        }
    }

    @Test
    void anIdleLongPollDoesNotReadStorageOncePerSecond() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        InMemoryStorage<String, Queue> storage = new InMemoryStorage<>() {
            @Override
            public Optional<Queue> get(String key) {
                reads.incrementAndGet();
                return super.get(key);
            }
        };
        SqsService service = new SqsService(storage, 30, 1048576, "http://localhost:4566", Clock.systemUTC());
        Queue queue = service.createQueue("idle-signal", null, REGION);
        Field field = SqsService.class.getDeclaredField("messagesByQueue");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, GuardedMessageQueue> queues =
                (ConcurrentHashMap<String, GuardedMessageQueue>) field.get(service);
        String key = queues.keySet().iterator().next();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch releaseWait = new CountDownLatch(1);
        AtomicInteger waits = new AtomicInteger();
        GuardedMessageQueue observedQueue = new GuardedMessageQueue(null, null) {
            @Override
            void awaitChange(long observed, long timeoutNanos) throws InterruptedException {
                waits.incrementAndGet();
                waiting.countDown();
                assertTrue(releaseWait.await(5, TimeUnit.SECONDS));
                assertEquals(2, reads.get(), "Empty poll rescanned before its deadline");
                super.awaitChange(observed, timeoutNanos);
            }
        };
        queues.put(key, observedQueue).close();
        reads.set(0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<List<Message>> receive = executor.submit(
                    () -> service.receiveMessage(queue.getQueueUrl(), 1, 30, 2, REGION));
            assertTrue(waiting.await(5, TimeUnit.SECONDS), "Receive must reach its notification wait");
            assertEquals(2, reads.get(), "Initial lookup and first claim precede waiting");
            releaseWait.countDown();
            assertTrue(receive.get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals(1, waits.get(), "An idle receive waits once until its deadline");
            assertEquals(3, reads.get(), "Only initial lookup, first claim and deadline claim are needed");
        } finally {
            releaseWait.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            service.stop();
        }
    }

    @Test
    void deliveryBetweenEmptyClaimAndWaitDoesNotLoseItsNotification() throws Exception {
        GuardedMessageQueue queue = new GuardedMessageQueue(null, null);
        long observed = queue.changeVersion();
        assertTrue(queue.claimVisibleMessages(1, 30, false, -1, null).claimed().isEmpty());
        queue.addMessage(new Message("arrived in the scan-to-wait gap"));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                queue.awaitChange(observed, TimeUnit.SECONDS.toNanos(20));
                return null;
            }).get(1, TimeUnit.SECONDS);
        }
        assertEquals(1, queue.claimVisibleMessages(1, 30, false, -1, null).claimed().size());
    }

    @Test
    void redriveDeliverySignalsAnEmptyDestination() throws Exception {
        GuardedMessageQueue destination = new GuardedMessageQueue(null, null);
        long observed = destination.changeVersion();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> waiter = executor.submit(() -> {
                destination.awaitChange(observed, TimeUnit.SECONDS.toNanos(20));
                return null;
            });
            destination.addAll(List.of(new Message("redriven")));
            waiter.get(1, TimeUnit.SECONDS);
        }
        assertEquals(1, destination.claimVisibleMessages(1, 30, false, -1, null).claimed().size());
    }

    @Test
    void closingAnIncarnationReleasesWaitersAndPreventsFurtherClaims() throws Exception {
        GuardedMessageQueue queue = new GuardedMessageQueue(null, null);
        long observed = queue.changeVersion();
        queue.close();
        queue.addMessage(new Message("late producer still holds the old incarnation"));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                queue.awaitChange(observed, TimeUnit.SECONDS.toNanos(20));
                return null;
            }).get(1, TimeUnit.SECONDS);
        }
        assertTrue(queue.isClosed());
        assertTrue(queue.claimVisibleMessages(1, 30, false, -1, null).claimed().isEmpty());
    }

    @Test
    void anUnresolvedRedriveClaimDoesNotSignalAnImmediateRetry() {
        GuardedMessageQueue queue = new GuardedMessageQueue(null, null);
        queue.addMessage(new Message("unresolved-dlq"));
        queue.claimVisibleMessages(1, 0, false, 1, "missing-dlq");
        long observed = queue.changeVersion();
        GuardedMessageQueue.ClaimResult claimed = queue.claimVisibleMessages(1, 0, false, 1, "missing-dlq");
        assertTrue(claimed.claimed().isEmpty());
        assertEquals(1, claimed.dlqCandidates().size());
        assertEquals(observed, queue.changeVersion());
    }
}

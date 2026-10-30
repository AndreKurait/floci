package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.lifecycle.EmulatorInfoController;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.ParkingStore;
import io.github.hectorvent.floci.testing.ThreadStates;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqsOuterResetPersistenceTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final TypeReference<Map<String, Queue>> QUEUES = new TypeReference<>() {};
    private static final TypeReference<Map<String, List<Message>>> MESSAGES = new TypeReference<>() {};
    private static final TypeReference<Map<String, Map<String, Long>>> DEDUP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Map<String, Map<String, String>>>> IDENTITIES =
            new TypeReference<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"standard-message", "fifo-message", "fifo-dedup", "fifo-identity", "create", "delete"})
    void controllerDrainsEveryWriterBeforeStorageWipe(String phase) throws Exception {
        StorageFactory factory = spy(factory());
        AccountAwareStorageBackend<Queue> metadata = spy(factory.create("sqs", "queues.json", QUEUES));
        AccountAwareStorageBackend<List<Message>> messages = spy(factory.create("sqs", "messages.json", MESSAGES));
        AccountAwareStorageBackend<Map<String, Long>> dedup = spy(factory.create("sqs", "dedup.json", DEDUP));
        AccountAwareStorageBackend<Map<String, Map<String, String>>> identities =
                spy(factory.create("sqs", "identities.json", IDENTITIES));
        ParkingStore<Object> parked = new ParkingStore<>();
        if (phase.endsWith("message")) {
            doAnswer(call -> {
                parked.put(call.getArgument(1), call.getArgument(2));
                call.callRealMethod();
                return null;
            }).when(messages).putForAccount(anyString(), anyString(), anyList());
        } else if ("fifo-dedup".equals(phase)) {
            parkPut(dedup, parked);
        } else if ("fifo-identity".equals(phase)) {
            parkPut(identities, parked);
        } else if ("create".equals(phase)) {
            parkPut(metadata, parked);
        } else {
            doAnswer(call -> {
                parked.put(call.getArgument(0), "delete");
                call.callRealMethod();
                return null;
            }).when(metadata).delete(anyString());
        }
        SqsService service = service(metadata, messages, dedup, identities);
        CountDownLatch releaseWipe = new CountDownLatch(1);
        AtomicBoolean wipeEntered = new AtomicBoolean();
        AtomicReference<Thread> resetThread = new AtomicReference<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<?> write = null;
        Future<?> reset = null;
        try {
            boolean fifo = phase.startsWith("fifo");
            String name = fifo ? "outer-reset.fifo" : "outer-reset";
            Queue queue = service.createQueue(name, null, REGION);
            parked.parkNextWrite();
            write = executor.submit(() -> {
                if ("create".equals(phase)) {
                    service.createQueue("during-write", null, REGION);
                } else if ("delete".equals(phase)) {
                    service.deleteQueue(queue.getQueueUrl(), REGION);
                } else {
                    service.sendMessage(queue.getQueueUrl(), "pre-reset", null,
                            fifo ? "group" : null, fifo ? "dedup" : null, REGION);
                }
            });
            assertTrue(parked.awaitParked());
            doAnswer(call -> {
                wipeEntered.set(true);
                call.callRealMethod();
                awaitWipeRelease(releaseWipe);
                return null;
            }).when(factory).clearAll();
            reset = executor.submit(() -> {
                resetThread.set(Thread.currentThread());
                reset(controller(factory, service));
            });
            awaitResetBoundary(resetThread, reset);
            assertFalse(wipeEntered.get(), "storage must not be wiped while an admitted SQS write is parked");
            AwsException refused = assertThrows(AwsException.class,
                    () -> service.createQueue("not-admitted", null, REGION));
            assertEquals(503, refused.getHttpStatus());
            assertFalse(service.queueExists("http://localhost:4566/" + ACCOUNT + "/not-admitted", REGION));
            parked.release();
            write.get(5, TimeUnit.SECONDS);
            releaseWipe.countDown();
            reset.get(5, TimeUnit.SECONDS);
            factory.flushAll();
            StorageFactory reopened = factory();
            SqsService restored = service(reopened);
            try {
                assertTrue(restored.listQueues(null, REGION).isEmpty(), "metadata is empty after disk reload");
                assertTrue(reopened.create("sqs", "messages.json", MESSAGES).keys().isEmpty());
                assertTrue(reopened.create("sqs", "dedup.json", DEDUP).keys().isEmpty());
                assertTrue(reopened.create("sqs", "identities.json", IDENTITIES).keys().isEmpty());
                Queue replacement = restored.createQueue(name, null, REGION);
                assertTrue(restored.receiveMessage(replacement.getQueueUrl(), 10, 30, 0, REGION).isEmpty());
                restored.sendMessage(replacement.getQueueUrl(), "new", null,
                        fifo ? "group" : null, fifo ? "dedup" : null, REGION);
                assertEquals(List.of("new"), restored.receiveMessage(replacement.getQueueUrl(), 10, 30, 0, REGION)
                        .stream().map(Message::getBody).toList());
            } finally {
                restored.stop();
                reopened.shutdownAll();
            }
        } finally {
            parked.release();
            releaseWipe.countDown();
            try {
                if (write != null) {
                    write.get(5, TimeUnit.SECONDS);
                }
                if (reset != null) {
                    reset.get(5, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                service.stop();
                factory.shutdownAll();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"earlier-before", "later-before", "storage", "clear", "earlier-clear"})
    void failedOuterResetAlwaysRestoresAdmission(String failurePoint) {
        StorageFactory factory = spy(factory());
        SqsService service = service(factory);
        Resettable failure = mock(Resettable.class);
        IllegalStateException expected = new IllegalStateException("injected reset failure");
        if (failurePoint.endsWith("before")) {
            doThrow(expected).when(failure).beforeReset();
        } else if ("storage".equals(failurePoint)) {
            doThrow(expected).when(factory).clearAll();
        } else {
            doThrow(expected).when(failure).clear();
        }
        try {
            Queue old = service.createQueue("before-failure", null, REGION);
            service.sendMessage(old.getQueueUrl(), "old", null, REGION);
            EmulatorInfoController controller = failurePoint.startsWith("earlier-")
                    ? controller(factory, failure, service) : controller(factory, service, failure);
            assertSame(expected, assertThrows(IllegalStateException.class, controller::reset));
            if (failurePoint.endsWith("clear")) {
                Queue replacement = service.createQueue("before-failure", null, REGION);
                assertTrue(service.receiveMessage(replacement.getQueueUrl(), 10, 30, 0, REGION).isEmpty(),
                        "afterReset must not keep messages whose authoritative storage was wiped");
            } else {
                assertEquals("old", service.receiveMessage(old.getQueueUrl(), 1, 30, 0, REGION).getFirst().getBody());
            }
            Queue fresh = service.createQueue("after-failure", null, REGION);
            service.sendMessage(fresh.getQueueUrl(), "accepted", null, REGION);
            assertEquals("accepted", service.receiveMessage(fresh.getQueueUrl(), 1, 30, 0, REGION).getFirst().getBody());
        } finally {
            service.stop();
            factory.shutdownAll();
        }
    }

    @Test
    void resetWakesAnAdmittedLongPollBeforeDraining() throws Exception {
        StorageFactory factory = factory();
        SqsService service = service(factory);
        AtomicReference<Thread> receiver = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Queue queue = service.createQueue("long-poll", null, REGION);
            Future<List<Message>> result = executor.submit(() -> {
                receiver.set(Thread.currentThread());
                started.countDown();
                return service.receiveMessage(queue.getQueueUrl(), 1, 30, 20, REGION);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            ThreadStates.awaitParkedOrDone(receiver.get());
            Future<?> reset = executor.submit(() -> reset(controller(factory, service)));
            reset.get(5, TimeUnit.SECONDS);
            assertTrue(result.get(5, TimeUnit.SECONDS).isEmpty());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            service.stop();
            factory.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedWritesResetAndUnpairedAfterResetRemainUsable(boolean fifo) {
        StorageFactory factory = factory();
        SqsService service = service(factory);
        try {
            service.afterReset();
            String name = fifo ? "normal.fifo" : "normal";
            Queue queue = service.createQueue(name, null, REGION);
            service.sendMessage(queue.getQueueUrl(), "old", null, fifo ? "group" : null,
                    fifo ? "dedup" : null, REGION);
            reset(controller(factory, service));
            service.afterReset();
            Queue fresh = service.createQueue(name, null, REGION);
            service.sendMessage(fresh.getQueueUrl(), "new", null, fifo ? "group" : null,
                    fifo ? "dedup" : null, REGION);
            assertEquals(List.of("new"), service.receiveMessage(fresh.getQueueUrl(), 10, 30, 0, REGION)
                    .stream().map(Message::getBody).toList());
        } finally {
            service.stop();
            factory.shutdownAll();
        }
    }

    @Test
    void drainTimeoutAbortsBeforeWipeAndRestoresAdmission() throws Exception {
        StorageFactory factory = spy(factory());
        AccountAwareStorageBackend<List<Message>> messages = spy(factory.create("sqs", "messages.json", MESSAGES));
        ParkingStore<Object> parked = new ParkingStore<>();
        doAnswer(call -> {
            parked.put(call.getArgument(1), call.getArgument(2));
            call.callRealMethod();
            return null;
        }).when(messages).putForAccount(anyString(), anyString(), anyList());
        SqsService service = service(factory.create("sqs", "queues.json", QUEUES), messages,
                factory.create("sqs", "dedup.json", DEDUP), factory.create("sqs", "identities.json", IDENTITIES));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<?> write = null;
        try {
            Queue queue = service.createQueue("timeout", null, REGION);
            parked.parkNextWrite();
            write = executor.submit(() -> service.sendMessage(queue.getQueueUrl(), "retained", null, REGION));
            assertTrue(parked.awaitParked());
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> controller(factory, service).reset());
            assertEquals("Timed out draining SQS operations before reset", failure.getMessage());
            verify(factory, never()).clearAll();
            service.createQueue("resumed-before-writer", null, REGION);
            parked.release();
            write.get(5, TimeUnit.SECONDS);
            assertEquals("retained", service.receiveMessage(queue.getQueueUrl(), 1, 30, 0, REGION)
                    .getFirst().getBody());
        } finally {
            parked.release();
            try {
                if (write != null) {
                    write.get(5, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                service.stop();
                factory.shutdownAll();
            }
        }
    }

    @Test
    void recoveryReadFailureDoesNotLeaveAdmissionClosed() {
        StorageFactory factory = factory();
        AccountAwareStorageBackend<List<Message>> messages = spy(factory.create("sqs", "messages.json", MESSAGES));
        SqsService service = service(factory.create("sqs", "queues.json", QUEUES), messages,
                factory.create("sqs", "dedup.json", DEDUP), factory.create("sqs", "identities.json", IDENTITIES));
        IllegalStateException expected = new IllegalStateException("recovery read failed");
        try {
            doThrow(expected).when(messages).scanAllAccountsAsMap();
            assertSame(expected, assertThrows(IllegalStateException.class, () -> controller(factory, service).reset()));
            doCallRealMethod().when(messages).scanAllAccountsAsMap();
            Queue queue = service.createQueue("after-recovery-failure", null, REGION);
            service.sendMessage(queue.getQueueUrl(), "accepted", null, REGION);
            assertEquals("accepted", service.receiveMessage(queue.getQueueUrl(), 1, 30, 0, REGION).getFirst().getBody());
        } finally {
            doCallRealMethod().when(messages).scanAllAccountsAsMap();
            service.stop();
            factory.shutdownAll();
        }
    }

    private static void awaitResetBoundary(AtomicReference<Thread> thread, Future<?> reset) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() - deadline < 0) {
            Thread current = thread.get();
            if (current == null) {
                Thread.onSpinWait();
                continue;
            }
            ThreadStates.awaitParkedOrDone(current);
            if (reset.isDone()) {
                reset.get(5, TimeUnit.SECONDS);
                return;
            }
            if (current != null) {
                for (StackTraceElement frame : current.getStackTrace()) {
                    if ("awaitDrained".equals(frame.getMethodName()) || "awaitWipeRelease".equals(frame.getMethodName())) {
                        return;
                    }
                }
            }
            Thread.onSpinWait();
        }
        fail("reset did not reach the admission-drain or controlled post-wipe boundary");
    }

    private static void awaitWipeRelease(CountDownLatch release) throws InterruptedException {
        release.await();
    }

    private static <T> void parkPut(StorageBackend<String, T> store, ParkingStore<Object> parked) {
        doAnswer(call -> {
            parked.put(call.getArgument(0), call.getArgument(1));
            call.callRealMethod();
            return null;
        }).when(store).put(anyString(), any());
    }

    private StorageFactory factory() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        return new StorageFactory(config, access);
    }

    private static SqsService service(StorageFactory factory) {
        return service(factory.create("sqs", "queues.json", QUEUES), factory.create("sqs", "messages.json", MESSAGES),
                factory.create("sqs", "dedup.json", DEDUP), factory.create("sqs", "identities.json", IDENTITIES));
    }

    private static SqsService service(AccountAwareStorageBackend<Queue> queues,
            AccountAwareStorageBackend<List<Message>> messages, AccountAwareStorageBackend<Map<String, Long>> dedup,
            AccountAwareStorageBackend<Map<String, Map<String, String>>> identities) {
        return new SqsService(queues, messages, dedup, identities, 30, 1048576, "http://localhost:4566",
                new RegionResolver(REGION, ACCOUNT), false, null, Clock.systemUTC());
    }

    private static void reset(EmulatorInfoController controller) {
        try (Response response = controller.reset()) {
            assertEquals(200, response.getStatus());
        }
    }

    @SuppressWarnings("unchecked")
    private static EmulatorInfoController controller(StorageFactory factory, Resettable... services) {
        Instance<Resettable> instances = mock(Instance.class);
        when(instances.iterator()).thenAnswer(call -> List.of(services).iterator());
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(call -> List.<ContainerTeardown>of().iterator());
        return new EmulatorInfoController(null, null, factory, instances, teardowns, null, null, null);
    }
}

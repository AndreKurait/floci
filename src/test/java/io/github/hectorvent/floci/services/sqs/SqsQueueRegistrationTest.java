package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.ParkingStore;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SqsQueueRegistrationTest {
    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registrationKeepsTheQueueAlreadyCapturedByALongPoll(boolean fifo) throws Exception {
        ParkingStore<String> publication = new ParkingStore<>();
        InMemoryStorage<String, Queue> storage = publishingStore(publication);
        SqsService service = new SqsService(storage, 30, 1048576, "http://localhost:4566");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Thread receiver = null;
        try {
            publication.parkNextWrite();
            Future<Queue> create = executor.submit(() -> service.createQueue(name(fifo), attributes(fifo), REGION));
            assertTrue(publication.awaitParked());
            String url = storage.scan(key -> true).getFirst().getQueueUrl();
            FutureTask<List<Message>> receive = new FutureTask<>(
                    () -> service.receiveMessage(url, 1, 30, 20, REGION));
            receiver = Thread.ofVirtual().start(receive);
            assertEquals(Thread.State.TIMED_WAITING, ThreadStates.awaitState(receiver,
                    Set.of(Thread.State.TIMED_WAITING, Thread.State.TERMINATED),
                    Duration.ofSeconds(2), "lazy queue long poll"));
            publication.release();
            assertEquals(url, create.get(2, TimeUnit.SECONDS).getQueueUrl());
            service.sendMessage(url, "after registration", null, "group", "message", REGION);
            List<Message> messages = assertDoesNotThrow(() -> receive.get(2, TimeUnit.SECONDS),
                    "Registration must retain and wake the receiver's queue");
            assertEquals(1, messages.size());
            assertEquals("after registration", messages.getFirst().getBody());
        } finally {
            publication.release();
            if (receiver != null) {
                receiver.interrupt();
                receiver.join(2000);
                assertFalse(receiver.isAlive());
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registrationPreservesMessagesSentAfterMetadataPublication(boolean fifo) throws Exception {
        ParkingStore<String> publication = new ParkingStore<>();
        InMemoryStorage<String, Queue> storage = publishingStore(publication);
        SqsService service = new SqsService(storage, 30, 1048576, "http://localhost:4566");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            publication.parkNextWrite();
            Future<Queue> create = executor.submit(() -> service.createQueue(name(fifo), attributes(fifo), REGION));
            assertTrue(publication.awaitParked());
            String url = storage.scan(key -> true).getFirst().getQueueUrl();
            Message sent = service.sendMessage(url, "before registration", null, "group", "message", REGION);
            publication.release();
            assertEquals(url, create.get(2, TimeUnit.SECONDS).getQueueUrl());
            List<Message> messages = service.receiveMessage(url, 1, 30, 0, REGION);
            assertEquals(1, messages.size(), "Registration must not discard a successful concurrent send");
            assertEquals(sent.getMessageId(), messages.getFirst().getMessageId());
            assertEquals("before registration", messages.getFirst().getBody());
        } finally {
            publication.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void idempotentCreationPreservesExistingMessages(boolean fifo) {
        SqsService service = new SqsService(new InMemoryStorage<>(), 30, 1048576, "http://localhost:4566");
        try {
            Queue queue = service.createQueue(name(fifo), attributes(fifo), REGION);
            Message sent = service.sendMessage(queue.getQueueUrl(), "retained", null, "group", "message", REGION);
            Queue repeated = service.createQueue(name(fifo), attributes(fifo), REGION);
            assertEquals(queue.getQueueUrl(), repeated.getQueueUrl());
            List<Message> messages = service.receiveMessage(repeated.getQueueUrl(), 1, 30, 0, REGION);
            assertEquals(1, messages.size());
            assertEquals(sent.getMessageId(), messages.getFirst().getMessageId());
        } finally {
            service.stop();
        }
    }

    private static InMemoryStorage<String, Queue> publishingStore(ParkingStore<String> publication) {
        return new InMemoryStorage<>() {
            @Override
            public void put(String key, Queue value) {
                super.put(key, value);
                publication.put(key, "metadata visible");
            }
        };
    }

    private static String name(boolean fifo) {
        return fifo ? "registration.fifo" : "registration";
    }

    private static Map<String, String> attributes(boolean fifo) {
        return fifo ? Map.of("FifoQueue", "true") : Map.of();
    }
}

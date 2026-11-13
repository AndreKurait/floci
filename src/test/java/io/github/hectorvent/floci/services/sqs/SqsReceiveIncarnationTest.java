package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SqsReceiveIncarnationTest {
    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metadataFromDeletedQueueCannotClaimReplacementMessages(boolean fifo) throws Exception {
        ParkingStore<Queue> storage = new ParkingStore<>();
        SqsService service = new SqsService(storage, 30, 1048576, "http://localhost:4566");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Queue original = service.createQueue(name(fifo), attributes(fifo), REGION);
            String url = original.getQueueUrl();
            storage.parkNextRead();
            Future<List<Message>> oldReceive = executor.submit(() -> service.receiveMessage(url, 1, 30, 0, REGION));
            assertTrue(storage.awaitParked());
            service.deleteQueue(url, REGION);
            Queue replacement = service.createQueue(name(fifo), attributes(fifo), REGION);
            assertEquals(url, replacement.getQueueUrl());
            Message sent = service.sendMessage(url, "replacement", null, "group", "replacement", REGION);
            storage.release();
            assertTrue(oldReceive.get(2, TimeUnit.SECONDS).isEmpty(),
                    "A receive holding deleted metadata must not claim the replacement's message");
            List<Message> delivered = service.receiveMessage(url, 1, 30, 0, REGION);
            assertEquals(1, delivered.size());
            assertEquals(sent.getMessageId(), delivered.getFirst().getMessageId());
            assertEquals(1, delivered.getFirst().getReceiveCount());
        } finally {
            storage.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingAnotherQueueDoesNotInvalidateMetadataLookup(boolean fifo) throws Exception {
        ParkingStore<Queue> storage = new ParkingStore<>();
        SqsService service = new SqsService(storage, 30, 1048576, "http://localhost:4566");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Queue queue = service.createQueue(name(fifo), attributes(fifo), REGION);
            Queue other = service.createQueue("other-" + name(fifo), attributes(fifo), REGION);
            Message sent = service.sendMessage(queue.getQueueUrl(), "retained", null, "group", "retained", REGION);
            storage.parkNextRead();
            Future<List<Message>> receive = executor.submit(
                    () -> service.receiveMessage(queue.getQueueUrl(), 1, 30, 0, REGION));
            assertTrue(storage.awaitParked());
            service.deleteQueue(other.getQueueUrl(), REGION);
            storage.release();
            List<Message> delivered = receive.get(2, TimeUnit.SECONDS);
            assertEquals(1, delivered.size());
            assertEquals(sent.getMessageId(), delivered.getFirst().getMessageId());
        } finally {
            storage.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void idempotentCreationDoesNotInvalidateMetadataLookup(boolean fifo) throws Exception {
        ParkingStore<Queue> storage = new ParkingStore<>();
        SqsService service = new SqsService(storage, 30, 1048576, "http://localhost:4566");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Queue queue = service.createQueue(name(fifo), attributes(fifo), REGION);
            Message sent = service.sendMessage(queue.getQueueUrl(), "retained", null, "group", "retained", REGION);
            storage.parkNextRead();
            Future<List<Message>> receive = executor.submit(
                    () -> service.receiveMessage(queue.getQueueUrl(), 1, 30, 0, REGION));
            assertTrue(storage.awaitParked());
            assertEquals(queue.getQueueUrl(), service.createQueue(name(fifo), attributes(fifo), REGION).getQueueUrl());
            storage.release();
            List<Message> delivered = receive.get(2, TimeUnit.SECONDS);
            assertEquals(1, delivered.size());
            assertEquals(sent.getMessageId(), delivered.getFirst().getMessageId());
        } finally {
            storage.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            service.stop();
        }
    }

    private static String name(boolean fifo) {
        return fifo ? "incarnation.fifo" : "incarnation";
    }

    private static Map<String, String> attributes(boolean fifo) {
        return fifo ? Map.of("FifoQueue", "true") : Map.of();
    }
}

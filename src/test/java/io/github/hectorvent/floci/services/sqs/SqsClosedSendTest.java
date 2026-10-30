package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class SqsClosedSendTest {
    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedQueueRejectsSingleAndBulkAddsWithoutMutation(boolean bulk) {
        InMemoryStorage<String, List<Message>> messages = new InMemoryStorage<>();
        GuardedMessageQueue queue = new GuardedMessageQueue(messages, "queue");
        Message retained = new Message("retained");
        queue.addMessage(retained);
        queue.close();
        AwsException failure = assertThrows(AwsException.class, () -> add(queue, bulk, new Message("lost")));
        assertEquals("AWS.SimpleQueueService.NonExistentQueue", failure.getErrorCode());
        assertEquals(List.of(retained), queue.peekAll());
        assertEquals(List.of(retained), messages.get("queue").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openQueueAcceptsSingleAndBulkAdds(boolean bulk) {
        InMemoryStorage<String, List<Message>> messages = new InMemoryStorage<>();
        GuardedMessageQueue queue = new GuardedMessageQueue(messages, "queue");
        try {
            Message message = new Message("accepted");
            add(queue, bulk, message);
            assertEquals(List.of(message), queue.peekAll());
            assertEquals(List.of(message), messages.get("queue").orElseThrow());
        } finally {
            queue.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedCapturedQueueCannotReturnASuccessfulSendOrReserveFifoDeduplication(boolean fifo) throws Exception {
        InMemoryStorage<String, Queue> metadata = new InMemoryStorage<>();
        InMemoryStorage<String, List<Message>> messages = new InMemoryStorage<>();
        InMemoryStorage<String, Map<String, Long>> dedup = new InMemoryStorage<>();
        SqsService service = new SqsService(metadata, messages, dedup, 30, 1048576,
                "http://localhost:4566", new RegionResolver(REGION, "000000000000"));
        ParkingStore<Message> gate = new ParkingStore<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Queue queue = service.createQueue(fifo ? "closed.fifo" : "closed", null, REGION);
            ConcurrentHashMap<String, GuardedMessageQueue> registry = queues(service);
            Map.Entry<String, GuardedMessageQueue> entry = registry.entrySet().iterator().next();
            GuardedMessageQueue captured = spy(entry.getValue());
            registry.put(entry.getKey(), captured);
            doAnswer(call -> {
                gate.put("captured", call.getArgument(0));
                call.callRealMethod();
                return null;
            }).when(captured).addMessage(any(Message.class));
            gate.parkNextWrite();
            Future<Message> send = executor.submit(() -> service.sendMessage(queue.getQueueUrl(), "rejected", null,
                    fifo ? "group" : null, fifo ? "id" : null, REGION));
            assertTrue(gate.awaitParked());
            service.deleteQueue(queue.getQueueUrl(), REGION);
            gate.release();
            ExecutionException completion = assertThrows(ExecutionException.class, () -> send.get(2, TimeUnit.SECONDS));
            AwsException failure = assertInstanceOf(AwsException.class, completion.getCause());
            assertEquals("AWS.SimpleQueueService.NonExistentQueue", failure.getErrorCode());
            assertTrue(captured.peekAll().isEmpty());
            assertTrue(messages.scan(k -> true).stream().allMatch(List::isEmpty));
            assertTrue(dedup.scan(k -> true).stream().allMatch(Map::isEmpty));
            service.createQueue(fifo ? "closed.fifo" : "closed", null, REGION);
            Message accepted = service.sendMessage(queue.getQueueUrl(), "retry", null,
                    fifo ? "group" : null, fifo ? "id" : null, REGION);
            Message received = service.receiveMessage(queue.getQueueUrl(), 1, 30, 0, REGION).getFirst();
            assertEquals(accepted.getMessageId(), received.getMessageId());
            assertEquals("retry", received.getBody());
        } finally {
            gate.release();
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            } finally {
                service.stop();
            }
        }
    }

    private static void add(GuardedMessageQueue queue, boolean bulk, Message message) {
        if (bulk) {
            queue.addAll(List.of(message));
        } else {
            queue.addMessage(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, GuardedMessageQueue> queues(SqsService service) throws Exception {
        Field field = SqsService.class.getDeclaredField("messagesByQueue");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, GuardedMessageQueue>) field.get(service);
    }
}

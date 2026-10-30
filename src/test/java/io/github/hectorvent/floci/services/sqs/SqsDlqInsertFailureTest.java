package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class SqsDlqInsertFailureTest {
    private static final String REGION = "us-east-1";
    private static final TypeReference<Map<String, List<Message>>> MESSAGES = new TypeReference<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletedCapturedDestinationRestoresSourceOnDisk(boolean fifo) throws Exception {
        Fixture fixture = new Fixture(fifo);
        ParkingStore<String> gate = new ParkingStore<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ConcurrentHashMap<String, GuardedMessageQueue> registry = queues(fixture.service);
            GuardedMessageQueue captured = spy(registry.get(fixture.destinationKey));
            registry.put(fixture.destinationKey, captured);
            doAnswer(call -> {
                gate.put("captured", "destination captured");
                return call.callRealMethod();
            }).when(captured).addAll(anyList());
            gate.parkNextWrite();
            Future<?> receive = executor.submit(fixture::redrive);
            assertTrue(gate.awaitParked());
            fixture.service.sendMessage(fixture.source.getQueueUrl(), "third", null, "group", "third", REGION);
            fixture.service.deleteQueue(fixture.destination.getQueueUrl(), REGION);
            gate.release();
            ExecutionException completion = assertThrows(ExecutionException.class,
                    () -> receive.get(2, TimeUnit.SECONDS));
            assertEquals("AWS.SimpleQueueService.NonExistentQueue",
                    assertInstanceOf(AwsException.class, completion.getCause()).getErrorCode());
            fixture.assertRetained(List.of("first", "second", "third"));
            assertTrue(captured.peekAll().isEmpty());
        } finally {
            gate.release();
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            } finally {
                fixture.service.stop();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedDestinationWriteRestoresSourceAndRetryMovesItOnce(boolean fifo) throws Exception {
        Fixture fixture = new Fixture(fifo);
        try {
            fixture.store.failKey = fixture.destinationKey;
            IllegalStateException failure = assertThrows(IllegalStateException.class, fixture::redrive);
            assertEquals("destination write failed", failure.getMessage());
            fixture.assertRetained();
            assertTrue(fixture.service.peekMessages(fixture.destination.getQueueUrl(), REGION).isEmpty());
            fixture.redrive();
            assertTrue(fixture.service.peekMessages(fixture.source.getQueueUrl(), REGION).isEmpty());
            assertEquals(List.of("first", "second"), fixture.service.peekMessages(
                    fixture.destination.getQueueUrl(), REGION).stream().map(Message::getBody).toList());
        } finally {
            fixture.service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulDestinationInsertKeepsExistingRedriveMetadata(boolean fifo) {
        Fixture fixture = new Fixture(fifo);
        try {
            fixture.redrive();
            assertTrue(fixture.service.peekMessages(fixture.source.getQueueUrl(), REGION).isEmpty());
            List<Message> moved = fixture.service.peekMessages(fixture.destination.getQueueUrl(), REGION);
            assertEquals(List.of("first", "second"), moved.stream().map(Message::getBody).toList());
            assertEquals(fixture.first.getMessageId(), moved.getFirst().getMessageId());
            assertEquals(fixture.source.getQueueUrl(), moved.getFirst().getOriginalSourceQueueUrl());
            assertNull(moved.getFirst().getReceiptHandle());
            assertNull(moved.getFirst().getVisibleAt());
            if (fifo) {
                assertTrue(moved.getFirst().getRetentionStartTimestamp().isAfter(fixture.retention));
            } else {
                assertEquals(fixture.retention, moved.getFirst().getRetentionStartTimestamp());
            }
        } finally {
            fixture.service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCompensationPreservesInsertionErrorAndInMemoryCandidates(boolean fifo) {
        Fixture fixture = new Fixture(fifo);
        try {
            fixture.store.failKey = fixture.destinationKey;
            fixture.store.failAfterKey = fixture.sourceKey;
            IllegalStateException failure = assertThrows(IllegalStateException.class, fixture::redrive);
            assertEquals("destination write failed", failure.getMessage());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals("destination write failed", failure.getSuppressed()[0].getMessage());
            assertEquals(List.of("first", "second"), fixture.service.peekMessages(fixture.source.getQueueUrl(), REGION)
                    .stream().map(Message::getBody).toList());
            fixture.redrive();
            assertEquals(2, fixture.service.peekMessages(fixture.destination.getQueueUrl(), REGION).size());
        } finally {
            fixture.service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compensationDoesNotReviveAConcurrentlyDeletedSource(boolean fifo) throws Exception {
        Fixture fixture = new Fixture(fifo);
        try {
            ConcurrentHashMap<String, GuardedMessageQueue> registry = queues(fixture.service);
            GuardedMessageQueue captured = spy(registry.get(fixture.destinationKey));
            registry.put(fixture.destinationKey, captured);
            IllegalStateException insertionFailure = new IllegalStateException("insertion failed");
            doAnswer(call -> {
                fixture.service.deleteQueue(fixture.source.getQueueUrl(), REGION);
                throw insertionFailure;
            }).when(captured).addAll(anyList());
            assertSame(insertionFailure, assertThrows(IllegalStateException.class, fixture::redrive));
            assertEquals(1, insertionFailure.getSuppressed().length);
            assertEquals("AWS.SimpleQueueService.NonExistentQueue",
                    assertInstanceOf(AwsException.class, insertionFailure.getSuppressed()[0]).getErrorCode());
            assertFalse(registry.containsKey(fixture.sourceKey));
            assertTrue(fixture.store.get(fixture.sourceKey).isEmpty());
        } finally {
            fixture.service.stop();
        }
    }

    private class Fixture {
        private final FailingStore store = new FailingStore(directory.resolve("messages.json"));
        private final SqsService service = new SqsService(new InMemoryStorage<>(), store, null,
                30, 1048576, "http://localhost:4566", new RegionResolver(REGION, "000000000000"));
        private final Queue source;
        private final Queue destination;
        private final String sourceKey;
        private final String destinationKey;
        private final Message first;
        private final String receipt;
        private final Instant visible;
        private final Instant retention = Instant.now().minusSeconds(100);

        Fixture(boolean fifo) {
            String suffix = fifo ? ".fifo" : "";
            destination = service.createQueue("destination" + suffix, null, REGION);
            source = service.createQueue("source" + suffix, Map.of("RedrivePolicy",
                    "{\"deadLetterTargetArn\":\"" + service.getQueueAttributes(destination.getQueueUrl(), List.of("QueueArn"), REGION).get("QueueArn")
                            + "\",\"maxReceiveCount\":1}"), REGION);
            sourceKey = REGION + "::" + URI.create(source.getQueueUrl()).getPath();
            destinationKey = REGION + "::" + URI.create(destination.getQueueUrl()).getPath();
            first = service.sendMessage(source.getQueueUrl(), "first", null, "group", "first", REGION);
            first.setRetentionStartTimestamp(retention);
            first.setOriginalSourceQueueUrl("previous-source");
            service.sendMessage(source.getQueueUrl(), "second", null, "group", "second", REGION);
            List<Message> received = service.receiveMessage(source.getQueueUrl(), 10, 0, 0, REGION);
            assertEquals(2, received.size());
            receipt = first.getReceiptHandle();
            visible = first.getVisibleAt();
        }

        void redrive() {
            service.receiveMessage(source.getQueueUrl(), 10, 0, 0, REGION);
        }

        void assertRetained() {
            assertRetained(List.of("first", "second"));
        }

        void assertRetained(List<String> expectedBodies) {
            PersistentStorage<String, List<Message>> reopened = new PersistentStorage<>(
                    directory.resolve("messages.json"), MESSAGES);
            reopened.load();
            List<Message> disk = reopened.get(sourceKey).orElse(List.of());
            assertEquals(expectedBodies, disk.stream().map(Message::getBody).toList());
            assertEquals(first.getMessageId(), disk.getFirst().getMessageId());
            assertEquals(receipt, disk.getFirst().getReceiptHandle());
            assertEquals(visible, disk.getFirst().getVisibleAt());
            assertEquals(retention, disk.getFirst().getRetentionStartTimestamp());
            assertEquals("previous-source", disk.getFirst().getOriginalSourceQueueUrl());
            assertEquals(expectedBodies, service.peekMessages(source.getQueueUrl(), REGION)
                    .stream().map(Message::getBody).toList());
        }
    }

    private static class FailingStore extends PersistentStorage<String, List<Message>> {
        private String failKey;
        private String failAfterKey;

        FailingStore(Path file) {
            super(file, MESSAGES);
        }

        @Override
        public void put(String key, List<Message> value) {
            if (key.equals(failKey)) {
                failKey = failAfterKey;
                failAfterKey = null;
                throw new IllegalStateException("destination write failed");
            }
            super.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, GuardedMessageQueue> queues(SqsService service) throws Exception {
        Field field = SqsService.class.getDeclaredField("messagesByQueue");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, GuardedMessageQueue>) field.get(service);
    }
}

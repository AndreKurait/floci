package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.ParkingStore;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SqsDeleteRecreateTest {
    private static final String REGION = "us-east-1";
    private static final TypeReference<Map<String, List<Message>>> MESSAGES = new TypeReference<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void recreationCannotLoseItsSuccessfulSendToAnEarlierDeletion(boolean fifo, boolean beforeMessageDelete) throws Exception {
        ParkingStore<String> deletion = new ParkingStore<>();
        InMemoryStorage<String, Queue> metadata = new InMemoryStorage<>() {
            @Override
            public void delete(String key) {
                super.delete(key);
                if (!beforeMessageDelete) {
                    deletion.put(key, "metadata deleted");
                }
            }
        };
        Path file = directory.resolve("messages.json");
        PersistentStorage<String, List<Message>> disk = spy(new PersistentStorage<>(file, MESSAGES));
        if (beforeMessageDelete) {
            doAnswer(call -> {
                deletion.put(call.getArgument(0), "before message deletion");
                call.callRealMethod();
                return null;
            }).when(disk).delete(anyString());
        }
        SqsService service = service(metadata, disk);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Queue original = service.createQueue(name(fifo), attributes(fifo), REGION);
            service.sendMessage(original.getQueueUrl(), "original", null, "group", "original", REGION);
            deletion.parkNextWrite();
            Future<?> delete = executor.submit(() -> service.deleteQueue(original.getQueueUrl(), REGION));
            assertTrue(deletion.awaitParked());
            AtomicReference<Thread> creator = new AtomicReference<>();
            Future<Message> recreate = executor.submit(() -> {
                creator.set(Thread.currentThread());
                Queue replacement = service.createQueue(name(fifo), attributes(fifo), REGION);
                return service.sendMessage(replacement.getQueueUrl(), "replacement", null,
                        "group", "replacement", REGION);
            });
            ThreadStates.awaitBlockedOrDone(creator, recreate);
            deletion.release();
            delete.get(2, TimeUnit.SECONDS);
            Message sent = recreate.get(2, TimeUnit.SECONDS);
            PersistentStorage<String, List<Message>> reopened = new PersistentStorage<>(file, MESSAGES);
            reopened.load();
            List<String> persistedBodies = reopened.scan(key -> true).stream().flatMap(List::stream)
                    .map(Message::getBody).toList();
            List<Message> received = service.receiveMessage(original.getQueueUrl(), 1, 30, 0, REGION);
            assertAll(
                    () -> assertEquals(List.of(sent.getMessageId()), received.stream().map(Message::getMessageId).toList(),
                            "Earlier deletion must not remove a replacement queue's successful send"),
                    () -> assertEquals(List.of("replacement"), persistedBodies,
                            "Replacement messages must survive disk reload before any receive rewrites them"));
        } finally {
            deletion.release();
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            } finally {
                service.stop();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anotherQueueCanBeCreatedWhileDeletionIsParked(boolean fifo) throws Exception {
        ParkingStore<String> deletion = new ParkingStore<>();
        InMemoryStorage<String, Queue> metadata = new InMemoryStorage<>() {
            @Override
            public void delete(String key) {
                super.delete(key);
                deletion.put(key, "metadata deleted");
            }
        };
        SqsService service = service(metadata, new PersistentStorage<>(directory.resolve("messages.json"), MESSAGES));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Queue original = service.createQueue(name(fifo), attributes(fifo), REGION);
            deletion.parkNextWrite();
            Future<?> delete = executor.submit(() -> service.deleteQueue(original.getQueueUrl(), REGION));
            assertTrue(deletion.awaitParked());
            Future<Queue> create = executor.submit(() -> service.createQueue("other-" + name(fifo), attributes(fifo), REGION));
            Queue other = create.get(2, TimeUnit.SECONDS);
            Message sent = service.sendMessage(other.getQueueUrl(), "retained", null, "group", "retained", REGION);
            deletion.release();
            delete.get(2, TimeUnit.SECONDS);
            assertEquals(sent.getMessageId(), service.receiveMessage(other.getQueueUrl(), 1, 30, 0, REGION)
                    .getFirst().getMessageId());
        } finally {
            deletion.release();
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            } finally {
                service.stop();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedDeletionReleasesTheQueueForLaterOperations(boolean fifo) throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        InMemoryStorage<String, Queue> metadata = new InMemoryStorage<>() {
            @Override
            public void delete(String key) {
                if (fail.getAndSet(false)) {
                    throw new IllegalStateException("storage unavailable");
                }
                super.delete(key);
            }
        };
        SqsService service = service(metadata, new PersistentStorage<>(directory.resolve("messages.json"), MESSAGES));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Queue original = service.createQueue(name(fifo), attributes(fifo), REGION);
            assertThrows(IllegalStateException.class, () -> service.deleteQueue(original.getQueueUrl(), REGION));
            Future<?> retry = executor.submit(() -> service.deleteQueue(original.getQueueUrl(), REGION));
            retry.get(2, TimeUnit.SECONDS);
            Queue replacement = service.createQueue(name(fifo), attributes(fifo), REGION);
            Message sent = service.sendMessage(replacement.getQueueUrl(), "retained", null, "group", "retained", REGION);
            assertEquals(sent.getMessageId(), service.receiveMessage(replacement.getQueueUrl(), 1, 30, 0, REGION)
                    .getFirst().getMessageId());
        } finally {
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            } finally {
                service.stop();
            }
        }
    }

    private static SqsService service(InMemoryStorage<String, Queue> metadata,
                                      PersistentStorage<String, List<Message>> messages) {
        return new SqsService(metadata, messages, null, 30, 1048576, "http://localhost:4566",
                new RegionResolver(REGION, "000000000000"));
    }

    private static String name(boolean fifo) {
        return fifo ? "recreate.fifo" : "recreate";
    }

    private static Map<String, String> attributes(boolean fifo) {
        return fifo ? Map.of("FifoQueue", "true") : Map.of();
    }
}

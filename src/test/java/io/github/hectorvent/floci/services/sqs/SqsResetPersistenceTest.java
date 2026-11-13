package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.ParkingStore;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqsResetPersistenceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final TypeReference<Map<String, List<Message>>> MESSAGE_TYPE = new TypeReference<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"false, true", "true, true", "false, false", "true, false"})
    void aPreResetWriteCannotReplaceTheNewPersistedIncarnation(boolean accountAware, boolean replacement)
            throws Exception {
        Path file = directory.resolve("messages.json");
        PersistentStorage<String, List<Message>> disk = spy(new PersistentStorage<>(file, MESSAGE_TYPE));
        ParkingStore<List<Message>> gate = new ParkingStore<>();
        doAnswer(call -> {
            gate.put(call.getArgument(0), call.getArgument(1));
            call.callRealMethod();
            return null;
        }).when(disk).put(anyString(), anyList());
        StorageBackend<String, List<Message>> messages = accountAware
                ? new AccountAwareStorageBackend<>(disk, null, ACCOUNT) : disk;
        InMemoryStorage<String, Queue> metadata = new InMemoryStorage<>();
        SqsService service = service(metadata, messages);
        Queue queue = service.createQueue("reset-persistence", null, REGION);
        ConcurrentHashMap<String, GuardedMessageQueue> queues = queues(service);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            gate.parkNextWrite();
            Future<?> oldSend = executor.submit(() -> service.sendMessage(queue.getQueueUrl(), "old", null, REGION));
            assertTrue(gate.awaitParked());
            metadata.clear();
            disk.clear();
            Future<?> reset = executor.submit(service::clear);
            await().atMost(Duration.ofSeconds(2)).until(queues::isEmpty);
            Future<?> newSend = null;
            if (replacement) {
                Queue recreated = service.createQueue("reset-persistence", null, REGION);
                AtomicReference<Thread> sender = new AtomicReference<>();
                newSend = executor.submit(() -> {
                    sender.set(Thread.currentThread());
                    return service.sendMessage(recreated.getQueueUrl(), "replacement", null, REGION);
                });
                ThreadStates.awaitBlockedOrDone(sender, newSend);
            }
            gate.release();
            oldSend.get(2, TimeUnit.SECONDS);
            if (newSend != null) {
                newSend.get(2, TimeUnit.SECONDS);
            }
            reset.get(2, TimeUnit.SECONDS);
            PersistentStorage<String, List<Message>> reopened = new PersistentStorage<>(file, MESSAGE_TYPE);
            reopened.load();
            List<String> bodies = reopened.scan(k -> true).stream().flatMap(List::stream)
                    .map(Message::getBody).toList();
            assertEquals(replacement ? List.of("replacement") : List.of(), bodies,
                    "A completed reset must not leave a detached queue's write on disk");
            if (replacement) {
                StorageBackend<String, List<Message>> restoredMessages = accountAware
                        ? new AccountAwareStorageBackend<>(reopened, null, ACCOUNT) : reopened;
                SqsService restored = service(metadata, restoredMessages);
                try {
                    assertEquals("replacement", restored.receiveMessage(queue.getQueueUrl(), 1, 30, 0, REGION)
                            .getFirst().getBody());
                } finally {
                    restored.stop();
                }
            }
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anActiveIncarnationStillPersistsAndLoads(boolean accountAware) {
        Path file = directory.resolve("messages.json");
        PersistentStorage<String, List<Message>> disk = new PersistentStorage<>(file, MESSAGE_TYPE);
        StorageBackend<String, List<Message>> messages = accountAware
                ? new AccountAwareStorageBackend<>(disk, null, ACCOUNT) : disk;
        InMemoryStorage<String, Queue> metadata = new InMemoryStorage<>();
        SqsService service = service(metadata, messages);
        try {
            Queue queue = service.createQueue("active-persistence", null, REGION);
            service.sendMessage(queue.getQueueUrl(), "retained", null, REGION);
            PersistentStorage<String, List<Message>> reopened = new PersistentStorage<>(file, MESSAGE_TYPE);
            reopened.load();
            StorageBackend<String, List<Message>> restoredMessages = accountAware
                    ? new AccountAwareStorageBackend<>(reopened, null, ACCOUNT) : reopened;
            SqsService restored = service(metadata, restoredMessages);
            try {
                assertEquals("retained", restored.receiveMessage(queue.getQueueUrl(), 1, 30, 0, REGION)
                        .getFirst().getBody());
            } finally {
                restored.stop();
            }
        } finally {
            service.stop();
        }
    }

    private static SqsService service(StorageBackend<String, Queue> metadata,
                                      StorageBackend<String, List<Message>> messages) {
        return new SqsService(metadata, messages, null, 30, 1048576, "http://localhost:4566",
                new RegionResolver(REGION, ACCOUNT));
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, GuardedMessageQueue> queues(SqsService service) throws Exception {
        Field field = SqsService.class.getDeclaredField("messagesByQueue");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, GuardedMessageQueue>) field.get(service);
    }
}

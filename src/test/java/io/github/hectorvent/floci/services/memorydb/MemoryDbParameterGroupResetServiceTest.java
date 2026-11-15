package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.lifecycle.EmulatorInfoController;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbParameterGroup;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.hectorvent.floci.services.memorydb.MemoryDbParameterGroupTestFixture.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryDbParameterGroupResetServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"delete", "recreate", "tag", "reset", "snapshot"})
    void anotherOperationCannotCrossCapturedUpdate(String operation) throws Exception {
        CapturingStorage storage = new CapturingStorage();
        MemoryDbParameterGroupTestFixture fixture = new MemoryDbParameterGroupTestFixture(storage);
        MemoryDbParameterGroup original = fixture.create("held");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicLong writerId = new AtomicLong();
        AtomicLong otherId = new AtomicLong();
        try {
            storage.armed = true;
            Future<MemoryDbParameterGroup> writer = executor.submit(() -> {
                writerId.set(Thread.currentThread().threadId());
                return fixture.service.update("held", update("timeout", "20"), REGION);
            });
            assertTrue(storage.captured.await(5, TimeUnit.SECONDS));
            assertSame(original, storage.observed);
            Future<MemoryDbParameterGroup> other = executor.submit(() -> {
                otherId.set(Thread.currentThread().threadId());
                return switch (operation) {
                    case "delete" -> fixture.service.delete("held", REGION);
                    case "recreate" -> {
                        fixture.service.delete("held", REGION);
                        yield fixture.create("held");
                    }
                    case "tag" -> {
                        fixture.service.tag(original.arn(), Map.of("team", "second"), REGION);
                        yield fixture.current("held");
                    }
                    case "reset" -> fixture.service.reset("held", true, null, REGION);
                    default -> fixture.current("held");
                };
            });
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                if (otherId.get() == 0) {
                    return false;
                }
                ThreadInfo thread = ManagementFactory.getThreadMXBean().getThreadInfo(otherId.get());
                return thread != null && thread.getLockOwnerId() == writerId.get();
            });
            storage.release.countDown();
            MemoryDbParameterGroup updated = writer.get(5, TimeUnit.SECONDS);
            assertEquals("20", updated.overrides().get("timeout"));
            MemoryDbParameterGroup last = other.get(5, TimeUnit.SECONDS);
            assertTrue(original.overrides().isEmpty());
            if (operation.equals("delete")) {
                assertEquals(updated, last);
                error("ParameterGroupNotFoundFault", () -> fixture.current("held"));
            } else if (operation.equals("recreate")) {
                assertNotEquals(original.generation(), last.generation());
                assertTrue(last.overrides().isEmpty());
                assertEquals(last, fixture.current("held"));
            } else if (operation.equals("tag")) {
                assertEquals("20", last.overrides().get("timeout"));
                assertEquals(Map.of("team", "second"), last.tags());
            } else if (operation.equals("reset")) {
                assertTrue(last.overrides().isEmpty());
                assertEquals(original.generation(), last.generation());
            } else {
                assertEquals(updated, last);
            }
        } finally {
            storage.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void resetDrainsCapturedWriterAndRefusesSnapshotsAndQueuedWritesUntilResume() throws Exception {
        CapturingStorage storage = new CapturingStorage();
        MemoryDbParameterGroupTestFixture fixture = new MemoryDbParameterGroupTestFixture(storage);
        MemoryDbParameterGroup original = fixture.create("held");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicLong writerId = new AtomicLong();
        AtomicLong resetId = new AtomicLong();
        try {
            storage.armed = true;
            Future<MemoryDbParameterGroup> writer = executor.submit(() -> {
                writerId.set(Thread.currentThread().threadId());
                return fixture.service.update("held", update("timeout", "20"), REGION);
            });
            assertTrue(storage.captured.await(5, TimeUnit.SECONDS));
            assertSame(original, storage.observed);
            Future<?> reset = executor.submit(() -> {
                resetId.set(Thread.currentThread().threadId());
                fixture.service.beforeReset();
            });
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                if (resetId.get() == 0) {
                    return false;
                }
                ThreadInfo thread = ManagementFactory.getThreadMXBean().getThreadInfo(resetId.get());
                return thread != null && thread.getLockOwnerId() == writerId.get();
            });
            storage.release.countDown();
            assertEquals("20", writer.get(5, TimeUnit.SECONDS).overrides().get("timeout"));
            reset.get(5, TimeUnit.SECONDS);
            Future<?> queued = executor.submit(() -> assertEquals(503,
                    assertThrows(AwsException.class, () -> fixture.create("queued")).getHttpStatus()));
            queued.get(5, TimeUnit.SECONDS);
            assertEquals(503, assertThrows(AwsException.class, () -> fixture.current("held")).getHttpStatus());
            assertEquals(503, assertThrows(AwsException.class,
                    () -> fixture.service.iamTags(original.arn(), "aws", REGION, ACCOUNT)).getHttpStatus());
            assertEquals(503, assertThrows(AwsException.class,
                    () -> fixture.service.listTags(original.arn(), REGION)).getHttpStatus());
            storage.clear();
            fixture.service.clear();
            fixture.service.afterReset();
            fixture.service.afterReset();
            assertTrue(storage.isEmpty());
            MemoryDbParameterGroup recreated = fixture.create("held");
            assertNotEquals(original.generation(), recreated.generation());
            assertTrue(recreated.overrides().isEmpty());
        } finally {
            storage.release.countDown();
            fixture.service.afterReset();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"earlier-hook", "later-hook", "storage"})
    @SuppressWarnings("unchecked")
    void controllerResumesRealServiceWhenAnotherResetStepFails(String failure) {
        MemoryDbParameterGroupTestFixture fixture = new MemoryDbParameterGroupTestFixture();
        MemoryDbParameterGroup original = fixture.create("retained");
        Resettable broken = mock(Resettable.class);
        doThrow(new IllegalStateException("hook failed")).when(broken).beforeReset();
        StorageFactory storage = mock(StorageFactory.class);
        if (failure.equals("storage")) {
            doThrow(new IllegalStateException("storage failed")).when(storage).clearAll();
        }
        List<Resettable> services = switch (failure) {
            case "earlier-hook" -> List.of(broken, fixture.service);
            case "later-hook" -> List.of(fixture.service, broken);
            default -> List.of(fixture.service);
        };
        Instance<Resettable> resettables = mock(Instance.class);
        when(resettables.iterator()).thenAnswer(call -> services.iterator());
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(call -> List.<ContainerTeardown>of().iterator());
        EmulatorInfoController controller = new EmulatorInfoController(null, null, storage, resettables, teardowns, null, null, null);
        assertThrows(IllegalStateException.class, controller::reset);
        assertEquals(original, fixture.current("retained"));
        assertEquals("20", fixture.service.update("retained", update("timeout", "20"), REGION).overrides().get("timeout"));
        fixture.service.afterReset();
        assertEquals(Map.of("team", "blue"), fixture.service.listTags(original.arn(), REGION));
    }

    private static final class CapturingStorage extends InMemoryStorage<String, MemoryDbParameterGroup> {
        private final CountDownLatch captured = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean armed;
        private volatile MemoryDbParameterGroup observed;

        @Override
        public Optional<MemoryDbParameterGroup> get(String key) {
            Optional<MemoryDbParameterGroup> result = super.get(key);
            if (armed && key.endsWith(":held")) {
                armed = false;
                observed = result.orElseThrow();
                captured.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("writer barrier expired");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
            return result;
        }
    }
}

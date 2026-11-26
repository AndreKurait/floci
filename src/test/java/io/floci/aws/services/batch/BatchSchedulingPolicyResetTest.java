package io.floci.aws.services.batch;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.lifecycle.EmulatorInfoController;
import io.floci.aws.services.batch.model.StoredSchedulingPolicy;
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

import static io.floci.aws.services.batch.BatchSchedulingPolicyTestFixture.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BatchSchedulingPolicyResetTest {
    @ParameterizedTest
    @ValueSource(strings = {"tag", "delete-recreate", "describe", "iam", "reset"})
    void capturedWriterDrainsBeforeConcurrentMutationsSnapshotsOrReset(String operation) throws Exception {
        CapturingStorage storage = new CapturingStorage();
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture(storage);
        StoredSchedulingPolicy original = fixture.create("held");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicLong writerId = new AtomicLong();
        AtomicLong otherId = new AtomicLong();
        try {
            storage.armed = true;
            Future<?> writer = executor.submit(() -> {
                writerId.set(Thread.currentThread().threadId());
                fixture.service.update(fixture.update("held", 90));
            });
            assertTrue(storage.captured.await(5, TimeUnit.SECONDS));
            assertSame(original, storage.observed);
            Future<?> other = executor.submit(() -> {
                otherId.set(Thread.currentThread().threadId());
                switch (operation) {
                    case "tag" -> fixture.service.tag(original.arn(), Map.of("extra", "yes"));
                    case "delete-recreate" -> {
                        fixture.service.delete(fixture.select("held"));
                        fixture.create("held");
                    }
                    case "describe" -> assertEquals(90, json(fixture.service.describe(
                            json(Map.of("arns", List.of(original.arn()))))).path("schedulingPolicies")
                            .get(0).path("fairsharePolicy").path("shareDecaySeconds").asInt());
                    case "iam" -> assertEquals(original.tags(), fixture.service.iamTags(original.arn(), "aws", REGION, ACCOUNT));
                    case "reset" -> fixture.service.beforeReset();
                    default -> throw new IllegalArgumentException(operation);
                }
            });
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                ThreadInfo thread = otherId.get() == 0 ? null : ManagementFactory.getThreadMXBean().getThreadInfo(otherId.get());
                return thread != null && thread.getLockOwnerId() == writerId.get();
            });
            storage.release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            other.get(5, TimeUnit.SECONDS);
            assertEquals(30, original.fairshare().shareDecaySeconds());
            if (operation.equals("reset")) {
                assertEquals(503, assertThrows(AwsException.class, () -> fixture.create("queued")).getHttpStatus());
                assertEquals(503, assertThrows(AwsException.class, () -> fixture.service.list(json(Map.of()))).getHttpStatus());
                assertEquals(503, assertThrows(AwsException.class, () -> fixture.service.listTags(original.arn())).getHttpStatus());
                assertEquals(503, assertThrows(AwsException.class,
                        () -> fixture.service.iamTags(original.arn(), "aws", REGION, ACCOUNT)).getHttpStatus());
                assertThrows(AwsException.class, fixture.service::getResources);
                assertThrows(AwsException.class, fixture.service::getSupportedResourceTypes);
                storage.clear();
                fixture.service.clear();
                fixture.service.afterReset();
                fixture.service.afterReset();
                assertNotEquals(original.generation(), fixture.create("held").generation());
            } else if (operation.equals("delete-recreate")) {
                assertNotEquals(original.generation(), fixture.stored("held").generation());
                assertEquals(0, fixture.stored("held").revision());
            } else {
                assertEquals(90, fixture.stored("held").fairshare().shareDecaySeconds());
                if (operation.equals("tag")) {
                    assertEquals(Map.of("team", "blue", "extra", "yes"), fixture.stored("held").tags());
                }
            }
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
    void actualResetControllerResumesContextualServiceAfterAnotherStepFails(String failure) {
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture();
        StoredSchedulingPolicy original = fixture.create("held");
        Resettable broken = mock(Resettable.class);
        doThrow(new IllegalStateException("hook failure")).when(broken).beforeReset();
        StorageFactory storage = mock(StorageFactory.class);
        if (failure.equals("storage")) {
            doThrow(new IllegalStateException("storage failure")).when(storage).clearAll();
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
        assertSame(original, fixture.stored("held"));
        fixture.service.update(fixture.update("held", 90));
        assertEquals(90, fixture.stored("held").fairshare().shareDecaySeconds());
        assertEquals(original.tags(), fixture.service.listTags(original.arn()));
    }

    @Test
    void resetRotatesContinuationSecretEvenWhenInventoryWasNotCleared() {
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture();
        fixture.create("a");
        fixture.create("b");
        String token = (String) fixture.service.list(json(Map.of("maxResults", 1))).get("nextToken");
        fixture.service.beforeReset();
        fixture.service.clear();
        fixture.service.afterReset();
        assertThrows(AwsException.class, () -> fixture.service.list(json(Map.of("maxResults", 1, "nextToken", token))));
        assertEquals(2, json(fixture.service.list(json(Map.of()))).path("schedulingPolicies").size());
    }

    private static final class CapturingStorage extends InMemoryStorage<String, StoredSchedulingPolicy> {
        private final CountDownLatch captured = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean armed;
        private volatile StoredSchedulingPolicy observed;

        @Override
        public Optional<StoredSchedulingPolicy> get(String key) {
            Optional<StoredSchedulingPolicy> result = super.get(key);
            if (armed && key.endsWith("/held")) {
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

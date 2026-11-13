package io.floci.aws.services.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kinesis.model.KinesisConsumer;
import io.floci.aws.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.floci.aws.services.kinesis.KinesisResourcePolicyServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class KinesisResourcePolicyTagCompositionServiceTest {
    private final RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void tagReplacementsPreserveEveryOtherConsumerFieldAndBothPolicies() {
        KinesisService service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(), regions, clock);
        String parent = service.createStream("target", 1, REGION).getStreamArn();
        KinesisConsumer before = service.registerStreamConsumer(parent, "child", Map.of("remove", "yes"), REGION);
        String child = before.getConsumerArn();
        service.putResourcePolicy(parent, POLICY, REGION);
        service.putResourcePolicy(child, DENY, REGION);
        service.tagResource(child, Map.of("owner", "new"), REGION);
        service.untagResource(child, List.of("remove"), REGION);
        service.tagResource(parent, Map.of("owner", "parent"), REGION);
        assertEquals(Map.of("owner", "new"), service.listTagsForResource(child, REGION));
        assertEquals(Map.of("owner", "new"), service.resourcePolicyTags(child, REGION));
        assertEquals(Map.of("remove", "yes"), before.getTags());
        KinesisConsumer after = service.describeStreamConsumer(null, null, child, REGION);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode beforeFields = mapper.valueToTree(before);
        ObjectNode afterFields = mapper.valueToTree(after);
        beforeFields.remove("tags");
        afterFields.remove("tags");
        assertEquals(beforeFields, afterFields);
        assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
        assertEquals(DENY, service.getResourcePolicy(child, REGION));
        assertEquals(Map.of("owner", "parent"), service.resourcePolicyTags(parent, REGION));
    }

    @Test
    void invalidSameArnRegistrationDoesNotClearTheExistingPolicy() {
        KinesisService service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(), regions, clock);
        String parent = service.createStream("target", 1, REGION).getStreamArn();
        KinesisConsumer original = service.registerStreamConsumer(parent, "child", Map.of("owner", "old"), REGION);
        service.putResourcePolicy(original.getConsumerArn(), POLICY, REGION);
        AwsException error = assertThrows(AwsException.class, () ->
                service.registerStreamConsumer(parent, "child", Map.of("aws:reserved", "no"), REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertSame(original, service.describeStreamConsumer(null, null, original.getConsumerArn(), REGION));
        assertEquals(POLICY, service.getResourcePolicy(original.getConsumerArn(), REGION));
    }

    @Test
    void retainedConsumerCannotBeTaggedThroughARecreatedParent() {
        InMemoryStorage<String, KinesisConsumer> consumers = new InMemoryStorage<>();
        KinesisService service = new KinesisService(new InMemoryStorage<>(), consumers, regions, clock);
        String parent = service.createStream("target", 1, REGION).getStreamArn();
        KinesisConsumer original = service.registerStreamConsumer(parent, "child", Map.of("owner", "old"), REGION);
        String child = original.getConsumerArn();
        service.deleteStream("target", REGION);
        service.createStream("target", 1, REGION);
        service.putResourcePolicy(parent, POLICY, REGION);
        assertThrows(AwsException.class, () -> service.tagResource(child, Map.of("owner", "new"), REGION));
        assertThrows(AwsException.class, () -> service.untagResource(child, List.of("owner"), REGION));
        assertThrows(AwsException.class, () -> service.listTagsForResource(child, REGION));
        assertSame(original, consumers.get(REGION + "::" + child).orElseThrow());
        assertEquals(Map.of("owner", "old"), original.getTags());
        assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedConsumerTagCommitPreservesOldTagsAndPolicies(boolean remove) {
        AtomicBoolean fail = new AtomicBoolean();
        InMemoryStorage<String, KinesisConsumer> consumers = new InMemoryStorage<>() {
            @Override
            public void putAllAtomically(Map<String, KinesisConsumer> entries) {
                if (fail.get()) {
                    throw new IllegalStateException("owned tag commit refusal");
                }
                super.putAllAtomically(entries);
            }
        };
        KinesisService service = new KinesisService(new InMemoryStorage<>(), consumers, regions, clock);
        String parent = service.createStream("target", 1, REGION).getStreamArn();
        KinesisConsumer original = service.registerStreamConsumer(parent, "child", Map.of("owner", "old"), REGION);
        String child = original.getConsumerArn();
        service.putResourcePolicy(parent, POLICY, REGION);
        service.putResourcePolicy(child, DENY, REGION);
        fail.set(true);
        assertThrows(IllegalStateException.class, () -> {
            if (remove) {
                service.untagResource(child, List.of("owner"), REGION);
            } else {
                service.tagResource(child, Map.of("owner", "new"), REGION);
            }
        });
        assertSame(original, service.describeStreamConsumer(null, null, child, REGION));
        assertEquals(Map.of("owner", "old"), service.resourcePolicyTags(child, REGION));
        assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
        assertEquals(DENY, service.getResourcePolicy(child, REGION));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedStreamTagCommitPreservesOldTagsAndBothPolicies(boolean remove) {
        AtomicBoolean fail = new AtomicBoolean();
        InMemoryStorage<String, KinesisStream> streams = new InMemoryStorage<>() {
            @Override
            public void putAllAtomically(Map<String, KinesisStream> entries) {
                if (fail.get()) {
                    throw new IllegalStateException("owned stream tag commit refusal");
                }
                super.putAllAtomically(entries);
            }
        };
        KinesisService service = new KinesisService(streams, new InMemoryStorage<>(), regions, clock);
        String parent = service.createStream("target", 1, REGION).getStreamArn();
        String child = service.registerStreamConsumer(parent, "child", REGION).getConsumerArn();
        service.addTagsToStream("target", Map.of("owner", "old"), REGION);
        service.putResourcePolicy(parent, POLICY, REGION);
        service.putResourcePolicy(child, DENY, REGION);
        KinesisStream original = service.describeStream("target", REGION);
        fail.set(true);
        assertThrows(IllegalStateException.class, () -> {
            if (remove) {
                service.untagResource(parent, List.of("owner"), REGION);
            } else {
                service.tagResource(parent, Map.of("owner", "new"), REGION);
            }
        });
        assertSame(original, service.describeStream("target", REGION));
        assertEquals(Map.of("owner", "old"), service.resourcePolicyTags(parent, REGION));
        assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
        assertEquals(DENY, service.getResourcePolicy(child, REGION));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void consumerTagAndDeregisterSerializeInEitherOrder(boolean deleteFirst) throws Exception {
        AtomicBoolean parked = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorage<String, KinesisConsumer> consumers = new InMemoryStorage<>() {
            @Override
            public void putAllAtomically(Map<String, KinesisConsumer> entries) {
                if (!deleteFirst && parked.get()) {
                    entered.countDown();
                    await(release);
                }
                super.putAllAtomically(entries);
            }

            @Override
            public void delete(String key) {
                if (deleteFirst && parked.get()) {
                    entered.countDown();
                    await(release);
                }
                super.delete(key);
            }
        };
        KinesisService service = new KinesisService(new InMemoryStorage<>(), consumers, regions, clock);
        String parent = service.createStream("target", 1, REGION).getStreamArn();
        String child = service.registerStreamConsumer(parent, "child", Map.of("owner", "old"), REGION).getConsumerArn();
        service.putResourcePolicy(parent, POLICY, REGION);
        service.putResourcePolicy(child, DENY, REGION);
        parked.set(true);
        Runnable tagging = () -> service.tagResource(child, Map.of("owner", "new"), REGION);
        Runnable deleting = () -> service.deregisterStreamConsumer(null, null, child, REGION);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(deleteFirst ? deleting : tagging);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                CountDownLatch secondStarted = new CountDownLatch(1);
                Future<?> second = executor.submit(() -> {
                    secondStarted.countDown();
                    if (deleteFirst) {
                        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, tagging::run).getErrorCode());
                    } else {
                        deleting.run();
                    }
                });
                assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertTrue(consumers.keys().isEmpty());
        assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
        assertThrows(AwsException.class, () -> service.getResourcePolicy(child, REGION));
        String recreated = service.registerStreamConsumer(parent, "child", REGION).getConsumerArn();
        assertEquals(child, recreated);
        assertEquals("{}", service.getResourcePolicy(recreated, REGION));
        assertEquals(Map.of(), service.listTagsForResource(recreated, REGION));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}

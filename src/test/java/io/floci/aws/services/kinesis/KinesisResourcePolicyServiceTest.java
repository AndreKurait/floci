package io.floci.aws.services.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kinesis.model.KinesisConsumer;
import io.floci.aws.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class KinesisResourcePolicyServiceTest {
    static final String REGION = "us-east-1";
    static final String ACCOUNT = "111111111111";
    static final String POLICY = " {\n \"Version\":\"2012-10-17\", \"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"AWS\":\"222222222222\"},\"Action\":\"kinesis:GetRecords\",\"Resource\":\"*\"}]} \n";
    static final String DENY = POLICY.replace("Allow", "Deny");
    private final InMemoryStorage<String, KinesisStream> streams = new InMemoryStorage<>();
    private final InMemoryStorage<String, KinesisConsumer> consumers = new InMemoryStorage<>();
    private final RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
    private final KinesisService service = new KinesisService(streams, consumers, regions,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void policySizeUsesUtf8BytesAndPreservesThePreviousDocument() {
        String arn = service.createStream("target", 1, REGION).getStreamArn();
        String policy = POLICY.replace("\"Effect\"", "\"Sid\":\"é\",\"Effect\"");
        String limit = policy + " ".repeat(20 * 1024 - policy.getBytes(StandardCharsets.UTF_8).length);
        service.putResourcePolicy(arn, limit, REGION);
        assertEquals(limit, service.getResourcePolicy(arn, REGION));
        AwsException error = assertThrows(AwsException.class,
                () -> service.putResourcePolicy(arn, limit + " ", REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(limit, service.getResourcePolicy(arn, REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"*\"", "\"kinesis:*\"", "[\"kinesis:GetRecords\",\"kinesis:Get*\"]"})
    void wildcardActionsCannotReplaceAStoredPolicy(String action) {
        String arn = service.createStream("target", 1, REGION).getStreamArn();
        service.putResourcePolicy(arn, POLICY, REGION);
        String invalid = POLICY.replace("\"kinesis:GetRecords\"", action);
        AwsException error = assertThrows(AwsException.class,
                () -> service.putResourcePolicy(arn, invalid, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(POLICY, service.getResourcePolicy(arn, REGION));
    }

    @Test
    void registrationRejectsMissingAndDeletedParentsWithoutPublishingConsumers() {
        String arn = regions.buildArn("kinesis", REGION, "stream/target");
        AwsException absent = assertThrows(AwsException.class,
                () -> service.registerStreamConsumer(arn, "child", REGION));
        assertEquals("ResourceNotFoundException", absent.getErrorCode());
        assertTrue(consumers.isEmpty());
        service.createStream("target", 1, REGION);
        service.deleteStream("target", REGION);
        AwsException deleted = assertThrows(AwsException.class,
                () -> service.registerStreamConsumer(arn, "child", REGION));
        assertEquals("ResourceNotFoundException", deleted.getErrorCode());
        assertTrue(consumers.isEmpty());
    }

    @Test
    void legacyTagsUseStoredOwnershipForNondefaultAccountsWithoutMigration() {
        AccountAwareStorageBackend<KinesisStream> scoped =
                new AccountAwareStorageBackend<>(streams, null, "999999999999");
        AccountAwareStorageBackend<KinesisConsumer> scopedConsumers =
                new AccountAwareStorageBackend<>(consumers, null, "999999999999");
        KinesisService local = new KinesisService(scoped, scopedConsumers, regions);
        String arn = service.createStream("target", 1, REGION).getStreamArn();
        String child = service.registerStreamConsumer(arn, "child", Map.of("scope", "consumer"), REGION).getConsumerArn();
        service.addTagsToStream("target", Map.of("scope", "stream"), REGION);
        assertEquals(Map.of("scope", "stream"), local.resourcePolicyTags(arn, REGION));
        assertEquals(Map.of("scope", "consumer"), local.resourcePolicyTags(child, REGION));
        assertEquals(Set.of(REGION + "::target"), streams.keys());
        assertEquals(Set.of(REGION + "::" + child), consumers.keys());
        KinesisStream stream = streams.get(REGION + "::target").orElseThrow();
        stream.setAccountId("222222222222");
        assertThrows(AwsException.class, () -> local.resourcePolicyTags(arn, REGION));
        assertSame(stream, streams.get(REGION + "::target").orElseThrow());
    }

    @Test
    void exactDocumentsAreIndependentForParentChildrenAndSibling() {
        String parent = service.createStream("target", 1, REGION).getStreamArn();
        String sibling = service.createStream("sibling", 1, REGION).getStreamArn();
        String child = service.registerStreamConsumer(parent, "child", Map.of("owner", "child"), REGION).getConsumerArn();
        service.addTagsToStream("target", Map.of("owner", "stream"), REGION);
        assertEquals("{}", service.getResourcePolicy(parent, REGION));
        assertEquals("{}", service.getResourcePolicy(child, REGION));
        service.putResourcePolicy(parent, POLICY, REGION);
        service.putResourcePolicy(child, DENY, REGION);
        assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
        assertEquals(DENY, service.getResourcePolicy(child, REGION));
        assertEquals("{}", service.getResourcePolicy(sibling, REGION));
        assertEquals(Map.of("owner", "stream"), service.resourcePolicyTags(parent, REGION));
        assertEquals(Map.of("owner", "child"), service.resourcePolicyTags(child, REGION));
        service.deleteResourcePolicy(child, REGION);
        assertEquals("{}", service.getResourcePolicy(child, REGION));
        assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
        AwsException absent = assertThrows(AwsException.class, () -> service.deleteResourcePolicy(child, REGION));
        assertEquals("ResourceNotFoundException", absent.getErrorCode());
        assertEquals("No resource policy found for resource ARN " + child + ".", absent.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "[]", "null", "{\"Statement\":[]}",
            "{\"Statement\":{\"Effect\":\"Permit\",\"Principal\":\"*\",\"Action\":\"*\"}}",
            "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":\"*\"}}",
            "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":[],\"Action\":\"*\"}}"})
    void invalidPolicyCannotChangeStoredDocument(String policy) {
        String parent = service.createStream("target", 1, REGION).getStreamArn();
        service.putResourcePolicy(parent, POLICY, REGION);
        AwsException error = assertThrows(AwsException.class, () -> service.putResourcePolicy(parent, policy, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
        assertThrows(AwsException.class, () -> service.putResourcePolicy(parent, POLICY + " {}", REGION));
        assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
    }

    @Test
    void foreignSelectorsMissingResourcesAndMalformedArnsCannotResolveSameNamedParent() {
        String arn = service.createStream("target", 1, REGION).getStreamArn();
        service.putResourcePolicy(arn, POLICY, REGION);
        for (String other : new String[]{arn.replace(ACCOUNT, "222222222222"), arn.replace(REGION, "us-west-2"),
                arn.replace("arn:aws:", "arn:aws-cn:"), arn + "/consumer/missing:1",
                arn.replace("stream/target", "stream/missing"), "target", arn + "/wrong"}) {
            assertThrows(AwsException.class, () -> service.getResourcePolicy(other, REGION));
            assertThrows(AwsException.class, () -> service.putResourcePolicy(other, DENY, REGION));
            assertThrows(AwsException.class, () -> service.deleteResourcePolicy(other, REGION));
        }
        assertEquals(POLICY, service.getResourcePolicy(arn, REGION));
    }

    @Test
    void detachedReplacementPreservesEveryUnrelatedStreamFieldAndRecords() throws Exception {
        String arn = service.createStream("target", 2, "ON_DEMAND", 2048, REGION).getStreamArn();
        service.putRecord("target", new byte[]{1, 2, 3}, "key", REGION);
        service.addTagsToStream("target", Map.of("keep", "value"), REGION);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        KinesisStream old = streams.get(REGION + "::target").orElseThrow();
        String before = json.writeValueAsString(old);
        service.putResourcePolicy(arn, POLICY, REGION);
        KinesisStream replacement = streams.get(REGION + "::target").orElseThrow();
        assertNotSame(old, replacement);
        assertEquals(before, json.writeValueAsString(old));
        assertEquals(before, json.writeValueAsString(replacement.withResourcePolicies(Map.of())));
        assertThrows(UnsupportedOperationException.class, () -> replacement.getResourcePolicies().put(arn, DENY));
    }

    @Test
    void deletedParentGenerationRejectsRetainedConsumerEvenWithFixedClockAndIdenticalArn() {
        String arn = service.createStream("target", 1, REGION).getStreamArn();
        String consumer = service.registerStreamConsumer(arn, "child", REGION).getConsumerArn();
        service.putResourcePolicy(consumer, POLICY, REGION);
        service.deleteStream("target", REGION);
        assertTrue(consumers.get(REGION + "::" + consumer).isPresent(), "Existing consumer store retains the old record");
        service.createStream("target", 1, REGION);
        assertEquals("{}", service.getResourcePolicy(arn, REGION));
        assertThrows(AwsException.class, () -> service.getResourcePolicy(consumer, REGION));
        assertThrows(AwsException.class, () -> service.putResourcePolicy(consumer, DENY, REGION));
        String replacement = service.registerStreamConsumer(arn, "child", REGION).getConsumerArn();
        assertEquals(consumer, replacement, "Fixed clock deliberately exercises an ARN collision");
        assertEquals("{}", service.getResourcePolicy(replacement, REGION));
        service.putResourcePolicy(replacement, DENY, REGION);
        service.registerStreamConsumer(arn, "child", REGION);
        assertEquals("{}", service.getResourcePolicy(replacement, REGION));
    }

    @Test
    void deregisterClearsOnlyChildPolicyAndReregisterNeverRestoresIt() {
        String arn = service.createStream("target", 1, REGION).getStreamArn();
        String child = service.registerStreamConsumer(arn, "child", REGION).getConsumerArn();
        service.putResourcePolicy(arn, POLICY, REGION);
        service.putResourcePolicy(child, DENY, REGION);
        service.deregisterStreamConsumer(null, null, child, REGION);
        assertThrows(AwsException.class, () -> service.getResourcePolicy(child, REGION));
        assertEquals(POLICY, service.getResourcePolicy(arn, REGION));
        service.registerStreamConsumer(arn, "child", REGION);
        assertEquals("{}", service.getResourcePolicy(child, REGION));
    }

    @Test
    void failedConsumerDeleteConservativelyKeepsTheConsumerWithoutItsPolicy() {
        InMemoryStorage<String, KinesisConsumer> failing = new InMemoryStorage<>() {
            @Override
            public void delete(String key) { throw new IllegalStateException("owned child deletion failure"); }
        };
        KinesisService local = new KinesisService(streams, failing, regions);
        String arn = local.createStream("target", 1, REGION).getStreamArn();
        String child = local.registerStreamConsumer(arn, "child", REGION).getConsumerArn();
        local.putResourcePolicy(child, POLICY, REGION);
        assertThrows(IllegalStateException.class, () -> local.deregisterStreamConsumer(null, null, child, REGION));
        assertEquals(child, local.describeStreamConsumer(null, null, child, REGION).getConsumerArn());
        assertEquals("{}", local.getResourcePolicy(child, REGION));
    }

    @Test
    void legacyOwnershipIsCheckedBeforeMigrationAndIamTagsNeverMigrate() {
        AccountAwareStorageBackend<KinesisStream> scoped = new AccountAwareStorageBackend<>(streams, null, ACCOUNT);
        KinesisService local = new KinesisService(scoped, consumers, regions);
        String arn = regions.buildArn("kinesis", REGION, "stream/legacy");
        KinesisStream legacy = new KinesisStream("legacy", arn);
        legacy.setResourcePolicyGeneration(null);
        legacy.setTags(Map.of("owner", "legacy"));
        streams.put(REGION + "::legacy", legacy);
        assertEquals(Map.of("owner", "legacy"), local.resourcePolicyTags(arn, REGION));
        assertEquals(1, streams.keys().size());
        assertTrue(scoped.getForAccount(ACCOUNT, REGION + "::legacy").isEmpty());
        local.putResourcePolicy(arn, POLICY, REGION);
        assertFalse(streams.keys().contains(REGION + "::legacy"));
        assertEquals(POLICY, local.getResourcePolicy(arn, REGION));
        local.deleteStream("legacy", REGION);
        assertThrows(AwsException.class, () -> local.getResourcePolicy(arn, REGION));
        legacy.setStreamArn(arn.replace(ACCOUNT, "222222222222"));
        streams.put(REGION + "::legacy", legacy);
        assertThrows(AwsException.class, () -> local.getResourcePolicy(arn, REGION));
        assertTrue(scoped.getForAccount(ACCOUNT, REGION + "::legacy").isEmpty());
        assertSame(legacy, streams.get(REGION + "::legacy").orElseThrow());
    }

    @Test
    void concurrentParentChildUpdatesComposeWithRecordAndTagWrites() throws Exception {
        String arn = service.createStream("target", 1, REGION).getStreamArn();
        String child = service.registerStreamConsumer(arn, "child", REGION).getConsumerArn();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> first = executor.submit(() -> { await(start); service.putResourcePolicy(arn, POLICY, REGION); });
            Future<?> second = executor.submit(() -> { await(start); service.putResourcePolicy(child, DENY, REGION); });
            Future<?> third = executor.submit(() -> {
                await(start);
                service.addTagsToStream("target", Map.of("concurrent", "kept"), REGION);
                service.putRecord("target", new byte[]{42}, "key", REGION);
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS); third.get(10, TimeUnit.SECONDS);
        }
        assertEquals(POLICY, service.getResourcePolicy(arn, REGION));
        assertEquals(DENY, service.getResourcePolicy(child, REGION));
        assertEquals(Map.of("concurrent", "kept"), service.resourcePolicyTags(arn, REGION));
        assertEquals(1, service.describeStream("target", REGION).getShards().getFirst().recordCount());
    }

    @Test
    void inactiveStreamsKeepIamTagContextButRefuseAllThreePolicyOperationsWithoutMutation() {
        String arn = service.createStream("target", 1, REGION).getStreamArn();
        service.addTagsToStream("target", Map.of("owner", "kept"), REGION);
        service.putResourcePolicy(arn, POLICY, REGION);
        KinesisStream stream = service.describeStream("target", REGION);
        stream.setStreamStatus("CREATING");
        assertEquals(Map.of("owner", "kept"), service.resourcePolicyTags(arn, REGION));
        AwsException get = assertThrows(AwsException.class, () -> service.getResourcePolicy(arn, REGION));
        AwsException put = assertThrows(AwsException.class, () -> service.putResourcePolicy(arn, DENY, REGION));
        AwsException delete = assertThrows(AwsException.class, () -> service.deleteResourcePolicy(arn, REGION));
        for (AwsException error : new AwsException[]{get, put, delete}) {
            assertEquals("ResourceInUseException", error.getErrorCode());
            assertEquals("Stream target under account " + ACCOUNT
                    + " not ACTIVE or UPDATING, instead in state CREATING", error.getMessage());
        }
        assertEquals(POLICY, stream.getResourcePolicies().get(arn));
        stream.setStreamStatus("UPDATING");
        assertEquals(POLICY, service.getResourcePolicy(arn, REGION));
        service.putResourcePolicy(arn, DENY, REGION);
        assertEquals(DENY, service.getResourcePolicy(arn, REGION));
        service.deleteResourcePolicy(arn, REGION);
        assertEquals("{}", service.getResourcePolicy(arn, REGION));
    }

    @Test
    void checkedConsumerSelectorRejectsMalformedArnBeforeAnyDeletion() {
        String arn = service.createStream("target", 1, REGION).getStreamArn();
        String child = service.registerStreamConsumer(arn, "child", REGION).getConsumerArn();
        service.putResourcePolicy(child, POLICY, REGION);
        for (String invalid : new String[]{"invalid", arn, arn.replace(ACCOUNT, "short"), child + "/suffix"}) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.deregisterStreamConsumer(null, null, invalid, REGION));
            assertEquals("InvalidArgumentException", error.getErrorCode());
        }
        assertEquals(POLICY, service.getResourcePolicy(child, REGION));
    }

    @Test
    void failedConsumerRegistrationClearsPolicyWithoutReplacingExistingConsumer() {
        AtomicBoolean fail = new AtomicBoolean();
        InMemoryStorage<String, KinesisConsumer> failing = new InMemoryStorage<>() {
            @Override
            public void put(String key, KinesisConsumer value) {
                if (fail.get()) {
                    throw new IllegalStateException("owned child publication failure");
                }
                super.put(key, value);
            }
        };
        KinesisService local = new KinesisService(streams, failing, regions,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        String arn = local.createStream("target", 1, REGION).getStreamArn();
        KinesisConsumer original = local.registerStreamConsumer(arn, "child", REGION);
        local.putResourcePolicy(original.getConsumerArn(), POLICY, REGION);
        fail.set(true);
        assertThrows(IllegalStateException.class, () -> local.registerStreamConsumer(arn, "child", REGION));
        assertSame(original, local.describeStreamConsumer(null, null, original.getConsumerArn(), REGION));
        assertEquals("{}", local.getResourcePolicy(original.getConsumerArn(), REGION));
    }

    @Test
    void parentDeleteWaitsForParkedPolicyCommitAndCannotBeResurrected() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorage<String, KinesisStream> parking = new InMemoryStorage<>() {
            @Override
            public void putAllAtomically(Map<String, KinesisStream> entries) {
                entered.countDown();
                await(release);
                super.putAllAtomically(entries);
            }
        };
        KinesisService local = new KinesisService(parking, consumers, regions);
        String arn = local.createStream("target", 1, REGION).getStreamArn();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> writer = executor.submit(() -> local.putResourcePolicy(arn, POLICY, REGION));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                CountDownLatch deleting = new CountDownLatch(1);
                Future<?> deletion = executor.submit(() -> {
                    deleting.countDown();
                    local.deleteStream("target", REGION);
                });
                assertTrue(deleting.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                writer.get(10, TimeUnit.SECONDS);
                deletion.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertTrue(parking.keys().isEmpty());
        assertThrows(AwsException.class, () -> local.putResourcePolicy(arn, DENY, REGION));
        assertTrue(parking.keys().isEmpty());
    }

    @Test
    void concurrentCreatesCannotReplaceThePublishedStreamGeneration() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        InMemoryStorage<String, KinesisStream> parking = new InMemoryStorage<>() {
            @Override
            public Optional<KinesisStream> get(String key) {
                if (firstRead.compareAndSet(true, false)) {
                    entered.countDown();
                    await(release);
                }
                return super.get(key);
            }
        };
        KinesisService local = new KinesisService(parking, consumers, regions);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<KinesisStream> first = executor.submit(() -> local.createStream("target", 1, REGION));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                CountDownLatch secondStarted = new CountDownLatch(1);
                Future<AwsException> second = executor.submit(() -> {
                    secondStarted.countDown();
                    return assertThrows(AwsException.class, () -> local.createStream("target", 1, REGION));
                });
                assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                KinesisStream created = first.get(10, TimeUnit.SECONDS);
                assertEquals("ResourceInUseException", second.get(10, TimeUnit.SECONDS).getErrorCode());
                assertSame(created, local.describeStream("target", REGION));
                local.putResourcePolicy(created.getStreamArn(), POLICY, REGION);
                assertEquals(created.getResourcePolicyGeneration(), local.describeStream("target", REGION)
                        .getResourcePolicyGeneration());
                assertEquals(POLICY, local.getResourcePolicy(created.getStreamArn(), REGION));
            } finally {
                release.countDown();
            }
        }
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

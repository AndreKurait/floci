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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.floci.aws.services.kinesis.KinesisResourcePolicyServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class KinesisPolicyReviewServiceTest {
    private static final String OTHER = "222222222222";
    private final InMemoryStorage<String, KinesisStream> streams = new InMemoryStorage<>();
    private final InMemoryStorage<String, KinesisConsumer> consumers = new InMemoryStorage<>();
    private final RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
    private final KinesisService service = new KinesisService(streams, consumers, regions);

    @ParameterizedTest
    @MethodSource("io.floci.aws.services.kinesis.KinesisPolicyReviewCases#rejectedPolicies")
    void unsupportedPolicyLeavesBothDocumentsGenerationRecordsAndTagsUntouched(String rejected) throws Exception {
        String parent = service.createStream("review", 1, REGION).getStreamArn();
        String child = service.registerStreamConsumer(parent, "child", Map.of("owner", "child"), REGION).getConsumerArn();
        service.putRecord("review", new byte[]{7}, "key", REGION);
        service.addTagsToStream("review", Map.of("owner", "parent"), REGION);
        service.putResourcePolicy(parent, POLICY, REGION);
        service.putResourcePolicy(child, DENY, REGION);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        KinesisStream before = streams.get(REGION + "::review").orElseThrow();
        String serialized = mapper.writeValueAsString(before);
        for (String target : new String[]{parent, child}) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.putResourcePolicy(target, rejected, REGION));
            assertEquals("InvalidArgumentException", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
            assertSame(before, streams.get(REGION + "::review").orElseThrow());
            assertEquals(serialized, mapper.writeValueAsString(before));
        }
        assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
        assertEquals(DENY, service.getResourcePolicy(child, REGION));
    }

    @Test
    void localUtf8BoundaryCountsSubmittedWhitespaceAndKeepsSupportedPrincipalArrays() {
        String arn = service.createStream("boundary", 1, REGION).getStreamArn();
        String document = KinesisPolicyReviewCases.supportedPrincipals()
                .replace("\"Version\"", "\"Id\":\"é中文\",\"Version\"");
        String exact = document + " ".repeat(20_480 - document.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(20_480, exact.getBytes(StandardCharsets.UTF_8).length);
        service.putResourcePolicy(arn, exact, REGION);
        assertEquals(exact, service.getResourcePolicy(arn, REGION));
        AwsException error = assertThrows(AwsException.class, () -> service.putResourcePolicy(arn, exact + " ", REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(exact, service.getResourcePolicy(arn, REGION));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownedNondefaultLegacyTagsAreReadWithoutMigrationForStreamAndConsumer(boolean child) throws Exception {
        AccountAwareStorageBackend<KinesisStream> scopedStreams =
                new AccountAwareStorageBackend<>(streams, null, OTHER);
        AccountAwareStorageBackend<KinesisConsumer> scopedConsumers =
                new AccountAwareStorageBackend<>(consumers, null, OTHER);
        KinesisService local = new KinesisService(scopedStreams, scopedConsumers, regions);
        String parent = regions.buildArn("kinesis", REGION, "stream/legacy");
        String consumerArn = parent + "/consumer/child:1";
        KinesisStream stream = new KinesisStream("legacy", parent);
        stream.setAccountId(ACCOUNT);
        stream.setTags(Map.of("owner", "parent"));
        KinesisConsumer consumer = new KinesisConsumer("child", consumerArn, parent);
        consumer.setPolicyStreamGeneration(stream.getResourcePolicyGeneration());
        consumer.setTags(Map.of("owner", "child"));
        streams.put(REGION + "::legacy", stream);
        consumers.put(REGION + "::" + consumerArn, consumer);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String before = mapper.writeValueAsString(stream) + mapper.writeValueAsString(consumer);
        assertEquals(Map.of("owner", child ? "child" : "parent"),
                local.resourcePolicyTags(child ? consumerArn : parent, REGION));
        assertEquals(Set.of(REGION + "::legacy"), streams.keys());
        assertEquals(Set.of(REGION + "::" + consumerArn), consumers.keys());
        assertEquals(before, mapper.writeValueAsString(stream) + mapper.writeValueAsString(consumer));
        consumer.setPolicyStreamGeneration("foreign-generation");
        assertThrows(AwsException.class, () -> local.resourcePolicyTags(consumerArn, REGION));
        stream.setAccountId(OTHER);
        assertThrows(AwsException.class, () -> local.resourcePolicyTags(parent, REGION));
        assertEquals(Set.of(REGION + "::legacy"), streams.keys());
    }

    @Test
    void conflictingScopedOwnerPreventsFallbackToAnOtherwiseOwnedLegacyRow() {
        AccountAwareStorageBackend<KinesisStream> scoped =
                new AccountAwareStorageBackend<>(streams, null, ACCOUNT);
        KinesisService local = new KinesisService(scoped, consumers, regions);
        String parent = regions.buildArn("kinesis", REGION, "stream/legacy");
        KinesisStream legacy = new KinesisStream("legacy", parent);
        legacy.setTags(Map.of("owner", "must-not-leak"));
        KinesisStream conflict = new KinesisStream("legacy", parent.replace(ACCOUNT, OTHER));
        streams.put(REGION + "::legacy", legacy);
        streams.put(ACCOUNT + "/" + REGION + "::legacy", conflict);
        assertThrows(AwsException.class, () -> local.resourcePolicyTags(parent, REGION));
        assertThrows(AwsException.class, () -> local.getResourcePolicy(parent, REGION));
        assertSame(legacy, streams.get(REGION + "::legacy").orElseThrow());
        assertSame(conflict, streams.get(ACCOUNT + "/" + REGION + "::legacy").orElseThrow());
    }

    @Test
    void registrationForMissingOrForeignParentDoesNotPublishConsumer() {
        String parent = regions.buildArn("kinesis", REGION, "stream/absent");
        for (String target : new String[]{parent, parent.replace(ACCOUNT, OTHER),
                parent.replace(REGION, "us-west-2"), parent.replace("arn:aws:", "arn:aws-cn:")}) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.registerStreamConsumer(target, "child", REGION));
            assertEquals("ResourceNotFoundException", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
            assertTrue(consumers.keys().isEmpty());
        }
        String valid = service.createStream("absent", 1, REGION).getStreamArn();
        KinesisConsumer consumer = service.registerStreamConsumer(valid, "child", Map.of("owner", "kept"), REGION);
        assertEquals(Map.of("owner", "kept"), consumer.getTags());
        assertEquals(service.describeStream("absent", REGION).getResourcePolicyGeneration(),
                consumer.getPolicyStreamGeneration());
    }

    @Test
    void deleteWinningTheParentLockPreventsWaitingRegistrationFromPublishingAnOrphan() throws Exception {
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorage<String, KinesisStream> parking = new InMemoryStorage<>() {
            @Override
            public void delete(String key) {
                deleting.countDown();
                await(release);
                super.delete(key);
            }
        };
        KinesisService local = new KinesisService(parking, consumers, regions);
        String parent = local.createStream("review", 1, REGION).getStreamArn();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> deletion = executor.submit(() -> local.deleteStream("review", REGION));
            try {
                assertTrue(deleting.await(10, TimeUnit.SECONDS));
                CountDownLatch registering = new CountDownLatch(1);
                Future<AwsException> registration = executor.submit(() -> {
                    registering.countDown();
                    return assertThrows(AwsException.class,
                            () -> local.registerStreamConsumer(parent, "child", REGION));
                });
                assertTrue(registering.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> registration.get(100, TimeUnit.MILLISECONDS));
                assertTrue(consumers.keys().isEmpty());
                release.countDown();
                deletion.get(10, TimeUnit.SECONDS);
                assertEquals("ResourceNotFoundException", registration.get(10, TimeUnit.SECONDS).getErrorCode());
            } finally {
                release.countDown();
            }
        }
        assertTrue(parking.keys().isEmpty());
        assertTrue(consumers.keys().isEmpty());
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

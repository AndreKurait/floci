package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.github.hectorvent.floci.services.kinesis.KinesisResourcePolicyServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class KinesisResourcePolicyPersistenceTest {
    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void streamConsumerPoliciesTagsAndDeletionSurviveReload(String mode, @TempDir Path directory) {
        RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        String parent;
        String consumer;
        try {
            KinesisService service = new KinesisService(first, regions);
            parent = service.createStream("saved", 1, REGION).getStreamArn();
            consumer = service.registerStreamConsumer(parent, "saved-child", Map.of("owner", "child"), REGION)
                    .getConsumerArn();
            service.addTagsToStream("saved", Map.of("owner", "parent"), REGION);
            service.putResourcePolicy(parent, POLICY, REGION);
            service.putResourcePolicy(consumer, DENY, REGION);
            service.putRecord("saved", new byte[]{1, 2, 3}, "key", REGION);
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            KinesisService service = new KinesisService(second, regions);
            assertEquals(POLICY, service.getResourcePolicy(parent, REGION));
            assertEquals(DENY, service.getResourcePolicy(consumer, REGION));
            assertEquals(Map.of("owner", "parent"), service.resourcePolicyTags(parent, REGION));
            assertEquals(Map.of("owner", "child"), service.resourcePolicyTags(consumer, REGION));
            assertEquals(1, service.describeStream("saved", REGION).getShards().getFirst().recordCount());
            service.deleteResourcePolicy(parent, REGION);
            service.deregisterStreamConsumer(null, null, consumer, REGION);
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            KinesisService service = new KinesisService(third, regions);
            assertEquals("{}", service.getResourcePolicy(parent, REGION));
            assertThrows(AwsException.class, () -> service.getResourcePolicy(consumer, REGION));
            service.deleteStream("saved", REGION);
            service.createStream("saved", 1, REGION);
            assertEquals("{}", service.getResourcePolicy(parent, REGION));
        } finally {
            third.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedAtomicPutOrDeletePreservesMemoryAndCommittedSnapshot(boolean delete, @TempDir Path directory)
            throws IOException {
        Path snapshot = directory.resolve("streams.json");
        StorageBackend<String, KinesisStream> raw = new PersistentStorage<>(snapshot, new TypeReference<>() {});
        raw.load();
        AccountAwareStorageBackend<KinesisStream> scoped = new AccountAwareStorageBackend<>(raw, null, ACCOUNT);
        RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
        KinesisService service = new KinesisService(scoped, new InMemoryStorage<>(), regions);
        String arn = service.createStream("target", 1, REGION).getStreamArn();
        service.putResourcePolicy(arn, POLICY, REGION);
        service.putRecord("target", new byte[]{9}, "key", REGION);
        KinesisStream old = scoped.getForAccount(ACCOUNT, REGION + "::target").orElseThrow();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String before = mapper.writeValueAsString(old);
        byte[] committed = Files.readAllBytes(snapshot);
        Files.delete(snapshot);
        Files.createDirectory(snapshot);
        Files.writeString(snapshot.resolve("obstacle"), "owned obstruction");
        try {
            assertThrows(UncheckedIOException.class, () -> {
                if (delete) {
                    service.deleteResourcePolicy(arn, REGION);
                } else {
                    service.putResourcePolicy(arn, DENY, REGION);
                }
            });
            assertSame(old, scoped.getForAccount(ACCOUNT, REGION + "::target").orElseThrow());
            assertEquals(before, mapper.writeValueAsString(old));
            assertEquals(POLICY, service.getResourcePolicy(arn, REGION));
        } finally {
            Files.delete(snapshot.resolve("obstacle"));
            Files.delete(snapshot);
            Files.write(snapshot, committed);
        }
        StorageBackend<String, KinesisStream> restored = new PersistentStorage<>(snapshot, new TypeReference<>() {});
        restored.load();
        KinesisService reader = new KinesisService(new AccountAwareStorageBackend<>(restored, null, ACCOUNT),
                new InMemoryStorage<>(), regions);
        assertEquals(POLICY, reader.getResourcePolicy(arn, REGION));
        assertArrayEquals(committed, Files.readAllBytes(snapshot));
        assertEquals(before, mapper.writeValueAsString(reader.describeStream("target", REGION)));
    }
}

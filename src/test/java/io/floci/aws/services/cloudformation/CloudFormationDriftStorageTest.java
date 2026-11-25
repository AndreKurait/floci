package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.DriftStorageFailures;
import io.floci.aws.core.storage.HybridStorage;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.cloudformation.model.StackDriftRecord;
import io.floci.aws.services.cloudformation.provisioners.CfnDriftProjection;
import io.floci.aws.services.cloudformation.provisioners.CfnResourceProvisioner;
import io.floci.aws.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFormationDriftStorageTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String STACK = "arn:aws:cloudformation:us-east-1:123456789012:stack/owned/lifetime";
    private static final String TOPIC = "arn:aws:sns:us-east-1:123456789012:topic";
    private static final TypeReference<Map<String, StackDriftRecord>> TYPE = new TypeReference<>() {};
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void aggregateResultsAndDetectionHistorySurviveReloadWithoutChangingOldSnapshots(String mode) {
        StorageBackend<String, StackDriftRecord> backend = backend(mode);
        String first;
        String second;
        try {
            backend.load();
            Harness h = new Harness(backend);
            first = h.service.detectStack("owned", List.of(), REGION);
            StackDriftRecord before = h.store.getForAccount(ACCOUNT, REGION + ":" + STACK).orElseThrow();
            h.actual.set("changed");
            second = h.service.detectStack("owned", List.of(), REGION);
            assertEquals("IN_SYNC", before.detections().get(first).stackStatus());
            assertEquals("IN_SYNC", before.resources().get("Topic").status());
            assertFalse(before.detections().containsKey(second));
            assertEquals("DRIFTED", h.service.detection(second, REGION).stackStatus());
            assertEquals("IN_SYNC", h.service.detection(first, REGION).stackStatus());
            assertEquals("MODIFIED", h.service.resources("owned", List.of(), 1, null, REGION).items().getFirst().status());
            backend.flush();
        } finally {
            close(backend);
        }
        if (!"memory".equals(mode)) {
            StorageBackend<String, StackDriftRecord> reloaded = backend(mode);
            try {
                reloaded.load();
                Harness h = new Harness(reloaded);
                assertEquals("IN_SYNC", h.service.detection(first, REGION).stackStatus());
                assertEquals("DRIFTED", h.service.detection(second, REGION).stackStatus());
                assertEquals(1, h.service.resources("owned", List.of("MODIFIED"), 1, null, REGION).items().size());
                byte[] bytes = new ObjectMapper().findAndRegisterModules().writeValueAsBytes(reloaded.scan(key -> true));
                h.service.resources("owned", List.of(), 1, null, REGION);
                h.service.detection(first, REGION);
                assertArrayEquals(bytes, new ObjectMapper().findAndRegisterModules().writeValueAsBytes(reloaded.scan(key -> true)));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            } finally {
                close(reloaded);
            }
        }
    }

    @Test
    void failedWalForcePublishesNeitherPartialResourcesNorDetectionAndPreservesLogBytes() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        WalStorage<String, StackDriftRecord> backend = DriftStorageFailures.wal(
                directory.resolve("snapshot.json"), directory.resolve("drift.wal"), fail);
        try {
            backend.load();
            Harness h = new Harness(backend);
            String before = h.service.detectStack("owned", List.of(), REGION);
            byte[] bytes = Files.readAllBytes(directory.resolve("drift.wal"));
            h.actual.set("changed");
            fail.set(true);
            assertThrows(RuntimeException.class, () -> h.service.detectStack("owned", List.of(), REGION));
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("drift.wal")));
            assertEquals(1, h.store.getForAccount(ACCOUNT, REGION + ":" + STACK).orElseThrow().detections().size());
            assertEquals("IN_SYNC", h.service.detection(before, REGION).stackStatus());
            assertEquals("IN_SYNC", h.service.resources("owned", List.of(), 1, null, REGION).items().getFirst().status());
            fail.set(false);
        } finally {
            close(backend);
        }
    }

    @Test
    void failedPersistentRenameLeavesOldRecordAndOriginalFileBytes() throws Exception {
        Path active = directory.resolve("active");
        Files.createDirectories(active);
        Path file = active.resolve("drift.json");
        PersistentStorage<String, StackDriftRecord> backend = new PersistentStorage<>(file, TYPE);
        backend.load();
        Harness h = new Harness(backend);
        String before = h.service.detectStack("owned", List.of(), REGION);
        byte[] bytes = Files.readAllBytes(file);
        Path preserved = directory.resolve("preserved");
        Files.move(active, preserved);
        Files.writeString(active, "owned obstruction");
        try {
            h.actual.set("changed");
            assertThrows(RuntimeException.class, () -> h.service.detectStack("owned", List.of(), REGION));
            assertEquals("IN_SYNC", h.service.detection(before, REGION).stackStatus());
            assertEquals("IN_SYNC", h.service.resources("owned", List.of(), 1, null, REGION).items().getFirst().status());
            assertArrayEquals(bytes, Files.readAllBytes(preserved.resolve("drift.json")));
        } finally {
            Files.delete(active);
            Files.move(preserved, active);
        }
    }

    @Test
    void foreignCorruptStoredAggregateIsRefusedWithoutRepairOrAbsence() {
        Harness h = new Harness(new InMemoryStorage<>());
        StackDriftRecord foreign = new StackDriftRecord("999999999999", REGION, STACK, 0, Map.of(), Map.of());
        h.store.putForAccount(ACCOUNT, REGION + ":" + STACK, foreign);
        assertThrows(AwsException.class, () -> h.service.resources("owned", List.of(), 1, null, REGION));
        assertThrows(AwsException.class, () -> h.service.detectStack("owned", List.of(), REGION));
        assertEquals(foreign, h.store.getForAccount(ACCOUNT, REGION + ":" + STACK).orElseThrow());
    }

    @Test
    void unsupportedProviderOrLiteralBoundaryDoesNotCreateAnInSyncResult() {
        Harness h = new Harness(new InMemoryStorage<>());
        when(h.providers.forType(anyString())).thenReturn(Optional.empty());
        assertThrows(AwsException.class, () -> h.service.detectStack("owned", List.of(), REGION));
        assertTrue(h.store.keysForAccount(ACCOUNT).isEmpty());
    }

    private StorageBackend<String, StackDriftRecord> backend(String mode) {
        return switch (mode) {
            case "memory" -> new InMemoryStorage<>();
            case "persistent" -> new PersistentStorage<>(directory.resolve("drift.json"), TYPE);
            case "hybrid" -> new HybridStorage<>(directory.resolve("drift.json"), TYPE, 3_600_000);
            case "wal" -> new WalStorage<>(directory.resolve("drift.json"), directory.resolve("drift.wal"), TYPE, 3_600_000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void close(StorageBackend<String, StackDriftRecord> store) {
        if (store instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        }
        if (store instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        }
    }

    private static final class Harness {
        private final AccountAwareStorageBackend<StackDriftRecord> store;
        private final AtomicReference<String> actual = new AtomicReference<>("before");
        private final CloudFormationResourceRegistry providers = mock(CloudFormationResourceRegistry.class);
        private final CloudFormationDriftService service;

        private Harness(StorageBackend<String, StackDriftRecord> backend) {
            store = new AccountAwareStorageBackend<>(backend, null, ACCOUNT);
            CloudFormationService stacks = mock(CloudFormationService.class);
            CloudFormationDriftSnapshot.Resource resource = new CloudFormationDriftSnapshot.Resource(
                    "Topic", TOPIC, "AWS::SNS::Topic", "CREATE_COMPLETE");
            CloudFormationDriftSnapshot snapshot = new CloudFormationDriftSnapshot("owned", ACCOUNT, REGION,
                    "owned", STACK, Instant.EPOCH, null, "CREATE_COMPLETE",
                    "{\"Resources\":{\"Topic\":{\"Type\":\"AWS::SNS::Topic\",\"Properties\":{\"DisplayName\":\"before\"}}}}",
                    Map.of(), Map.of(), Map.of("Topic", resource), null, null, false);
            when(stacks.captureDriftStack("owned", REGION, ACCOUNT)).thenReturn(snapshot);
            doAnswer(call -> {
                call.getArgument(1, Runnable.class).run();
                return null;
            }).when(stacks).withCurrentDriftStack(any(), any());
            CfnResourceProvisioner provider = mock(CfnResourceProvisioner.class);
            when(providers.forType("AWS::SNS::Topic")).thenReturn(Optional.of(provider));
            when(provider.readDrift(any(), any(), anyString(), anyString())).thenAnswer(call ->
                    new CfnDriftProjection(resource, Map.of("DisplayName", "before"), Map.of("DisplayName", actual.get()),
                            false, new CfnDriftProjection.Permission("sns:GetTopicAttributes", TOPIC, ACCOUNT, null, Map.of())));
            service = new CloudFormationDriftService(stacks, providers, new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), store);
        }
    }
}

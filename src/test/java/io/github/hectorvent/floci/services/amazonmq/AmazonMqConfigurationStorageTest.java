package io.github.hectorvent.floci.services.amazonmq;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.amazonmq.model.ConfigurationState;
import io.github.hectorvent.floci.services.amazonmq.model.ConfigurationState.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AmazonMqConfigurationStorageTest {

    private static final String ACCOUNT = "000000000000";
    private static final TypeReference<Map<String, ConfigurationState>> TYPE = new TypeReference<>() {};
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void reopenRetainsWholeLineagesTagsPartitionsAndRetiredIdentity(String mode) {
        StorageBackend<String, ConfigurationState> backend = backend(mode);
        backend.load();
        Configuration expected;
        String retired;
        try {
            AmazonMqConfigurationService service = service(backend, "cn-north-1");
            Configuration a = create(service, "stored");
            service.update(a.id(), "aGVhcnRiZWF0ID0gMTIwCg==", "second");
            expected = service.update(a.id(), "aGVhcnRiZWF0ID0gNjAK", "third");
            retired = create(service, "retired").id();
            service.delete(retired);
            backend.flush();
        } finally {
            shutdown(backend);
        }
        StorageBackend<String, ConfigurationState> reopened = backend(mode);
        reopened.load();
        try {
            AmazonMqConfigurationService service = service(reopened, "cn-north-1");
            assertEquals(expected, service.describe(expected.id()));
            assertEquals(Map.of("owner", "stored"), service.tagsForAuthorization(expected.id()));
            assertEquals(409, assertThrows(AwsException.class,
                    () -> service.update(retired, "eA==", "refused")).getHttpStatus());
            assertEquals(404, assertThrows(AwsException.class,
                    () -> service(reopened, "us-east-1").describe(expected.id())).getHttpStatus());
        } finally {
            shutdown(reopened);
        }
    }

    @Test
    void persistentAtomicWriteRefusalPreservesMemoryAndLastDurableFile() throws Exception {
        PersistentStorage<String, ConfigurationState> backend = new PersistentStorage<>(directory.resolve("state.json"), TYPE);
        AmazonMqConfigurationService service = service(backend, "us-east-1");
        Configuration a = create(service, "persistent");
        Configuration before = service.update(a.id(), "eA==", "accepted");
        Path path = directory.resolve("state.json");
        Path retained = directory.resolve("retained.json");
        byte[] committed = Files.readAllBytes(path);
        Files.move(path, retained);
        Files.createDirectory(path);
        try {
            assertEquals(500, assertThrows(AwsException.class,
                    () -> service.update(a.id(), "eQ==", "must-not-publish")).getHttpStatus());
            assertEquals(before, service.describe(a.id()));
            assertArrayEquals(committed, Files.readAllBytes(retained));
        } finally {
            Files.delete(path);
            Files.move(retained, path);
        }
        PersistentStorage<String, ConfigurationState> reopened = new PersistentStorage<>(path, TYPE);
        reopened.load();
        assertEquals(before, service(reopened, "us-east-1").describe(a.id()));
    }

    @Test
    void walAppendRefusalPreservesLiveSnapshotAndPriorReplayBytes() throws Exception {
        Path snapshot = directory.resolve("state.json");
        Path journal = directory.resolve("state.wal");
        Path retained = directory.resolve("retained.wal");
        WalStorage<String, ConfigurationState> backend = new WalStorage<>(snapshot, journal, TYPE, 3_600_000);
        backend.load();
        AmazonMqConfigurationService service = service(backend, "us-east-1");
        Configuration a = create(service, "wal");
        Configuration before = service.update(a.id(), "eA==", "accepted");
        byte[] committed = Files.readAllBytes(journal);
        Files.move(journal, retained);
        Files.createDirectory(journal);
        try {
            assertEquals(500, assertThrows(AwsException.class,
                    () -> service.delete(a.id())).getHttpStatus());
            assertEquals(before, service.describe(a.id()));
            assertArrayEquals(committed, Files.readAllBytes(retained));
        } finally {
            Files.delete(journal);
            Files.move(retained, journal);
            backend.shutdown();
        }
        WalStorage<String, ConfigurationState> reopened = new WalStorage<>(snapshot, journal, TYPE, 3_600_000);
        reopened.load();
        try {
            assertEquals(before, service(reopened, "us-east-1").describe(a.id()));
        } finally {
            reopened.shutdown();
        }
    }

    @Test
    void documentCapacityRefusalDoesNotLosePreviouslyCommittedRevisions() {
        StorageBackend<String, ConfigurationState> backend = backend("persistent");
        AmazonMqConfigurationService service = service(backend, "us-east-1");
        Configuration a = create(service, "capacity");
        String document = Base64.getEncoder().encodeToString(new byte[65536]);
        for (int count = 0; count < 128; count++) {
            service.update(a.id(), document, "");
        }
        Configuration full = service.describe(a.id());
        assertEquals(429, assertThrows(AwsException.class,
                () -> service.update(a.id(), "eA==", "one-byte-over")).getHttpStatus());
        assertEquals(full, service.describe(a.id()));
        PersistentStorage<String, ConfigurationState> reopened = new PersistentStorage<>(directory.resolve("state.json"), TYPE);
        reopened.load();
        assertEquals(full, service(reopened, "us-east-1").describe(a.id()));
    }

    private StorageBackend<String, ConfigurationState> backend(String mode) {
        Path path = directory.resolve("state.json");
        return switch (mode) {
            case "persistent" -> new PersistentStorage<>(path, TYPE);
            case "hybrid" -> new HybridStorage<>(path, TYPE, 3_600_000);
            case "wal" -> new WalStorage<>(path, directory.resolve("state.wal"), TYPE, 3_600_000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static AmazonMqConfigurationService service(StorageBackend<String, ConfigurationState> backend, String region) {
        return new AmazonMqConfigurationService(new AccountAwareStorageBackend<>(backend, null, ACCOUNT),
                new RegionResolver(region, ACCOUNT), Clock.systemUTC(), () -> "c-" + UUID.randomUUID());
    }

    private static Configuration create(AmazonMqConfigurationService service, String name) {
        return service.create(new AmazonMqConfigurationService.Create(name, "RABBITMQ", "3.13", null,
                Map.of("owner", name)), arn -> {});
    }

    private static void shutdown(StorageBackend<String, ConfigurationState> backend) {
        if (backend instanceof WalStorage<String, ConfigurationState> wal) {
            wal.shutdown();
        } else if (backend instanceof HybridStorage<String, ConfigurationState> hybrid) {
            hybrid.shutdown();
        }
    }
}

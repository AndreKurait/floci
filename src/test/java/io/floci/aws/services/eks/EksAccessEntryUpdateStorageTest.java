package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.HybridStorage;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.eks.model.AccessEntry;
import io.floci.aws.services.eks.model.UpdateAccessEntryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.eks.EksAccessEntryUpdateServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class EksAccessEntryUpdateStorageTest {
    private static final TypeReference<Map<String, EksAccessEntryService.StoredEntry>> TYPE = new TypeReference<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void accountScopedReplacementSurvivesSupportedStorageLifecycle(String mode) {
        StorageBackend<String, EksAccessEntryService.StoredEntry> backend = backend(mode);
        backend.load();
        AccessEntry updated;
        EksAccessEntryService.StoredEntry sibling;
        try {
            AccountAwareStorageBackend<EksAccessEntryService.StoredEntry> accounts =
                    new AccountAwareStorageBackend<>(backend, null, ACCOUNT);
            Fixture fixture = fixture(accounts, "aws", "us-east-1");
            create(fixture, "STANDARD");
            String key = accounts.keys().iterator().next();
            sibling = accounts.get(key).orElseThrow();
            accounts.putAllAtomicallyForAccount("999999999999", Map.of(key, sibling));
            updated = fixture.service().update(ACCOUNT, fixture.cluster(), PRINCIPAL,
                    new UpdateAccessEntryRequest("persistent-user", List.of("persisted"), null));
            assertEquals(sibling, accounts.getForAccount("999999999999", key).orElseThrow());
            assertEquals(updated, fixture.service().describe(fixture.cluster(), PRINCIPAL));
            backend.flush();
        } finally {
            close(backend);
        }
        if (!"memory".equals(mode)) {
            StorageBackend<String, EksAccessEntryService.StoredEntry> reloaded = backend(mode);
            reloaded.load();
            try {
                AccountAwareStorageBackend<EksAccessEntryService.StoredEntry> accounts =
                        new AccountAwareStorageBackend<>(reloaded, null, ACCOUNT);
                Fixture fixture = fixture(accounts, "aws", "us-east-1");
                assertEquals(updated, fixture.service().describe(fixture.cluster(), PRINCIPAL));
                assertEquals(sibling, accounts.scanForAccount("999999999999", key -> true).getFirst());
                EksAccessEntryService.StoredEntry stored = accounts.scan(key -> true).getFirst();
                assertEquals("stable-principal", stored.principalId());
                assertEquals("create-token", stored.clientRequestToken());
            } finally {
                close(reloaded);
            }
        }
    }

    @Test
    void realPersistentTargetRefusalDoesNotPublishEitherChangedField() throws Exception {
        Path snapshot = directory.resolve("entries.json");
        Path backup = directory.resolve("previous.json");
        PersistentStorage<String, EksAccessEntryService.StoredEntry> backend = new PersistentStorage<>(snapshot, TYPE);
        backend.load();
        Fixture fixture = fixture(backend, "aws", "us-east-1");
        AccessEntry before = create(fixture, "STANDARD");
        byte[] previous = Files.readAllBytes(snapshot);
        Files.move(snapshot, backup);
        Files.createDirectory(snapshot);
        Path blocker = snapshot.resolve("owned-blocker");
        Files.writeString(blocker, "prevent atomic rename over a nonempty directory");
        try {
            assertThrows(UncheckedIOException.class, () -> fixture.service().update(ACCOUNT, fixture.cluster(), PRINCIPAL,
                    new UpdateAccessEntryRequest("not-published", List.of("not-published"), null)));
            assertSame(before, fixture.service().describe(fixture.cluster(), PRINCIPAL));
            assertArrayEquals(previous, Files.readAllBytes(backup));
            PersistentStorage<String, EksAccessEntryService.StoredEntry> recovered = new PersistentStorage<>(backup, TYPE);
            recovered.load();
            assertEquals(before, new EksAccessEntryService(recovered, fixture.iam())
                    .describe(fixture.cluster(), PRINCIPAL));
        } finally {
            Files.delete(blocker);
            Files.delete(snapshot);
            Files.move(backup, snapshot);
        }
        AccessEntry after = fixture.service().update(ACCOUNT, fixture.cluster(), PRINCIPAL,
                new UpdateAccessEntryRequest("published", List.of("published"), null));
        PersistentStorage<String, EksAccessEntryService.StoredEntry> reopened = new PersistentStorage<>(snapshot, TYPE);
        reopened.load();
        assertEquals(after, new EksAccessEntryService(reopened, fixture.iam()).describe(fixture.cluster(), PRINCIPAL));
    }

    private StorageBackend<String, EksAccessEntryService.StoredEntry> backend(String mode) {
        Path snapshot = directory.resolve("entries.json");
        return switch (mode) {
            case "memory" -> new InMemoryStorage<>();
            case "persistent" -> new PersistentStorage<>(snapshot, TYPE);
            case "hybrid" -> new HybridStorage<>(snapshot, TYPE, Long.MAX_VALUE);
            case "wal" -> new WalStorage<>(snapshot, directory.resolve("entries.wal"), TYPE, Long.MAX_VALUE);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void close(StorageBackend<?, ?> backend) {
        if (backend instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        } else if (backend instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        }
    }
}

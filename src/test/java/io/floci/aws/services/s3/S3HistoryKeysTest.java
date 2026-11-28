package io.floci.aws.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.HybridStorage;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.s3.model.Bucket;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class S3HistoryKeysTest {
    @Test
    void migrationPreservesCurrentObjectsWhoseKeysLookLikeHistory() {
        InMemoryStorage<String, S3Object> store = new InMemoryStorage<>();
        S3Object history = version("bucket", "key", "version-1");
        S3Object current = version("bucket", "key#v#version-1", "version-2");
        store.put("bucket/key#v#version-1", current);
        store.put("bucket/key#v#version-1#v#version-2", current);
        store.put("bucket/another#v#version-1", version("bucket", "another", "version-1"));

        S3HistoryKeys.migrate(store);

        assertSame(current, store.get("bucket/key#v#version-1").orElseThrow());
        assertSame(current, store.get(S3HistoryKeys.key("bucket", current.getKey(), "version-2")).orElseThrow());
        assertTrue(store.get(S3HistoryKeys.key("bucket", history.getKey(), "version-1")).isEmpty());
        assertTrue(store.get(S3HistoryKeys.key("bucket", "another", "version-1")).isPresent());
    }

    @Test
    void scopedAndLegacyNumericBucketsKeepTheirAccounts() {
        InMemoryStorage<String, S3Object> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<S3Object> store = new AccountAwareStorageBackend<>(raw, null, "111111111111");
        raw.put("222222222222/key#v#v1", version("222222222222", "key", "v1"));
        raw.put("333333333333/222222222222/key#v#v1", version("222222222222", "key", "v1"));
        raw.put("333333333333/bucket/key#v#v1", version("bucket", "key", "v1"));

        S3HistoryKeys.migrate(store);
        S3HistoryKeys.migrate(store);

        String numeric = S3HistoryKeys.key("222222222222", "key", "v1");
        assertTrue(store.getForAccount("111111111111", numeric).isPresent());
        assertTrue(store.getForAccount("333333333333", numeric).isPresent());
        assertTrue(store.getForAccount("222222222222", numeric).isEmpty());
        assertEquals(3, raw.keys().size());
    }

    @Test
    void persistedMigrationSurvivesReload(@TempDir Path directory) {
        Path file = directory.resolve("objects.json");
        PersistentStorage<String, S3Object> first = persistent(file);
        first.put("bucket/key#v#v1", version("bucket", "key", "v1"));
        first.put("bucket/key", version("bucket", "key", "v2"));
        S3HistoryKeys.migrate(first);

        PersistentStorage<String, S3Object> restarted = persistent(file);
        restarted.load();
        S3HistoryKeys.migrate(restarted);

        assertEquals("v1", restarted.get(S3HistoryKeys.key("bucket", "key", "v1")).orElseThrow().getVersionId());
        assertEquals("v2", restarted.get("bucket/key").orElseThrow().getVersionId());
        assertTrue(restarted.get("bucket/key#v#v1").isEmpty());
    }

    @Test
    void failedDestinationWritePreservesLegacyRecord() {
        InMemoryStorage<String, S3Object> store = new InMemoryStorage<>() {
            @Override
            public synchronized void put(String key, S3Object value) {
                if (key.startsWith(S3HistoryKeys.prefix("bucket"))) {
                    throw new IllegalStateException("write failed");
                }
                super.put(key, value);
            }
        };
        store.put("bucket/key#v#v1", version("bucket", "key", "v1"));
        assertThrows(IllegalStateException.class, () -> S3HistoryKeys.migrate(store));
        assertEquals(1, store.keys().size());
        assertTrue(store.get("bucket/key#v#v1").isPresent());
    }

    @Test
    void restartAfterFailedSourceDeletionKeepsTheDestination() {
        boolean[] fail = {true};
        InMemoryStorage<String, S3Object> store = new InMemoryStorage<>() {
            @Override
            public synchronized void delete(String key) {
                if (fail[0]) {
                    throw new IllegalStateException("delete failed");
                }
                super.delete(key);
            }
        };
        S3Object legacy = version("bucket", "key", "v1");
        store.put("bucket/key#v#v1", legacy);
        assertThrows(IllegalStateException.class, () -> S3HistoryKeys.migrate(store));
        assertEquals(2, store.keys().size());
        S3Object destination = version("bucket", "key", "v1");
        destination.setLatest(false);
        store.put(S3HistoryKeys.key("bucket", "key", "v1"), destination);
        fail[0] = false;

        S3HistoryKeys.migrate(store);

        assertEquals(1, store.keys().size());
        assertSame(destination, store.get(S3HistoryKeys.key("bucket", "key", "v1")).orElseThrow());
    }

    @Test
    void corruptDestinationDoesNotEraseEitherRecord() {
        InMemoryStorage<String, S3Object> store = new InMemoryStorage<>();
        store.put("bucket/key#v#v1", version("bucket", "key", "v1"));
        store.put(S3HistoryKeys.key("bucket", "key", "v1"), version("foreign", "key", "v1"));

        assertThrows(IllegalStateException.class, () -> S3HistoryKeys.migrate(store));

        assertEquals(2, store.keys().size());
    }

    @Test
    void retainedMarkerFromAnOlderCollisionCanBeDeletedAfterMigration(@TempDir Path directory) {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objects = new InMemoryStorage<>();
        S3Object marker = version("bucket", "k", "marker-version");
        marker.setDeleteMarker(true);
        objects.put("bucket/k#v#marker-version#v#marker-version", marker);
        S3Service service = new S3Service(buckets, objects, directory, true);
        service.createBucket("bucket", "us-east-1");
        service.putBucketVersioning("bucket", "Enabled");

        assertEquals(1, service.listObjectVersions("bucket", "", 100, null).versions().size());
        service.deleteObject("bucket", "k", "marker-version");

        assertTrue(service.listObjectVersions("bucket", "", 100, null).versions().isEmpty());
        assertDoesNotThrow(() -> service.deleteBucket("bucket"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wal", "hybrid"})
    void migrationSurvivesTheJournaledAndHybridBackends(String mode, @TempDir Path directory) {
        StorageBackend<String, S3Object> first = storage(mode, directory);
        try {
            first.load();
            first.put("bucket/key#v#v1", version("bucket", "key", "v1"));
            S3HistoryKeys.migrate(first);
            first.flush();
        } finally {
            shutdown(first);
        }
        StorageBackend<String, S3Object> restarted = storage(mode, directory);
        try {
            restarted.load();
            S3HistoryKeys.migrate(restarted);
            assertEquals("v1", restarted.get(S3HistoryKeys.key("bucket", "key", "v1"))
                    .orElseThrow().getVersionId());
            assertFalse(restarted.get("bucket/key#v#v1").isPresent());
        } finally {
            shutdown(restarted);
        }
    }

    @Test
    void legacyMetadataStillReadsAndPromotesTheExistingBodyFiles(@TempDir Path directory) {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        Path metadata = directory.resolve("objects.json");
        PersistentStorage<String, S3Object> objects = persistent(metadata);
        S3Service original = new S3Service(buckets, objects, directory.resolve("data"), false);
        original.createBucket("bucket", "us-east-1");
        original.putBucketVersioning("bucket", "Enabled");
        S3Object first = original.putObject("bucket", "key", "first".getBytes(StandardCharsets.UTF_8), null, null);
        S3Object second = original.putObject("bucket", "key", "second".getBytes(StandardCharsets.UTF_8), null, null);
        for (S3Object object : List.of(first, second)) {
            String currentHistory = S3HistoryKeys.key("bucket", "key", object.getVersionId());
            objects.put("bucket/key#v#" + object.getVersionId(), objects.get(currentHistory).orElseThrow());
            objects.delete(currentHistory);
        }
        PersistentStorage<String, S3Object> reloaded = persistent(metadata);
        reloaded.load();
        S3Service restarted = new S3Service(buckets, reloaded, directory.resolve("data"), false);

        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8),
                restarted.getObject("bucket", "key", first.getVersionId()).getData());
        restarted.deleteObject("bucket", "key", second.getVersionId());
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), restarted.getObject("bucket", "key").getData());
        restarted.deleteObject("bucket", "key", first.getVersionId());
        assertDoesNotThrow(() -> restarted.deleteBucket("bucket"));
    }

    private static StorageBackend<String, S3Object> storage(String mode, Path directory) {
        TypeReference<Map<String, S3Object>> type = new TypeReference<>() {};
        return switch (mode) {
            case "wal" -> new WalStorage<>(directory.resolve("snapshot.json"), directory.resolve("objects.wal"), type, 60000);
            case "hybrid" -> new HybridStorage<>(directory.resolve("objects.json"), type, 60000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void shutdown(StorageBackend<String, S3Object> storage) {
        if (storage instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        } else if (storage instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        }
    }

    private static S3Object version(String bucket, String key, String versionId) {
        S3Object object = new S3Object(bucket, key, new byte[0], null);
        object.setVersionId(versionId);
        return object;
    }

    private static PersistentStorage<String, S3Object> persistent(Path file) {
        return new PersistentStorage<>(file, new TypeReference<Map<String, S3Object>>() {});
    }
}

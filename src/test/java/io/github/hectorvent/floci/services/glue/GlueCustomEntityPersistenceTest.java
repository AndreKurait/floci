package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.glue.model.CustomEntityType;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.glue.GlueCustomEntityServiceTest.json;
import static io.github.hectorvent.floci.services.glue.GlueCustomEntityServiceTest.named;
import static io.github.hectorvent.floci.services.glue.GlueCustomEntityServiceTest.pattern;
import static org.junit.jupiter.api.Assertions.*;

class GlueCustomEntityPersistenceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void definitionsTagsAndDeletesSurviveCloseAndReload(String mode, @TempDir Path directory) {
        Path snapshot = directory.resolve("custom_entity_types.json");
        StorageBackend<String, CustomEntityType> backend = open(mode, snapshot);
        try {
            service(backend).create(json("""
                    {"Name":"saved","RegexString":"[A-Z]+","ContextWords":["invoice","case"],"Tags":{"group":"kept"}}
                    """), REGION);
        } finally {
            close(backend);
        }
        backend = open(mode, snapshot);
        try {
            GlueCustomEntityService service = service(backend);
            assertEquals(Map.of("Name", "saved", "RegexString", "[A-Z]+", "ContextWords", List.of("invoice", "case")),
                    service.get(named("saved"), REGION));
            assertEquals(1, ((List<?>) service.list(json("{\"Tags\":{\"group\":\"kept\"}}"), REGION)
                    .get("CustomEntityTypes")).size());
            service.delete(named("saved"), REGION);
        } finally {
            close(backend);
        }
        backend = open(mode, snapshot);
        try {
            GlueCustomEntityService service = service(backend);
            assertThrows(AwsException.class, () -> service.get(named("saved"), REGION));
            service.create(pattern("saved", "replacement"), REGION);
        } finally {
            close(backend);
        }
        backend = open(mode, snapshot);
        try {
            assertEquals(Map.of("Name", "saved", "RegexString", "replacement"),
                    service(backend).get(named("saved"), REGION));
            assertTrue(((List<?>) service(backend).list(json("{\"Tags\":{\"group\":\"kept\"}}"), REGION)
                    .get("CustomEntityTypes")).isEmpty());
        } finally {
            close(backend);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusedAtomicCreateOrDeletePreservesThePreviouslyCommittedState(boolean delete,
                                                                        @TempDir Path directory) throws IOException {
        Path snapshot = directory.resolve("custom_entity_types.json");
        StorageBackend<String, CustomEntityType> backend = open("persistent", snapshot);
        GlueCustomEntityService service = service(backend);
        service.create(pattern("sibling", "preserved"), REGION);
        if (delete) {
            service.create(pattern("target", "old"), REGION);
        }
        byte[] committed = Files.readAllBytes(snapshot);
        Files.delete(snapshot);
        Files.createDirectory(snapshot);
        Files.writeString(snapshot.resolve("obstacle"), "owned test obstacle");
        try {
            assertThrows(UncheckedIOException.class, () -> {
                if (delete) {
                    service.delete(named("target"), REGION);
                } else {
                    service.create(pattern("target", "new"), REGION);
                }
            });
            assertEquals("preserved", service.get(named("sibling"), REGION).get("RegexString"));
            if (delete) {
                assertEquals("old", service.get(named("target"), REGION).get("RegexString"));
            } else {
                assertThrows(AwsException.class, () -> service.get(named("target"), REGION));
            }
        } finally {
            Files.delete(snapshot.resolve("obstacle"));
            Files.delete(snapshot);
            Files.write(snapshot, committed);
        }
        StorageBackend<String, CustomEntityType> reloaded = open("persistent", snapshot);
        try {
            if (delete) {
                assertEquals("old", service(reloaded).get(named("target"), REGION).get("RegexString"));
            } else {
                assertThrows(AwsException.class, () -> service(reloaded).get(named("target"), REGION));
            }
            assertArrayEquals(committed, Files.readAllBytes(snapshot));
        } finally {
            close(reloaded);
        }
    }

    private static GlueCustomEntityService service(StorageBackend<String, CustomEntityType> backend) {
        return new GlueCustomEntityService(new AccountAwareStorageBackend<>(backend, null, ACCOUNT),
                new RegionResolver(REGION, ACCOUNT), new ObjectMapper());
    }

    private static StorageBackend<String, CustomEntityType> open(String mode, Path snapshot) {
        TypeReference<Map<String, CustomEntityType>> type = new TypeReference<>() {};
        StorageBackend<String, CustomEntityType> backend = switch (mode) {
            case "persistent" -> new PersistentStorage<>(snapshot, type);
            case "hybrid" -> new HybridStorage<>(snapshot, type, 60_000);
            case "wal" -> new WalStorage<>(snapshot, snapshot.resolveSibling("custom_entity_types.wal"), type, 60_000);
            default -> throw new IllegalArgumentException(mode);
        };
        backend.load();
        return backend;
    }

    private static void close(StorageBackend<String, CustomEntityType> backend) {
        if (backend instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        } else if (backend instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        } else {
            backend.flush();
        }
    }
}

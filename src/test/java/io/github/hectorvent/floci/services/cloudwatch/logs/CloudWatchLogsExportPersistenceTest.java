package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogExportTask;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CloudWatchLogsExportPersistenceTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final TypeReference<Map<String, LogExportTask>> TYPE = new TypeReference<>() {};
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void restartPreservesHistoryAndFailsEveryInterruptedStateWithoutReplayingObjects(String mode) {
        List<String> states = List.of("PENDING", "RUNNING", "PENDING_CANCEL", "CANCELLED", "COMPLETED", "FAILED");
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AccountAwareStorageBackend<LogExportTask> tasks = first.create("cloudwatchlogs", "cwlogs-export-tasks.json", TYPE);
            for (String state : states) {
                tasks.putAllAtomicallyForAccount(ACCOUNT, Map.of(REGION + "::" + state, task(state, state)));
            }
            tasks.putAllAtomicallyForAccount("999999999999", Map.of(REGION + "::foreign",
                    new LogExportTask("999999999999", REGION, "foreign", "foreign name", "foreign group",
                            1, 2, "foreign bucket", "foreign prefix", 3, 4L, "COMPLETED", "complete")));
        } finally {
            first.shutdownAll();
        }
        StorageFactory reopened = StorageFactories.open(directory, mode, ACCOUNT);
        S3Service s3 = mock(S3Service.class);
        AccountAwareStorageBackend<LogExportTask> tasks = reopened.create("cloudwatchlogs", "cwlogs-export-tasks.json", TYPE);
        CloudWatchLogsExportService service = service(tasks, s3);
        try {
            service.recoverAfterRestart();
            for (String state : states) {
                LogExportTask actual = service.describe(state, null, 50, null, REGION).tasks().getFirst();
                LogExportTask original = task(state, state);
                if (original.terminal()) {
                    assertEquals(original, actual);
                } else {
                    assertEquals("FAILED", actual.status());
                    assertEquals(original.taskName(), actual.taskName());
                    assertEquals(original.logGroupName(), actual.logGroupName());
                    assertEquals(original.destination(), actual.destination());
                    assertEquals(original.destinationPrefix(), actual.destinationPrefix());
                    assertTrue(actual.completionTime() >= original.creationTime());
                }
            }
            assertEquals(6, service.describe(null, null, 50, null, REGION).tasks().size());
            assertTrue(service.describe("foreign", null, 50, null, REGION).tasks().isEmpty());
            assertTrue(service.describe(null, null, 50, null, "us-west-2").tasks().isEmpty());
            verifyNoInteractions(s3);
        } finally {
            service.shutdown();
            reopened.shutdownAll();
        }
    }

    @Test
    void realWalRefusalPreservesPendingRevisionAndRecoveryBytesThenCancellationCanRetry() throws Exception {
        Path snapshot = directory.resolve("tasks.json");
        Path log = directory.resolve("tasks.wal");
        Path saved = directory.resolve("saved.wal");
        WalStorage<String, LogExportTask> raw = new WalStorage<>(snapshot, log, TYPE, Long.MAX_VALUE);
        raw.load();
        AccountAwareStorageBackend<LogExportTask> tasks = new AccountAwareStorageBackend<>(raw, null, ACCOUNT);
        S3Service s3 = mock(S3Service.class);
        CloudWatchLogsExportService service = service(tasks, s3);
        service.recoverAfterRestart();
        LogExportTask pending = task("id", "PENDING");
        tasks.putAllAtomicallyForAccount(ACCOUNT, Map.of(REGION + "::id", pending));
        byte[] before = Files.readAllBytes(log);
        try {
            Files.move(log, saved);
            Files.createDirectory(log);
            try {
                assertThrows(UncheckedIOException.class, () -> service.cancel("id", REGION));
                assertEquals(pending, tasks.getForAccount(ACCOUNT, REGION + "::id").orElseThrow());
                assertArrayEquals(before, Files.readAllBytes(saved));
                assertEquals(pending, readRecoveryCopy(snapshot, saved).get(ACCOUNT + "/" + REGION + "::id"));
            } finally {
                Files.delete(log);
                Files.move(saved, log);
            }
            service.cancel("id", REGION);
            assertEquals("CANCELLED", tasks.getForAccount(ACCOUNT, REGION + "::id").orElseThrow().status());
            assertEquals("CANCELLED", readRecoveryCopy(snapshot, log)
                    .get(ACCOUNT + "/" + REGION + "::id").status());
            verifyNoInteractions(s3);
        } finally {
            service.shutdown();
            raw.shutdown();
        }
    }

    private Map<String, LogExportTask> readRecoveryCopy(Path snapshot, Path log) throws IOException {
        Path copy = Files.createDirectory(directory.resolve("replay-" + UUID.randomUUID()));
        Path copiedSnapshot = copy.resolve("tasks.json");
        Path copiedLog = copy.resolve("tasks.wal");
        if (Files.exists(snapshot)) {
            Files.copy(snapshot, copiedSnapshot);
        }
        Files.copy(log, copiedLog);
        WalStorage<String, LogExportTask> reader = new WalStorage<>(copiedSnapshot, copiedLog, TYPE, Long.MAX_VALUE);
        try {
            reader.load();
            Map<String, LogExportTask> result = new HashMap<>();
            for (String key : reader.keys()) {
                result.put(key, reader.get(key).orElseThrow());
            }
            return result;
        } finally {
            reader.shutdown();
        }
    }

    private static CloudWatchLogsExportService service(AccountAwareStorageBackend<LogExportTask> tasks, S3Service s3) {
        return new CloudWatchLogsExportService(tasks, mock(CloudWatchLogsService.class), s3, null,
                new RegionResolver(REGION, ACCOUNT), Clock.systemUTC(), Executors.newSingleThreadExecutor());
    }

    private static LogExportTask task(String id, String status) {
        Long completion = List.of("CANCELLED", "COMPLETED", "FAILED").contains(status) ? 4L : null;
        return new LogExportTask(ACCOUNT, REGION, id, "name", "group", 1, 2,
                "bucket", "prefix", 3, completion, status, "detail");
    }
}

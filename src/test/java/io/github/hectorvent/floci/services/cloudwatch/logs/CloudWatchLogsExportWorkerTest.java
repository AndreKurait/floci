package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogExportTask;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.S3Service.LogExportDestination;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class CloudWatchLogsExportWorkerTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private final FailingStore raw = new FailingStore();
    private final AccountAwareStorageBackend<LogExportTask> tasks = new AccountAwareStorageBackend<>(raw, null, ACCOUNT);
    private final CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
    private final S3Service s3 = mock(S3Service.class);
    private final IamEnforcementFilter iam = mock(IamEnforcementFilter.class);
    private final ManualExecutor executor = new ManualExecutor();
    private final LogExportDestination receipt = new LogExportDestination(ACCOUNT, REGION, "bucket",
            Instant.EPOCH, 1L, "explicit read-only policy");
    private final CloudWatchLogsExportService service = new CloudWatchLogsExportService(tasks, logs, s3, iam,
            new RegionResolver(REGION, ACCOUNT), Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC), executor);

    CloudWatchLogsExportWorkerTest() {
        when(logs.captureExport(ACCOUNT, REGION, "group", null, 0, 100)).thenReturn(List.of(
                new LogExportPlan.Event("stream", 50, "message")));
        when(s3.readLogExportDestination(ACCOUNT, REGION, "bucket")).thenReturn(receipt);
        when(s3.admitLogExportDestination(eq(receipt), anyString(), anyList())).thenReturn(receipt);
    }

    @AfterEach
    void close() {
        service.shutdown();
    }

    @Test
    void pendingAndCompletedStateUseExactCallerPolicyAndRejectSecondActiveAdmission() {
        String id = create();
        assertEquals("PENDING", status(id));
        AwsException limit = assertThrows(AwsException.class, this::create);
        assertEquals("LimitExceededException", limit.getErrorCode());
        verify(iam, atLeastOnce()).authorizeAdditionalResourceWithPolicies(eq("credential"),
                eq("s3:PutObject"), startsWith("arn:aws:s3:::bucket/prefix/"), eq(ACCOUNT),
                eq(List.of("explicit read-only policy")));
        executor.runNext();
        assertEquals("COMPLETED", status(id));
        assertEquals(1000, service.describe(id, null, null, null, REGION).tasks().getFirst().completionTime());
        verify(s3).writeLogExportObject(eq(receipt), eq("arn:aws:logs:us-east-1:123456789012:log-group:group"),
                startsWith("prefix/" + id + "/"), any(byte[].class));
        assertEquals("InvalidOperationException", assertThrows(AwsException.class,
                () -> service.cancel(id, REGION)).getErrorCode());
    }

    @Test
    void queuedCancellationIsTerminalAndCreatesNoObjects() {
        String id = create();
        service.cancel(id, REGION);
        assertEquals("CANCELLED", status(id));
        executor.runNext();
        verify(s3, never()).writeLogExportObject(any(), anyString(), anyString(), any());
        assertNotEquals(id, create());
    }

    @Test
    void cancellationWaitsForClaimedWriteAndPreventsLaterChunks() throws Exception {
        when(logs.captureExport(ACCOUNT, REGION, "group", null, 0, 100)).thenReturn(List.of(
                new LogExportPlan.Event("stream", 1, "a".repeat(800000)),
                new LogExportPlan.Event("stream", 2, "b".repeat(800000)),
                new LogExportPlan.Event("stream", 3, "c".repeat(800000))));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(s3).writeLogExportObject(any(), anyString(), anyString(), any());
        String id = create();
        CompletableFuture<Void> running = CompletableFuture.runAsync(executor::runNext);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            service.cancel(id, REGION);
            assertEquals("PENDING_CANCEL", status(id));
            assertEquals("LimitExceededException", assertThrows(AwsException.class, this::create).getErrorCode());
        } finally {
            release.countDown();
        }
        running.get(5, TimeUnit.SECONDS);
        assertEquals("CANCELLED", status(id));
        verify(s3, times(1)).writeLogExportObject(any(), anyString(), anyString(), any());
    }

    @Test
    void delayedFailureOfCancelledTaskCannotReleaseTheNextTasksActiveWrite() throws Exception {
        CloudWatchLogsExportService observed = spy(service);
        CountDownLatch oldFailureWaiting = new CountDownLatch(1);
        CountDownLatch recordOldFailure = new CountDownLatch(1);
        CountDownLatch newWriteEntered = new CountDownLatch(1);
        CountDownLatch finishNewWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            oldFailureWaiting.countDown();
            assertTrue(recordOldFailure.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(observed).recordFailure(any(), any());
        doThrow(new IllegalStateException("first write refused")).doAnswer(invocation -> {
            newWriteEntered.countDown();
            assertTrue(finishNewWrite.await(5, TimeUnit.SECONDS));
            return null;
        }).when(s3).writeLogExportObject(any(), anyString(), anyString(), any());
        String oldId = observed.create("group", null, 0, 100, "bucket", "prefix", "old", REGION, "credential");
        CompletableFuture<Void> oldWorker = CompletableFuture.runAsync(executor::runNext);
        assertTrue(oldFailureWaiting.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> newWorker = null;
        try {
            observed.cancel(oldId, REGION);
            assertEquals("CANCELLED", observed.describe(oldId, null, null, null, REGION).tasks().getFirst().status());
            String nextId = observed.create("group", null, 0, 100, "bucket", "prefix", "new", REGION, "credential");
            newWorker = CompletableFuture.runAsync(executor::runNext);
            assertTrue(newWriteEntered.await(5, TimeUnit.SECONDS));
            recordOldFailure.countDown();
            oldWorker.get(5, TimeUnit.SECONDS);
            observed.cancel(nextId, REGION);
            assertEquals("PENDING_CANCEL",
                    observed.describe(nextId, null, null, null, REGION).tasks().getFirst().status());
            finishNewWrite.countDown();
            newWorker.get(5, TimeUnit.SECONDS);
            assertEquals("CANCELLED", observed.describe(nextId, null, null, null, REGION).tasks().getFirst().status());
        } finally {
            recordOldFailure.countDown();
            finishNewWrite.countDown();
            oldWorker.get(5, TimeUnit.SECONDS);
            if (newWorker != null) {
                newWorker.get(5, TimeUnit.SECONDS);
            }
            observed.shutdown();
        }
    }

    @Test
    void workerFailureRetainsTheTaskAndDoesNotReportCompletion() {
        doThrow(new IllegalStateException("object storage refused")).when(s3)
                .writeLogExportObject(any(), anyString(), anyString(), any());
        String id = create();
        executor.runNext();
        assertEquals("FAILED", status(id));
        assertEquals(1, tasks.keysForAccount(ACCOUNT).size());
    }

    @Test
    void terminalPersistenceFailureKeepsTheActiveSlotOccupied() {
        String id = create();
        raw.failTerminal = true;
        executor.runNext();
        assertEquals("RUNNING", status(id));
        assertEquals("LimitExceededException", assertThrows(AwsException.class, this::create).getErrorCode());
    }

    @Test
    void abortedResetSettlesQueuedTasksBeforeNewAdmissionAndEarlierHookFailureLeavesWorkerAlone() {
        String first = create();
        service.afterReset(); // An earlier beforeReset hook failed before this service was reached.
        assertEquals("PENDING", status(first));
        executor.runNext();
        assertEquals("COMPLETED", status(first));
        String interrupted = create();
        service.beforeReset(); // A later hook fails; StorageFactory never clears the task row.
        service.afterReset();
        assertEquals("FAILED", status(interrupted));
        assertNotEquals(interrupted, create());
    }

    @Test
    void abortedResetTerminalWriteFailureDoesNotReopenAdmission() {
        String id = create();
        service.beforeReset();
        raw.failTerminal = true;
        assertThrows(IllegalStateException.class, service::afterReset);
        assertEquals("PENDING", status(id));
        assertEquals("ServiceUnavailableException", assertThrows(AwsException.class, this::create).getErrorCode());
    }

    @Test
    void startupFailsPersistedNonterminalTasksWithoutReplayingObjects() {
        LogExportTask persisted = new LogExportTask(ACCOUNT, REGION, "old", "name", "group",
                0, 100, "bucket", "prefix", 1, null, "RUNNING", "interrupted");
        tasks.putForAccount(ACCOUNT, REGION + "::old", persisted);
        service.recoverAfterRestart();
        assertEquals("FAILED", status("old"));
        verifyNoInteractions(s3);
        assertTrue(service.describe("absent", null, null, null, REGION).tasks().isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.cancel("absent", REGION)).getErrorCode());
    }

    @Test
    void paginationTokensBindStatusAccountRegionAndLimitRange() {
        for (String id : List.of("a", "b", "c")) {
            tasks.putForAccount(ACCOUNT, REGION + "::" + id, new LogExportTask(ACCOUNT, REGION, id, null, "group",
                    0, 100, "bucket", "prefix", 1, 2L, "COMPLETED", "complete"));
        }
        CloudWatchLogsExportService.Page first = service.describe(null, "COMPLETED", 1, null, REGION);
        assertEquals("a", first.tasks().getFirst().taskId());
        assertNotNull(first.nextToken());
        assertEquals("b", service.describe(null, "COMPLETED", 1, first.nextToken(), REGION).tasks().getFirst().taskId());
        assertThrows(AwsException.class, () -> service.describe(null, "FAILED", 1, first.nextToken(), REGION));
        assertThrows(AwsException.class, () -> service.describe(null, "COMPLETED", 1, first.nextToken(), "us-west-2"));
        assertThrows(AwsException.class, () -> service.describe(null, null, 0, null, REGION));
    }

    @Test
    void callerDenialDoesNotInitializeDestinationOrPublishTask() {
        doThrow(new AwsException("AccessDenied", "refused", 403)).when(iam)
                .authorizeAdditionalResourceWithPolicies(anyString(), anyString(), anyString(), anyString(), anyList());
        assertThrows(AwsException.class, this::create);
        assertTrue(tasks.keysForAccount(ACCOUNT).isEmpty());
        verify(s3, never()).admitLogExportDestination(any(), anyString(), anyList());
    }

    private String create() {
        return service.create("group", null, 0, 100, "bucket", "prefix", "name", REGION, "credential");
    }

    private String status(String id) {
        return service.describe(id, null, null, null, REGION).tasks().getFirst().status();
    }

    private static final class FailingStore extends InMemoryStorage<String, LogExportTask> {
        private boolean failTerminal;

        @Override
        public void putAllAtomically(Map<String, LogExportTask> entries) {
            if (failTerminal && entries.values().stream().anyMatch(LogExportTask::terminal)) {
                throw new IllegalStateException("injected atomic terminal persistence refusal");
            }
            super.putAllAtomically(entries);
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> queue = new ArrayDeque<>();
        private boolean stopped;

        void runNext() {
            queue.remove().run();
        }

        @Override
        public void shutdown() {
            stopped = true;
            while (!queue.isEmpty()) {
                queue.remove().run();
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return stopped;
        }

        @Override
        public boolean isTerminated() {
            return stopped && queue.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (stopped) {
                throw new IllegalStateException("executor stopped");
            }
            queue.add(command);
        }
    }
}

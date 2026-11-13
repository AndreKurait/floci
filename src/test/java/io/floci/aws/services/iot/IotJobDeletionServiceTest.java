package io.floci.aws.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.dynamodb.DynamoDbFacade;
import io.floci.aws.services.firehose.FirehoseService;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iot.model.IotJob;
import io.floci.aws.services.iot.model.IotJobExecution;
import io.floci.aws.services.iot.model.Thing;
import io.floci.aws.services.kinesis.KinesisService;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.sqs.SqsService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IotJobDeletionServiceTest {
    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "us-west-2";
    private static final String ACCOUNT = "000000000000";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "REJECTED"})
    void snapshotCompletesOnlyAfterEveryExecutionIsTerminalAndThenDeletesWithoutForce(String finalStatus) {
        Fixture fixture = memory();
        String first = thing(fixture, "first", REGION);
        String second = thing(fixture, "second", REGION);
        job(fixture, "snapshot", "SNAPSHOT", REGION, first, second);
        fixture.service().startNextPendingJobExecution("first", Map.of(), REGION);
        if (!"REJECTED".equals(finalStatus)) {
            fixture.service().startNextPendingJobExecution("second", Map.of(), REGION);
        }
        fixture.service().updateJobExecution("first", "snapshot", "SUCCEEDED", Map.of(), null, REGION);

        assertEquals("IN_PROGRESS", fixture.service().describeJob("snapshot", REGION).getStatus());
        awsError("InvalidStateTransitionException", 409,
                () -> fixture.service().deleteJob("snapshot", false, REGION));
        assertEquals(2, fixture.executions().keys().size());

        fixture.service().updateJobExecution("second", "snapshot", finalStatus, Map.of(), null, REGION);

        assertEquals("COMPLETED", fixture.service().describeJob("snapshot", REGION).getStatus());
        assertEquals("COMPLETED", fixture.service().listJobs(REGION, null, null).items().getFirst().getStatus());
        fixture.service().deleteJob("snapshot", false, REGION);
        assertDeleted(fixture, "snapshot", REGION);
        assertTrue(fixture.executions().keys().isEmpty());
        assertNotNull(fixture.service().describeThing("first", REGION));
        assertNotNull(fixture.service().describeThing("second", REGION));
    }

    @Test
    void continuousJobRemainsOpenAfterItsCurrentExecutionsFinishAndNeedsForce() {
        Fixture fixture = memory();
        thing(fixture, "device", REGION);
        String groupArn = fixture.service().createThingGroup("continuous-fleet", MAPPER.createObjectNode(), REGION)
                .getThingGroupArn();
        fixture.service().addThingToThingGroup("continuous-fleet", "device", REGION);
        job(fixture, "continuous", "CONTINUOUS", REGION, groupArn);
        fixture.service().startNextPendingJobExecution("device", Map.of(), REGION);
        fixture.service().updateJobExecution("device", "continuous", "SUCCEEDED", Map.of(), null, REGION);

        assertEquals("IN_PROGRESS", fixture.service().describeJob("continuous", REGION).getStatus());
        awsError("InvalidStateTransitionException", 409,
                () -> fixture.service().deleteJob("continuous", false, REGION));
        assertEquals("SUCCEEDED",
                fixture.service().describeJobExecution("device", "continuous", REGION).getStatus());

        fixture.service().deleteJob("continuous", true, REGION);
        assertDeleted(fixture, "continuous", REGION);
        assertTrue(fixture.service().listJobExecutionsForThing("device", REGION, null, null).items().isEmpty());
    }

    @Test
    void forcedDeletionRemovesEveryStoredExecutionAndPreservesOtherJobsRegionsAndThings() {
        Fixture fixture = memory();
        String groupArn = fixture.service().createThingGroup("fleet", MAPPER.createObjectNode(), REGION)
                .getThingGroupArn();
        // One valid group target materializes more executions than a default list page.
        for (int i = 0; i < 105; i++) {
            String name = "device-" + i;
            thing(fixture, name, REGION);
            fixture.service().addThingToThingGroup("fleet", name, REGION);
        }
        job(fixture, "remove", "SNAPSHOT", REGION, groupArn);
        assertEquals(105, fixture.executions().keys().size());
        fixture.service().removeThingFromThingGroup("fleet", "device-104", REGION);
        job(fixture, "keep", "SNAPSHOT", REGION,
                fixture.service().describeThing("device-0", REGION).getThingArn());
        job(fixture, "remove", "SNAPSHOT", OTHER_REGION, thing(fixture, "device-0", OTHER_REGION));

        fixture.service().deleteJob("remove", true, REGION);

        assertDeleted(fixture, "remove", REGION);
        assertEquals(2, fixture.executions().keys().size());
        assertEquals(List.of("keep"), fixture.service().listJobs(REGION, null, null).items().stream()
                .map(IotJob::getJobId).toList());
        assertEquals("QUEUED", fixture.service().describeJobExecution("device-0", "keep", REGION).getStatus());
        assertEquals("QUEUED",
                fixture.service().describeJobExecution("device-0", "remove", OTHER_REGION).getStatus());
        assertTrue(fixture.service().listJobExecutionsForThing("device-104", REGION, null, null).items().isEmpty());
        assertNotNull(fixture.service().describeThing("device-104", REGION));
        assertNotNull(fixture.service().describeThingGroup("fleet", REGION));
    }

    @Test
    void storedCanceledJobCanBeDeletedWithoutAddingACancelApi() {
        Fixture fixture = memory();
        IotJob canceled = job(fixture, "canceled", "SNAPSHOT", REGION, thing(fixture, "device", REGION));
        // Canceled snapshots can exist in storage; this fixture does not claim a CancelJob route.
        canceled.setStatus("CANCELED");
        fixture.jobs().put("job:" + REGION + ":canceled", canceled);

        fixture.service().deleteJob("canceled", false, REGION);

        assertDeleted(fixture, "canceled", REGION);
        assertTrue(fixture.executions().keys().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidJobIds")
    void invalidIdsAreRejectedBeforeAnyExistingJobChanges(String invalid) {
        Fixture fixture = memory();
        job(fixture, "keep", "SNAPSHOT", REGION, thing(fixture, "device", REGION));

        awsError("InvalidRequestException", 400, () -> fixture.service().deleteJob(invalid, true, REGION));

        assertEquals("keep", fixture.service().describeJob("keep", REGION).getJobId());
        assertEquals(1, fixture.executions().keys().size());
    }

    private static Stream<String> invalidJobIds() {
        return Stream.of(null, "", "a".repeat(65), "has space", "has.dot", "has/slash", "has+plus");
    }

    @Test
    void missingAndRepeatedDeletionReturnNotFound() {
        Fixture fixture = memory();
        awsError("ResourceNotFoundException", 404,
                () -> fixture.service().deleteJob("missing", true, REGION));
        String id = "a".repeat(64);
        job(fixture, id, "SNAPSHOT", REGION, thing(fixture, "device", REGION));

        fixture.service().deleteJob(id, true, REGION);

        awsError("ResourceNotFoundException", 404, () -> fixture.service().deleteJob(id, true, REGION));
        assertTrue(fixture.executions().keys().isEmpty());
    }

    @Test
    void deletionUsesTheOwningAccountPartition() {
        InMemoryStorage<String, Thing> things = new InMemoryStorage<>();
        InMemoryStorage<String, IotJob> jobs = new InMemoryStorage<>();
        InMemoryStorage<String, IotJobExecution> executions = new InMemoryStorage<>();
        Fixture first = accountFixture(things, jobs, executions, ACCOUNT);
        Fixture other = accountFixture(things, jobs, executions, "111111111111");
        job(first, "same", "SNAPSHOT", REGION, thing(first, "device", REGION));
        job(other, "same", "SNAPSHOT", REGION, thing(other, "device", REGION));

        first.service().deleteJob("same", true, REGION);

        assertDeleted(first, "same", REGION);
        assertTrue(first.executions().keys().isEmpty());
        assertEquals("same", other.service().describeJob("same", REGION).getJobId());
        assertEquals("QUEUED", other.service().describeJobExecution("device", "same", REGION).getStatus());
    }

    @Test
    void completionAndDeletionSurviveIndependentPersistentStoreReloads(@TempDir Path directory) {
        Fixture first = persistent(directory);
        String target = thing(first, "device", REGION);
        job(first, "finish", "SNAPSHOT", REGION, target);
        job(first, "keep", "SNAPSHOT", REGION, target);
        first.service().updateJobExecution("device", "finish", "IN_PROGRESS", Map.of(), null, REGION);
        first.service().updateJobExecution("device", "finish", "SUCCEEDED", Map.of(), null, REGION);

        Fixture reloaded = persistent(directory);
        assertEquals("COMPLETED", reloaded.service().describeJob("finish", REGION).getStatus());
        assertEquals("SUCCEEDED", reloaded.service().describeJobExecution("device", "finish", REGION).getStatus());
        reloaded.service().deleteJob("finish", false, REGION);

        Fixture afterDelete = persistent(directory);
        assertDeleted(afterDelete, "finish", REGION);
        assertEquals(List.of("keep"), afterDelete.service()
                .listJobExecutionsForThing("device", REGION, null, null).items().stream()
                .map(IotJobExecution::getJobId).toList());
        assertNotNull(afterDelete.service().describeThing("device", REGION));
    }

    @Test
    void legacyDeletionRemovesEveryExecutionWithoutChangingOtherAccountsRegionsOrJobs() {
        Fixture legacy = memory();
        Fixture foreign = accountFixture(legacy.things(), legacy.jobs(), legacy.executions(), "111111111111");
        job(foreign, "same", "SNAPSHOT", REGION, thing(foreign, "foreign", REGION));
        job(legacy, "same", "SNAPSHOT", REGION, thing(legacy, "old", REGION));
        job(legacy, "keep", "SNAPSHOT", REGION, thing(legacy, "keep", REGION));
        job(legacy, "same", "SNAPSHOT", OTHER_REGION, thing(legacy, "old", OTHER_REGION));
        Fixture owner = accountFixture(legacy.things(), legacy.jobs(), legacy.executions(), ACCOUNT);

        owner.service().deleteJob("same", true, REGION);

        assertDeleted(owner, "same", REGION);
        assertTrue(legacy.executions().get("job-execution:" + REGION + ":old:same").isEmpty());
        assertTrue(legacy.executions().get(ACCOUNT + "/job-execution:" + REGION + ":old:same").isEmpty());
        assertTrue(legacy.executions().get("111111111111/job-execution:" + REGION + ":foreign:same").isPresent());
        assertTrue(legacy.executions().get("job-execution:" + OTHER_REGION + ":old:same").isPresent());
        assertTrue(legacy.executions().get("job-execution:" + REGION + ":keep:keep").isPresent());
        job(owner, "same", "SNAPSHOT", REGION, thing(owner, "replacement", REGION));
        awsError("ResourceNotFoundException", 404,
                () -> owner.service().describeJobExecution("old", "same", REGION));
        assertEquals("QUEUED", owner.service().describeJobExecution("replacement", "same", REGION).getStatus());
    }

    @Test
    void partiallyMigratedSnapshotDoesNotCompleteBeforeTheRemainingLegacyExecution() {
        Fixture legacy = memory();
        job(legacy, "snapshot", "SNAPSHOT", REGION,
                thing(legacy, "first", REGION), thing(legacy, "second", REGION));
        String firstKey = "job-execution:" + REGION + ":first:snapshot";
        IotJobExecution first = legacy.executions().get(firstKey).orElseThrow();
        legacy.executions().put(ACCOUNT + "/" + firstKey, first);
        legacy.executions().delete(firstKey);
        Fixture owner = accountFixture(legacy.things(), legacy.jobs(), legacy.executions(), ACCOUNT);

        owner.service().updateJobExecution("first", "snapshot", "SUCCEEDED", Map.of(), null, REGION);

        assertEquals("IN_PROGRESS", owner.service().describeJob("snapshot", REGION).getStatus());
        awsError("InvalidStateTransitionException", 409,
                () -> owner.service().deleteJob("snapshot", false, REGION));
        assertEquals("QUEUED", owner.service().describeJobExecution("second", "snapshot", REGION).getStatus());
        owner.service().updateJobExecution("second", "snapshot", "FAILED", Map.of(), null, REGION);
        assertEquals("COMPLETED", owner.service().describeJob("snapshot", REGION).getStatus());
        owner.service().deleteJob("snapshot", false, REGION);
        assertTrue(legacy.executions().keys().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionAndDeletionPreserveOtherJobIdsAndMismatchedExecutionKeys(boolean accountWrappedLegacy) {
        Fixture stored = memory();
        String target = thing(stored, "device", REGION);
        job(stored, "x", "SNAPSHOT", REGION, target);
        // CreateJob historically accepted this ID; deleting x must not delete its stored work.
        job(stored, "other:x", "SNAPSHOT", REGION, target);
        IotJobExecution mismatched = new IotJobExecution();
        mismatched.setJobId("x");
        mismatched.setThingName("different");
        mismatched.setStatus("QUEUED");
        String mismatchedKey = "job-execution:" + REGION + ":wrong-key:x";
        stored.executions().put(mismatchedKey, mismatched);
        Fixture owner = accountWrappedLegacy
                ? accountFixture(stored.things(), stored.jobs(), stored.executions(), ACCOUNT) : stored;

        owner.service().updateJobExecution("device", "x", "SUCCEEDED", Map.of(), null, REGION);

        assertEquals("COMPLETED", owner.service().describeJob("x", REGION).getStatus());
        owner.service().deleteJob("x", false, REGION);
        assertDeleted(owner, "x", REGION);
        assertTrue(stored.executions().get("job-execution:" + REGION + ":device:other:x").isPresent());
        assertTrue(stored.executions().get(mismatchedKey).isPresent());
        assertEquals("QUEUED", owner.service().describeJobExecution("device", "other:x", REGION).getStatus());
        assertEquals("IN_PROGRESS", owner.service().describeJob("other:x", REGION).getStatus());
    }

    @Test
    void aForeignCallerCannotClaimTheDefaultAccountsLegacyJobOrExecutions() {
        Fixture legacy = memory();
        job(legacy, "legacy", "SNAPSHOT", REGION, thing(legacy, "device", REGION));
        Fixture foreign = requestAccountFixture(legacy.things(), legacy.jobs(), legacy.executions(), "111111111111");

        awsError("ResourceNotFoundException", 404,
                () -> foreign.service().deleteJob("legacy", true, REGION));

        assertTrue(legacy.jobs().get(ACCOUNT + "/job:" + REGION + ":legacy").isPresent());
        assertTrue(legacy.jobs().get("111111111111/job:" + REGION + ":legacy").isEmpty());
        assertTrue(legacy.executions().get("job-execution:" + REGION + ":device:legacy").isPresent());
        assertTrue(foreign.executions().keys().isEmpty());
        Fixture owner = accountFixture(legacy.things(), legacy.jobs(), legacy.executions(), ACCOUNT);
        assertEquals("IN_PROGRESS", owner.service().describeJob("legacy", REGION).getStatus());
        owner.service().deleteJob("legacy", true, REGION);
        assertTrue(legacy.executions().keys().isEmpty());
    }

    @Test
    void wrapperDeletionOfLegacyPersistentDataSurvivesReloadAndJobIdReuse(@TempDir Path directory) {
        Fixture legacy = persistent(directory);
        job(legacy, "reusable", "SNAPSHOT", REGION, thing(legacy, "original", REGION));
        Fixture owner = accountFixture(legacy.things(), legacy.jobs(), legacy.executions(), ACCOUNT);

        owner.service().deleteJob("reusable", true, REGION);

        Fixture reloaded = persistent(directory);
        assertTrue(reloaded.jobs().keys().isEmpty());
        assertTrue(reloaded.executions().keys().isEmpty());
        Fixture newOwner = accountFixture(reloaded.things(), reloaded.jobs(), reloaded.executions(), ACCOUNT);
        job(newOwner, "reusable", "SNAPSHOT", REGION, thing(newOwner, "replacement", REGION));
        awsError("ResourceNotFoundException", 404,
                () -> newOwner.service().describeJobExecution("original", "reusable", REGION));
        assertEquals("QUEUED", newOwner.service()
                .describeJobExecution("replacement", "reusable", REGION).getStatus());
    }

    @Test
    void rejectedCompletionWriteDoesNotMutateThePreviouslyStoredJob() {
        RejectingCompletionJobs jobs = new RejectingCompletionJobs();
        Fixture fixture = fixture(new InMemoryStorage<>(), jobs, new InMemoryStorage<>(), ACCOUNT);
        IotJob original = job(fixture, "snapshot", "SNAPSHOT", REGION, thing(fixture, "device", REGION));
        Instant previousUpdate = original.getLastUpdatedAt();

        assertThrows(IllegalStateException.class, () -> fixture.service()
                .updateJobExecution("device", "snapshot", "SUCCEEDED", Map.of(), null, REGION));

        assertEquals("IN_PROGRESS", original.getStatus());
        assertEquals(previousUpdate, original.getLastUpdatedAt());
        assertEquals("IN_PROGRESS", fixture.service().describeJob("snapshot", REGION).getStatus());
        assertEquals(original.getJobArn(), jobs.rejected.getJobArn());
        assertEquals(original.getDocument(), jobs.rejected.getDocument());
        assertEquals(original.getTargets(), jobs.rejected.getTargets());
        assertEquals(original.getCreatedAt(), jobs.rejected.getCreatedAt());
    }

    @Test
    void anExecutionUpdateCannotRepublishAnExecutionAfterForcedDeletion() throws Exception {
        PausingExecutions executions = new PausingExecutions();
        Fixture fixture = fixture(new InMemoryStorage<>(), new InMemoryStorage<>(), executions, ACCOUNT);
        job(fixture, "racing", "SNAPSHOT", REGION, thing(fixture, "device", REGION));
        executions.pauseUpdates = true;
        Worker<IotJobExecution> update = start(() -> fixture.service()
                .updateJobExecution("device", "racing", "IN_PROGRESS", Map.of(), null, REGION));
        executions.awaitPaused();
        Worker<Void> deletion = start(() -> {
            fixture.service().deleteJob("racing", true, REGION);
            return null;
        });
        try {
            deletion.awaitBlockedOrFinished(update.thread());
        } finally {
            executions.resume.countDown();
        }
        update.result();
        deletion.result();

        assertDeleted(fixture, "racing", REGION);
        assertTrue(executions.keys().isEmpty(), "a late execution write must not resurrect deleted work");
        assertTrue(fixture.service().listJobExecutionsForThing("device", REGION, null, null).items().isEmpty());
    }

    private static String thing(Fixture fixture, String name, String region) {
        return fixture.service().createThing(name, Map.of(), region).getThingArn();
    }

    private static IotJob job(Fixture fixture, String id, String selection, String region, String... targets) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("document", "{\"operation\":\"test\"}");
        request.put("targetSelection", selection);
        for (String target : targets) {
            request.withArray("targets").add(target);
        }
        return fixture.service().createJob(id, request, region);
    }

    private static void assertDeleted(Fixture fixture, String id, String region) {
        awsError("ResourceNotFoundException", 404, () -> fixture.service().describeJob(id, region));
    }

    private static void awsError(String code, int status, Executable operation) {
        AwsException error = assertThrows(AwsException.class, operation);
        assertEquals(code, error.getErrorCode());
        assertEquals(status, error.getHttpStatus());
    }

    private record Fixture(IotService service, StorageBackend<String, Thing> things,
                           StorageBackend<String, IotJob> jobs, StorageBackend<String, IotJobExecution> executions) {}

    private static Fixture memory() {
        return fixture(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), ACCOUNT);
    }

    private static Fixture accountFixture(StorageBackend<String, Thing> things, StorageBackend<String, IotJob> jobs,
                                          StorageBackend<String, IotJobExecution> executions, String account) {
        return fixture(new AccountAwareStorageBackend<>(things, null, account),
                new AccountAwareStorageBackend<>(jobs, null, account),
                new AccountAwareStorageBackend<>(executions, null, account), account);
    }

    @SuppressWarnings("unchecked")
    private static Fixture requestAccountFixture(StorageBackend<String, Thing> things, StorageBackend<String, IotJob> jobs,
                                                 StorageBackend<String, IotJobExecution> executions, String account) {
        RequestContext context = mock(RequestContext.class);
        when(context.getAccountId()).thenReturn(account);
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        return fixture(new AccountAwareStorageBackend<>(things, contexts, ACCOUNT),
                new AccountAwareStorageBackend<>(jobs, contexts, ACCOUNT),
                new AccountAwareStorageBackend<>(executions, contexts, ACCOUNT), ACCOUNT);
    }

    private static Fixture persistent(Path directory) {
        PersistentStorage<String, Thing> things = new PersistentStorage<>(
                directory.resolve("things.json"), new TypeReference<Map<String, Thing>>() {});
        PersistentStorage<String, IotJob> jobs = new PersistentStorage<>(
                directory.resolve("jobs.json"), new TypeReference<Map<String, IotJob>>() {});
        PersistentStorage<String, IotJobExecution> executions = new PersistentStorage<>(
                directory.resolve("executions.json"), new TypeReference<Map<String, IotJobExecution>>() {});
        things.load();
        jobs.load();
        executions.load();
        return fixture(things, jobs, executions, ACCOUNT);
    }

    private static Fixture fixture(StorageBackend<String, Thing> things, StorageBackend<String, IotJob> jobs,
                                    StorageBackend<String, IotJobExecution> executions, String account) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultRegion()).thenReturn(REGION);
        IotService service = new IotService(things, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), jobs, executions, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), config, new RegionResolver(REGION, account), MAPPER,
                new IotPublishEventRecorder(), mock(IotMqttBrokerService.class), mock(SqsService.class),
                mock(SnsService.class), mock(S3Service.class), mock(KinesisService.class),
                mock(DynamoDbFacade.class), mock(LambdaService.class), mock(FirehoseService.class),
                mock(CloudWatchLogsService.class), null, new IamPolicyEvaluator(MAPPER));
        return new Fixture(service, things, jobs, executions);
    }

    private static <T> Worker<T> start(Callable<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread thread = Thread.ofPlatform().daemon().start(() -> {
            try {
                future.complete(operation.call());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return new Worker<>(thread, future);
    }

    private record Worker<T>(Thread thread, CompletableFuture<T> future) {
        private void awaitBlockedOrFinished(Thread owner) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!future.isDone()) {
                ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
                if (info != null && info.getLockOwnerId() == owner.threadId()) {
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    fail("contending job operation neither completed nor blocked on the first writer");
                }
                Thread.sleep(1);
            }
        }

        private T result() throws Exception {
            return future.get(10, TimeUnit.SECONDS);
        }
    }

    private static final class PausingExecutions extends InMemoryStorage<String, IotJobExecution> {
        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicBoolean claimed = new AtomicBoolean();
        private boolean pauseUpdates;

        private void awaitPaused() throws InterruptedException {
            assertTrue(paused.await(5, TimeUnit.SECONDS), "execution update did not reach publication");
        }

        @Override
        public void put(String key, IotJobExecution value) {
            if (pauseUpdates && "IN_PROGRESS".equals(value.getStatus()) && claimed.compareAndSet(false, true)) {
                paused.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release execution publication");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("execution publication interrupted", error);
                }
            }
            super.put(key, value);
        }
    }

    private static final class RejectingCompletionJobs extends InMemoryStorage<String, IotJob> {
        private IotJob rejected;

        @Override
        public void put(String key, IotJob value) {
            if ("COMPLETED".equals(value.getStatus())) {
                rejected = value;
                throw new IllegalStateException("completion write rejected");
            }
            super.put(key, value);
        }
    }
}

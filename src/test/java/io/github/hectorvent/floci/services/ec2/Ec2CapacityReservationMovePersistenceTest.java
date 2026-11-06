package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class Ec2CapacityReservationMovePersistenceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String FILE = "ec2-capacity-reservations.json";
    private static final String SNAPSHOT = "ec2-capacity-reservations-snapshot.json";
    private static final String WAL = "ec2-capacity-reservations.wal";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void walReplayPreservesCapacityAcrossMoveRecordBoundary(boolean cutBatch) throws Exception {
        Path live = directory.resolve("live");
        Path recovered = Files.createDirectories(directory.resolve("recovered"));
        try (Fixture fixture = new Fixture("wal", live)) {
            CapacityReservation source = fixture.create(3);
            CapacityReservation destination = fixture.create(1);
            fixture.store.flush();
            Files.copy(live.resolve(SNAPSHOT), recovered.resolve(SNAPSHOT));
            fixture.move(source, destination);
            byte[] log = Files.readAllBytes(live.resolve(WAL));
            // A torn batch must not expose either reservation or its completed token.
            Files.write(recovered.resolve(WAL), cutBatch ? Arrays.copyOf(log, log.length - 1) : log);
            try (Fixture restarted = new Fixture("wal", recovered)) {
                CapacityReservation restoredSource = restarted.describe(source);
                CapacityReservation restoredDestination = restarted.describe(destination);
                assertEquals(4, restoredSource.getTotalInstanceCount() + restoredDestination.getTotalInstanceCount(),
                        "A move must not create or destroy capacity when replay stops between its records");
                if (cutBatch) {
                    assertEquals(3, restoredSource.getTotalInstanceCount());
                    assertEquals(1, restoredDestination.getTotalInstanceCount());
                    assertTrue(restoredSource.getCompletedMoves().isEmpty());
                    restarted.move(source, destination);
                    assertEquals(1, restarted.describe(source).getTotalInstanceCount());
                } else {
                    assertEquals(1, restoredSource.getTotalInstanceCount());
                    assertEquals(3, restoredDestination.getTotalInstanceCount());
                    restarted.move(source, destination);
                    assertEquals(1, restarted.describe(source).getTotalInstanceCount());
                    assertEquals(3, restarted.describe(destination).getTotalInstanceCount());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistentAcknowledgementMatchesRestartedState(boolean failWrite) throws Exception {
        Path live = directory.resolve("live");
        Path recovered = Files.createDirectories(directory.resolve("recovered"));
        try (Fixture fixture = new Fixture("persistent", live)) {
            CapacityReservation source = fixture.create(3);
            CapacityReservation destination = fixture.create(1);
            Path snapshot = live.resolve(FILE);
            Path saved = live.resolve("before-move.json");
            if (failWrite) {
                Files.move(snapshot, saved);
                Files.createDirectory(snapshot);
                Files.writeString(snapshot.resolve("owned-blocker"), "Prevent replacement of this directory");
            }
            Ec2Service.CapacityReservationMoveResult acknowledged;
            try {
                if (failWrite) {
                    AwsException failure = assertThrows(AwsException.class, () -> fixture.move(source, destination));
                    assertEquals("InternalError", failure.getErrorCode());
                    assertEquals(500, failure.getHttpStatus());
                    assertEquals(3, fixture.describe(source).getTotalInstanceCount());
                    assertEquals(1, fixture.describe(destination).getTotalInstanceCount());
                    assertTrue(fixture.describe(source).getCompletedMoves().isEmpty());
                    acknowledged = new Ec2Service.CapacityReservationMoveResult(source, destination, 0);
                } else {
                    acknowledged = fixture.move(source, destination);
                }
            } finally {
                if (failWrite) {
                    Files.delete(snapshot.resolve("owned-blocker"));
                    Files.delete(snapshot);
                    Files.move(saved, snapshot);
                }
            }
            Files.copy(snapshot, recovered.resolve(FILE));
            try (Fixture restarted = new Fixture("persistent", recovered)) {
                assertEquals(acknowledged.source().getTotalInstanceCount(),
                        restarted.describe(source).getTotalInstanceCount(),
                        "A write-through move acknowledged success although its snapshot write failed");
                assertEquals(acknowledged.destination().getTotalInstanceCount(),
                        restarted.describe(destination).getTotalInstanceCount());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hybridSnapshotContainsWholeMove(boolean flushDuringMove) throws Exception {
        Path live = directory.resolve("live");
        Path recovered = Files.createDirectories(directory.resolve("recovered"));
        try (Fixture fixture = new Fixture("hybrid", live);
                ExecutorService worker = Executors.newSingleThreadExecutor()) {
            CapacityReservation source = fixture.create(3);
            CountDownLatch beforePublish = new CountDownLatch(1);
            CountDownLatch resumeMove = new CountDownLatch(1);
            doAnswer(invocation -> {
                beforePublish.countDown();
                assertTrue(resumeMove.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(fixture.store).putAllAtomically(any());
            CapacityReservation destination = fixture.create(1);
            fixture.store.flush();
            Future<Ec2Service.CapacityReservationMoveResult> move =
                    worker.submit(() -> fixture.move(source, destination));
            try {
                assertTrue(beforePublish.await(10, TimeUnit.SECONDS));
                if (flushDuringMove) {
                    fixture.store.flush();
                    Files.copy(live.resolve(FILE), recovered.resolve(FILE));
                }
            } finally {
                resumeMove.countDown();
            }
            move.get(10, TimeUnit.SECONDS);
            if (!flushDuringMove) {
                fixture.store.flush();
                Files.copy(live.resolve(FILE), recovered.resolve(FILE));
            }
            try (Fixture restarted = new Fixture("hybrid", recovered)) {
                CapacityReservation restoredSource = restarted.describe(source);
                CapacityReservation restoredDestination = restarted.describe(destination);
                assertEquals(4, restoredSource.getTotalInstanceCount() + restoredDestination.getTotalInstanceCount(),
                        "An asynchronous flush may be stale, but must not snapshot half of a move");
                if (flushDuringMove) {
                    assertEquals(3, restoredSource.getTotalInstanceCount());
                    assertEquals(1, restoredDestination.getTotalInstanceCount());
                    assertTrue(restoredSource.getCompletedMoves().isEmpty());
                } else {
                    assertEquals(1, restoredSource.getTotalInstanceCount());
                    assertEquals(3, restoredDestination.getTotalInstanceCount());
                }
            }
        }
    }

    @Test
    void concurrentTagUpdateSurvivesMovePublication() throws Exception {
        try (Fixture fixture = new Fixture("memory", directory);
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CapacityReservation source = fixture.create(3);
            CapacityReservation destination = fixture.create(1);
            CountDownLatch beforePublish = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            doAnswer(invocation -> {
                beforePublish.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(fixture.store).putAllAtomically(any());
            Future<?> move = workers.submit(() -> fixture.move(source, destination));
            try {
                assertTrue(beforePublish.await(10, TimeUnit.SECONDS));
                AtomicReference<Thread> taggingThread = new AtomicReference<>();
                Future<?> tags = workers.submit(() -> {
                    taggingThread.set(Thread.currentThread());
                    fixture.service.createTags(REGION, List.of(source.getCapacityReservationId()),
                            List.of(new Tag("purpose", "move")));
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> tags.isDone()
                        || taggingThread.get() != null && taggingThread.get().getState() == Thread.State.BLOCKED);
                resume.countDown();
                move.get(10, TimeUnit.SECONDS);
                tags.get(10, TimeUnit.SECONDS);
                CapacityReservation result = fixture.describe(source);
                assertEquals(1, result.getTotalInstanceCount());
                assertEquals(1, result.getTags().size());
                assertEquals("purpose", result.getTags().getFirst().getKey());
                assertEquals("move", result.getTags().getFirst().getValue());
            } finally {
                resume.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tornWalMoveCannotSplitInstanceMembershipFromCapacity(boolean torn) throws Exception {
        Path live = directory.resolve("live");
        Path recovered = Files.createDirectories(directory.resolve("recovered"));
        try (Fixture fixture = new Fixture("wal", live)) {
            CapacityReservation source = fixture.create(1);
            Instance instance = fixture.launch();
            CapacityReservation destination = fixture.create(2);
            fixture.factory.flushAll();
            Files.copy(live.resolve(SNAPSHOT), recovered.resolve(SNAPSHOT));
            Files.copy(live.resolve("ec2-instances-snapshot.json"), recovered.resolve("ec2-instances-snapshot.json"));
            fixture.service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                    destination.getCapacityReservationId(), 1, "consumed");
            byte[] log = Files.readAllBytes(live.resolve(WAL));
            Files.write(recovered.resolve(WAL), torn ? Arrays.copyOf(log, log.length - 1) : log);
            try (Fixture restarted = new Fixture("wal", recovered)) {
                assertEquals(torn ? source.getCapacityReservationId() : destination.getCapacityReservationId(),
                        restarted.service.instanceCapacityReservationId(instance));
                assertEquals(torn ? 1 : 0, restarted.describe(source).getTotalInstanceCount());
                assertEquals(torn ? 2 : 3, restarted.describe(destination).getTotalInstanceCount());
                assertEquals(torn, restarted.describe(source).getCompletedMoves().isEmpty());
                assertEquals(2, restarted.describe(destination).getAvailableInstanceCount());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void consumedMoveMembershipSurvivesRestartAndReleasesOnce(String mode) {
        CapacityReservation source;
        CapacityReservation destination;
        String instanceId;
        try (Fixture fixture = new Fixture(mode, directory)) {
            source = fixture.create(1);
            Instance instance = fixture.launch();
            instanceId = instance.getInstanceId();
            destination = fixture.create(2);
            fixture.service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                    destination.getCapacityReservationId(), 1, "consumed");
            assertEquals(destination.getCapacityReservationId(), fixture.service.instanceCapacityReservationId(instance));
        }
        try (Fixture restarted = new Fixture(mode, directory)) {
            Instance instance = restarted.service.describeInstances(REGION, List.of(instanceId), Map.of())
                    .getFirst().getInstances().getFirst();
            assertEquals(destination.getCapacityReservationId(), restarted.service.instanceCapacityReservationId(instance));
            assertEquals(2, restarted.describe(destination).getAvailableInstanceCount());
            assertEquals("cancelled", restarted.describe(source).getState());
            restarted.service.stopInstances(REGION, List.of(instanceId));
            restarted.service.stopInstances(REGION, List.of(instanceId));
            assertEquals(3, restarted.describe(destination).getAvailableInstanceCount());
            assertTrue(restarted.describe(destination).getInstanceIds().isEmpty());
        }
    }

    @Test
    void failedConsumedMovePreservesMembershipCountsAndToken() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            CapacityReservation source = fixture.create(1);
            Instance instance = fixture.launch();
            CapacityReservation destination = fixture.create(2);
            doThrow(new IllegalStateException("injected batch failure")).when(fixture.store).putAllAtomically(any());
            AwsException failure = assertThrows(AwsException.class, () ->
                    fixture.service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                            destination.getCapacityReservationId(), 1, "consumed"));
            assertEquals("InternalError", failure.getErrorCode());
            assertEquals(source.getCapacityReservationId(), fixture.service.instanceCapacityReservationId(instance));
            assertEquals(0, fixture.describe(source).getAvailableInstanceCount());
            assertTrue(fixture.describe(source).getCompletedMoves().isEmpty());
            assertTrue(fixture.describe(destination).getInstanceIds().isEmpty());
            reset(fixture.store);
            fixture.service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                    destination.getCapacityReservationId(), 1, "consumed");
            assertEquals(destination.getCapacityReservationId(), fixture.service.instanceCapacityReservationId(instance));
        }
    }

    @Test
    void moveReconcilesFailedReleaseBeforeTransferringCapacity() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            CapacityReservation source = fixture.create(2);
            Instance instance = fixture.launch();
            CapacityReservation destination = fixture.create(1);
            doThrow(new IllegalStateException("injected release failure")).when(fixture.store).putAllAtomically(any());
            assertThrows(AwsException.class,
                    () -> fixture.service.stopInstances(REGION, List.of(instance.getInstanceId())));
            reset(fixture.store);
            Ec2Service.CapacityReservationMoveResult result = fixture.move(source, destination);
            assertEquals(3, result.destination().getAvailableInstanceCount());
            assertTrue(result.destination().getInstanceIds().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admissionSerializesWithMove(boolean restart) throws Exception {
        try (Fixture fixture = new Fixture("memory", directory);
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CapacityReservation source = fixture.create(1);
            CapacityReservation destination = fixture.create(1);
            Instance stopped = restart ? fixture.launch() : null;
            if (stopped != null) {
                fixture.service.stopInstances(REGION, List.of(stopped.getInstanceId()));
            }
            CountDownLatch beforePublish = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            AtomicReference<String> acquiredId = new AtomicReference<>();
            doAnswer(invocation -> {
                Map<String, CapacityReservation> batch = invocation.getArgument(0);
                acquiredId.compareAndSet(null, batch.values().iterator().next().getCapacityReservationId());
                beforePublish.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(fixture.store).putAllAtomically(any());
            Future<Instance> admission = workers.submit(() -> {
                if (stopped == null) {
                    return fixture.launch();
                }
                fixture.service.startInstances(REGION, List.of(stopped.getInstanceId()));
                return stopped;
            });
            try {
                assertTrue(beforePublish.await(10, TimeUnit.SECONDS));
                CapacityReservation admittedSource = source.getCapacityReservationId().equals(acquiredId.get())
                        ? source : destination;
                CapacityReservation other = admittedSource == source ? destination : source;
                AtomicReference<Thread> mutationThread = new AtomicReference<>();
                Future<?> mutation = workers.submit(() -> {
                    mutationThread.set(Thread.currentThread());
                    fixture.service.moveCapacityReservationInstances(REGION, admittedSource.getCapacityReservationId(),
                            other.getCapacityReservationId(), 1, "admission-race");
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> mutation.isDone()
                        || mutationThread.get() != null && mutationThread.get().getState() == Thread.State.BLOCKED);
                assertTrue(!mutation.isDone(), "Mutation must wait for atomic admission publication");
                resume.countDown();
                Instance instance = admission.get(10, TimeUnit.SECONDS);
                mutation.get(10, TimeUnit.SECONDS);
                CapacityReservation currentSource = fixture.describe(admittedSource);
                CapacityReservation currentDestination = fixture.describe(other);
                assertTrue(currentSource.getInstanceIds().isEmpty());
                assertEquals(List.of(instance.getInstanceId()), currentDestination.getInstanceIds());
                assertEquals(2, currentDestination.getTotalInstanceCount());
                assertEquals(1, currentDestination.getAvailableInstanceCount());
                assertTrue(currentSource.getCompletedMoves().containsKey("admission-race"));
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void rejectedLaunchAfterMoveReclaimsItsPublishedResources() throws Exception {
        try (Fixture fixture = new Fixture("persistent", directory);
                ExecutorService workers = Executors.newSingleThreadExecutor()) {
            CapacityReservation source = fixture.create(1);
            CapacityReservation destination = fixture.create(1);
            CountDownLatch beforeAdmission = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            doAnswer(invocation -> {
                beforeAdmission.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(fixture.service).acquireCapacity(eq(REGION), any());
            Future<Instance> launch = workers.submit(() -> fixture.launch(source.getCapacityReservationId()));
            try {
                assertTrue(beforeAdmission.await(10, TimeUnit.SECONDS));
                fixture.service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                        destination.getCapacityReservationId(), 1, "before-admission");
                resume.countDown();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> launch.get(10, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof AwsException);
                assertTrue(fixture.describe(source).getInstanceIds().isEmpty());
                assertTrue(fixture.describe(destination).getInstanceIds().isEmpty());
                assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                        .flatMap(reservation -> reservation.getInstances().stream())
                        .allMatch(instance -> "terminated".equals(instance.getState().getName())));
                assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void consumedMoveSelectsOpenMemberWithoutRetargetingExplicitMember() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            CapacityReservation source = fixture.create(2);
            Instance targeted = fixture.launch(source.getCapacityReservationId());
            Instance open = fixture.launch();
            fixture.service.describeInstances(REGION,
                    List.of(targeted.getInstanceId(), open.getInstanceId()), Map.of());
            CapacityReservation destination = fixture.create(1);

            fixture.service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                    destination.getCapacityReservationId(), 1, "eligible-member");

            assertEquals(List.of(targeted.getInstanceId()), fixture.describe(source).getInstanceIds());
            assertEquals(List.of(open.getInstanceId()), fixture.describe(destination).getInstanceIds());
            assertEquals(1, fixture.describe(source).getTotalInstanceCount());
            assertEquals(0, fixture.describe(source).getAvailableInstanceCount());
            assertEquals(2, fixture.describe(destination).getTotalInstanceCount());
            assertEquals(1, fixture.describe(destination).getAvailableInstanceCount());
        }
    }

    @Test
    void explicitlyTargetedInstanceCannotMoveEvenBetweenOpenReservations() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            CapacityReservation source = fixture.create(1);
            CapacityReservation destination = fixture.create(1);
            Instance targeted = fixture.launch(source.getCapacityReservationId());
            fixture.service.describeInstances(REGION, List.of(targeted.getInstanceId()), Map.of());

            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                            destination.getCapacityReservationId(), 1, "targeted-consumer"));

            assertEquals("Unsupported", failure.getErrorCode());
            assertEquals(List.of(targeted.getInstanceId()), fixture.describe(source).getInstanceIds());
            assertEquals(0, fixture.describe(source).getAvailableInstanceCount());
            assertEquals(1, fixture.describe(destination).getAvailableInstanceCount());
            assertTrue(fixture.describe(source).getCompletedMoves().isEmpty());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final StorageFactory factory;
        private final AccountAwareStorageBackend<CapacityReservation> store;
        private final Ec2Service service;

        private Fixture(String mode, Path path) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.services().ec2().mock()).thenReturn(true);
            when(config.services().ec2().mockPublicAddresses())
                    .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
            when(config.storage().persistentPath()).thenReturn(path.toString());
            when(config.storage().wal().compactionIntervalMs()).thenReturn(3600000L);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("ec2")).thenReturn(mode);
            when(access.storageFlushInterval("ec2")).thenReturn(3600000L);
            factory = new StorageFactory(config, access);
            store = spy(factory.create("ec2", FILE, new TypeReference<Map<String, CapacityReservation>>() {}));
            StorageFactory serviceFactory = spy(factory);
            doReturn(store).when(serviceFactory).create(eq("ec2"), eq(FILE), any());
            Ec2ImageCatalog catalog = new Ec2ImageCatalog();
            service = spy(new Ec2Service(config, null, null, new AmiImageResolver(catalog),
                    catalog, new Ec2InstanceTypeCatalog(), serviceFactory));
        }

        private Instance launch() {
            return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1,
                    null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        }

        private Instance launch(String targetId) {
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
            target.setCapacityReservationId(targetId);
            specification.setCapacityReservationTarget(target);
            return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null, null,
                    List.of(), null, null, null, null, 0, null, null, null, null, false, null, null, null,
                    false, null, specification).getInstances().getFirst();
        }

        private CapacityReservation create(int count) {
            return service.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX", REGION + "a",
                    null, count, null, null, null, null, null, null, null, null);
        }

        private Ec2Service.CapacityReservationMoveResult move(CapacityReservation source,
                CapacityReservation destination) {
            return service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                    destination.getCapacityReservationId(), 2, "audit-move");
        }

        private CapacityReservation describe(CapacityReservation reservation) {
            return service.describeCapacityReservations(REGION,
                    List.of(reservation.getCapacityReservationId()), Map.of()).getFirst();
        }

        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
}

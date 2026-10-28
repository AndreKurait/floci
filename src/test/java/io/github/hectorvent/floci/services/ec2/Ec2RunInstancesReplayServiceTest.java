package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.RunInstancesReplayJournal;
import io.github.hectorvent.floci.services.ec2.model.RunInstancesReplayRecord.Allocation;
import io.github.hectorvent.floci.services.ec2.model.RunInstancesReplayRecord.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class Ec2RunInstancesReplayServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String FILE = "ec2-run-instances-replays.json";
    private static final String SNAPSHOT = "ec2-run-instances-replays-snapshot.json";
    private static final String WAL = "ec2-run-instances-replays.wal";
    private static final Map<String, List<String>> PARAMETERS = Map.of("ImageId", List.of("ami-test"));
    private static final Allocation ALLOCATION = new Allocation("i-owned", "vol-owned", "subnet-owned", "10.0.0.5", null);
    private static final Result RESULT = new Result("r-owned", List.of("i-owned"));

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void exactReplayReturnsOriginalOutcomeWithoutLaunchingOrCompensating(String mode) {
        try (Fixture fixture = new Fixture(mode, directory)) {
            AtomicInteger launches = new AtomicInteger();
            assertEquals(RESULT, execute(fixture.replay, PARAMETERS, attempt -> {
                launches.incrementAndGet();
                attempt.remember(ALLOCATION);
                return RESULT;
            }, allocations -> fail("Successful launch must not compensate")));
            assertEquals(RESULT, execute(fixture.replay, PARAMETERS, attempt -> fail("Duplicate launch"),
                    allocations -> fail("Completed launch must not compensate")));
            assertEquals(1, launches.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void restartRetainsSuccessfulOutcomeAndParameterIdentity(String mode) {
        try (Fixture fixture = new Fixture(mode, directory)) {
            execute(fixture.replay, PARAMETERS, attempt -> RESULT, allocations -> fail());
        }
        try (Fixture fixture = new Fixture(mode, directory)) {
            assertEquals(RESULT, execute(fixture.replay, PARAMETERS, attempt -> fail(), allocations -> fail()));
            AwsException error = assertThrows(AwsException.class, () -> execute(fixture.replay,
                    Map.of("ImageId", List.of("ami-different")), attempt -> fail(), allocations -> fail()));
            assertEquals("IdempotentParameterMismatch", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
        }
    }

    @Test
    void accountRegionZoneAndTokenCaseHaveIndependentOutcomes() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            AtomicInteger launches = new AtomicInteger();
            Function<Ec2RunInstancesReplay.Attempt, Result> launch = attempt ->
                    new Result("r-" + launches.incrementAndGet(), List.of());
            Consumer<List<Allocation>> cleanup = allocations -> fail();
            Result regional = fixture.replay.execute(ACCOUNT, REGION, "us-east-1a", true, "Token", PARAMETERS, launch, cleanup);
            Result zonal = fixture.replay.execute(ACCOUNT, REGION, "us-east-1b", false, "Token", PARAMETERS, launch, cleanup);
            assertNotEquals(regional, zonal);
            assertNotEquals(regional, fixture.replay.execute(ACCOUNT, "us-west-2", "us-west-2a", true, "Token",
                    PARAMETERS, launch, cleanup));
            assertNotEquals(regional, fixture.replay.execute("222222222222", REGION, "us-east-1a", true, "Token",
                    PARAMETERS, launch, cleanup));
            assertNotEquals(regional, fixture.replay.execute(ACCOUNT, REGION, "us-east-1a", true, "token",
                    PARAMETERS, launch, cleanup));
            assertEquals(regional, fixture.replay.execute(ACCOUNT, REGION, "us-east-1b", true, "Token",
                    PARAMETERS, launch, cleanup), "Regional replay must retain its original chosen zone");
            assertEquals(5, launches.get());
        }
    }

    @Test
    void addingExplicitPlacementConflictsWithRegionalLaunchInThatZone() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            execute(fixture.replay, PARAMETERS, attempt -> RESULT, allocations -> fail());
            AwsException failure = assertThrows(AwsException.class, () -> fixture.replay.execute(ACCOUNT, REGION,
                    "us-east-1a", false, "token", Map.of("ImageId", List.of("ami-test"),
                            "Placement.AvailabilityZone", List.of("us-east-1a")), attempt -> fail(), allocations -> fail()));
            assertEquals("IdempotentParameterMismatch", failure.getErrorCode());
        }
    }

    @Test
    void concurrentSameTokenLaunchesOnce() throws Exception {
        try (Fixture fixture = new Fixture("memory", directory);
                ExecutorService workers = Executors.newFixedThreadPool(8)) {
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger launches = new AtomicInteger();
            List<Future<Result>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return execute(fixture.replay, PARAMETERS, attempt -> {
                        launches.incrementAndGet();
                        attempt.remember(ALLOCATION);
                        return RESULT;
                    }, allocations -> fail());
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            for (Future<Result> future : results) {
                assertEquals(RESULT, future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, launches.get());
        }
    }

    @Test
    void unrelatedTokensDoNotWaitForSlowLaunch() throws Exception {
        try (Fixture fixture = new Fixture("memory", directory);
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<Result> first = workers.submit(() -> execute(fixture.replay, PARAMETERS, attempt -> {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return RESULT;
            }, allocations -> fail()));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                Future<Result> other = workers.submit(() -> fixture.replay.execute(ACCOUNT, REGION, "us-east-1a", true, "other",
                        PARAMETERS, attempt -> RESULT, allocations -> fail()));
                assertEquals(RESULT, other.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertEquals(RESULT, first.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedOwnershipWriteCannotAuthorizeAllocation() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            AtomicInteger writes = new AtomicInteger();
            doAnswer(invocation -> {
                if (writes.incrementAndGet() == 2) {
                    throw new IllegalStateException("ownership write failed");
                }
                return invocation.callRealMethod();
            }).when(fixture.store).putAllDurablyForAccount(anyString(), anyMap());
            AtomicInteger allocations = new AtomicInteger();
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> execute(fixture.replay,
                    PARAMETERS, attempt -> {
                        attempt.remember(ALLOCATION);
                        allocations.incrementAndGet();
                        return RESULT;
                    }, owned -> assertTrue(owned.isEmpty())));
            assertEquals("ownership write failed", failure.getMessage());
            assertEquals(0, allocations.get());
        }
    }

    @Test
    void failedOutcomeWriteCompensatesOwnedResourcesBeforeRetry() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            AtomicInteger writes = new AtomicInteger();
            doAnswer(invocation -> {
                if (writes.incrementAndGet() == 3) {
                    throw new IllegalStateException("outcome write failed");
                }
                return invocation.callRealMethod();
            }).when(fixture.store).putAllDurablyForAccount(anyString(), anyMap());
            List<Allocation> reclaimed = new ArrayList<>();
            assertThrows(IllegalStateException.class, () -> execute(fixture.replay, PARAMETERS, attempt -> {
                attempt.remember(ALLOCATION);
                return RESULT;
            }, reclaimed::addAll));
            assertEquals(List.of(ALLOCATION), reclaimed);
            reset(fixture.store);
            assertEquals(RESULT, execute(fixture.replay, PARAMETERS, attempt -> RESULT, owned -> fail()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void failedCompensationRetainsOwnershipAcrossRestart(String mode) {
        try (Fixture fixture = new Fixture(mode, directory)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> execute(fixture.replay,
                    PARAMETERS, attempt -> {
                        attempt.remember(ALLOCATION);
                        throw new IllegalStateException("launch failed");
                    }, owned -> { throw new IllegalStateException("cleanup failed"); }));
            assertEquals("launch failed", failure.getMessage());
            assertEquals("cleanup failed", failure.getSuppressed()[0].getMessage());
        }
        try (Fixture fixture = new Fixture(mode, directory)) {
            AtomicReference<List<Allocation>> recovered = new AtomicReference<>();
            assertEquals(RESULT, execute(fixture.replay, PARAMETERS, attempt -> {
                assertEquals(List.of(ALLOCATION), recovered.get());
                return RESULT;
            }, recovered::set));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void interruptedAttemptRecoversBeforeChangedRequestCanLaunch(String mode) {
        try (Fixture fixture = new Fixture(mode, directory)) {
            assertThrows(AssertionError.class, () -> execute(fixture.replay, PARAMETERS, attempt -> {
                attempt.remember(ALLOCATION);
                throw new AssertionError("simulate process interruption");
            }, owned -> fail()));
        }
        try (Fixture fixture = new Fixture(mode, directory)) {
            AtomicInteger recovered = new AtomicInteger();
            assertEquals(RESULT, execute(fixture.replay, Map.of("ImageId", List.of("ami-new")), attempt -> {
                assertEquals(1, recovered.get());
                return RESULT;
            }, owned -> {
                assertEquals(List.of(ALLOCATION), owned);
                recovered.incrementAndGet();
            }));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tornWalOutcomeRetainsRecoverableIntent(boolean truncate) throws Exception {
        Path live = directory.resolve("live");
        Path recovered = Files.createDirectories(directory.resolve("recovered"));
        try (Fixture fixture = new Fixture("wal", live)) {
            execute(fixture.replay, PARAMETERS, attempt -> {
                attempt.remember(ALLOCATION);
                fixture.factory.flushAll();
                try {
                    Files.copy(live.resolve(SNAPSHOT), recovered.resolve(SNAPSHOT));
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
                return RESULT;
            }, owned -> fail());
            byte[] wal = Files.readAllBytes(live.resolve(WAL));
            Files.write(recovered.resolve(WAL), truncate ? Arrays.copyOf(wal, wal.length - 1) : wal);
            try (Fixture restart = new Fixture("wal", recovered)) {
                AtomicInteger compensation = new AtomicInteger();
                AtomicInteger launches = new AtomicInteger();
                assertEquals(RESULT, execute(restart.replay, PARAMETERS, attempt -> {
                    launches.incrementAndGet();
                    return RESULT;
                }, owned -> {
                    assertEquals(List.of(ALLOCATION), owned);
                    compensation.incrementAndGet();
                }));
                assertEquals(truncate ? 1 : 0, launches.get());
                assertEquals(truncate ? 1 : 0, compensation.get());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void failedRecoveryPublicationRetainsItsZoneWhileOtherZonesRecover(String mode) {
        try (Fixture fixture = new Fixture(mode, directory)) {
            for (String zone : List.of("us-east-1a", "us-east-1b")) {
                assertThrows(AssertionError.class, () -> fixture.replay.execute(ACCOUNT, REGION, zone,
                        false, "token", PARAMETERS, attempt -> {
                            attempt.remember(ALLOCATION);
                            throw new AssertionError("interrupted launch");
                        }, allocations -> fail()));
            }
            doThrow(new IllegalStateException("journal unavailable")).doCallRealMethod()
                    .when(fixture.store).putAllDurablyForAccount(anyString(), anyMap());
            AtomicInteger recovered = new AtomicInteger();
            assertDoesNotThrow(() -> fixture.replay.recoverPending(recovery -> recovered.incrementAndGet()));
            assertEquals(2, recovered.get());
            assertEquals(1, fixture.store.scan(key -> true).getFirst().zones().size());
        }
        try (Fixture fixture = new Fixture(mode, directory)) {
            AtomicInteger recovered = new AtomicInteger();
            fixture.replay.recoverPending(recovery -> recovered.incrementAndGet());
            assertEquals(1, recovered.get(), "Unpublished recovery must remain retryable after restart");
            assertTrue(fixture.store.scan(key -> true).getFirst().zones().isEmpty());
        }
    }

    @Test
    void requestCollectionsCannotMutatePublishedIdentity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            List<String> image = new ArrayList<>(List.of("ami-test"));
            execute(fixture.replay, Map.of("ImageId", image), attempt -> RESULT, owned -> fail());
            image.set(0, "ami-changed");
            assertEquals(RESULT, execute(fixture.replay, PARAMETERS, attempt -> fail(), owned -> fail()));
        }
    }

    private static Result execute(Ec2RunInstancesReplay replay, Map<String, List<String>> parameters,
                                  Function<Ec2RunInstancesReplay.Attempt, Result> launch,
                                  Consumer<List<Allocation>> compensate) {
        return replay.execute(ACCOUNT, REGION, "us-east-1a", true, "token", parameters, launch, compensate);
    }

    private static final class Fixture implements AutoCloseable {
        private final StorageFactory factory;
        private final AccountAwareStorageBackend<RunInstancesReplayJournal> store;
        private final Ec2RunInstancesReplay replay;

        private Fixture(String mode, Path directory) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.storage().persistentPath()).thenReturn(directory.toString());
            when(config.storage().wal().compactionIntervalMs()).thenReturn(3600000L);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("ec2")).thenReturn(mode);
            when(access.storageFlushInterval("ec2")).thenReturn(3600000L);
            factory = new StorageFactory(config, access);
            store = spy(factory.create("ec2", FILE, new TypeReference<Map<String, RunInstancesReplayJournal>>() {}));
            replay = new Ec2RunInstancesReplay(store);
        }

        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
}

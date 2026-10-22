package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ec2CapacityReservationMoveServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private Ec2Service service;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services().ec2().mock()).thenReturn(true);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        service = newService(new InMemoryStorageFactory(ACCOUNT));
    }

    @Test
    void transfersCapacityAndReturnsIndependentSnapshots() {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        Ec2Service.CapacityReservationMoveResult first = move(source, destination, 1, "first");
        move(source, destination, 2, "second");
        assertEquals(2, first.source().getTotalInstanceCount());
        assertEquals("active", first.source().getState());
        assertEquals(2, first.destination().getTotalInstanceCount());
        assertCounts(source, 0, 0);
        assertEquals("cancelled", describe(source).getState());
        assertCounts(destination, 4, 4);
    }

    @Test
    void retryAfterSourceCancellationDoesNotTransferAgain() {
        CapacityReservation source = create(service, 2);
        CapacityReservation destination = create(service, 1);
        move(source, destination, 2, "retry");
        Ec2Service.CapacityReservationMoveResult retry = move(source, destination, 2, "retry");
        assertEquals(2, retry.instanceCount());
        assertEquals("cancelled", retry.source().getState());
        assertCounts(destination, 3, 3);
        assertError("IdempotentParameterMismatch", () -> move(source, destination, 1, "retry"));
        CapacityReservation other = create(service, 2);
        assertError("IdempotentParameterMismatch", () -> move(other, destination, 2, "retry"));
        assertError("IdempotentParameterMismatch", () -> move(source, other, 2, "retry"));
    }

    @Test
    void clientTokensAreCaseSensitive() {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        move(source, destination, 1, "Token");
        move(source, destination, 1, "token");
        assertCounts(source, 1, 1);
        assertCounts(destination, 3, 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 4})
    void invalidCountDoesNotChangeEitherReservation(int count) {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        assertError("InvalidParameterValue", () -> move(source, destination, count, null));
        assertCounts(source, 3, 3);
        assertCounts(destination, 1, 1);
    }

    @Test
    void requiresAllParametersAndDistinctReservations() {
        CapacityReservation source = create(service, 3);
        String id = source.getCapacityReservationId();
        assertError("MissingParameter", () -> service.moveCapacityReservationInstances(REGION, null, id, 1, null));
        assertError("MissingParameter", () -> service.moveCapacityReservationInstances(REGION, id, "", 1, null));
        assertError("MissingParameter", () -> service.moveCapacityReservationInstances(REGION, id, id, null, null));
        assertError("InvalidParameterValue", () -> move(source, source, 1, null));
        assertCounts(source, 3, 3);
    }

    @Test
    void rejectsMissingReservationsWithoutChangingExistingCapacity() {
        CapacityReservation source = create(service, 3);
        assertError("InvalidCapacityReservationId.NotFound", () -> service.moveCapacityReservationInstances(
                REGION, source.getCapacityReservationId(), "cr-missing", 1, null));
        assertCounts(source, 3, 3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsInactiveSourceOrDestination(boolean cancelSource) {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        service.cancelCapacityReservation(REGION,
                (cancelSource ? source : destination).getCapacityReservationId());
        assertError("InvalidCapacityReservationState", () -> move(source, destination, 1, null));
        assertCounts(source, 3, cancelSource ? 0 : 3);
        assertCounts(destination, 1, cancelSource ? 1 : 0);
    }

    @Test
    void requiresMatchingEndTimeButNotEligibilityOrTags() {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        Instant end = Instant.parse("2030-01-01T00:00:00Z");
        service.modifyCapacityReservation(REGION, source.getCapacityReservationId(), null, end, "limited", null);
        assertError("InvalidParameterValue", () -> move(source, destination, 1, "move"));
        service.modifyCapacityReservation(REGION, destination.getCapacityReservationId(),
                null, end.plusSeconds(1), "limited", "targeted");
        assertError("InvalidParameterValue", () -> move(source, destination, 1, "move"));
        service.modifyCapacityReservation(REGION, destination.getCapacityReservationId(), null, end, "limited", null);
        move(source, destination, 1, "move");
        assertCounts(source, 2, 2);
        assertCounts(destination, 2, 2);
        assertEquals("targeted", describe(destination).getInstanceMatchCriteria());
    }

    @Test
    void neverMovesUsedCapacityWithoutInstanceAssociationSupport() {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        source.setAvailableInstanceCount(1);
        assertError("InvalidParameterValue", () -> move(source, destination, 2, null));
        move(source, destination, 1, null);
        assertCounts(source, 2, 0);
        assertEquals("active", describe(source).getState());
        assertCounts(destination, 2, 2);
    }

    @Test
    void rejectsDestinationIntegerOverflow() {
        CapacityReservation source = create(service, 2);
        CapacityReservation destination = create(service, Integer.MAX_VALUE);
        assertError("InvalidParameterValue", () -> move(source, destination, 1, null));
        assertCounts(source, 2, 2);
        assertCounts(destination, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Test
    void concurrentMovesCannotOversellTheSource() throws Exception {
        CapacityReservation source = create(service, 5);
        CapacityReservation destination = create(service, 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                results.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        move(source, destination, 1, null);
                        return true;
                    } catch (AwsException expected) {
                        assertEquals("InvalidCapacityReservationState", expected.getErrorCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertEquals(5, successes);
        }
        assertCounts(source, 0, 0);
        assertCounts(destination, 6, 6);
    }

    @Test
    void concurrentRetriesTransferOnlyOnce() throws Exception {
        CapacityReservation source = create(service, 5);
        CapacityReservation destination = create(service, 1);
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            List<Future<Ec2Service.CapacityReservationMoveResult>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(workers.submit(() -> move(source, destination, 2, "same-token")));
            }
            for (Future<Ec2Service.CapacityReservationMoveResult> result : results) {
                assertEquals(2, result.get(10, TimeUnit.SECONDS).instanceCount());
            }
        }
        assertCounts(source, 3, 3);
        assertCounts(destination, 3, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void completedMoveSurvivesStorageRestart(String mode, @TempDir Path directory) {
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("ec2")).thenReturn(mode);
        when(access.storageFlushInterval("ec2")).thenReturn(60000L);
        StorageFactory firstFactory = new StorageFactory(config, access);
        CapacityReservation source;
        CapacityReservation destination;
        try {
            service = newService(firstFactory);
            source = create(service, 2);
            destination = create(service, 1);
            move(source, destination, 2, "persisted");
        } finally {
            firstFactory.shutdownAll();
        }
        StorageFactory secondFactory = new StorageFactory(config, access);
        try {
            service = newService(secondFactory);
            move(source, destination, 2, "persisted");
            assertCounts(source, 0, 0);
            assertCounts(destination, 3, 3);
            assertError("IdempotentParameterMismatch", () -> move(source, destination, 1, "persisted"));
        } finally {
            secondFactory.shutdownAll();
        }
    }

    private Ec2Service newService(StorageFactory factory) {
        Ec2ImageCatalog catalog = new Ec2ImageCatalog();
        return new Ec2Service(config, null, null, new AmiImageResolver(catalog),
                catalog, new Ec2InstanceTypeCatalog(), factory);
    }

    private CapacityReservation create(Ec2Service target, int count) {
        return target.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX", REGION + "a",
                null, count, null, null, null, null, null, null, null, null);
    }

    private Ec2Service.CapacityReservationMoveResult move(CapacityReservation source,
            CapacityReservation destination, int count, String token) {
        return service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                destination.getCapacityReservationId(), count, token);
    }

    private CapacityReservation describe(CapacityReservation reservation) {
        return service.describeCapacityReservations(
                REGION, List.of(reservation.getCapacityReservationId()), Map.of()).getFirst();
    }

    private void assertCounts(CapacityReservation reservation, int total, int available) {
        CapacityReservation current = describe(reservation);
        assertEquals(total, current.getTotalInstanceCount());
        assertEquals(available, current.getAvailableInstanceCount());
    }

    private void assertError(String code, Runnable request) {
        assertEquals(code, assertThrows(AwsException.class, request::run).getErrorCode());
    }
}

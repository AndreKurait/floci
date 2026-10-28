package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.Instance;
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
    void retryAfterSourceCancellationRejectsWithoutTransferringAgain() {
        CapacityReservation source = create(service, 2);
        CapacityReservation destination = create(service, 1);
        move(source, destination, 2, "retry");
        assertError("Unsupported", () -> move(source, destination, 2, "retry"));
        assertError("Unsupported", () -> move(source, destination, 1, "new-token"));
        assertCounts(destination, 3, 3);
        assertError("IdempotentParameterMismatch", () -> move(source, destination, 1, "retry"));
    }

    @Test
    void retryRejectsCancelledDestinationWithoutTransferringAgain() {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        move(source, destination, 1, "retry");
        service.cancelCapacityReservation(REGION, destination.getCapacityReservationId());
        assertError("Unsupported", () -> move(source, destination, 1, "retry"));
        assertCounts(source, 2, 2);
    }

    @Test
    void countLimitAppliesBeforeCapacityAndLifecycleValidation() {
        CapacityReservation source = create(service, 1002);
        CapacityReservation destination = create(service, 1);
        assertError("InvalidParameterValue", () -> move(source, destination, 1001, null));
        assertCounts(source, 1002, 1002);
        assertCounts(destination, 1, 1);
        service.cancelCapacityReservation(REGION, destination.getCapacityReservationId());
        assertError("InvalidParameterValue", () -> move(source, destination, 0, null));
    }

    @Test
    void identicalReservationsTakePrecedenceOverInvalidCount() {
        CapacityReservation source = create(service, 3);
        assertError("InvalidParameterCombination", () -> move(source, source, 0, null));
        assertCounts(source, 3, 3);
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
        assertError("InvalidCapacityReservationId.Malformed",
                () -> service.moveCapacityReservationInstances(REGION, id, "", 1, null));
        assertError("MissingParameter", () -> service.moveCapacityReservationInstances(REGION, id, id, null, null));
        assertError("InvalidParameterCombination", () -> move(source, source, 1, null));
        assertCounts(source, 3, 3);
    }

    @Test
    void rejectsMissingReservationsWithoutChangingExistingCapacity() {
        CapacityReservation source = create(service, 3);
        assertError("InvalidCapacityReservationId.NotFound", () -> service.moveCapacityReservationInstances(
                REGION, source.getCapacityReservationId(), "cr-00000000000000001", 1, null));
        assertCounts(source, 3, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad", "", "cr-00000000000000000", "cr-12345678", "cr-1234567890abcdefg"})
    void malformedSourcePrecedesMissingDestination(String sourceId) {
        assertError("InvalidCapacityReservationId.Malformed", () -> service.moveCapacityReservationInstances(
                REGION, sourceId, "cr-00000000000000002", 1, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001})
    void missingSourcePrecedesInvalidCount(int count) {
        assertError("InvalidCapacityReservationId.NotFound", () -> service.moveCapacityReservationInstances(
                REGION, "cr-00000000000000001", "cr-00000000000000002", count, null));
    }

    @Test
    void missingSourcePrecedesIdenticalReservationCheck() {
        assertError("InvalidCapacityReservationId.NotFound", () -> service.moveCapacityReservationInstances(
                REGION, "cr-00000000000000001", "cr-00000000000000001", 1, null));
    }

    @Test
    void missingSourcePrecedesEmptyDestination() {
        assertError("InvalidCapacityReservationId.NotFound", () -> service.moveCapacityReservationInstances(
                REGION, "cr-00000000000000001", "", 1, null));
    }

    @Test
    void uppercaseHexIdIsNotRejectedAsMalformed() {
        assertError("InvalidCapacityReservationId.NotFound", () -> service.moveCapacityReservationInstances(
                REGION, "cr-0910846388FB08593", "cr-00000000000000002", 1, null));
    }

    @Test
    void invalidIdsPrecedeCompletedTokenMismatchWithoutMutation() {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        move(source, destination, 1, "retry");
        assertError("InvalidCapacityReservationId.Malformed", () -> service.moveCapacityReservationInstances(
                REGION, "bad", destination.getCapacityReservationId(), 1, "retry"));
        assertError("InvalidCapacityReservationId.NotFound", () -> service.moveCapacityReservationInstances(
                REGION, "cr-00000000000000001", destination.getCapacityReservationId(), 1, "retry"));
        for (String destinationId : List.of("", "bad")) {
            assertError("InvalidCapacityReservationId.Malformed", () -> service.moveCapacityReservationInstances(
                    REGION, source.getCapacityReservationId(), destinationId, 1, "retry"));
        }
        assertError("InvalidParameterValue", () -> move(source, destination, 0, "retry"));
        assertError("InvalidParameterCombination", () -> move(source, source, 1, "retry"));
        assertError("IdempotentParameterMismatch", () -> move(source, destination, 2, "retry"));
        move(source, destination, 1, "retry");
        assertCounts(source, 2, 2);
        assertCounts(destination, 2, 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsInactiveSourceOrDestination(boolean cancelSource) {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        service.cancelCapacityReservation(REGION,
                (cancelSource ? source : destination).getCapacityReservationId());
        assertError("Unsupported", () -> move(source, destination, 1, null));
        assertCounts(source, 3, cancelSource ? 0 : 3);
        assertCounts(destination, 1, cancelSource ? 1 : 0);
    }

    @Test
    void requiresMatchingEndTimeButNotEligibilityOrTags() {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        Instant end = Instant.parse("2030-01-01T00:00:00Z");
        service.modifyCapacityReservation(REGION, source.getCapacityReservationId(), null, end, "limited", null);
        assertError("Unsupported", () -> move(source, destination, 1, "move"));
        service.modifyCapacityReservation(REGION, destination.getCapacityReservationId(),
                null, end.plusSeconds(1), "limited", "targeted");
        assertError("Unsupported", () -> move(source, destination, 1, "move"));
        service.modifyCapacityReservation(REGION, destination.getCapacityReservationId(), null, end, "limited", null);
        move(source, destination, 1, "move");
        assertCounts(source, 2, 2);
        assertCounts(destination, 2, 2);
        assertEquals("targeted", describe(destination).getInstanceMatchCriteria());
    }

    @Test
    void insufficientCapacityPrecedesUnsupportedZoneWithoutMutation() {
        CapacityReservation source = create(service, 2);
        CapacityReservation destination = create(service, 1);
        destination.setAvailabilityZone("us-east-1b");
        assertError("InvalidParameterValue", () -> move(source, destination, 3, null));
        assertError("Unsupported", () -> move(source, destination, 1, null));
        assertCounts(source, 2, 2);
        assertCounts(destination, 1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "platform", "tenancy", "placement"})
    void insufficientCapacityPrecedesUnsupportedAttributesWithoutMutation(String attribute) {
        CapacityReservation source = create(service, 2);
        CapacityReservation destination = service.createCapacityReservation(REGION,
                "type".equals(attribute) ? "t3.small" : "t3.micro",
                "platform".equals(attribute) ? "Windows" : "Linux/UNIX", REGION + "a",
                null, 1, "tenancy".equals(attribute) ? "dedicated" : null, null, null, null, null, null, null,
                "placement".equals(attribute) ? "arn:aws:ec2:us-east-1:111111111111:placement-group/other" : null);
        assertError("InvalidParameterValue", () -> move(source, destination, 3, "insufficient"));
        assertError("Unsupported", () -> move(source, destination, 1, "incompatible"));
        assertCounts(source, 2, 2);
        assertCounts(destination, 1, 1);
        assertEquals("active", describe(source).getState());
        assertEquals("active", describe(destination).getState());
        assertTrue(describe(source).getCompletedMoves().isEmpty());
    }

    @Test
    void completedTokenCoversOtherSourcesAndPrecedesAttributeValidation() {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        CapacityReservation otherSource = create(service, 2);
        CapacityReservation otherDestination = service.createCapacityReservation(REGION,
                "t3.micro", "Linux/UNIX", REGION + "a", null, 1, "dedicated",
                null, null, null, null, null, null, null);
        move(source, destination, 1, "shared-token");
        assertError("IdempotentParameterMismatch", () -> move(destination, source, 1, "shared-token"));
        assertError("IdempotentParameterMismatch", () -> move(otherSource, otherDestination, 1, "shared-token"));
        assertError("Unsupported", () -> move(otherSource, otherDestination, 1, "failed-token"));
        move(source, destination, 1, "failed-token");
        assertCounts(source, 1, 1);
        assertCounts(destination, 3, 3);
        assertCounts(otherSource, 2, 2);
        assertCounts(otherDestination, 1, 1);
        assertTrue(describe(otherSource).getCompletedMoves().isEmpty());
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
    void foreignDestinationDistinguishesUnusedAndConsumedCapacityWithoutMutation() {
        InMemoryStorageFactory factory = new InMemoryStorageFactory(ACCOUNT);
        service = newService(factory);
        CapacityReservation source = create(service, 2);
        Instance instance = service.runInstances(REGION, "ami-test", "t3.micro", 1, 1,
                null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        CapacityReservation destination = create(service, 1);
        AccountAwareStorageBackend<CapacityReservation> store = factory.create("ec2",
                "ec2-capacity-reservations.json", new TypeReference<Map<String, CapacityReservation>>() {});
        String destinationKey = REGION + "::" + destination.getCapacityReservationId();
        store.delete(destinationKey);
        destination.setOwnerId("222222222222");
        store.putForAccount("222222222222", destinationKey, destination);

        assertError("InvalidCapacityReservationId.NotFound", () -> move(source, destination, 1, "foreign"));
        assertError("InvalidParameterValue", () -> move(source, destination, 2, "foreign"));
        assertCounts(source, 2, 1);
        assertEquals(source.getCapacityReservationId(), service.instanceCapacityReservationId(instance));
        assertTrue(describe(source).getCompletedMoves().isEmpty());
        CapacityReservation foreign = store.getForAccount("222222222222", destinationKey).orElseThrow();
        assertEquals(1, foreign.getTotalInstanceCount());
        assertEquals(1, foreign.getAvailableInstanceCount());
        assertTrue(foreign.getCompletedMoves().isEmpty());

        CapacityReservation ownedDestination = create(service, 1);
        move(source, ownedDestination, 2, "foreign");
        assertCounts(source, 0, 0);
        assertCounts(ownedDestination, 3, 2);
        assertEquals(ownedDestination.getCapacityReservationId(), service.instanceCapacityReservationId(instance));
    }

    @Test
    void pendingInstanceKeepsMovedCapacityWhenLaunchCompletes() {
        CapacityReservation source = create(service, 1);
        Instance instance = service.runInstances(REGION, "ami-test", "t3.micro", 1, 1,
                null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        CapacityReservation destination = create(service, 1);
        assertEquals("pending", instance.getState().getName());
        assertEquals(source.getCapacityReservationId(), service.instanceCapacityReservationId(instance));

        move(source, destination, 1, "pending-move");

        assertEquals("pending", instance.getState().getName());
        assertEquals(destination.getCapacityReservationId(), service.instanceCapacityReservationId(instance));
        assertCounts(source, 0, 0);
        assertEquals("cancelled", describe(source).getState());
        assertCounts(destination, 2, 1);
        service.describeInstances(REGION, List.of(instance.getInstanceId()), Map.of());
        assertEquals("running", instance.getState().getName());
        assertEquals(destination.getCapacityReservationId(), service.instanceCapacityReservationId(instance));
        assertCounts(destination, 2, 1);
        service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        assertCounts(destination, 2, 2);
        assertTrue(describe(destination).getInstanceIds().isEmpty());
        assertTrue(describe(source).getInstanceIds().isEmpty());
    }

    @Test
    void openReservationsMoveConsumedCapacityAndCancelEmptySource() {
        CapacityReservation source = create(service, 1);
        Instance instance = service.runInstances(REGION, "ami-test", "t3.micro", 1, 1,
                null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        assertEquals(source.getCapacityReservationId(), service.instanceCapacityReservationId(instance));
        CapacityReservation destination = create(service, 3);

        move(source, destination, 1, "open-used");

        assertCounts(source, 0, 0);
        assertEquals("cancelled", describe(source).getState());
        assertCounts(destination, 4, 3);
        assertEquals(destination.getCapacityReservationId(), service.instanceCapacityReservationId(instance));
        service.stopInstances(REGION, List.of(instance.getInstanceId()));
        assertCounts(destination, 4, 4);
        assertEquals(null, service.instanceCapacityReservationId(instance));
        service.startInstances(REGION, List.of(instance.getInstanceId()));
        assertCounts(destination, 4, 3);
        assertEquals(destination.getCapacityReservationId(), service.instanceCapacityReservationId(instance));
        service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        assertCounts(destination, 4, 4);
    }

    @Test
    void targetedReservationsMoveOnlyUnusedCapacity() {
        CapacityReservation source = create(service, 3);
        CapacityReservation destination = create(service, 1);
        source.setInstanceMatchCriteria("targeted");
        destination.setInstanceMatchCriteria("targeted");
        source.setAvailableInstanceCount(2);

        move(source, destination, 1, "first-unused");
        assertError("InvalidParameterValue", () -> move(source, destination, 2, "used"));
        assertCounts(source, 2, 1);
        assertCounts(destination, 2, 2);
        move(source, destination, 1, "last-unused");
        assertError("InvalidParameterValue", () -> move(source, destination, 1, "only-used"));
        assertCounts(source, 1, 0);
        assertEquals("active", describe(source).getState());
        assertCounts(destination, 3, 3);
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
                        assertEquals("Unsupported", expected.getErrorCode());
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
            source = create(service, 3);
            destination = create(service, 1);
            move(source, destination, 2, "persisted");
        } finally {
            firstFactory.shutdownAll();
        }
        StorageFactory secondFactory = new StorageFactory(config, access);
        try {
            service = newService(secondFactory);
            move(source, destination, 2, "persisted");
            assertCounts(source, 1, 1);
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

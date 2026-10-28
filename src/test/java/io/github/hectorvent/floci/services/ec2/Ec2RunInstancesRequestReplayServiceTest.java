package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.RunInstancesReplayJournal;
import io.github.hectorvent.floci.services.ec2.model.RunInstancesReplayRecord.Allocation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2RunInstancesRequestReplayServiceTest {
    @TempDir
    Path directory;

    @Test
    void exactTokenReplayReturnsOneReservation() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Reservation first = fixture.launch("token", "t3.micro");
            Reservation second = fixture.launch("token", "t3.micro");
            assertEquals(first.getReservationId(), second.getReservationId());
            assertEquals(first.getInstances().getFirst().getInstanceId(), second.getInstances().getFirst().getInstanceId());
        }
    }

    @Test
    void changedTypeWithSameTokenIsRejected() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            fixture.launch("token", "t3.micro");
            AwsException error = assertThrows(AwsException.class, () -> fixture.launch("token", "t3.small"));
            assertEquals("IdempotentParameterMismatch", error.getErrorCode());
        }
    }

    @Test
    void concurrentTokenReplayCreatesOneInstance() throws Exception {
        try (Fixture fixture = new Fixture("memory", directory);
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Reservation> first = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return fixture.launch("token", "t3.micro");
            });
            Future<Reservation> second = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return fixture.launch("token", "t3.micro");
            });
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            assertEquals(first.get(10, TimeUnit.SECONDS).getInstances().getFirst().getInstanceId(),
                    second.get(10, TimeUnit.SECONDS).getInstances().getFirst().getInstanceId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void tokenReplaySurvivesServiceRestart(String mode) {
        String instance;
        try (Fixture fixture = new Fixture(mode, directory)) {
            instance = fixture.launch("token", "t3.micro").getInstances().getFirst().getInstanceId();
        }
        try (Fixture fixture = new Fixture(mode, directory)) {
            assertEquals(instance, fixture.launch("token", "t3.micro").getInstances().getFirst().getInstanceId());
        }
    }

    @Test
    void absentTokenAllowsIndependentLaunches() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            assertNotEquals(fixture.launch(null, "t3.micro").getInstances().getFirst().getInstanceId(),
                    fixture.launch(null, "t3.micro").getInstances().getFirst().getInstanceId());
        }
    }

    @Test
    void replayRetainsReservationAndCurrentTerminatedStateAfterPruning() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Reservation first = fixture.launch("token", "t3.micro");
            Instance instance = first.getInstances().getFirst();
            fixture.service.terminateInstances("us-east-1", List.of(instance.getInstanceId()));
            instance.setTerminatedAt(System.currentTimeMillis() - 7_200_000);
            fixture.service.pruneTerminatedInstances();
            Reservation replay = fixture.launch("token", "t3.micro");
            assertEquals(first.getReservationId(), replay.getReservationId());
            assertEquals("terminated", replay.getInstances().getFirst().getState().getName());
        }
    }

    @Test
    void regionalAndExplicitZonesHaveSeparateOutcomesExceptSelectedZone() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Reservation regional = fixture.launch("token", "t3.micro");
            String selected = regional.getInstances().getFirst().getPlacement().getAvailabilityZone();
            AwsException mismatch = assertThrows(AwsException.class,
                    () -> fixture.launchIn("us-east-1", selected, "token", null));
            assertEquals("IdempotentParameterMismatch", mismatch.getErrorCode());
            String other = selected.equals("us-east-1a") ? "us-east-1b" : "us-east-1a";
            Reservation zonal = fixture.launchIn("us-east-1", other, "token", null);
            assertNotEquals(regional.getReservationId(), zonal.getReservationId());
            assertEquals(zonal.getReservationId(), fixture.launchIn("us-east-1", other, "token", null).getReservationId());
            assertNotEquals(regional.getReservationId(), fixture.launchIn("us-west-2", null, "token", null).getReservationId());
        }
    }

    @Test
    void factoryResetClearsRequestOutcomes() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            String first = fixture.launch("token", "t3.micro").getReservationId();
            fixture.factory.clearAll();
            fixture.service.clear();
            assertNotEquals(first, fixture.launch("token", "t3.micro").getReservationId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void partialAllocationFailuresReleaseOnlyOwnedResources(String mode) {
        for (String stage : List.of("ownership", "tags", "eni", "volume", "instance", "flush", "outcome")) {
            try (Fixture fixture = new Fixture(mode, directory.resolve(stage))) {
                String subnet = fixture.service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
                NetworkInterface original = fixture.service.createNetworkInterface("us-east-1", subnet,
                        "supplied", null, List.of(), List.of(), List.of(new Tag("original", "preserved")));
                AccountAwareStorageBackend<Volume> volumes = fixture.factory.store("ec2-volumes.json");
                AccountAwareStorageBackend<Instance> instances = fixture.factory.store("ec2-instances.json");
                AccountAwareStorageBackend<NetworkInterface> interfaces = fixture.factory.store("ec2-network-interfaces.json");
                AccountAwareStorageBackend<List<Tag>> tags = fixture.factory.store("ec2-tags.json");
                AccountAwareStorageBackend<RunInstancesReplayJournal> journal = fixture.factory.store("ec2-run-instances-replays.json");
                IllegalStateException failure = new IllegalStateException("injected " + stage);
                switch (stage) {
                    case "ownership", "outcome" -> {
                        AtomicInteger writes = new AtomicInteger();
                        doAnswer(call -> {
                            if (writes.incrementAndGet() == (stage.equals("ownership") ? 2 : 3)) {
                                throw failure;
                            }
                            return call.callRealMethod();
                        }).when(journal).putAllDurablyForAccount(anyString(), anyMap());
                    }
                    case "tags" -> doThrow(failure).doCallRealMethod().when(tags).put(anyString(), anyList());
                    case "eni" -> doThrow(failure).doCallRealMethod().when(interfaces).putAllAtomically(anyMap());
                    case "volume" -> doThrow(failure).doCallRealMethod().when(volumes).putAllAtomically(anyMap());
                    case "instance" -> doThrow(failure).doCallRealMethod().when(instances).putAllAtomically(anyMap());
                    case "flush" -> doThrow(failure).doCallRealMethod().when(volumes).flushOrThrow();
                    default -> fail("Unknown cut point");
                }
                assertSame(failure, assertThrows(IllegalStateException.class,
                        () -> fixture.launchIn("us-east-1", null, "token", original.getNetworkInterfaceId())), stage);
                assertTrue(volumes.scan(key -> true).isEmpty(), stage + " must reclaim a volume created before instance publication");
                assertTrue(instances.scan(key -> true).stream()
                        .allMatch(instance -> "terminated".equals(instance.getState().getName())), stage);
                NetworkInterface retained = interfaces.get("us-east-1::" + original.getNetworkInterfaceId()).orElseThrow();
                assertNull(retained.getAttachment(), stage);
                assertEquals("preserved", retained.getTagSet().getFirst().getValue());
                assertNull(original.getAttachment(), "The supplied record must not be mutated before successful persistence");
                reset(journal, volumes, instances, interfaces, tags);
                Reservation retry = fixture.launchIn("us-east-1", null, "token", original.getNetworkInterfaceId());
                assertEquals(1, retry.getInstances().size(), stage);
                assertEquals(retry.getReservationId(), fixture.launchIn("us-east-1", null, "token", original.getNetworkInterfaceId()).getReservationId());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void failedCleanupRetainsAllocationUntilServiceRestart(String mode) {
        try (Fixture fixture = new Fixture(mode, directory)) {
            AccountAwareStorageBackend<Instance> instances = fixture.factory.store("ec2-instances.json");
            AccountAwareStorageBackend<Volume> volumes = fixture.factory.store("ec2-volumes.json");
            doThrow(new IllegalStateException("instance write failed")).when(instances).putAllAtomically(anyMap());
            doThrow(new IllegalStateException("volume delete failed")).when(volumes).delete(anyString());
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> fixture.launch("token", "t3.micro"));
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(1, volumes.scan(key -> true).size());
        }
        try (Fixture fixture = new Fixture(mode, directory)) {
            fixture.service.restoreMetadataRegistrations();
            assertTrue(fixture.factory.<Volume>store("ec2-volumes.json").scan(key -> true).isEmpty());
            assertEquals(1, fixture.launch("token", "t3.micro").getInstances().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void startupContinuesAfterOnePendingCompensationFails(String mode) {
        try (Fixture fixture = new Fixture(mode, directory)) {
            AccountAwareStorageBackend<Instance> instances = fixture.factory.store("ec2-instances.json");
            AccountAwareStorageBackend<Volume> volumes = fixture.factory.store("ec2-volumes.json");
            AccountAwareStorageBackend<RunInstancesReplayJournal> journal = fixture.factory.store("ec2-run-instances-replays.json");
            doThrow(new IllegalStateException("instance write failed")).when(instances).putAllAtomically(anyMap());
            doThrow(new IllegalStateException("volume delete failed")).when(volumes).delete(anyString());
            for (String zone : List.of("us-east-1a", "us-east-1b")) {
                assertThrows(IllegalStateException.class, () -> fixture.launchIn("us-east-1", zone, "token", null));
            }
            assertEquals(2, volumes.scan(key -> true).size());
            reset(instances, volumes);
            AtomicInteger cleanups = new AtomicInteger();
            doAnswer(call -> {
                if (cleanups.incrementAndGet() == 1) {
                    throw new IllegalStateException("first recovery unavailable");
                }
                return call.callRealMethod();
            }).when(volumes).delete(anyString());
            assertDoesNotThrow(fixture.service::restoreMetadataRegistrations);
            assertEquals(2, cleanups.get(), "Failure must not prevent the next zone's compensation");
            assertEquals(1, volumes.scan(key -> true).size());
            assertEquals(1, journal.scan(key -> true).stream().mapToInt(value -> value.zones().size()).sum());
            reset(volumes);
            fixture.service.restoreMetadataRegistrations();
            assertTrue(volumes.scan(key -> true).isEmpty());
            assertEquals(0, journal.scan(key -> true).stream().mapToInt(value -> value.zones().size()).sum());
        }
    }

    @Test
    void suppliedInterfaceRetainsChangesMadeBeforeTheLockedRecheck() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            String subnet = fixture.service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
            NetworkInterface original = fixture.service.createNetworkInterface("us-east-1", subnet,
                    "original", null, List.of(), List.of(), List.of());
            Ec2Service service = spy(fixture.service);
            AtomicInteger lookups = new AtomicInteger();
            doAnswer(call -> {
                if (lookups.incrementAndGet() == 2) {
                    original.setDescription("edited before admission");
                    original.setSourceDestCheck(false);
                }
                return call.callRealMethod();
            }).when(service).takeNetworkInterfaceForLaunch("us-east-1", original.getNetworkInterfaceId());
            Reservation reservation = service.runInstances("us-east-1", "ami-test", "t3.micro", 1, 1,
                    null, List.of(), null, "token", List.of(), null, null, null,
                    original.getNetworkInterfaceId(), 0, null, null, null, null, false, null, null, null,
                    false, null, null);
            NetworkInterface retained = fixture.factory.<NetworkInterface>store("ec2-network-interfaces.json")
                    .get("us-east-1::" + original.getNetworkInterfaceId()).orElseThrow();
            assertEquals(2, lookups.get());
            assertEquals("edited before admission", retained.getDescription());
            assertFalse(retained.isSourceDestCheck());
            assertEquals(reservation.getInstances().getFirst().getInstanceId(), retained.getAttachment().getInstanceId());
            assertNull(original.getAttachment(), "Launch must still attach a copy of the supplied interface");
        }
    }

    @Test
    void failedSecondContainerLaunchCancelsTheOwnedBatchAndRetryLaunchesOnce() {
        try (Fixture fixture = new Fixture("memory", directory, false)) {
            IllegalStateException failure = new IllegalStateException("second container rejected");
            fixture.launchFailure = failure;
            doAnswer(call -> {
                Instance instance = call.getArgument(0);
                instance.setState(InstanceState.terminated());
                return null;
            }).when(fixture.manager).terminate(any(Instance.class), any(Runnable.class));
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> fixture.service.runInstances("us-east-1", "ami-test", "t3.micro", 2, 2,
                            null, List.of(), null, "batch", List.of(), null, null)));
            verify(fixture.manager, times(2)).terminate(any(Instance.class), any(Runnable.class));
            assertTrue(fixture.factory.<Volume>store("ec2-volumes.json").scan(key -> true).isEmpty());
            fixture.launchFailure = null;
            fixture.launchCalls.set(0);
            Reservation first = fixture.service.runInstances("us-east-1", "ami-test", "t3.micro", 2, 2,
                    null, List.of(), null, "batch", List.of(), null, null);
            Reservation replay = fixture.service.runInstances("us-east-1", "ami-test", "t3.micro", 2, 2,
                    null, List.of(), null, "batch", List.of(), null, null);
            assertEquals(first.getReservationId(), replay.getReservationId());
            assertEquals(2, fixture.launchCalls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedOutcomeRestoresSuppliedInterfaceTagsWithoutUndoingLaterEdits(boolean concurrentEdit) {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            String subnet = fixture.service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
            NetworkInterface original = fixture.service.createNetworkInterface("us-east-1", subnet,
                    "supplied", null, List.of(), List.of(), List.of(new Tag("original", "preserved")));
            AccountAwareStorageBackend<RunInstancesReplayJournal> journal = fixture.factory.store("ec2-run-instances-replays.json");
            AtomicBoolean rejected = new AtomicBoolean();
            doAnswer(call -> {
                Map<String, RunInstancesReplayJournal> replacements = call.getArgument(1);
                if (replacements.values().stream().flatMap(value -> value.zones().values().stream())
                        .anyMatch(record -> record.result() != null) && !rejected.getAndSet(true)) {
                    if (concurrentEdit) {
                        fixture.service.createTags("us-east-1", List.of(original.getNetworkInterfaceId()),
                                List.of(new Tag("original", "later")));
                    }
                    throw new IllegalStateException("outcome failed");
                }
                return call.callRealMethod();
            }).when(journal).putAllDurablyForAccount(anyString(), anyMap());
            MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
            Map.of("ImageId", "ami-test", "InstanceType", "t3.micro", "ClientToken", "query-token",
                    "NetworkInterface.1.NetworkInterfaceId", original.getNetworkInterfaceId(),
                    "NetworkInterface.1.DeviceIndex", "0", "TagSpecification.1.ResourceType", "network-interface",
                    "TagSpecification.1.Tag.1.Key", "original", "TagSpecification.1.Tag.1.Value", "launch",
                    "TagSpecification.1.Tag.2.Key", "added", "TagSpecification.1.Tag.2.Value", "owned")
                    .forEach(parameters::putSingle);
            Ec2QueryHandler handler = new Ec2QueryHandler(fixture.service,
                    mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), null, null, null, null, null);
            assertThrows(IllegalStateException.class, () -> handler.handle("RunInstances", parameters, "us-east-1"));
            NetworkInterface retained = fixture.factory.<NetworkInterface>store("ec2-network-interfaces.json")
                    .get("us-east-1::" + original.getNetworkInterfaceId()).orElseThrow();
            assertNull(retained.getAttachment());
            assertEquals(1, retained.getTagSet().size());
            assertEquals(concurrentEdit ? "later" : "preserved", retained.getTagSet().getFirst().getValue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletedSuppliedInterfaceIsRejectedBeforeExactReplay(boolean query) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            String subnet = fixture.service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
            String eni = fixture.service.createNetworkInterface("us-east-1", subnet,
                    "supplied", null, List.of(), List.of(), List.of()).getNetworkInterfaceId();
            MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
            Map.of("ImageId", "ami-test", "InstanceType", "t3.micro", "ClientToken", "token",
                    "NetworkInterface.1.NetworkInterfaceId", eni, "NetworkInterface.1.DeviceIndex", "0")
                    .forEach(parameters::putSingle);
            Ec2QueryHandler handler = new Ec2QueryHandler(fixture.service,
                    mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), null, null, null, null, null);
            Runnable launch = () -> {
                if (query) {
                    assertEquals(200, handler.handle("RunInstances", parameters, "us-east-1").getStatus());
                } else {
                    fixture.launchIn("us-east-1", null, "token", eni);
                }
            };
            launch.run();
            assertDoesNotThrow(launch::run, "Attached interface still permits an exact replay");
            List<Instance> instances = fixture.factory.<Instance>store("ec2-instances.json").scan(key -> true);
            fixture.service.terminateInstances("us-east-1", List.of(instances.getFirst().getInstanceId()));
            fixture.service.deleteNetworkInterface("us-east-1", eni);
            if (query) {
                Response failure = handler.handle("RunInstances", parameters, "us-east-1");
                assertEquals(400, failure.getStatus());
                assertTrue(failure.getEntity().toString().contains("<Code>InvalidNetworkInterfaceID.NotFound</Code>"));
            } else {
                AwsException failure = assertThrows(AwsException.class, launch::run);
                assertEquals("InvalidNetworkInterfaceID.NotFound", failure.getErrorCode());
            }
            assertEquals(1, fixture.factory.<Instance>store("ec2-instances.json").scan(key -> true).size());
        }
    }

    @Test
    void hostSelectedZoneIsTheRegionalReplayZone() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            String host = fixture.service.allocateHosts("us-east-1", "us-east-1b", "t3.micro", null,
                    1, null, null, null, null, null).getFirst().getHostId();
            Reservation first = fixture.launchOnHost(host, null);
            assertEquals("us-east-1b", first.getInstances().getFirst().getPlacement().getAvailabilityZone());
            assertEquals(first.getReservationId(), fixture.launchOnHost(host, null).getReservationId());
            AwsException error = assertThrows(AwsException.class, () -> fixture.launchOnHost(host, "us-east-1b"));
            assertEquals("IdempotentParameterMismatch", error.getErrorCode());
        }
    }

    @Test
    void interruptedAllocationReclaimsEveryPlannedVolumeBeforeInstancePublication() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            AccountAwareStorageBackend<Volume> volumes = fixture.factory.store("ec2-volumes.json");
            assertThrows(IllegalStateException.class, () -> fixture.service.replayRunInstances("us-east-1", "volumes",
                    false, Map.of(), () -> new Ec2RunInstancesReplay.Scope("us-east-1a", true), attempt -> {
                        Allocation allocation = new Allocation("i-owned", "vol-root", null, "10.0.0.4", null,
                                "eni-owned", Map.of(), Map.of(), List.of("vol-data"));
                        attempt.rememberAll(List.of(allocation));
                        for (String id : allocation.volumeIds()) {
                            Volume volume = new Volume();
                            volume.setVolumeId(id);
                            volume.setRegion("us-east-1");
                            volume.setState("available");
                            volumes.putAllAtomically(Map.of("us-east-1::" + id, volume));
                        }
                        throw new IllegalStateException("instance publication failed");
                    }));
            assertTrue(volumes.scan(key -> true).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void plannedVolumesAreJournaledBeforeInstancePublicationAndRetriedWithoutLeaks(String mode) {
        try (Fixture fixture = new Fixture(mode, directory)) {
            AccountAwareStorageBackend<Volume> volumes = fixture.factory.store("ec2-volumes.json");
            AccountAwareStorageBackend<Instance> instances = fixture.factory.store("ec2-instances.json");
            Volume unrelated = fixture.service.createVolume("us-east-1", "us-east-1a", "gp2", 4,
                    false, 0, null, null, List.of());
            IllegalStateException failure = new IllegalStateException("instance publication rejected");
            AtomicInteger planned = new AtomicInteger();
            doAnswer(call -> {
                planned.set(volumes.scan(key -> true).size() - 1);
                throw failure;
            }).when(instances).putAllAtomically(anyMap());
            assertSame(failure, assertThrows(IllegalStateException.class, fixture::launchVolumes));
            assertEquals(3, planned.get(), "All planned devices must be created before the injected failure");
            assertEquals(List.of(unrelated.getVolumeId()), volumes.scan(key -> true).stream()
                    .map(Volume::getVolumeId).toList(), "Compensation must reclaim every owned volume only");
            reset(instances);
            Reservation first = fixture.launchVolumes();
            Reservation replay = fixture.launchVolumes();
            assertEquals(first.getReservationId(), replay.getReservationId());
            assertEquals(4, volumes.scan(key -> true).size(), "Replay must not allocate duplicate devices");
            assertEquals(List.of(12, 20, 30), volumes.scan(key -> !key.endsWith(unrelated.getVolumeId()))
                    .stream().map(Volume::getSize).sorted().toList());
        }
    }

    @Test
    void queryReplayRetainsEveryPlannedVolumeInResponse() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
            Map.of("ImageId", "ami-test", "InstanceType", "t3.micro", "ClientToken", "devices",
                    "BlockDeviceMapping.1.DeviceName", "/dev/sdf", "BlockDeviceMapping.1.Ebs.VolumeSize", "20",
                    "BlockDeviceMapping.2.DeviceName", "/dev/sdg", "BlockDeviceMapping.2.Ebs.VolumeSize", "30")
                    .forEach(parameters::putSingle);
            Ec2QueryHandler handler = new Ec2QueryHandler(fixture.service,
                    mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), null, null, null, null, null);
            Response first = handler.handle("RunInstances", parameters, "us-east-1");
            Response replay = handler.handle("RunInstances", parameters, "us-east-1");
            assertEquals(200, first.getStatus());
            assertEquals(200, replay.getStatus());
            List<Volume> volumes = fixture.factory.<Volume>store("ec2-volumes.json").scan(key -> true);
            assertEquals(3, volumes.size());
            for (Volume volume : volumes) {
                assertTrue(first.getEntity().toString().contains(volume.getVolumeId()));
                assertTrue(replay.getEntity().toString().contains(volume.getVolumeId()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void queryLaunchReclaimsRootAndDataVolumesBeforeRetry(String mode) {
        try (Fixture fixture = new Fixture(mode, directory)) {
            AccountAwareStorageBackend<Volume> volumes = fixture.factory.store("ec2-volumes.json");
            AccountAwareStorageBackend<Instance> instances = fixture.factory.store("ec2-instances.json");
            AtomicInteger volumeWrites = new AtomicInteger();
            doAnswer(call -> {
                volumeWrites.incrementAndGet();
                return call.callRealMethod();
            }).when(volumes).putAllAtomically(anyMap());
            IllegalStateException failure = new IllegalStateException("instance publication failed");
            doThrow(failure).when(instances).putAllAtomically(anyMap());
            MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
            Map.of("ImageId", "ami-test", "InstanceType", "t3.micro", "ClientToken", "volume-token",
                    "BlockDeviceMapping.1.DeviceName", "/dev/sdf", "BlockDeviceMapping.1.Ebs.VolumeSize", "3",
                    "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false")
                    .forEach(parameters::putSingle);
            Ec2QueryHandler handler = new Ec2QueryHandler(fixture.service,
                    mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), null, null, null, null, null);

            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> handler.handle("RunInstances", parameters, "us-east-1")));
            assertEquals(2, volumeWrites.get(), "Both planned volumes must exist before the injected failure");
            assertTrue(volumes.scan(key -> true).isEmpty(), "Failed publication must reclaim root and data volumes");
            reset(instances);
            assertEquals(200, handler.handle("RunInstances", parameters, "us-east-1").getStatus());
            assertEquals(2, volumes.scan(key -> true).size());
            assertEquals(200, handler.handle("RunInstances", parameters, "us-east-1").getStatus());
            assertEquals(4, volumeWrites.get(), "Exact replay must not allocate another volume");
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final FaultFactory factory;
        private final Ec2Service service;
        private final AtomicInteger launchCalls = new AtomicInteger();
        private IllegalStateException launchFailure;
        private final Ec2ContainerManager manager = mock(Ec2ContainerManager.class, call -> {
            if (call.getMethod().getName().equals("launch") && launchCalls.incrementAndGet() == 2
                    && launchFailure != null) {
                throw launchFailure;
            }
            return RETURNS_DEFAULTS.answer(call);
        });

        private Fixture(String mode, Path directory) {
            this(mode, directory, true);
        }

        private Fixture(String mode, Path directory, boolean mockMode) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn("111111111111");
            when(config.services().ec2().mock()).thenReturn(mockMode);
            when(config.services().ec2().mockPublicAddresses())
                    .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
            when(config.storage().persistentPath()).thenReturn(directory.toString());
            when(config.storage().wal().compactionIntervalMs()).thenReturn(3600000L);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("ec2")).thenReturn(mode);
            when(access.storageFlushInterval("ec2")).thenReturn(3600000L);
            factory = new FaultFactory(config, access);
            Ec2ImageCatalog catalog = new Ec2ImageCatalog();
            AmiImageResolver resolver = mock(AmiImageResolver.class);
            when(resolver.resolveImage(anyString())).thenReturn(ResolvedAmiImage.minimal("example-image"));
            service = new Ec2Service(config, manager, null, resolver, catalog,
                    new Ec2InstanceTypeCatalog(), factory);
        }

        private Reservation launchVolumes() {
            List<LaunchTemplateData.BlockDeviceMapping> mappings = List.of(
                    device("/dev/xvda", 12), device("/dev/sdf", 20), device("/dev/sdg", 30));
            return service.runInstances("us-east-1", "ami-test", "t3.micro", 1, 1, null, List.of(), null,
                    "multi-volume", List.of(), null, null, null, null, 0, null, null, null, null,
                    false, null, null, mappings, false, null, null);
        }

        private LaunchTemplateData.BlockDeviceMapping device(String name, int size) {
            LaunchTemplateData.BlockDeviceMapping mapping = new LaunchTemplateData.BlockDeviceMapping();
            mapping.setDeviceName(name);
            LaunchTemplateData.Ebs ebs = new LaunchTemplateData.Ebs();
            ebs.setVolumeSize(size);
            ebs.setDeleteOnTermination(false);
            mapping.setEbs(ebs);
            return mapping;
        }

        private Reservation launch(String token, String type) {
            return service.runInstances("us-east-1", "ami-test", type, 1, 1, null, List.of(), null,
                    token, List.of(), null, null);
        }

        private Reservation launchOnHost(String host, String zone) {
            return service.runInstances("us-east-1", "ami-test", "t3.micro", 1, 1, null, List.of(), null,
                    "host-token", List.of(), null, null, null, null, 0, zone, null, null, null,
                    false, host, "host", null, false, null, null);
        }

        private Reservation launchIn(String region, String zone, String token, String eni) {
            return service.runInstances(region, "ami-test", "t3.micro", 1, 1, null, List.of(), null,
                    token, eni == null ? List.of() : List.of(new Tag("launch", "owned")), null, null,
                    null, eni, 0, zone, null, null, null, false, null, null, null, false, null, null);
        }

        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
    private static final class FaultFactory extends StorageFactory {
        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

        private FaultFactory(EmulatorConfig config, ServiceConfigAccess access) {
            super(config, access);
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <V> AccountAwareStorageBackend<V> create(String service, String file,
                                                                      TypeReference<Map<String, V>> type) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(file,
                    ignored -> spy(super.create(service, file, type)));
        }

        @SuppressWarnings("unchecked")
        private <V> AccountAwareStorageBackend<V> store(String file) {
            return (AccountAwareStorageBackend<V>) stores.get(file);
        }
    }

}

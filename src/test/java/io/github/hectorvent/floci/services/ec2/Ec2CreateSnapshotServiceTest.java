package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ec2CreateSnapshotServiceTest {

    private static final String REGION = "us-east-1";

    @Test
    void snapshotCapturesIndependentVolumeMetadataAndRequestedTags() {
        Ec2Service service = service();
        Volume volume = volume(service);
        Tag requested = new Tag("Purpose", "backup");
        List<Tag> tags = new ArrayList<>(List.of(requested));
        Snapshot snapshot = service.createSnapshot(REGION, volume.getVolumeId(), "backup", tags, false);

        volume.setSize(20);
        volume.setEncrypted(false);
        volume.getTags().getFirst().setValue("changed");
        requested.setValue("changed");
        tags.clear();

        Snapshot observed = service.describeSnapshots(REGION, List.of(snapshot.getSnapshotId()),
                List.of("self"), Map.of()).getFirst();
        assertEquals(10, observed.getVolumeSize());
        assertTrue(observed.isEncrypted());
        assertEquals(1, observed.getTags().size());
        assertEquals("Purpose", observed.getTags().getFirst().getKey());
        assertEquals("backup", observed.getTags().getFirst().getValue());
        assertEquals(volume.getVolumeId(), observed.getVolumeId());
    }

    @Test
    void inUseVolumeCanHaveDistinctSnapshotsWithoutMutatingTheVolume() {
        Ec2Service service = service();
        Volume volume = volume(service);
        volume.setState("in-use");

        Snapshot first = service.createSnapshot(REGION, volume.getVolumeId(), null, List.of(), false);
        Snapshot second = service.createSnapshot(REGION, volume.getVolumeId(), null, List.of(), false);

        assertNotEquals(first.getSnapshotId(), second.getSnapshotId());
        assertEquals("in-use", volume.getState());
        assertEquals("", first.getDescription());
        assertTrue(first.getTags().isEmpty());
        assertEquals(2, service.describeSnapshots(REGION, List.of(), List.of(), Map.of()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"creating", "deleting", "deleted", "error"})
    void invalidVolumeStateRefusesCreationAndDryRunWithoutWritingASnapshot(String state) {
        Ec2Service service = service();
        Volume volume = volume(service);
        volume.setState(state);

        for (boolean dryRun : List.of(false, true)) {
            assertEquals("IncorrectState", assertThrows(AwsException.class,
                    () -> service.createSnapshot(REGION, volume.getVolumeId(), null, List.of(), dryRun))
                    .getErrorCode());
        }
        assertTrue(service.describeSnapshots(REGION, List.of(), List.of(), Map.of()).isEmpty());
        assertEquals(state, volume.getState());
    }

    @Test
    void tagFiltersCombineKeysAndValuesWithoutMatchingOtherSnapshots() {
        Ec2Service service = service();
        Volume volume = volume(service);
        Snapshot backup = service.createSnapshot(REGION, volume.getVolumeId(), null,
                List.of(new Tag("Purpose", "backup"), new Tag("Environment", "test")), false);
        service.createSnapshot(REGION, volume.getVolumeId(), null,
                List.of(new Tag("Purpose", "backup"), new Tag("Environment", "production")), false);

        assertEquals(List.of(backup), service.describeSnapshots(REGION, List.of(), List.of("self"),
                Map.of("tag:Purpose", List.of("other", "back?p"),
                        "tag:Environment", List.of("te*"), "tag-key", List.of("Pur*"))));
        assertTrue(service.describeSnapshots(REGION, List.of(), List.of(),
                Map.of("tag:Missing", List.of("*"))).isEmpty());
        assertTrue(service.describeSnapshots(REGION, List.of(), List.of(),
                Map.of("tag-key", List.of("purpose"))).isEmpty());
        assertFalse(service.describeSnapshots(REGION, List.of(), List.of(),
                Map.of("tag-key", List.of("Environment"))).isEmpty());
    }

    private Volume volume(Ec2Service service) {
        return service.createVolume(REGION, REGION + "a", "gp2", 10, true,
                0, null, null, List.of(new Tag("SourceOnly", "volume")));
    }

    private Ec2Service service() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        StorageFactory storage = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                          TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), new Ec2ImageCatalog(), new Ec2InstanceTypeCatalog(), storage);
    }
}

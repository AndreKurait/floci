package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ec2ImageSharingServiceTest {

    private static final String OWNER = "333344445555";
    private static final String RECIPIENT = "666677778888";
    private static final String OTHER = "999900001111";
    private static final String REGION = "us-east-1";
    private static final String DESTINATION = "us-west-2";
    private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();
    private final Ec2ImageCatalog catalog = catalog();
    private final Ec2Service owner = service(OWNER);
    private final Ec2Service recipient = service(RECIPIENT);

    @Test
    void catalogBoundCopyRetainsRuntimeAncestryAndIndependentSnapshotMetadata() {
        Image source = source();
        grant(source);
        String sourceSnapshotId = snapshotId(source);
        Image copy = recipient.copyImage(DESTINATION, REGION, source.getImageId(), "copied", null);

        assertEquals("ami-catalog-worker", source.getSourceImageId());
        assertEquals(source.getSourceImageId(), copy.getSourceImageId());
        assertEquals(source.getImageId(), copy.getCreationSourceImageId());
        assertEquals(REGION, copy.getCreationSourceImageRegion());
        assertEquals(RECIPIENT, copy.getOwnerId());
        assertEquals(DESTINATION, copy.getRegion());
        assertTrue(copy.getLaunchPermissionUserIds().isEmpty());
        assertTrue(copy.getTags().isEmpty());
        Snapshot copied = recipient.describeSnapshots(DESTINATION, List.of(snapshotId(copy)),
                List.of("self"), Map.of()).getFirst();
        assertNotEquals(sourceSnapshotId, copied.getSnapshotId());
        assertEquals(10, copied.getVolumeSize());
        assertEquals(RECIPIENT, copied.getOwnerId());
        assertTrue(copied.getCreateVolumePermissionUserIds().isEmpty());
        assertTrue(copied.getTags().isEmpty());
        recipient.deregisterImage(DESTINATION, copy.getImageId(), true);
        assertEquals(sourceSnapshotId, owner.describeSnapshots(REGION, List.of(sourceSnapshotId),
                List.of("self"), Map.of()).getFirst().getSnapshotId());
        assertEquals(Set.of(RECIPIENT), source.getLaunchPermissionUserIds());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foreignCapturedImageOrAncestorRefusesWithoutDestinationWrites(boolean ancestor) {
        Image source = source();
        grant(source);
        if (ancestor) {
            Image captured = owner.registerImage(REGION, "captured-parent", null, "arm64", "/dev/xvda", List.of());
            captured.setDockerImage("example/captured:1");
            source.setSourceImageId(captured.getImageId());
        } else {
            source.setDockerImage("example/captured:1");
        }
        assertRefusedWithoutWrites(source, "UnsupportedOperation");
    }

    @Test
    void foreignAncestryWalkUsesTheSourceOwnerAtEveryHop() {
        Image source = source();
        grant(source);
        Image intermediate = owner.registerImage(REGION, "intermediate", null, "arm64", "/dev/xvda", List.of());
        intermediate.setSourceImageId(source.getSourceImageId());
        source.setSourceImageId(intermediate.getImageId());

        Image copy = recipient.copyImage(DESTINATION, REGION, source.getImageId(), "copied-chain", null);

        assertEquals("ami-catalog-worker", copy.getSourceImageId());
        assertEquals(intermediate.getImageId(), source.getSourceImageId());
        assertEquals("ami-catalog-worker", intermediate.getSourceImageId());
        assertEquals(RECIPIENT, copy.getOwnerId());
        assertEquals(RECIPIENT, recipient.callerAccountId());
        assertEquals(OWNER, owner.callerAccountId());
    }

    @Test
    void unboundForeignImageRefusesWithoutDestinationWrites() {
        Image source = source();
        grant(source);
        source.setSourceImageId(null);

        assertRefusedWithoutWrites(source, "UnsupportedOperation");
    }

    @Test
    void sameAccountCapturedCopyRetainsExistingLayerBehavior() {
        Image source = source();
        source.setDockerImage("example/captured:1");

        Image copy = owner.copyImage(DESTINATION, REGION, source.getImageId(), "own-capture", null);

        assertEquals(source.getDockerImage(), copy.getDockerImage());
        assertEquals(source.getSourceImageId(), copy.getSourceImageId());
        assertEquals(OWNER, copy.getOwnerId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"image-owner", "image-region", "image-id", "snapshot-owner",
            "snapshot-region", "snapshot-id", "snapshot-permission", "snapshot-missing"})
    void inconsistentStoredIdentityOrMissingBackingGrantRefusesWithoutWrites(String mismatch) {
        Image source = source();
        grant(source);
        Snapshot snapshot = owner.describeSnapshots(REGION, List.of(snapshotId(source)), List.of(), Map.of()).getFirst();
        switch (mismatch) {
            case "image-owner" -> source.setOwnerId(OTHER);
            case "image-region" -> source.setRegion(DESTINATION);
            case "image-id" -> source.setImageId("ami-0123456789abcdef0");
            case "snapshot-owner" -> snapshot.setOwnerId(OTHER);
            case "snapshot-region" -> snapshot.setRegion(DESTINATION);
            case "snapshot-id" -> snapshot.setSnapshotId("snap-0123456789abcdef0");
            case "snapshot-permission" -> snapshot.setCreateVolumePermissionUserIds(Set.of(OTHER));
            case "snapshot-missing" -> store("ec2-snapshots.json").delete(OWNER + "/" + REGION + "::" + snapshotId(source));
            default -> throw new AssertionError(mismatch);
        }
        String storedImageId = store("ec2-registered-images.json").keys().stream()
                .filter(key -> key.startsWith(OWNER + "/" + REGION + "::"))
                .findFirst().orElseThrow().substring((OWNER + "/" + REGION + "::").length());
        assertRefusedWithoutWrites(storedImageId, "AuthFailure");
    }

    @Test
    void ambiguousForeignOwnersDoNotSelectTheFirstPermittedImage() {
        Image source = source();
        grant(source);
        store("ec2-registered-images.json").put(OTHER + "/" + REGION + "::" + source.getImageId(), source);

        assertRefusedWithoutWrites(source, "AuthFailure");
        assertEquals("InvalidAMIID.NotFound", assertThrows(AwsException.class,
                () -> recipient.describeImages(REGION, List.of(source.getImageId()), List.of(), Map.of()))
                .getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "error", "encrypted", "size"})
    void unusableBackingSnapshotRefusesWithoutDestinationWrites(String condition) {
        Image source = source();
        grant(source);
        Snapshot snapshot = owner.describeSnapshots(REGION, List.of(snapshotId(source)), List.of(), Map.of()).getFirst();
        String code;
        if (condition.equals("encrypted")) {
            snapshot.setEncrypted(true);
            code = "UnsupportedOperation";
        } else if (condition.equals("size")) {
            snapshot.setVolumeSize(null);
            code = "InvalidParameterValue";
        } else {
            snapshot.setState(condition);
            code = "IncorrectState";
        }
        assertRefusedWithoutWrites(source, code);
    }

    @Test
    void foreignDescriptionsDoNotCreateCallerRowsOrMutateOwnerTags() {
        Image source = source();
        grant(source);
        source.setTags(List.of(new Tag("Private", "owner")));
        Set<String> before = Set.copyOf(store("ec2-registered-images.json").keys());
        assertEquals("InvalidAMIID.NotFound", assertThrows(AwsException.class,
                () -> recipient.describeImages(REGION, List.of(source.getImageId()), List.of(), Map.of()))
                .getErrorCode());
        assertEquals(before, store("ec2-registered-images.json").keys());
        assertEquals("owner", source.getTags().getFirst().getValue());
        assertFalse(store("ec2-tags.json").keys().stream().anyMatch(key -> key.startsWith(RECIPIENT + "/")));
        assertEquals("ami-0123456789abcdef0", recipient.describeImages(REGION,
                List.of("ami-0123456789abcdef0"), List.of(), Map.of()).getFirst().getImageId());
    }

    @Test
    void matchingAttributePermissionsRemainOwnerScopedAndEncryptedGrantIsRefused() {
        Image source = source();
        Snapshot snapshot = owner.describeSnapshots(REGION, List.of(snapshotId(source)), List.of(), Map.of()).getFirst();
        snapshot.setEncrypted(true);
        assertEquals("UnsupportedOperation", assertThrows(AwsException.class,
                () -> owner.modifySnapshotCreateVolumePermissions(REGION, snapshotId(source),
                        List.of(RECIPIENT), List.of(), false)).getErrorCode());
        assertTrue(snapshot.getCreateVolumePermissionUserIds().isEmpty());
        assertEquals("InvalidAMIID.NotFound", assertThrows(AwsException.class,
                () -> recipient.modifyImageLaunchPermissions(REGION, source.getImageId(),
                        List.of(OTHER), List.of(), false)).getErrorCode());
        assertTrue(source.getLaunchPermissionUserIds().isEmpty());
    }

    private void assertRefusedWithoutWrites(Image source, String code) {
        assertRefusedWithoutWrites(source.getImageId(), code);
    }

    private void assertRefusedWithoutWrites(String sourceImageId, String code) {
        Set<String> images = Set.copyOf(store("ec2-registered-images.json").keys());
        Set<String> snapshots = Set.copyOf(store("ec2-snapshots.json").keys());
        assertEquals(code, assertThrows(AwsException.class,
                () -> recipient.copyImage(DESTINATION, REGION, sourceImageId, "refused-copy", null)).getErrorCode());
        assertEquals(images, store("ec2-registered-images.json").keys());
        assertEquals(snapshots, store("ec2-snapshots.json").keys());
    }

    private Image source() {
        Volume volume = owner.createVolume(REGION, REGION + "a", "gp2", 10, false,
                0, null, null, List.of());
        Snapshot snapshot = owner.createSnapshot(REGION, volume.getVolumeId(), "source snapshot",
                List.of(new Tag("Private", "snapshot")), false);
        owner.deleteVolume(REGION, volume.getVolumeId());
        EbsBlockDevice ebs = new EbsBlockDevice();
        ebs.setSnapshotId(snapshot.getSnapshotId());
        BlockDeviceMapping mapping = new BlockDeviceMapping();
        mapping.setDeviceName("/dev/xvda");
        mapping.setEbs(ebs);
        return owner.registerImage(REGION, "catalog-release", "source", "arm64", "/dev/xvda", List.of(mapping));
    }

    private void grant(Image image) {
        owner.modifyImageLaunchPermissions(REGION, image.getImageId(), List.of(RECIPIENT), List.of(), false);
        owner.modifySnapshotCreateVolumePermissions(REGION, snapshotId(image), List.of(RECIPIENT), List.of(), false);
    }

    private String snapshotId(Image image) {
        return image.getBlockDeviceMappings().getFirst().getEbs().getSnapshotId();
    }

    @SuppressWarnings("unchecked")
    private <V> StorageBackend<String, V> store(String name) {
        return (StorageBackend<String, V>) stores.computeIfAbsent(name, ignored -> new InMemoryStorage<>());
    }

    private Ec2Service service(String account) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(account);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        StorageFactory storage = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                          TypeReference<Map<String, V>> typeReference) {
                return new AccountAwareStorageBackend<>(store(fileName), null, account);
            }
        };
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                new AmiImageResolver(catalog), catalog, new Ec2InstanceTypeCatalog(), storage);
    }

    private static Ec2ImageCatalog catalog() {
        Ec2ImageCatalog.CatalogImage image = new Ec2ImageCatalog.CatalogImage();
        image.imageId = "ami-catalog-worker";
        image.dockerImage = "example/worker:1";
        image.name = "worker";
        image.description = "worker";
        image.architecture = "arm64";
        image.creationDate = "2026-10-01T00:00:00.000Z";
        image.registrationNames = List.of("catalog-release");
        Ec2ImageCatalog.Catalog catalog = new Ec2ImageCatalog.Catalog();
        catalog.defaultDockerImage = "example/base:1";
        catalog.images = List.of(image);
        return new Ec2ImageCatalog(catalog);
    }
}

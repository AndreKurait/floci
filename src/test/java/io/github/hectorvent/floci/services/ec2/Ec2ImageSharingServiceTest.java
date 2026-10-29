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
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void catalogBoundSharedLaunchRetainsRuntimeAndRecipientOwnership(boolean intermediateAncestor) {
        Image source = source();
        if (intermediateAncestor) {
            Image intermediate = owner.registerImage(REGION, "intermediate", null,
                    "arm64", "/dev/xvda", List.of());
            intermediate.setSourceImageId(source.getSourceImageId());
            source.setSourceImageId(intermediate.getImageId());
        }
        owner.modifyImageLaunchPermissions(REGION, source.getImageId(), List.of(RECIPIENT), List.of(), false);
        Set<String> images = Set.copyOf(store("ec2-registered-images.json").keys());
        Set<String> snapshots = Set.copyOf(store("ec2-snapshots.json").keys());
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        Ec2Service launcher = service(RECIPIENT, false, manager);

        Instance instance = launcher.runInstances(REGION, source.getImageId(), "m6g.large", 1, 1,
                null, List.of(), null, null, List.of(), "native user data", null).getInstances().getFirst();

        ArgumentCaptor<ResolvedAmiImage> runtime = ArgumentCaptor.forClass(ResolvedAmiImage.class);
        verify(manager).launch(eq(instance), runtime.capture(), any(), eq(REGION), any(), any(), any(), any());
        assertEquals(new AmiImageResolver(catalog).resolveImage("ami-catalog-worker"), runtime.getValue());
        assertEquals("example/worker:1", runtime.getValue().dockerImage());
        assertEquals("arm64", instance.getArchitecture());
        assertEquals(source.getImageId(), instance.getImageId());
        assertEquals("native user data", instance.getUserData());
        assertEquals(RECIPIENT, launcher.callerAccountId());
        assertEquals(OWNER, source.getOwnerId());
        assertTrue(store("ec2-instances.json").keys().contains(RECIPIENT + "/" + REGION + "::" + instance.getInstanceId()));
        assertEquals(snapshotId(source), launcher.describeVolumes(REGION, List.of(), Map.of())
                .getFirst().getSnapshotId());
        assertEquals(images, store("ec2-registered-images.json").keys());
        assertEquals(snapshots, store("ec2-snapshots.json").keys());
        assertTrue(owner.describeSnapshots(REGION, List.of(snapshotId(source)), List.of(), Map.of())
                .getFirst().getCreateVolumePermissionUserIds().isEmpty());
        owner.modifyImageLaunchPermissions(REGION, source.getImageId(), List.of(), List.of(RECIPIENT), false);
        assertEquals("InvalidAMIID.NotFound", assertThrows(AwsException.class,
                () -> launcher.runInstances(REGION, source.getImageId(), "m6g.large", 1, 1,
                        null, List.of(), null, null, List.of(), null, null)).getErrorCode());
        assertEquals(1, launcher.describeInstances(REGION, List.of(), Map.of()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unbound", "captured", "captured-ancestor", "missing-ancestor", "cycle"})
    void unsupportedSharedRuntimeCannotFallBackOrAllocateLaunchResources(String fault) {
        Image source = source();
        grant(source);
        switch (fault) {
            case "unbound" -> source.setSourceImageId(null);
            case "captured" -> source.setDockerImage("example/captured:1");
            case "captured-ancestor" -> {
                Image ancestor = owner.registerImage(REGION, "captured", null, "arm64", "/dev/xvda", List.of());
                ancestor.setDockerImage("example/captured:1");
                source.setSourceImageId(ancestor.getImageId());
            }
            case "missing-ancestor" -> source.setSourceImageId("ami-0123456789abcdef0");
            case "cycle" -> source.setSourceImageId(source.getImageId());
            default -> throw new AssertionError(fault);
        }
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        Ec2Service launcher = service(RECIPIENT, false, manager);
        String code = switch (fault) {
            case "missing-ancestor" -> "InvalidAMIID.NotFound";
            case "cycle" -> "InvalidAMIID.Unavailable";
            default -> "UnsupportedOperation";
        };
        assertEquals(code, assertThrows(AwsException.class,
                () -> launcher.runInstances(REGION, source.getImageId(), "m6g.large", 1, 1,
                        null, List.of(), null, null, List.of(), null, null)).getErrorCode());
        assertTrue(launcher.describeInstances(REGION, List.of(), Map.of()).isEmpty());
        assertTrue(launcher.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
        verifyNoInteractions(manager);
    }

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
    @CsvSource({"false,us-west-2", "true,us-east-1", "true,us-west-2"})
    void copiesOnlyTagsVisibleToTheCallerWhenRequested(boolean foreign, String destination) {
        Image source = source();
        owner.createTags(REGION, List.of(source.getImageId()), List.of(
                new Tag("Private", "owner"), new Tag("ec2:SharedTag/purpose", "worker"),
                new Tag("ec2:SharedTag/empty", "")));
        owner.createSystemTags(REGION, List.of(source.getImageId()), List.of(new Tag("aws:source", "system")));
        if (foreign) {
            grant(source);
            recipient.createTags(REGION, List.of(source.getImageId()), List.of(new Tag("ReceiverPrivate", "receiver")));
        }
        Ec2Service copier = foreign ? recipient : owner;
        Image withoutTags = copier.copyImage(destination, REGION, source.getImageId(), "untagged-copy", null);
        assertTrue(withoutTags.getTags().isEmpty());
        Image copy = copier.copyImage(destination, REGION, source.getImageId(), "tagged-copy", null, false, true);

        assertEquals(Map.of("ec2:SharedTag/purpose", "worker", "ec2:SharedTag/empty", "",
                        foreign ? "ReceiverPrivate" : "Private", foreign ? "receiver" : "owner"),
                copier.describeImages(destination, List.of(copy.getImageId()), List.of("self"), Map.of())
                        .getFirst().getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
        assertEquals(3, copier.describeTags(destination,
                Map.of("resource-id", List.of(copy.getImageId()))).size());
        assertEquals(foreign, copier.describeImages(destination, List.of(copy.getImageId()), List.of(),
                Map.of("tag:Private", List.of("owner"))).isEmpty());
        assertTrue(copier.describeSnapshots(destination, List.of(snapshotId(copy)), List.of("self"), Map.of())
                .getFirst().getTags().isEmpty());
        copier.createTags(destination, List.of(copy.getImageId()), List.of(
                new Tag("ec2:SharedTag/purpose", "changed")));
        assertEquals(List.of("Private=owner", "ec2:SharedTag/purpose=worker", "ec2:SharedTag/empty=", "aws:source=system"),
                owner.describeImages(REGION, List.of(source.getImageId()), List.of("self"), Map.of())
                        .getFirst().getTags().stream().map(tag -> tag.getKey() + "=" + tag.getValue()).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recipientCannotModifyOwnersSharedTagsButKeepsPrivateTags(boolean delete) {
        Image source = source();
        grant(source);
        owner.createTags(REGION, List.of(source.getImageId()), List.of(
                new Tag("ec2:SharedTag/purpose", "owner"), new Tag("OwnerPrivate", "private")));
        recipient.createTags(REGION, List.of(source.getImageId()), List.of(new Tag("ReceiverPrivate", "receiver")));
        List<Tag> update = List.of(new Tag("ReceiverPrivate", "changed"), new Tag("ec2:SharedTag/purpose", "changed"));

        AwsException failure = assertThrows(AwsException.class, () -> {
            if (delete) {
                recipient.deleteTags(REGION, List.of(source.getImageId()), update);
            } else {
                recipient.createTags(REGION, List.of(source.getImageId()), update);
            }
        });

        assertEquals("AccessDenied", failure.getErrorCode());
        assertEquals(403, failure.getHttpStatus());
        assertEquals("Only the resource owner can " + (delete ? "delete" : "create")
                + " shared tags on resource '" + source.getImageId() + "'.", failure.getMessage());
        assertEquals(Map.of("ec2:SharedTag/purpose", "owner", "ReceiverPrivate", "receiver"),
                recipient.describeImages(REGION, List.of(source.getImageId()), List.of(), Map.of()).getFirst()
                        .getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
        recipient.deleteTags(REGION, List.of(source.getImageId()), List.of(new Tag("ReceiverPrivate", null)));
        assertEquals(List.of("ec2:SharedTag/purpose"), recipient.describeImages(REGION, List.of(source.getImageId()),
                List.of(), Map.of()).getFirst().getTags().stream().map(Tag::getKey).toList());
        assertEquals(Map.of("ec2:SharedTag/purpose", "owner", "OwnerPrivate", "private"),
                owner.describeImages(REGION, List.of(source.getImageId()), List.of(), Map.of()).getFirst()
                        .getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
        owner.modifyImageLaunchPermissions(REGION, source.getImageId(), List.of(), List.of(RECIPIENT), false);
        assertEquals("InvalidAMIID.NotFound", assertThrows(AwsException.class, () -> recipient.createTags(
                REGION, List.of(source.getImageId()), List.of(new Tag("ReceiverPrivate", "after-revocation")))).getErrorCode());
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
    void unboundForeignImageCopiesInMetadataModeWithoutInventingRuntimeAncestry() {
        Image source = source();
        grant(source);
        source.setSourceImageId(null);

        Image copy = recipient.copyImage(DESTINATION, REGION, source.getImageId(), "metadata-copy", null);
        assertEquals(source.getImageId(), copy.getCreationSourceImageId());
        assertEquals(null, copy.getSourceImageId());
        assertEquals(RECIPIENT, copy.getOwnerId());
        assertNotEquals(snapshotId(source), snapshotId(copy));
        assertEquals("UnsupportedOperation", assertThrows(AwsException.class,
                () -> service(RECIPIENT, false).copyImage(DESTINATION, REGION,
                        source.getImageId(), "container-copy", null)).getErrorCode());
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
        EbsBlockDevice sourceEbs = source.getBlockDeviceMappings().getFirst().getEbs();
        sourceEbs.setIops(6000);
        sourceEbs.setThroughput(300);
        Set<String> before = Set.copyOf(store("ec2-registered-images.json").keys());
        Image described = recipient.describeImages(REGION, List.of(source.getImageId()), List.of(), Map.of()).getFirst();
        assertEquals(OWNER, described.getOwnerId());
        assertTrue(described.getTags().isEmpty());
        EbsBlockDevice describedEbs = described.getBlockDeviceMappings().getFirst().getEbs();
        assertEquals(6000, describedEbs.getIops());
        assertEquals(300, describedEbs.getThroughput());
        describedEbs.setSnapshotId("snap-changed");
        describedEbs.setIops(9000);
        describedEbs.setThroughput(500);
        assertNotEquals("snap-changed", snapshotId(source));
        assertEquals(6000, sourceEbs.getIops());
        assertEquals(300, sourceEbs.getThroughput());
        assertEquals(before, store("ec2-registered-images.json").keys());
        assertEquals("owner", source.getTags().getFirst().getValue());
        assertFalse(store("ec2-tags.json").keys().stream().anyMatch(key -> key.startsWith(RECIPIENT + "/")));
        assertEquals("ami-0123456789abcdef0", recipient.describeImages(REGION,
                List.of("ami-0123456789abcdef0"), List.of(), Map.of()).getFirst().getImageId());
    }

    @Test
    void sharedImageReadsHonorOwnersFiltersRevocationAndSharedTagVisibility() {
        Image source = source();
        grant(source);
        source.setTags(List.of(new Tag("Private", "owner"), new Tag("ec2:SharedTag/purpose", "worker")));
        assertTrue(recipient.describeImages(REGION, List.of(source.getImageId()), List.of("self"), Map.of()).isEmpty());
        assertTrue(recipient.describeImages(REGION, List.of(source.getImageId()), List.of(),
                Map.of("tag:Private", List.of("owner"))).isEmpty());
        Image visible = recipient.describeImages(REGION, List.of(), List.of(OWNER),
                Map.of("tag:ec2:SharedTag/purpose", List.of("worker"))).getFirst();
        assertEquals(source.getImageId(), visible.getImageId());
        assertEquals(1, visible.getTags().size());
        owner.modifyImageLaunchPermissions(REGION, source.getImageId(), List.of(), List.of(RECIPIENT), false);
        assertTrue(recipient.describeImages(REGION, List.of(), List.of(OWNER), Map.of()).isEmpty());
        assertEquals("InvalidAMIID.NotFound", assertThrows(AwsException.class,
                () -> recipient.describeImages(REGION, List.of(source.getImageId()), List.of(), Map.of())).getErrorCode());
        assertEquals("InvalidAMIID.NotFound", assertThrows(AwsException.class,
                () -> recipient.runInstances(REGION, source.getImageId(), "m6g.large", 1, 1,
                        null, List.of(), null, null, List.of(), null, null)).getErrorCode());
    }

    @Test
    void createVolumeFromSharedSnapshotUsesNativeSizeAndDoesNotMutateTheSource() {
        Image source = source();
        grant(source);
        String id = snapshotId(source);
        Volume volume = recipient.createVolume(REGION, REGION + "a", "gp3", 0, false,
                3000, 125, id, List.of(new Tag("Recipient", "volume")));
        assertEquals(10, volume.getSize());
        assertEquals(id, volume.getSnapshotId());
        assertEquals(125, volume.getThroughput());
        Snapshot original = owner.describeSnapshots(REGION, List.of(id), List.of("self"), Map.of()).getFirst();
        assertEquals(OWNER, original.getOwnerId());
        assertEquals("Private", original.getTags().getFirst().getKey());
        assertTrue(recipient.describeSnapshots(REGION, List.of(), List.of("self"), Map.of()).isEmpty());
        Set<String> before = Set.copyOf(store("ec2-volumes.json").keys());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> recipient.createVolume(REGION, REGION + "a", "gp3", 9, false,
                        3000, 125, id, List.of())).getErrorCode());
        assertEquals("InvalidSnapshot.NotFound", assertThrows(AwsException.class,
                () -> service(OTHER).createVolume(REGION, REGION + "a", "gp3", 10, false,
                        3000, 125, id, List.of())).getErrorCode());
        assertEquals("InvalidSnapshot.NotFound", assertThrows(AwsException.class,
                () -> recipient.createVolume(DESTINATION, DESTINATION + "a", "gp3", 10, false,
                        3000, 125, id, List.of())).getErrorCode());
        owner.modifySnapshotCreateVolumePermissions(REGION, id, List.of(), List.of(RECIPIENT), false);
        assertEquals("InvalidSnapshot.NotFound", assertThrows(AwsException.class,
                () -> recipient.createVolume(REGION, REGION + "a", "gp3", 10, false,
                        3000, 125, id, List.of())).getErrorCode());
        assertEquals(before, store("ec2-volumes.json").keys());
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
        return service(account, true);
    }

    private Ec2Service service(String account, boolean mockMode) {
        return service(account, mockMode, mock(Ec2ContainerManager.class));
    }

    private Ec2Service service(String account, boolean mockMode, Ec2ContainerManager manager) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(account);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(mockMode);
        StorageFactory storage = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                          TypeReference<Map<String, V>> typeReference) {
                return new AccountAwareStorageBackend<>(store(fileName), null, account);
            }
        };
        return new Ec2Service(config, manager, mock(Ec2PortForwardManager.class),
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

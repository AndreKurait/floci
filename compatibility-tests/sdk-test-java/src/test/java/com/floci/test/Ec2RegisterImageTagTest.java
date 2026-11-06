package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.ArchitectureValues;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.CopyImageRequest;
import software.amazon.awssdk.services.ec2.model.CreateVolumePermission;
import software.amazon.awssdk.services.ec2.model.CreateVolumePermissionModifications;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.LaunchPermission;
import software.amazon.awssdk.services.ec2.model.LaunchPermissionModifications;
import software.amazon.awssdk.services.ec2.model.RegisterImageRequest;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.VirtualizationType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2RegisterImageTagTest {

    @Test
    void registerImagePublishesInitialTagsOnlyInItsOwnersRegion() {
        String name = TestFixtures.uniqueName("register-image-tags");
        Region home = TestFixtures.region();
        Region other = Region.regions().stream()
                .filter(region -> !region.equals(home) && !region.isGlobalRegion() && region.metadata() != null)
                .filter(region -> TestFixtures.partition().equals(region.metadata().partition().id()))
                .findFirst().orElseThrow();
        List<Tag> tags = List.of(Tag.builder().key("SdkImageProbe").value(name).build(),
                Tag.builder().key("Purpose").value("registration").build());
        try (Ec2Client ec2 = client("111111111111", home);
                Ec2Client otherAccount = client("222222222222", home);
                Ec2Client otherRegion = client("111111111111", other);
                ImageSource source = new ImageSource(ec2, name)) {
            String imageId = source.register(source.registration(name).tagSpecifications(
                    TagSpecification.builder().resourceType(ResourceType.IMAGE).tags(tags).build()).build());
            Image image = ec2.describeImages(request -> request.imageIds(imageId)).images().get(0);
            assertThat(image.ownerId()).isEqualTo("111111111111");
            assertThat(image.publicLaunchPermissions()).isFalse();
            assertThat(image.tags()).containsExactlyInAnyOrderElementsOf(tags);
            Filter filter = Filter.builder().name("tag:SdkImageProbe").values(name).build();
            assertThat(ec2.describeImages(request -> request.filters(filter)).images())
                    .extracting(Image::imageId).containsExactly(imageId);
            assertThat(otherAccount.describeImages(request -> request.filters(filter)).images()).isEmpty();
            assertThat(otherRegion.describeImages(request -> request.filters(filter)).images()).isEmpty();
        }
    }

    @Test
    void registerImageRefusesOtherTagResourceTypesBeforePublishingAnImage() {
        String name = TestFixtures.uniqueName("register-image-invalid-tags");
        try (Ec2Client ec2 = client("111111111111", TestFixtures.region());
                ImageSource source = new ImageSource(ec2, name)) {
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> source.register(
                    source.registration(name).tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.INSTANCE)
                            .tags(Tag.builder().key("SdkImageProbe").value(name).build()).build()).build()));
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");
            assertThat(ec2.describeImages(request -> request.filters(
                    Filter.builder().name("name").values(name).build())).images()).isEmpty();
        }
    }

    @Test
    void sharedImageCopyPreservesSharedTagsWithoutCopyingPrivateTags() {
        String name = TestFixtures.uniqueName("shared-image-copy");
        Region home = TestFixtures.region();
        Region destination = Region.regions().stream()
                .filter(region -> !region.equals(home) && !region.isGlobalRegion() && region.metadata() != null)
                .filter(region -> TestFixtures.partition().equals(region.metadata().partition().id()))
                .findFirst().orElseThrow();
        Tag shared = Tag.builder().key("ec2:SharedTag/SdkImageProbe").value(name).build();
        Tag privateTag = Tag.builder().key("Purpose").value("private-image-note").build();
        Tag recipientTag = Tag.builder().key("ReceiverPrivate").value("recipient-note").build();
        try (Ec2Client owner = client("111111111111", home);
                Ec2Client ownerDestination = client("111111111111", destination);
                Ec2Client recipient = client("222222222222", destination);
                Ec2Client recipientHome = client("222222222222", home);
                ImageSource source = new ImageSource(owner, name);
                CopiedImages copies = new CopiedImages(recipient)) {
            String sourceId = source.register(source.registration(name).tagSpecifications(
                    TagSpecification.builder().resourceType(ResourceType.IMAGE).tags(privateTag).build()).build());
            CopyImageRequest request = CopyImageRequest.builder().sourceImageId(sourceId)
                    .sourceRegion(home.id()).name(name + "-copy").copyImageTags(true).build();
            Ec2Exception denied = assertThrows(Ec2Exception.class, () -> copies.copy(request));
            assertThat(denied.awsErrorDetails().errorCode()).isEqualTo("AuthFailure");
            owner.modifyImageAttribute(change -> change.imageId(sourceId).launchPermission(
                    LaunchPermissionModifications.builder().add(
                            LaunchPermission.builder().userId("222222222222").build()).build()));
            denied = assertThrows(Ec2Exception.class, () -> copies.copy(request));
            assertThat(denied.awsErrorDetails().errorCode()).isEqualTo("AuthFailure");
            owner.modifySnapshotAttribute(change -> change.snapshotId(source.snapshotId).createVolumePermission(
                    CreateVolumePermissionModifications.builder().add(
                            CreateVolumePermission.builder().userId("222222222222").build()).build()));
            owner.createTags(change -> change.resources(sourceId).tags(shared));
            assertThat(owner.describeImages(read -> read.imageIds(sourceId)).images().get(0).tags())
                    .containsExactlyInAnyOrder(privateTag, shared);
            recipientHome.createTags(change -> change.resources(sourceId).tags(recipientTag));
            assertThat(recipientHome.describeImages(read -> read.imageIds(sourceId)).images().get(0).tags())
                    .containsExactlyInAnyOrder(shared, recipientTag);
            Ec2Exception sharedTagError = assertThrows(Ec2Exception.class, () -> recipientHome.createTags(
                    change -> change.resources(sourceId).tags(shared.toBuilder().value("spoofed").build())));
            assertThat(sharedTagError.statusCode()).isEqualTo(403);
            assertThat(sharedTagError.awsErrorDetails().errorCode()).isEqualTo("AccessDenied");
            String untaggedCopyId = copies.copy(request.toBuilder().copyImageTags(false)
                    .name(name + "-untagged").build());
            String ownedUntaggedCopyId = untaggedCopyId;
            assertThat(recipient.describeImages(read -> read.imageIds(ownedUntaggedCopyId))
                    .images().get(0).tags()).isEmpty();
            String copyId = copies.copy(request);
            String ownedCopyId = copyId;
            Image copy = recipient.describeImages(read -> read.imageIds(ownedCopyId)).images().get(0);
            assertThat(copy.ownerId()).isEqualTo("222222222222");
            assertThat(copy.publicLaunchPermissions()).isFalse();
            assertThat(copy.tags()).containsExactlyInAnyOrder(shared, recipientTag);
            assertThat(copy.blockDeviceMappings().get(0).ebs().snapshotId()).isNotEqualTo(source.snapshotId);
            assertThat(recipient.describeSnapshots(read -> read.snapshotIds(
                    copy.blockDeviceMappings().get(0).ebs().snapshotId())).snapshots().get(0).ownerId())
                    .isEqualTo("222222222222");
            assertThat(recipientHome.describeImages(read -> read.filters(
                    Filter.builder().name("name").values(name + "-copy").build())).images()).isEmpty();
            assertThat(ownerDestination.describeImages(read -> read.filters(
                    Filter.builder().name("name").values(name + "-copy").build())).images()).isEmpty();
        }
    }

    private static RuntimeException attemptCleanup(RuntimeException failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException error) {
            if (failure == null) {
                return error;
            }
            if (failure != error) {
                failure.addSuppressed(error);
            }
        }
        return failure;
    }

    static final class CopiedImages implements AutoCloseable {
        private final Ec2Client ec2;
        private final List<String> imageIds = new ArrayList<>();

        CopiedImages(Ec2Client ec2) {
            this.ec2 = ec2;
        }

        String copy(CopyImageRequest request) {
            String imageId = ec2.copyImage(request).imageId();
            imageIds.add(imageId);
            return imageId;
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (int index = imageIds.size() - 1; index >= 0; index--) {
                String imageId = imageIds.get(index);
                List<String> snapshots = new ArrayList<>();
                failure = attemptCleanup(failure, () -> {
                    Image image = ec2.describeImages(read -> read.imageIds(imageId)).images().get(0);
                    for (BlockDeviceMapping mapping : image.blockDeviceMappings()) {
                        if (mapping.ebs() != null && mapping.ebs().snapshotId() != null) {
                            snapshots.add(mapping.ebs().snapshotId());
                        }
                    }
                });
                failure = attemptCleanup(failure, () -> ec2.deregisterImage(change -> change.imageId(imageId)));
                for (String snapshot : snapshots) {
                    failure = attemptCleanup(failure, () -> ec2.deleteSnapshot(change -> change.snapshotId(snapshot)));
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static Ec2Client client(String account, Region region) {
        return Ec2Client.builder().endpointOverride(TestFixtures.endpoint()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    static final class ImageSource implements AutoCloseable {
        private final Ec2Client ec2;
        private final List<String> imageIds = new ArrayList<>();
        private String volumeId;
        private String snapshotId;

        ImageSource(Ec2Client ec2, String name) {
            this.ec2 = ec2;
            try {
                String zone = ec2.describeAvailabilityZones().availabilityZones().get(0).zoneName();
                volumeId = ec2.createVolume(request -> request.availabilityZone(zone).size(1)).volumeId();
                ec2.waiter().waitUntilVolumeAvailable(DescribeVolumesRequest.builder().volumeIds(volumeId).build(),
                        TestFixtures.waiterOverride(Duration.ofMinutes(2))).matched().response().orElseThrow();
                snapshotId = ec2.createSnapshot(request -> request.volumeId(volumeId).description(name)).snapshotId();
                ec2.waiter().waitUntilSnapshotCompleted(DescribeSnapshotsRequest.builder().snapshotIds(snapshotId).build(),
                        TestFixtures.waiterOverride(Duration.ofMinutes(2))).matched().response().orElseThrow();
            } catch (RuntimeException error) {
                try {
                    close();
                } catch (RuntimeException cleanup) {
                    if (error != cleanup) {
                        error.addSuppressed(cleanup);
                    }
                }
                throw error;
            }
        }

        String register(RegisterImageRequest request) {
            String imageId = ec2.registerImage(request).imageId();
            imageIds.add(imageId);
            return imageId;
        }

        RegisterImageRequest.Builder registration(String imageName) {
            return RegisterImageRequest.builder().name(imageName).architecture(ArchitectureValues.X86_64)
                    .virtualizationType(VirtualizationType.HVM.toString()).rootDeviceName("/dev/sda1")
                    .blockDeviceMappings(BlockDeviceMapping.builder().deviceName("/dev/sda1")
                            .ebs(EbsBlockDevice.builder().snapshotId(snapshotId).deleteOnTermination(true).build())
                            .build());
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (int index = imageIds.size() - 1; index >= 0; index--) {
                String imageId = imageIds.get(index);
                failure = attemptCleanup(failure, () -> ec2.deregisterImage(request -> request.imageId(imageId)));
            }
            if (snapshotId != null) {
                failure = attemptCleanup(failure, () -> ec2.deleteSnapshot(request -> request.snapshotId(snapshotId)));
            }
            if (volumeId != null) {
                failure = attemptCleanup(failure, () -> ec2.deleteVolume(request -> request.volumeId(volumeId)));
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}

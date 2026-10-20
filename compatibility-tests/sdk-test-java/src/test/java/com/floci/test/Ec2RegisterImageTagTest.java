package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.ArchitectureValues;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.RegisterImageRequest;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.VirtualizationType;

import java.time.Duration;
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
            String imageId = ec2.registerImage(source.registration(name).tagSpecifications(
                    TagSpecification.builder().resourceType(ResourceType.IMAGE).tags(tags).build()).build()).imageId();
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
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> ec2.registerImage(
                    source.registration(name).tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.INSTANCE)
                            .tags(Tag.builder().key("SdkImageProbe").value(name).build()).build()).build()));
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");
            assertThat(ec2.describeImages(request -> request.filters(
                    Filter.builder().name("name").values(name).build())).images()).isEmpty();
        }
    }

    private static Ec2Client client(String account, Region region) {
        return Ec2Client.builder().endpointOverride(TestFixtures.endpoint()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private static final class ImageSource implements AutoCloseable {
        private final Ec2Client ec2;
        private final String name;
        private String volumeId;
        private String snapshotId;

        private ImageSource(Ec2Client ec2, String name) {
            this.ec2 = ec2;
            this.name = name;
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
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        }

        private RegisterImageRequest.Builder registration(String imageName) {
            return RegisterImageRequest.builder().name(imageName).architecture(ArchitectureValues.X86_64)
                    .virtualizationType(VirtualizationType.HVM.toString()).rootDeviceName("/dev/sda1")
                    .blockDeviceMappings(BlockDeviceMapping.builder().deviceName("/dev/sda1")
                            .ebs(EbsBlockDevice.builder().snapshotId(snapshotId).deleteOnTermination(true).build())
                            .build());
        }

        @Override
        public void close() {
            try {
                ec2.describeImages(request -> request.filters(Filter.builder().name("name").values(name).build()))
                        .images().forEach(image -> ec2.deregisterImage(request -> request.imageId(image.imageId())));
            } finally {
                try {
                    if (snapshotId != null) {
                        ec2.deleteSnapshot(request -> request.snapshotId(snapshotId));
                    }
                } finally {
                    if (volumeId != null) {
                        ec2.deleteVolume(request -> request.volumeId(volumeId));
                    }
                }
            }
        }
    }
}

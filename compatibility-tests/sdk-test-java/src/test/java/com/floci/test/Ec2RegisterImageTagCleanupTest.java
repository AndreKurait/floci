package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AvailabilityZone;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.CopyImageRequest;
import software.amazon.awssdk.services.ec2.model.CopyImageResponse;
import software.amazon.awssdk.services.ec2.model.CreateSnapshotRequest;
import software.amazon.awssdk.services.ec2.model.CreateSnapshotResponse;
import software.amazon.awssdk.services.ec2.model.CreateVolumeRequest;
import software.amazon.awssdk.services.ec2.model.CreateVolumeResponse;
import software.amazon.awssdk.services.ec2.model.DeleteSnapshotRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSnapshotResponse;
import software.amazon.awssdk.services.ec2.model.DeleteVolumeRequest;
import software.amazon.awssdk.services.ec2.model.DeleteVolumeResponse;
import software.amazon.awssdk.services.ec2.model.DeregisterImageRequest;
import software.amazon.awssdk.services.ec2.model.DeregisterImageResponse;
import software.amazon.awssdk.services.ec2.model.DescribeAvailabilityZonesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeAvailabilityZonesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.RegisterImageRequest;
import software.amazon.awssdk.services.ec2.model.RegisterImageResponse;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.waiters.Ec2Waiter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2RegisterImageTagCleanupTest {
    @Test
    void sourceAttemptsEveryOwnedResourceAndRetainsCleanupCauses() {
        RecordingClient recording = new RecordingClient();
        RuntimeException first = recording.fail("deregister:ami-1");
        RuntimeException snapshot = recording.fail("snapshot:snap-source");
        RuntimeException volume = recording.fail("volume:vol-source");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try (Ec2RegisterImageTagTest.ImageSource source = recording.source()) {
                source.register(source.registration("one").build());
                source.register(source.registration("two").build());
            }
        });
        assertThat(thrown).isSameAs(first);
        assertThat(thrown.getSuppressed()).containsExactly(snapshot, volume);
        assertThat(recording.deletions).containsExactly("deregister:ami-2", "deregister:ami-1",
                "snapshot:snap-source", "volume:vol-source");
    }

    @Test
    void sourcePreservesAssertionFailureAndSuppressesTeardown() {
        RecordingClient recording = new RecordingClient();
        RuntimeException cleanup = recording.fail("deregister:ami-1");
        AssertionError body = new AssertionError("body");
        AssertionError thrown = assertThrows(AssertionError.class, () -> {
            try (Ec2RegisterImageTagTest.ImageSource source = recording.source()) {
                source.register(source.registration("one").build());
                throw body;
            }
        });
        assertThat(thrown).isSameAs(body);
        assertThat(thrown.getSuppressed()).containsExactly(cleanup);
        assertThat(recording.deletions).containsExactly("deregister:ami-1", "snapshot:snap-source", "volume:vol-source");
    }

    @Test
    void setupFailureCleansAllocatedVolumeAndPreservesPrimary() {
        RecordingClient recording = new RecordingClient();
        RuntimeException setup = recording.fail("createSnapshot");
        RuntimeException cleanup = recording.fail("volume:vol-source");
        RuntimeException thrown = assertThrows(RuntimeException.class, recording::source);
        assertThat(thrown).isSameAs(setup);
        assertThat(thrown.getSuppressed()).containsExactly(cleanup);
        assertThat(recording.deletions).containsExactly("volume:vol-source");
    }

    @Test
    void snapshotWaitFailureStillAttemptsSnapshotAndVolume() {
        RecordingClient recording = new RecordingClient();
        recording.fail("describeSnapshots");
        RuntimeException snapshot = recording.fail("snapshot:snap-source");
        RuntimeException volume = recording.fail("volume:vol-source");
        RuntimeException thrown = assertThrows(RuntimeException.class, recording::source);
        assertThat(thrown.getSuppressed()).containsExactly(snapshot);
        assertThat(snapshot.getSuppressed()).containsExactly(volume);
        assertThat(recording.deletions).containsExactly("snapshot:snap-source", "volume:vol-source");
    }

    @Test
    void failedFirstAllocationHasNothingToDelete() {
        RecordingClient recording = new RecordingClient();
        RuntimeException setup = recording.fail("createVolume");
        assertThat(assertThrows(RuntimeException.class, recording::source)).isSameAs(setup);
        assertThat(recording.deletions).isEmpty();
    }

    @Test
    void copiesAttemptEverySnapshotAndImageAndRetainPrimary() {
        RecordingClient recording = new RecordingClient();
        RuntimeException snapshot = recording.fail("snapshot:ami-2-snapshot-1");
        RuntimeException deregister = recording.fail("deregister:ami-1");
        AssertionError body = new AssertionError("copy assertion");
        AssertionError thrown = assertThrows(AssertionError.class, () -> {
            try (Ec2RegisterImageTagTest.CopiedImages copies = new Ec2RegisterImageTagTest.CopiedImages(recording.client)) {
                copies.copy(CopyImageRequest.builder().name("one").build());
                copies.copy(CopyImageRequest.builder().name("two").build());
                throw body;
            }
        });
        assertThat(thrown).isSameAs(body);
        assertThat(thrown.getSuppressed()).containsExactly(snapshot);
        assertThat(snapshot.getSuppressed()).containsExactly(deregister);
        assertThat(recording.deletions).containsExactly("deregister:ami-2", "snapshot:ami-2-snapshot-1",
                "snapshot:ami-2-snapshot-2", "deregister:ami-1", "snapshot:ami-1-snapshot-1", "snapshot:ami-1-snapshot-2");
    }

    @Test
    void failedCopyDescribeStillDeregistersAndCleansOtherCopy() {
        RecordingClient recording = new RecordingClient();
        RuntimeException describe = recording.fail("describe:ami-2");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try (Ec2RegisterImageTagTest.CopiedImages copies = new Ec2RegisterImageTagTest.CopiedImages(recording.client)) {
                copies.copy(CopyImageRequest.builder().name("one").build());
                copies.copy(CopyImageRequest.builder().name("two").build());
            }
        });
        assertThat(thrown).isSameAs(describe);
        assertThat(recording.deletions).containsExactly("deregister:ami-2", "deregister:ami-1",
                "snapshot:ami-1-snapshot-1", "snapshot:ami-1-snapshot-2");
    }

    @Test
    void failedCopyAllocationCleansOnlyEarlierCopy() {
        RecordingClient recording = new RecordingClient();
        RuntimeException copyFailure = new IllegalStateException("second copy");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try (Ec2RegisterImageTagTest.CopiedImages copies = new Ec2RegisterImageTagTest.CopiedImages(recording.client)) {
                copies.copy(CopyImageRequest.builder().name("one").build());
                recording.failures.put("copyImage", copyFailure);
                copies.copy(CopyImageRequest.builder().name("two").build());
            }
        });
        assertThat(thrown).isSameAs(copyFailure);
        assertThat(recording.deletions).containsExactly("deregister:ami-1", "snapshot:ami-1-snapshot-1",
                "snapshot:ami-1-snapshot-2");
    }

    @Test
    void unexpectedRegistrationSuccessIsTrackedBeforeAssertionFails() {
        RecordingClient recording = new RecordingClient();
        assertThrows(AssertionError.class, () -> {
            try (Ec2RegisterImageTagTest.ImageSource source = recording.source()) {
                assertThrows(RuntimeException.class, () -> source.register(RegisterImageRequest.builder().name("bad").build()));
            }
        });
        assertThat(recording.deletions).containsExactly("deregister:ami-1", "snapshot:snap-source", "volume:vol-source");
    }

    @Test
    void successfulCleanupUsesOnlyRecordedIdentifiers() {
        RecordingClient recording = new RecordingClient();
        try (Ec2RegisterImageTagTest.ImageSource source = recording.source()) {
            source.register(source.registration("one").build());
        }
        assertThat(recording.deletions).containsExactly("deregister:ami-1", "snapshot:snap-source", "volume:vol-source");
        assertThat(recording.filteredReads).isZero();
    }

    @Test
    void volumeWaitFailureCleansOnlyAllocatedVolume() {
        RecordingClient recording = new RecordingClient();
        recording.fail("describeVolumes");
        assertThrows(RuntimeException.class, recording::source);
        assertThat(recording.deletions).containsExactly("volume:vol-source");
    }

    @Test
    void unexpectedCopySuccessIsTrackedBeforeAssertionFails() {
        RecordingClient recording = new RecordingClient();
        assertThrows(AssertionError.class, () -> {
            try (Ec2RegisterImageTagTest.CopiedImages copies = new Ec2RegisterImageTagTest.CopiedImages(recording.client)) {
                assertThrows(RuntimeException.class, () -> copies.copy(CopyImageRequest.builder().name("denied").build()));
            }
        });
        assertThat(recording.deletions).containsExactly("deregister:ami-1", "snapshot:ami-1-snapshot-1", "snapshot:ami-1-snapshot-2");
    }

    @Test
    void sourceRecoversAllocationWhenRegistrationResponseIsLost() {
        RecordingClient recording = new RecordingClient();
        recording.imageNames.put("ami-unrelated", "unrelated");
        RuntimeException response = recording.fail("registerImageResponse");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try (Ec2RegisterImageTagTest.ImageSource source = recording.source()) {
                source.register(source.registration("owned").build());
            }
        });
        assertThat(thrown).isSameAs(response);
        assertThat(recording.images).isEqualTo(1);
        assertThat(recording.deletions).containsExactly("deregister:ami-1", "snapshot:snap-source", "volume:vol-source");
    }

    @Test
    void copiesRecoverAllocationWhenCopyResponseIsLost() {
        RecordingClient recording = new RecordingClient();
        RuntimeException response = recording.fail("copyImageResponse");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try (Ec2RegisterImageTagTest.CopiedImages copies = new Ec2RegisterImageTagTest.CopiedImages(recording.client)) {
                copies.copy(CopyImageRequest.builder().name("owned-copy").build());
            }
        });
        assertThat(thrown).isSameAs(response);
        assertThat(recording.images).isEqualTo(1);
        assertThat(recording.deletions).containsExactly("deregister:ami-1", "snapshot:ami-1-snapshot-1", "snapshot:ami-1-snapshot-2");
    }

    @Test
    void failedRegistrationRecoveryStillAttemptsSnapshotAndVolumeCleanup() {
        RecordingClient recording = new RecordingClient();
        RuntimeException response = recording.fail("registerImageResponse");
        RuntimeException lookup = recording.fail("describeFilteredImages");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try (Ec2RegisterImageTagTest.ImageSource source = recording.source()) {
                source.register(source.registration("owned").build());
            }
        });
        assertThat(thrown).isSameAs(response);
        assertThat(thrown.getSuppressed()).containsExactly(lookup);
        assertThat(recording.deletions).containsExactly("snapshot:snap-source", "volume:vol-source");
    }

    @Test
    void failedCopyRecoveryStillCleansTheKnownCopy() {
        RecordingClient recording = new RecordingClient();
        RuntimeException response = new IllegalStateException("lost second response");
        RuntimeException lookup = recording.fail("describeFilteredImages");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try (Ec2RegisterImageTagTest.CopiedImages copies = new Ec2RegisterImageTagTest.CopiedImages(recording.client)) {
                copies.copy(CopyImageRequest.builder().name("known").build());
                recording.failures.put("copyImageResponse", response);
                copies.copy(CopyImageRequest.builder().name("unknown").build());
            }
        });
        assertThat(thrown).isSameAs(response);
        assertThat(thrown.getSuppressed()).containsExactly(lookup);
        assertThat(recording.deletions).containsExactly("deregister:ami-1", "snapshot:ami-1-snapshot-1", "snapshot:ami-1-snapshot-2");
    }

    private static final class RecordingClient implements Ec2Client {
        private final Map<String, RuntimeException> failures = new LinkedHashMap<>();
        private final List<String> deletions = new ArrayList<>();
        private final Map<String, String> imageNames = new LinkedHashMap<>();
        private final Ec2Client client = this;
        private int images;
        private int filteredReads;

        RuntimeException fail(String operation) {
            RuntimeException failure = new IllegalStateException(operation);
            failures.put(operation, failure);
            return failure;
        }

        Ec2RegisterImageTagTest.ImageSource source() {
            return new Ec2RegisterImageTagTest.ImageSource(client, "owned");
        }

        private void call(String operation) {
            if (operation.startsWith("deregister:") || operation.startsWith("snapshot:") || operation.startsWith("volume:")) {
                deletions.add(operation);
            }
            if (failures.containsKey(operation)) {
                throw failures.get(operation);
            }
        }

        @Override
        public Ec2Waiter waiter() {
            return Ec2Waiter.builder().client(this).build();
        }

        @Override
        public String serviceName() {
            return "ec2";
        }

        @Override
        public void close() {
            // The recording client owns no transport or external resources.
        }

        @Override
        public DescribeAvailabilityZonesResponse describeAvailabilityZones(DescribeAvailabilityZonesRequest request) {
            call("describeAvailabilityZones");
            return DescribeAvailabilityZonesResponse.builder().availabilityZones(AvailabilityZone.builder().zoneName("us-east-1a").build()).build();
        }

        @Override
        public CreateVolumeResponse createVolume(CreateVolumeRequest request) {
            call("createVolume");
            return CreateVolumeResponse.builder().volumeId("vol-source").build();
        }

        @Override
        public DescribeVolumesResponse describeVolumes(DescribeVolumesRequest request) {
            call("describeVolumes");
            return DescribeVolumesResponse.builder().volumes(Volume.builder().volumeId("vol-source").state("available").build()).build();
        }

        @Override
        public CreateSnapshotResponse createSnapshot(CreateSnapshotRequest request) {
            call("createSnapshot");
            return CreateSnapshotResponse.builder().snapshotId("snap-source").build();
        }

        @Override
        public DescribeSnapshotsResponse describeSnapshots(DescribeSnapshotsRequest request) {
            call("describeSnapshots");
            return DescribeSnapshotsResponse.builder().snapshots(Snapshot.builder().snapshotId("snap-source").state("completed").build()).build();
        }

        @Override
        public RegisterImageResponse registerImage(RegisterImageRequest request) {
            call("registerImage");
            String imageId = "ami-" + ++images;
            imageNames.put(imageId, request.name());
            call("registerImageResponse");
            return RegisterImageResponse.builder().imageId(imageId).build();
        }

        @Override
        public CopyImageResponse copyImage(CopyImageRequest request) {
            call("copyImage");
            String imageId = "ami-" + ++images;
            imageNames.put(imageId, request.name());
            call("copyImageResponse");
            return CopyImageResponse.builder().imageId(imageId).build();
        }

        @Override
        public DeregisterImageResponse deregisterImage(DeregisterImageRequest request) {
            call("deregister:" + request.imageId());
            return DeregisterImageResponse.builder().build();
        }

        @Override
        public DeleteSnapshotResponse deleteSnapshot(DeleteSnapshotRequest request) {
            call("snapshot:" + request.snapshotId());
            return DeleteSnapshotResponse.builder().build();
        }

        @Override
        public DeleteVolumeResponse deleteVolume(DeleteVolumeRequest request) {
            call("volume:" + request.volumeId());
            return DeleteVolumeResponse.builder().build();
        }

        @Override
        public DescribeImagesResponse describeImages(DescribeImagesRequest request) {
            if (request.imageIds().isEmpty()) {
                filteredReads++;
                call("describeFilteredImages");
                assertThat(request.owners()).containsExactly("self");
                assertThat(request.filters()).hasSize(1);
                assertThat(request.filters().get(0).name()).isEqualTo("name");
                assertThat(request.filters().get(0).values()).hasSize(1);
                List<Image> allocated = new ArrayList<>();
                for (String imageId : imageNames.keySet()) {
                    allocated.add(image(imageId));
                }
                return DescribeImagesResponse.builder().images(allocated).build();
            }
            call("describe:" + request.imageIds().get(0));
            return DescribeImagesResponse.builder().images(image(request.imageIds().get(0))).build();
        }

        private Image image(String id) {
            return Image.builder().imageId(id).name(imageNames.get(id)).blockDeviceMappings(
                    BlockDeviceMapping.builder().ebs(EbsBlockDevice.builder().snapshotId(id + "-snapshot-1").build()).build(),
                    BlockDeviceMapping.builder().ebs(EbsBlockDevice.builder().snapshotId(id + "-snapshot-2").build()).build(),
                    BlockDeviceMapping.builder().virtualName("ephemeral0").build()).build();
        }
    }
}

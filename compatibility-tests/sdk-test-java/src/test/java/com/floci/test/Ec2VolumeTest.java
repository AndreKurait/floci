package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateVolumeResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ModifyVolumeResponse;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.model.VolumeType;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ec2VolumeTest {

    @Test
    void modifyVolumeMigratesGp2AndRetainsUpdatedGp3Performance() {
        String scope = TestFixtures.uniqueName("volume-modify");
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String id = ec2.createVolume(request -> request
                    .availabilityZone(TestFixtures.region().id() + "a")
                    .volumeType(VolumeType.GP2).size(500).tagSpecifications(tags(scope))).volumeId();
            try {
                ModifyVolumeResponse migrated = ec2.modifyVolume(request -> request
                        .volumeId(id).volumeType(VolumeType.GP3));
                assertThat(migrated.volumeModification().targetIops()).isEqualTo(3000);
                assertThat(migrated.volumeModification().targetThroughput()).isEqualTo(250);
                ec2.modifyVolume(request -> request.volumeId(id).size(600).iops(80000).throughput(2000));
                ec2.modifyVolume(request -> request.volumeId(id).size(700));
                Volume described = ec2.describeVolumes(request -> request.volumeIds(id)).volumes().get(0);
                assertThat(described.volumeType()).isEqualTo(VolumeType.GP3);
                assertThat(described.size()).isEqualTo(700);
                assertThat(described.iops()).isEqualTo(80000);
                assertThat(described.throughput()).isEqualTo(2000);
            } finally {
                ec2.deleteVolume(request -> request.volumeId(id));
            }
        }
    }

    @Test
    void invalidVolumeModificationsPreserveTheExistingVolume() {
        String scope = TestFixtures.uniqueName("volume-invalid-modify");
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String id = ec2.createVolume(request -> request
                    .availabilityZone(TestFixtures.region().id() + "a")
                    .volumeType(VolumeType.GP3).size(10).tagSpecifications(tags(scope))).volumeId();
            try {
                assertThatThrownBy(() -> ec2.modifyVolume(request -> request.volumeId(id).iops(1)))
                        .isInstanceOfSatisfying(Ec2Exception.class, error ->
                                assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue"));
                assertThatThrownBy(() -> ec2.modifyVolume(request -> request.volumeId(id).size(20)
                        .iops(3000).throughput(751)))
                        .isInstanceOfSatisfying(Ec2Exception.class, error ->
                                assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue"));
                assertThatThrownBy(() -> ec2.modifyVolume(request -> request.volumeId(id)
                        .volumeType(VolumeType.IO2).size(20).iops(20001)))
                        .isInstanceOfSatisfying(Ec2Exception.class, error ->
                                assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue"));
                Volume described = ec2.describeVolumes(request -> request.volumeIds(id)).volumes().get(0);
                assertThat(described.volumeType()).isEqualTo(VolumeType.GP3);
                assertThat(described.size()).isEqualTo(10);
                assertThat(described.iops()).isEqualTo(3000);
                assertThat(described.throughput()).isEqualTo(125);
                assertThat(described.tags()).contains(Tag.builder().key("VolumeScope").value(scope).build());
            } finally {
                ec2.deleteVolume(request -> request.volumeId(id));
            }
        }
    }

    @Test
    void volumeDefaultsAndInvalidPerformanceHaveCorrectReadbackAndNoExtraVolumes() {
        String scope = TestFixtures.uniqueName("volume-performance");
        List<String> ids = new ArrayList<>();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            try {
                for (VolumeType type : List.of(VolumeType.GP2, VolumeType.GP3)) {
                    CreateVolumeResponse created = ec2.createVolume(request -> request
                            .availabilityZone(TestFixtures.region().id() + "a").volumeType(type).size(80)
                            .tagSpecifications(tags(scope)));
                    ids.add(created.volumeId());
                    Volume read = ec2.describeVolumes(request -> request.volumeIds(created.volumeId()))
                            .volumes().get(0);
                    assertThat(created.iops()).isEqualTo(type == VolumeType.GP2 ? 240 : 3000);
                    assertThat(read.iops()).isEqualTo(created.iops());
                    if (type == VolumeType.GP3) {
                        assertThat(read.throughput()).isEqualTo(125);
                    } else {
                        assertThat(read.throughput()).isNull();
                    }
                }
                assertThatThrownBy(() -> {
                    ids.add(ec2.createVolume(request -> request
                            .availabilityZone(TestFixtures.region().id() + "a").volumeType(VolumeType.GP2)
                            .size(80).throughput(250).tagSpecifications(tags(scope))).volumeId());
                })
                        .isInstanceOfSatisfying(Ec2Exception.class, failure -> {
                            assertThat(failure.statusCode()).isEqualTo(400);
                            assertThat(failure.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterCombination");
                        });
                assertThatThrownBy(() -> {
                    ids.add(ec2.createVolume(request -> request
                            .availabilityZone(TestFixtures.region().id() + "a").volumeType(VolumeType.GP3)
                            .size(1).iops(3001).tagSpecifications(tags(scope))).volumeId());
                })
                        .isInstanceOfSatisfying(Ec2Exception.class, failure ->
                                assertThat(failure.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue"));
                assertThat(ec2.describeVolumes(request -> request.filters(filter(scope))).volumes())
                        .extracting(Volume::volumeId).containsExactlyInAnyOrderElementsOf(ids);
            } finally {
                for (String id : ids) {
                    ec2.deleteVolume(request -> request.volumeId(id));
                }
            }
        }
    }

    @Test
    void volumePaginationStaysAccountAndRegionScoped() {
        String scope = TestFixtures.uniqueName("volume-pages");
        Region home = TestFixtures.region();
        Region other = Region.regions().stream()
                .filter(region -> !region.equals(home) && !region.isGlobalRegion() && region.metadata() != null)
                .filter(region -> TestFixtures.partition().equals(region.metadata().partition().id()))
                .findFirst().orElseThrow();
        try (Ec2Client owner = TestFixtures.ec2Client("111111111111");
                Ec2Client foreign = TestFixtures.ec2Client("222222222222");
                Ec2Client otherRegion = client("111111111111", other)) {
            List<String> ids = new ArrayList<>();
            List<String> foreignIds = new ArrayList<>();
            List<String> regionalIds = new ArrayList<>();
            try {
                for (int index = 0; index < 6; index++) {
                    ids.add(owner.createVolume(request -> request.availabilityZone(home.id() + "a").size(1)
                            .tagSpecifications(tags(scope))).volumeId());
                }
                foreignIds.add(foreign.createVolume(request -> request.availabilityZone(home.id() + "a").size(1)
                        .tagSpecifications(tags(scope))).volumeId());
                regionalIds.add(otherRegion.createVolume(request -> request.availabilityZone(other.id() + "a").size(1)
                        .tagSpecifications(tags(scope))).volumeId());
                DescribeVolumesResponse first = owner.describeVolumes(request -> request.filters(filter(scope))
                        .maxResults(5));
                assertThat(first.volumes()).hasSize(5);
                assertThat(first.nextToken()).isNotBlank();
                DescribeVolumesResponse last = owner.describeVolumes(request -> request.filters(filter(scope))
                        .maxResults(5).nextToken(first.nextToken()));
                assertThat(last.volumes()).hasSize(1);
                assertThat(last.nextToken()).isNull();
                List<String> read = new ArrayList<>(first.volumes().stream().map(Volume::volumeId).toList());
                read.addAll(last.volumes().stream().map(Volume::volumeId).toList());
                assertThat(read).containsExactlyElementsOf(ids.stream().sorted().toList());
                assertThat(foreign.describeVolumes(request -> request.filters(filter(scope))).volumes())
                        .extracting(Volume::volumeId).containsExactlyElementsOf(foreignIds);
                assertThat(otherRegion.describeVolumes(request -> request.filters(filter(scope))).volumes())
                        .extracting(Volume::volumeId).containsExactlyElementsOf(regionalIds);
            } finally {
                for (String id : ids) {
                    owner.deleteVolume(request -> request.volumeId(id));
                }
                for (String id : foreignIds) {
                    foreign.deleteVolume(request -> request.volumeId(id));
                }
                for (String id : regionalIds) {
                    otherRegion.deleteVolume(request -> request.volumeId(id));
                }
            }
        }
    }

    private static TagSpecification tags(String scope) {
        return TagSpecification.builder().resourceType(ResourceType.VOLUME)
                .tags(Tag.builder().key("VolumeScope").value(scope).build()).build();
    }

    private static Filter filter(String scope) {
        return Filter.builder().name("tag:VolumeScope").values(scope).build();
    }

    private static Ec2Client client(String account, Region region) {
        return Ec2Client.builder().endpointOverride(TestFixtures.endpoint()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }
}

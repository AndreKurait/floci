package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AvailabilityZone;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.LaunchTemplatePlacementRequest;
import software.amazon.awssdk.services.ec2.model.Placement;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ec2RunInstancesPlacementTest {

    @ParameterizedTest
    @CsvSource(value = {
            "-,false,a,false,a", "b,false,-,false,b", "b,false,a,false,a",
            "-,false,b,true,b", "b,true,-,false,b", "a,false,b,true,b",
            "b,true,a,false,a", "b,true,a,true,a"
    }, nullValues = "-")
    @DisplayName("RunInstances uses explicit placement before the launch template's zone")
    void requestAndTemplateZonesSelectTheInstanceSubnet(String templateSuffix, boolean templateUsesId,
                                                        String requestedSuffix, boolean requestUsesId,
                                                        String expectedSuffix) throws Exception {
        String name = TestFixtures.uniqueName("sdk-placement");
        String region = TestFixtures.region().id();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String imageId = ec2.registerImage(request -> request.name(name)
                    .architecture("x86_64").rootDeviceName("/dev/sda1")).imageId();
            try (AutoCloseable image = () -> ec2.deregisterImage(request -> request.imageId(imageId))) {
                LaunchTemplatePlacementRequest.Builder templatePlacement = LaunchTemplatePlacementRequest.builder();
                if (templateSuffix != null) {
                    if (templateUsesId) {
                        templatePlacement.availabilityZoneId(zoneId(ec2, region + templateSuffix));
                    } else {
                        templatePlacement.availabilityZone(region + templateSuffix);
                    }
                }
                String templateId = templateSuffix == null ? null : ec2.createLaunchTemplate(
                        request -> request.launchTemplateName(name).launchTemplateData(data ->
                                data.imageId(imageId).instanceType("t3.micro")
                                        .placement(templatePlacement.build())))
                        .launchTemplate().launchTemplateId();
                try (AutoCloseable template = () -> {
                    if (templateId != null) {
                        ec2.deleteLaunchTemplate(request -> request.launchTemplateId(templateId));
                    }
                }) {
                    RunInstancesRequest.Builder request = RunInstancesRequest.builder()
                            .imageId(imageId).instanceType("t3.micro").minCount(1).maxCount(1);
                    if (templateId != null) {
                        request.launchTemplate(value -> value.launchTemplateId(templateId).version("1"));
                    }
                    if (requestedSuffix != null) {
                        request.placement(placement(ec2, region + requestedSuffix, requestUsesId));
                    }
                    Instance launched = ec2.runInstances(request.build()).instances().get(0);
                    try (AutoCloseable instance = () -> ec2.terminateInstances(
                            cleanup -> cleanup.instanceIds(launched.instanceId()))) {
                        assertThat(launched.placement().availabilityZone()).isEqualTo(region + expectedSuffix);
                        Instance described = ec2.describeInstances(
                                describe -> describe.instanceIds(launched.instanceId()))
                                .reservations().get(0).instances().get(0);
                        assertThat(described.placement().availabilityZone()).isEqualTo(region + expectedSuffix);
                        assertThat(ec2.describeSubnets(describe -> describe.subnetIds(described.subnetId()))
                                .subnets().get(0).availabilityZone()).isEqualTo(region + expectedSuffix);
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @DisplayName("RunInstances rejects a subnet outside the requested zone before launch or dry run")
    void contradictorySubnetAndZoneNeverLaunches(boolean dryRun, boolean useZoneId) throws Exception {
        String name = TestFixtures.uniqueName("sdk-placement-mismatch");
        String region = TestFixtures.region().id();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String imageId = ec2.registerImage(request -> request.name(name)
                    .architecture("x86_64").rootDeviceName("/dev/sda1")).imageId();
            try (AutoCloseable image = () -> ec2.deregisterImage(request -> request.imageId(imageId))) {
                String subnetId = ec2.describeSubnets(request -> request.filters(
                        filter -> filter.name("availability-zone").values(region + "a"),
                        filter -> filter.name("default-for-az").values("true")))
                        .subnets().get(0).subnetId();
                Placement placement = placement(ec2, region + "b", useZoneId);
                List<String> before = instanceIds(ec2, imageId);
                List<String> unexpected = new ArrayList<>();
                try {
                    assertThatThrownBy(() -> {
                        unexpected.addAll(ec2.runInstances(request -> request.imageId(imageId)
                                .instanceType("t3.micro").minCount(1).maxCount(1)
                                .subnetId(subnetId).placement(placement)
                                .dryRun(dryRun)).instances().stream().map(Instance::instanceId).toList());
                    }).isInstanceOfSatisfying(Ec2Exception.class, failure -> {
                        assertThat(failure.statusCode()).isEqualTo(400);
                        assertThat(failure.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterCombination");
                    });
                    assertThat(instanceIds(ec2, imageId)).containsExactlyInAnyOrderElementsOf(before);
                } finally {
                    if (!unexpected.isEmpty()) {
                        ec2.terminateInstances(request -> request.instanceIds(unexpected));
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"a,false", "b,false", "a,true", "b,true"})
    @DisplayName("RunInstances rejects both zone selectors even when they identify the same zone")
    void nameAndIdTogetherNeverLaunch(String idSuffix, boolean dryRun) throws Exception {
        String name = TestFixtures.uniqueName("sdk-placement-duplicate");
        String region = TestFixtures.region().id();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String imageId = ec2.registerImage(request -> request.name(name)
                    .architecture("x86_64").rootDeviceName("/dev/sda1")).imageId();
            try (AutoCloseable image = () -> ec2.deregisterImage(request -> request.imageId(imageId))) {
                String id = zoneId(ec2, region + idSuffix);
                List<String> unexpected = new ArrayList<>();
                try {
                    assertThatThrownBy(() -> unexpected.addAll(ec2.runInstances(request -> request.imageId(imageId)
                            .instanceType("t3.micro").minCount(1).maxCount(1).dryRun(dryRun)
                            .placement(placement -> placement.availabilityZone(region + "a").availabilityZoneId(id)))
                            .instances().stream().map(Instance::instanceId).toList()))
                            .isInstanceOfSatisfying(Ec2Exception.class, failure ->
                                    assertThat(failure.awsErrorDetails().errorCode())
                                            .isEqualTo("InvalidParameterCombination"));
                    assertThat(instanceIds(ec2, imageId)).isEmpty();
                } finally {
                    if (!unexpected.isEmpty()) {
                        ec2.terminateInstances(request -> request.instanceIds(unexpected));
                    }
                }
            }
        }
    }

    private static Placement placement(Ec2Client ec2, String name, boolean useId) {
        return useId ? Placement.builder().availabilityZoneId(zoneId(ec2, name)).build()
                : Placement.builder().availabilityZone(name).build();
    }

    private static String zoneId(Ec2Client ec2, String name) {
        return ec2.describeAvailabilityZones(request -> request.zoneNames(name)).availabilityZones().stream()
                .filter(zone -> name.equals(zone.zoneName())).map(AvailabilityZone::zoneId).findFirst().orElseThrow();
    }

    private static List<String> instanceIds(Ec2Client ec2, String imageId) {
        return ec2.describeInstances(request -> request.filters(
                        filter -> filter.name("image-id").values(imageId))).reservations().stream()
                .flatMap(reservation -> reservation.instances().stream()).map(Instance::instanceId).toList();
    }
}

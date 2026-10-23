package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.CapacityReservationSpecification;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CapacityReservation;
import software.amazon.awssdk.services.ec2.model.Instance;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auto Scaling group reservation preferences")
class AutoScalingGroupReservationTest {
    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    @DisplayName("Create and update override template preference and preserve failed launch state")
    void groupReservationOnlyOverridesTemplate(boolean available, boolean update) throws InterruptedException {
        String name = TestFixtures.uniqueName("asg-group-reservation");
        try (AutoScalingClient asg = TestFixtures.autoScalingClient(); Ec2Client ec2 = TestFixtures.ec2Client()) {
            CapacityReservation capacity = ec2.createCapacityReservation(request -> request
                    .instanceType("t3.small").instancePlatform("Linux/UNIX")
                    .availabilityZone(TestFixtures.availabilityZone()).instanceCount(1))
                    .capacityReservation();
            try {
                if (!available) {
                    ec2.cancelCapacityReservation(request -> request
                            .capacityReservationId(capacity.capacityReservationId()));
                }
                ec2.createLaunchTemplate(request -> request.launchTemplateName(name).launchTemplateData(data -> data
                        .imageId("ami-amazonlinux2023").instanceType("t3.small")
                        .capacityReservationSpecification(specification -> specification
                                .capacityReservationPreference("none"))));
                try {
                    CapacityReservationSpecification only = CapacityReservationSpecification.builder()
                            .capacityReservationPreference("capacity-reservations-only").build();
                    String subnetId = ec2.describeSubnets().subnets().stream()
                            .filter(subnet -> TestFixtures.availabilityZone().equals(subnet.availabilityZone()))
                            .findFirst().orElseThrow().subnetId();
                    asg.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                            .launchTemplate(template -> template.launchTemplateName(name).version("1"))
                            .minSize(0).maxSize(1).desiredCapacity(update ? 0 : 1)
                            .availabilityZones(TestFixtures.availabilityZone()).vpcZoneIdentifier(subnetId)
                            .capacityReservationSpecification(update ? null : only)
                            .tags(tag -> tag.key("reservation-probe").value(name).propagateAtLaunch(true)));
                    try {
                        if (update) {
                            asg.updateAutoScalingGroup(request -> request.autoScalingGroupName(name)
                                    .desiredCapacity(1).capacityReservationSpecification(only));
                        }
                        TestFixtures.await(() -> available ? !describe(asg, name).instances().isEmpty()
                                        : asg.describeScalingActivities(request -> request.autoScalingGroupName(name))
                                                .activities().stream().anyMatch(activity ->
                                                        "Failed".equals(activity.statusCodeAsString())),
                                name, "reservation launch outcome", Duration.ofSeconds(40).toNanos(), 250);
                        AutoScalingGroup group = describe(asg, name);
                        assertThat(group.capacityReservationSpecification().capacityReservationPreferenceAsString())
                                .isEqualTo("capacity-reservations-only");
                        assertThat(group.instances()).hasSize(available ? 1 : 0);
                        List<Instance> instances = ec2.describeInstances(request -> request.filters(filter -> filter
                                        .name("tag:reservation-probe").values(name)))
                                .reservations().stream().flatMap(reservation -> reservation.instances().stream())
                                .filter(instance -> !"terminated".equals(instance.state().nameAsString())).toList();
                        assertThat(instances).hasSize(available ? 1 : 0);
                        if (available) {
                            assertThat(instances.get(0).capacityReservationId()).isEqualTo(capacity.capacityReservationId());
                        } else {
                            asg.updateAutoScalingGroup(request -> request.autoScalingGroupName(name)
                                    .capacityReservationSpecification(specification -> specification
                                            .capacityReservationPreference("capacity-reservations-first")));
                            TestFixtures.await(() -> !describe(asg, name).instances().isEmpty(), name,
                                    "On-Demand fallback", Duration.ofSeconds(40).toNanos(), 250);
                            String id = describe(asg, name).instances().get(0).instanceId();
                            assertThat(ec2.describeInstances(request -> request.instanceIds(id)).reservations().get(0)
                                    .instances().get(0).capacityReservationId()).isNullOrEmpty();
                        }
                    } finally {
                        asg.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                    }
                } finally {
                    ec2.deleteLaunchTemplate(request -> request.launchTemplateName(name));
                }
            } finally {
                if (available) {
                    ec2.cancelCapacityReservation(request -> request
                            .capacityReservationId(capacity.capacityReservationId()));
                }
            }
        }
    }

    private static AutoScalingGroup describe(AutoScalingClient asg, String name) {
        return asg.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name)).autoScalingGroups().get(0);
    }
}

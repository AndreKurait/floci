package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.CapacityReservationSpecification;
import software.amazon.awssdk.services.autoscaling.model.CapacityReservationTarget;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(AutoScalingMultipleCapacityTargetsIntegrationTest.MultipleTargets.class)
class AutoScalingMultipleCapacityTargetsIntegrationTest {
    private static final String ACCOUNT = "753190420029";
    private static final String REGION = "us-east-1";
    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCOUNT, "test"));

    public static class MultipleTargets implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.autoscaling.max-capacity-reservation-ids", "3");
        }
    }

    @TestHTTPResource
    URI endpoint;

    @Inject
    AutoScalingService service;

    @Inject
    AutoScalingReconciler reconciler;

    @Test
    void configuredLimitAppliesToCreateAndUpdateWithoutRelaxingGroupOrMixedTargets() {
        String name = "multiple-targets-" + UUID.randomUUID();
        try (AutoScalingClient client = client(); Ec2Client ec2 = ec2()) {
            createTemplate(ec2, name);
            try {
                CapacityReservationSpecification two = specification("capacity-reservations-first",
                        List.of("cr-first", "cr-second"));
                client.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchTemplate(template -> template.launchTemplateName(name))
                        .availabilityZones(REGION + "a").minSize(0).maxSize(1).desiredCapacity(0)
                        .capacityReservationSpecification(two));
                assertEquals(two, describe(client, name).capacityReservationSpecification());
                CapacityReservationSpecification three = specification("capacity-reservations-first",
                        List.of("cr-third", "cr-second", "cr-first"));
                client.updateAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .capacityReservationSpecification(three));
                assertEquals(three, describe(client, name).capacityReservationSpecification());

                List<CapacityReservationTarget> invalid = List.of(
                        CapacityReservationTarget.builder()
                                .capacityReservationIds("cr-one", "cr-two", "cr-three", "cr-four").build(),
                        CapacityReservationTarget.builder().capacityReservationResourceGroupArns(
                                groupArn("one"), groupArn("two")).build(),
                        CapacityReservationTarget.builder().capacityReservationIds("cr-one")
                                .capacityReservationResourceGroupArns(groupArn("one")).build());
                for (CapacityReservationTarget target : invalid) {
                    CapacityReservationSpecification rejected = CapacityReservationSpecification.builder()
                            .capacityReservationPreference("capacity-reservations-first")
                            .capacityReservationTarget(target).build();
                    AutoScalingException create = assertThrows(AutoScalingException.class,
                            () -> client.createAutoScalingGroup(request -> request.autoScalingGroupName(name + "-invalid")
                                    .launchTemplate(template -> template.launchTemplateName(name))
                                    .availabilityZones(REGION + "a").minSize(0).maxSize(1).desiredCapacity(0)
                                    .capacityReservationSpecification(rejected)));
                    assertEquals(400, create.statusCode());
                    assertEquals("ValidationError", create.awsErrorDetails().errorCode());
                    assertTrue(client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name + "-invalid"))
                            .autoScalingGroups().isEmpty());
                    AutoScalingGroup before = describe(client, name);
                    AutoScalingException update = assertThrows(AutoScalingException.class,
                            () -> client.updateAutoScalingGroup(request -> request.autoScalingGroupName(name)
                                    .maxSize(2).capacityReservationSpecification(rejected)));
                    assertEquals(create.awsErrorDetails().errorMessage(), update.awsErrorDetails().errorMessage());
                    assertEquals(before.maxSize(), describe(client, name).maxSize());
                    assertEquals(three, describe(client, name).capacityReservationSpecification());
                }
            } finally {
                deleteGroup(client, name);
                deleteGroup(client, name + "-invalid");
                ec2.deleteLaunchTemplate(request -> request.launchTemplateName(name));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"capacity-reservations-first", "capacity-reservations-only"})
    void everyZoneUsesItsTargetBeforeOnDemandFallback(String preference) {
        String name = "multiple-capacity-" + UUID.randomUUID();
        List<String> reservations = new ArrayList<>();
        try (AutoScalingClient client = client(); Ec2Client ec2 = ec2()) {
            createTemplate(ec2, name);
            try {
                // Reversed order forces the first zone to pass an incompatible target before finding its own.
                for (String zone : List.of(REGION + "b", REGION + "a")) {
                    reservations.add(ec2.createCapacityReservation(request -> request.instanceType("t3.micro")
                                    .instancePlatform("Linux/UNIX").availabilityZone(zone).instanceCount(1)
                                    .instanceMatchCriteria("targeted"))
                            .capacityReservation().capacityReservationId());
                }
                client.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchTemplate(template -> template.launchTemplateName(name))
                        .availabilityZones(REGION + "a", REGION + "b").minSize(0).maxSize(3).desiredCapacity(2)
                        .capacityReservationSpecification(specification(preference, reservations)));
                reconcile(name);
                reconcile(name);
                AutoScalingGroup group = describe(client, name);
                assertEquals(2, group.instances().size());
                assertEquals(Set.of("InService"), group.instances().stream()
                        .map(instance -> instance.lifecycleStateAsString()).collect(Collectors.toSet()));
                List<Instance> instances = instances(ec2, group);
                assertEquals(Set.of(REGION + "a", REGION + "b"), instances.stream()
                        .map(instance -> instance.placement().availabilityZone()).collect(Collectors.toSet()));
                assertEquals(Set.copyOf(reservations), instances.stream()
                        .map(Instance::capacityReservationId).collect(Collectors.toSet()));

                client.setDesiredCapacity(request -> request.autoScalingGroupName(name).desiredCapacity(3));
                reconcile(name);
                reconcile(name);
                group = describe(client, name);
                if ("capacity-reservations-first".equals(preference)) {
                    assertEquals(3, group.instances().size());
                    assertEquals(2, instances(ec2, group).stream()
                            .filter(instance -> instance.capacityReservationId() != null).count());
                } else {
                    assertEquals(2, group.instances().size());
                    assertTrue(client.describeScalingActivities(request -> request.autoScalingGroupName(name))
                            .activities().stream().anyMatch(activity -> "Failed".equals(activity.statusCodeAsString())));
                    ec2.modifyCapacityReservation(request -> request.capacityReservationId(reservations.get(1))
                            .instanceCount(2));
                    reconcile(name);
                    reconcile(name);
                    List<Instance> recovered = instances(ec2, describe(client, name));
                    assertEquals(3, recovered.size());
                    recovered.forEach(instance -> assertNotNull(instance.capacityReservationId()));
                }
            } finally {
                deleteGroup(client, name);
                assertTrue(client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                        .autoScalingGroups().isEmpty());
                ec2.deleteLaunchTemplate(request -> request.launchTemplateName(name));
                for (String reservation : reservations) {
                    ec2.cancelCapacityReservation(request -> request.capacityReservationId(reservation));
                }
            }
        }
    }

    private void reconcile(String name) {
        RequestScopes.runAs(ACCOUNT, REGION, () ->
                reconciler.reconcile(service.describeAutoScalingGroups(REGION, List.of(name)).getFirst()));
    }

    @Test
    void missingTargetDoesNotLaunchFromAnotherTargetOrOnDemand() {
        String name = "missing-capacity-" + UUID.randomUUID();
        try (AutoScalingClient client = client(); Ec2Client ec2 = ec2()) {
            createTemplate(ec2, name);
            String reservation = null;
            try {
                reservation = ec2.createCapacityReservation(request -> request.instanceType("t3.micro")
                                .instancePlatform("Linux/UNIX").availabilityZone(REGION + "a").instanceCount(1)
                                .instanceMatchCriteria("targeted"))
                        .capacityReservation().capacityReservationId();
                List<String> targets = List.of("cr-missing-" + UUID.randomUUID(), reservation);
                client.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchTemplate(template -> template.launchTemplateName(name))
                        .availabilityZones(REGION + "a").minSize(0).maxSize(1).desiredCapacity(1)
                        .capacityReservationSpecification(specification("capacity-reservations-first", targets)));
                reconcile(name);
                assertTrue(describe(client, name).instances().isEmpty());
                assertTrue(client.describeScalingActivities(request -> request.autoScalingGroupName(name))
                        .activities().stream().anyMatch(activity -> "Failed".equals(activity.statusCodeAsString())
                                && activity.statusMessage().contains(targets.getFirst())));
                String existingReservation = reservation;
                assertEquals(1, ec2.describeCapacityReservations(request -> request.capacityReservationIds(existingReservation))
                        .capacityReservations().getFirst().availableInstanceCount());
            } finally {
                deleteGroup(client, name);
                ec2.deleteLaunchTemplate(request -> request.launchTemplateName(name));
                if (reservation != null) {
                    String existingReservation = reservation;
                    ec2.cancelCapacityReservation(request -> request.capacityReservationId(existingReservation));
                }
            }
        }
    }

    private AutoScalingClient client() {
        return AutoScalingClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(CREDENTIALS).build();
    }

    private Ec2Client ec2() {
        return Ec2Client.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(CREDENTIALS).build();
    }

    private static CapacityReservationSpecification specification(String preference, List<String> ids) {
        return CapacityReservationSpecification.builder().capacityReservationPreference(preference)
                .capacityReservationTarget(CapacityReservationTarget.builder().capacityReservationIds(ids).build())
                .build();
    }

    private static AutoScalingGroup describe(AutoScalingClient client, String name) {
        return client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst();
    }

    private static List<Instance> instances(Ec2Client ec2, AutoScalingGroup group) {
        return ec2.describeInstances(request -> request.instanceIds(
                        group.instances().stream().map(instance -> instance.instanceId()).toList()))
                .reservations().stream().flatMap(reservation -> reservation.instances().stream()).toList();
    }

    private static void createTemplate(Ec2Client ec2, String name) {
        ec2.createLaunchTemplate(request -> request.launchTemplateName(name)
                .launchTemplateData(data -> data.imageId("ami-amazonlinux2023").instanceType("t3.micro")));
    }

    private static void deleteGroup(AutoScalingClient client, String name) {
        if (!client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().isEmpty()) {
            client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
        }
    }

    private static String groupArn(String name) {
        return "arn:aws:resource-groups:" + REGION + ":" + ACCOUNT + ":group/" + name;
    }
}

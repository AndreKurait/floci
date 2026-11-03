package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.CapacityReservationSpecification;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
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
import software.amazon.awssdk.services.autoscaling.model.CapacityReservationTarget;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AutoScalingMultipleReservationsIntegrationTest.Profile.class)
class AutoScalingMultipleReservationsIntegrationTest {
    private static final String ACCOUNT = "753190420019";
    private static final String REGION = "us-east-1";

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.autoscaling.multiple-capacity-reservation-scopes",
                    ACCOUNT + "/" + REGION, "floci.default-account-id", ACCOUNT,
                    "floci.services.autoscaling.max-capacity-reservation-ids", "6");
        }
    }

    @Inject
    Ec2Service ec2;
    @Inject
    AutoScalingService service;
    @Inject
    AutoScalingReconciler reconciler;

    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkCreateAndUpdatePreserveTargetsAndRejectInvalidUpdates() {
        String name = "sdk-multiple-" + UUID.randomUUID();
        try (AutoScalingClient client = AutoScalingClient.builder().endpointOverride(endpoint)
                .region(Region.of(REGION)).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test"))).build()) {
            client.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                    .imageId("ami-test").instanceType("t3.small"));
            boolean created = false;
            try {
                client.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchConfigurationName(name).minSize(0).maxSize(1).desiredCapacity(0)
                        .availabilityZones(REGION + "a").capacityReservationSpecification(spec -> spec
                                .capacityReservationPreference("capacity-reservations-only")
                                .capacityReservationTarget(target -> target.capacityReservationIds("cr-one", "cr-two"))));
                created = true;
                client.updateAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .capacityReservationSpecification(spec -> spec
                                .capacityReservationPreference("capacity-reservations-only")
                                .capacityReservationTarget(target -> target.capacityReservationIds("cr-three", "cr-four"))));
                AutoScalingException failure = assertThrows(AutoScalingException.class, () ->
                        client.updateAutoScalingGroup(request -> request.autoScalingGroupName(name)
                                .desiredCapacity(1).capacityReservationSpecification(spec -> spec
                                        .capacityReservationPreference("capacity-reservations-only")
                                        .capacityReservationTarget(CapacityReservationTarget.builder()
                                                .capacityReservationIds(IntStream.range(0, 7)
                                                        .mapToObj(i -> "cr-" + i).toList()).build()))));
                assertEquals("ValidationError", failure.awsErrorDetails().errorCode());
                // The SDK and emulator both define AutoScalingGroup.
                software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup actual =
                        client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                                .autoScalingGroups().getFirst();
                assertEquals(0, actual.desiredCapacity());
                assertEquals(List.of("cr-three", "cr-four"),
                        actual.capacityReservationSpecification().capacityReservationTarget().capacityReservationIds());
            } finally {
                if (created) {
                    client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                }
                client.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
            }
        }
    }

    @Test
    void capabilityIsScopedAndDoesNotRelaxGroupOrMixedTargetLimits() {
        CapacityReservationSpecification specification = specification("capacity-reservations-only",
                List.of("cr-one", "cr-two"));
        RequestScopes.runAs(ACCOUNT, REGION, () ->
                assertDoesNotThrow(() -> service.validateCapacityReservationSpecification(specification, REGION)));
        RequestScopes.runAs("753190420020", REGION, () ->
                assertThrows(AwsException.class,
                        () -> service.validateCapacityReservationSpecification(specification, REGION)));
        RequestScopes.runAs(ACCOUNT, "us-west-2", () ->
                assertThrows(AwsException.class,
                        () -> service.validateCapacityReservationSpecification(specification, "us-west-2")));
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            specification.setCapacityReservationIds(IntStream.range(0, 6).mapToObj(i -> "cr-" + i).toList());
            assertDoesNotThrow(() -> service.validateCapacityReservationSpecification(specification, REGION));
            specification.setCapacityReservationIds(IntStream.range(0, 7).mapToObj(i -> "cr-" + i).toList());
            assertThrows(AwsException.class,
                    () -> service.validateCapacityReservationSpecification(specification, REGION));
            specification.setCapacityReservationIds(List.of("cr-one"));
            specification.setCapacityReservationResourceGroupArns(List.of("group-one"));
            assertThrows(AwsException.class,
                    () -> service.validateCapacityReservationSpecification(specification, REGION));
            specification.setCapacityReservationIds(List.of());
            specification.setCapacityReservationResourceGroupArns(List.of("group-one", "group-two"));
            assertThrows(AwsException.class,
                    () -> service.validateCapacityReservationSpecification(specification, REGION));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"capacity-reservations-only", "capacity-reservations-first"})
    void launchesAcrossZonesExhaustsTargetsAndReleasesCapacity(String preference) {
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            List<CapacityReservation> reservations = new ArrayList<>();
            String name = "multi-reservation-" + UUID.randomUUID();
            LaunchTemplate template = null;
            boolean created = false;
            try {
                // A wrong platform and a wrong type precede the usable targets.
                reservations.add(reserve("t3.small", "Windows", "a", 2));
                reservations.add(reserve("t3.micro", "Linux/UNIX", "a", 2));
                reservations.add(reserve("t3.small", "Linux/UNIX", "b", 1));
                reservations.add(reserve("t3.small", "Linux/UNIX", "a", 1));
                List<String> ids = reservations.stream().map(CapacityReservation::getCapacityReservationId).toList();
                LaunchTemplateData data = new LaunchTemplateData();
                data.setImageId("ami-test");
                data.setInstanceType("t3.small");
                template = ec2.createLaunchTemplate(REGION, name, data, List.of());
                List<String> subnets = ec2.describeSubnets(REGION, List.of(), Map.of()).stream()
                        .filter(subnet -> List.of(REGION + "a", REGION + "b").contains(subnet.getAvailabilityZone()))
                        .map(subnet -> subnet.getSubnetId()).toList();
                AutoScalingGroup group = service.createAutoScalingGroup(REGION, name, null,
                        template.getLaunchTemplateId(), null, "$Latest", null, 0, 4, 2, 300,
                        List.of(REGION + "a", REGION + "b"), subnets, List.of(), List.of(),
                        "EC2", 0, List.of(), Map.of(), Map.of(),
                        new AsgOptionalFields(null, null, null, null, null, null, null,
                                specification(preference, ids)));
                created = true;
                reconciler.reconcile(group);
                AutoScalingGroup current = service.describeAutoScalingGroups(REGION, List.of(name)).getFirst();
                assertEquals(2, current.getInstances().size());
                assertEquals(ids, current.getCapacityReservationSpecification().getCapacityReservationIds());
                List<Instance> instances = ec2.describeInstances(REGION, current.getInstances().stream()
                                .map(instance -> instance.getInstanceId()).toList(), Map.of()).stream()
                        .flatMap(reservation -> reservation.getInstances().stream()).toList();
                for (Instance instance : instances) {
                    String expectedId = instance.getPlacement().getAvailabilityZone().endsWith("a")
                            ? ids.get(3) : ids.get(2);
                    assertEquals(expectedId, instance.getCapacityReservationTarget());
                }
                assertEquals(0, available(ids.get(2)));
                assertEquals(0, available(ids.get(3)));
                assertEquals(2, available(ids.get(0)));
                assertEquals(2, available(ids.get(1)));
                service.setDesiredCapacity(REGION, name, 4);
                reconciler.reconcile(service.describeAutoScalingGroups(REGION, List.of(name)).getFirst());
                assertEquals("capacity-reservations-first".equals(preference) ? 4 : 2,
                        service.describeAutoScalingGroups(REGION, List.of(name)).getFirst().getInstances().size());
                service.deleteAutoScalingGroup(REGION, name, true);
                created = false;
                assertEquals(1, available(ids.get(2)));
                assertEquals(1, available(ids.get(3)));
            } finally {
                if (created) {
                    service.deleteAutoScalingGroup(REGION, name, true);
                }
                if (template != null) {
                    ec2.deleteLaunchTemplate(REGION, template.getLaunchTemplateId(), null);
                }
                for (CapacityReservation reservation : reservations) {
                    ec2.cancelCapacityReservation(REGION, reservation.getCapacityReservationId());
                }
            }
        });
    }

    private CapacityReservation reserve(String type, String platform, String zone, int count) {
        return ec2.createCapacityReservation(REGION, type, platform, REGION + zone,
                null, count, null, null, null, null, null, "targeted", null, null);
    }

    private int available(String id) {
        return ec2.describeCapacityReservations(REGION, List.of(id), Map.of())
                .getFirst().getAvailableInstanceCount();
    }

    private static CapacityReservationSpecification specification(String preference, List<String> ids) {
        CapacityReservationSpecification result = new CapacityReservationSpecification();
        result.setCapacityReservationPreference(preference);
        result.setCapacityReservationIds(ids);
        return result;
    }
}

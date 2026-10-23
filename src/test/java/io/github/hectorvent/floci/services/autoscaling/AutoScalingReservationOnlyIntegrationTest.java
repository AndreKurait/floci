package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AutoScalingReservationOnlyIntegrationTest {
    private static final String ACCOUNT = "753190420009";
    private static final String REGION = "us-east-1";

    @Inject
    Ec2Service ec2;

    @Inject
    AutoScalingService service;

    @Inject
    AutoScalingReconciler reconciler;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void templateReservationOnlyDoesNotFallBack(boolean available) {
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            CapacityReservation capacity = ec2.createCapacityReservation(REGION, "t3.small", "Linux/UNIX",
                    REGION + "a", null, 1, null, null, null, null, null, null, null, null);
            String groupName = "reservation-only-" + UUID.randomUUID();
            LaunchTemplate template = null;
            boolean groupCreated = false;
            try {
                if (!available) {
                    ec2.cancelCapacityReservation(REGION, capacity.getCapacityReservationId());
                }
                LaunchTemplateData data = new LaunchTemplateData();
                data.setImageId("ami-test");
                data.setInstanceType("t3.small");
                LaunchTemplateData.CapacityReservationSpecification specification =
                        new LaunchTemplateData.CapacityReservationSpecification();
                specification.setCapacityReservationPreference("capacity-reservations-only");
                data.setCapacityReservationSpecification(specification);
                template = ec2.createLaunchTemplate(REGION, groupName, data, List.of());
                String subnet = ec2.describeSubnets(REGION, List.of(), Map.of()).stream()
                        .filter(value -> (REGION + "a").equals(value.getAvailabilityZone()))
                        .findFirst().orElseThrow().getSubnetId();
                AutoScalingGroup group = service.createAutoScalingGroup(REGION, groupName, null,
                        template.getLaunchTemplateId(), null, "1", null, 0, 1, 1, 300,
                        List.of(REGION + "a"), List.of(subnet), List.of(), List.of(), "EC2", 0,
                        List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
                groupCreated = true;
                reconciler.reconcile(group);
                AutoScalingGroup current = service.describeAutoScalingGroups(REGION, List.of(groupName)).getFirst();
                assertEquals(available ? 1 : 0, current.getInstances().size());
                assertEquals(available ? 1 : 0, ec2.describeInstances(REGION, List.of(), Map.of()).stream()
                        .flatMap(value -> value.getInstances().stream())
                        .filter(value -> !"terminated".equals(value.getState().getName())).count());
                if (available) {
                    assertEquals(List.of(current.getInstances().getFirst().getInstanceId()),
                            ec2.describeCapacityReservations(REGION, List.of(capacity.getCapacityReservationId()),
                                    Map.of()).getFirst().getInstanceIds());
                }
            } finally {
                if (groupCreated) {
                    service.deleteAutoScalingGroup(REGION, groupName, true);
                }
                if (template != null) {
                    ec2.deleteLaunchTemplate(REGION, template.getLaunchTemplateId(), null);
                }
                if (available) {
                    ec2.cancelCapacityReservation(REGION, capacity.getCapacityReservationId());
                }
            }
        });
    }
}

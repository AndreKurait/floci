package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingTerminationActivityIntegrationTest {
    @Inject
    AutoScalingReconciler reconciler;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sdkReceivesAndTracksTheTerminationActivity(boolean decrement) throws Exception {
        verifyTermination(false, decrement);
    }

    @Test
    void sdkCanTerminateAStandbyInstanceWithoutReplacingIt() throws Exception {
        verifyTermination(true, false);
    }

    private void verifyTermination(boolean standby, boolean decrement) throws Exception {
        String name = "termination-" + UUID.randomUUID();
        try (AutoScalingClient client = AwsTestClients.create(
                AutoScalingClient.builder(), "000000000000", Region.US_EAST_1)) {
            client.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                    .imageId("ami-12345678").instanceType("t3.micro"));
            try (AutoCloseable configuration = () -> client.deleteLaunchConfiguration(
                    request -> request.launchConfigurationName(name))) {
                client.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchConfigurationName(name).minSize(0).maxSize(1).desiredCapacity(1)
                        .availabilityZones("us-east-1a"));
                try (AutoCloseable group = () -> client.deleteAutoScalingGroup(
                        request -> request.autoScalingGroupName(name).forceDelete(true))) {
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        reconciler.reconcileAll();
                        AutoScalingGroup current = group(client, name);
                        assertEquals(1, current.instances().size());
                        assertEquals("InService", current.instances().getFirst().lifecycleStateAsString());
                    });
                    String instanceId = group(client, name).instances().getFirst().instanceId();
                    if (standby) {
                        client.enterStandby(request -> request.autoScalingGroupName(name)
                                .instanceIds(instanceId).shouldDecrementDesiredCapacity(true));
                        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                            reconciler.reconcileAll();
                            assertEquals("Standby", group(client, name).instances().getFirst().lifecycleStateAsString());
                        });
                    }

                    Activity accepted = client.terminateInstanceInAutoScalingGroup(request -> request
                            .instanceId(instanceId).shouldDecrementDesiredCapacity(decrement)).activity();

                    assertNotNull(accepted, "the SDK must receive the termination Activity");
                    assertFalse(accepted.activityId().isBlank());
                    assertEquals(name, accepted.autoScalingGroupName());
                    assertEquals("Terminating EC2 instance: " + instanceId, accepted.description());
                    assertFalse(accepted.cause().isBlank());
                    assertNotNull(accepted.startTime());
                    assertEquals("InProgress", accepted.statusCodeAsString());
                    assertEquals(0, accepted.progress());
                    assertNull(accepted.endTime());
                    int desired = standby || decrement ? 0 : 1;
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        reconciler.reconcileAll();
                        Activity completed = client.describeScalingActivities(
                                        request -> request.autoScalingGroupName(name))
                                .activities().stream()
                                .filter(activity -> accepted.activityId().equals(activity.activityId()))
                                .findFirst().orElseThrow();
                        assertEquals("Successful", completed.statusCodeAsString());
                        assertEquals(100, completed.progress());
                        assertNotNull(completed.endTime());
                        assertEquals(accepted.startTime(), completed.startTime());
                        AutoScalingGroup current = group(client, name);
                        assertEquals(desired, current.desiredCapacity());
                        assertEquals(desired, current.instances().size());
                        assertTrue(current.instances().stream().noneMatch(member -> instanceId.equals(member.instanceId())));
                    });
                }
            }
            assertTrue(client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                    .autoScalingGroups().isEmpty());
            assertTrue(client.describeLaunchConfigurations(request -> request.launchConfigurationNames(name))
                    .launchConfigurations().isEmpty());
        }
    }

    private static AutoScalingGroup group(AutoScalingClient client, String name) {
        return client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst();
    }
}

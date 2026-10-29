package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.EnterStandbyResponse;
import software.amazon.awssdk.services.autoscaling.model.LifecycleState;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class AutoScalingStandbyIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Inject
    AutoScalingReconciler reconciler;

    @Test
    void standbyPreservesMembershipAndRestoresCapacityThroughSdk() {
        String name = "standby-" + UUID.randomUUID();
        try (AutoScalingClient client = AutoScalingClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("414141414141", "test"))).build()) {
            client.createLaunchConfiguration(r -> r.launchConfigurationName(name)
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
            client.createAutoScalingGroup(r -> r.autoScalingGroupName(name).launchConfigurationName(name)
                    .minSize(1).maxSize(2).desiredCapacity(1).availabilityZones("us-east-1a"));
            try {
                reconciler.reconcileAll();
                reconciler.reconcileAll();
                String id = group(client, name).instances().getFirst().instanceId();
                AutoScalingException minimum = assertThrows(AutoScalingException.class,
                        () -> client.enterStandby(r -> r.autoScalingGroupName(name)
                                .instanceIds(id).shouldDecrementDesiredCapacity(true)));
                assertEquals("ValidationError", minimum.awsErrorDetails().errorCode());
                assertEquals(LifecycleState.IN_SERVICE, group(client, name).instances().getFirst().lifecycleState());
                client.updateAutoScalingGroup(r -> r.autoScalingGroupName(name).minSize(0));
                EnterStandbyResponse response = client.enterStandby(r -> r.autoScalingGroupName(name)
                        .instanceIds(id).shouldDecrementDesiredCapacity(true));
                assertEquals(1, response.activities().size());
                assertEquals("Successful", response.activities().getFirst().statusCodeAsString());
                reconciler.reconcileAll();
                assertEquals(0, group(client, name).desiredCapacity());
                assertEquals(1, group(client, name).instances().size());
                assertEquals(LifecycleState.STANDBY, group(client, name).instances().getFirst().lifecycleState());
                assertEquals("Standby", client.describeAutoScalingInstances(r -> r.instanceIds(id))
                        .autoScalingInstances().getFirst().lifecycleState());
                assertThrows(AutoScalingException.class, () -> client.enterStandby(r -> r.autoScalingGroupName(name)
                        .instanceIds(id).shouldDecrementDesiredCapacity(true)));
                assertEquals(1, client.exitStandby(r -> r.autoScalingGroupName(name).instanceIds(id)).activities().size());
                reconciler.reconcileAll();
                assertEquals(1, group(client, name).desiredCapacity());
                assertEquals(id, group(client, name).instances().getFirst().instanceId());
                assertEquals(LifecycleState.IN_SERVICE, group(client, name).instances().getFirst().lifecycleState());
                // Keeping desired capacity launches a replacement without terminating the standby member.
                client.enterStandby(r -> r.autoScalingGroupName(name).instanceIds(id).shouldDecrementDesiredCapacity(false));
                reconciler.reconcileAll();
                reconciler.reconcileAll();
                assertEquals(1, group(client, name).desiredCapacity());
                assertEquals(2, group(client, name).instances().size());
                client.updateAutoScalingGroup(r -> r.autoScalingGroupName(name).maxSize(1));
                assertThrows(AutoScalingException.class, () -> client.exitStandby(r -> r.autoScalingGroupName(name).instanceIds(id)));
                assertEquals(1, group(client, name).desiredCapacity());
                client.updateAutoScalingGroup(r -> r.autoScalingGroupName(name).maxSize(2));
                client.exitStandby(r -> r.autoScalingGroupName(name).instanceIds(id));
                assertEquals(2, group(client, name).desiredCapacity());
            } finally {
                client.deleteAutoScalingGroup(r -> r.autoScalingGroupName(name).forceDelete(true));
                client.deleteLaunchConfiguration(r -> r.launchConfigurationName(name));
            }
        }
    }

    private static AutoScalingGroup group(AutoScalingClient client, String name) {
        return client.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name)).autoScalingGroups().getFirst();
    }
}

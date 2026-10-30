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
import software.amazon.awssdk.services.autoscaling.model.CreateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.CreateLaunchConfigurationRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteLaunchConfigurationRequest;
import software.amazon.awssdk.services.autoscaling.model.EnterStandbyResponse;
import software.amazon.awssdk.services.autoscaling.model.ExitStandbyResponse;
import software.amazon.awssdk.services.autoscaling.model.LifecycleState;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            withStandbyGroup(client, name, () -> {
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
                assertEquals("InProgress", response.activities().getFirst().statusCodeAsString());
                reconciler.reconcileAll();
                assertEquals(0, group(client, name).desiredCapacity());
                assertEquals(1, group(client, name).instances().size());
                assertEquals(LifecycleState.STANDBY, group(client, name).instances().getFirst().lifecycleState());
                assertEquals("Standby", client.describeAutoScalingInstances(r -> r.instanceIds(id))
                        .autoScalingInstances().getFirst().lifecycleState());
                assertThrows(AutoScalingException.class, () -> client.enterStandby(r -> r.autoScalingGroupName(name)
                        .instanceIds(id).shouldDecrementDesiredCapacity(true)));
                ExitStandbyResponse exiting = client.exitStandby(r -> r.autoScalingGroupName(name).instanceIds(id));
                assertEquals(1, exiting.activities().size());
                assertEquals("PreInService", exiting.activities().getFirst().statusCodeAsString());
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
            });
        }
    }

    @Test
    void failedBodyCleansActualGroupAndLaunchConfiguration() {
        String name = "standby-cleanup-" + UUID.randomUUID();
        AssertionError primary = new AssertionError("Injected after successful group creation");
        try (AutoScalingClient client = AutoScalingClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("414141414141", "test"))).build()) {
            AssertionError actual = assertThrows(AssertionError.class, () -> withStandbyGroup(client, name, () -> {
                assertEquals(name, group(client, name).autoScalingGroupName());
                assertEquals(1, client.describeLaunchConfigurations(r -> r.launchConfigurationNames(name))
                        .launchConfigurations().size());
                throw primary;
            }));
            assertSame(primary, actual);
            assertTrue(client.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name)).autoScalingGroups().isEmpty());
            assertTrue(client.describeLaunchConfigurations(r -> r.launchConfigurationNames(name)).launchConfigurations().isEmpty());
        }
    }

    static void withStandbyGroup(AutoScalingClient client, String name, Runnable body) {
        try (Cleanup launch = () -> deleteOwned(() -> client.deleteLaunchConfiguration(
                DeleteLaunchConfigurationRequest.builder().launchConfigurationName(name).build()),
                "Launch configuration '" + name + "' not found.")) {
            client.createLaunchConfiguration(CreateLaunchConfigurationRequest.builder().launchConfigurationName(name)
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro").build());
            try (Cleanup group = () -> deleteOwned(() -> client.deleteAutoScalingGroup(
                    DeleteAutoScalingGroupRequest.builder().autoScalingGroupName(name).forceDelete(true).build()),
                    "Auto Scaling group '" + name + "' not found.")) {
                client.createAutoScalingGroup(CreateAutoScalingGroupRequest.builder().autoScalingGroupName(name)
                        .launchConfigurationName(name).minSize(1).maxSize(2).desiredCapacity(1)
                        .availabilityZones("us-east-1a").build());
                body.run();
            }
        }
    }

    private static void deleteOwned(Runnable delete, String missingMessage) {
        try {
            delete.run();
        } catch (AutoScalingException expected) {
            // A failed create may leave no resource; tolerate only this exact owned name being absent.
            if (expected.awsErrorDetails() == null || !"ValidationError".equals(expected.awsErrorDetails().errorCode())
                    || !missingMessage.equals(expected.awsErrorDetails().errorMessage())) {
                throw expected;
            }
        }
    }

    private interface Cleanup extends AutoCloseable {
        @Override
        void close();
    }

    private static AutoScalingGroup group(AutoScalingClient client, String name) {
        return client.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name)).autoScalingGroups().getFirst();
    }
}

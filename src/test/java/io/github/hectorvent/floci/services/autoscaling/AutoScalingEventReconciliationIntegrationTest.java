package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingEventReconciliationIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkMutationsConvergeWithoutManualReconcileAndKeepOwnersIsolated() throws Exception {
        String name = "event-asg-" + UUID.randomUUID().toString().substring(0, 8);
        try (AutoScalingClient first = client("111111111111", "us-east-1");
             AutoScalingClient second = client("222222222222", "us-gov-west-1")) {
            try (OwnedGroup firstGroup = new OwnedGroup(first, name);
                 OwnedGroup secondGroup = new OwnedGroup(second, name)) {
                firstGroup.create("us-east-1");
                secondGroup.create("us-gov-west-1");
                first.setDesiredCapacity(request -> request.autoScalingGroupName(name).desiredCapacity(1));
                second.setDesiredCapacity(request -> request.autoScalingGroupName(name).desiredCapacity(1));
                await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
                    assertReady(first, name, "111111111111", "us-east-1");
                    assertReady(second, name, "222222222222", "us-gov-west-1");
                });
                firstGroup.deleteGroup();
                assertTrue(first.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                        .autoScalingGroups().isEmpty());
                assertReady(second, name, "222222222222", "us-gov-west-1");
            }
            assertRemoved(first, name);
            assertRemoved(second, name);
        }
    }

    private static void assertRemoved(AutoScalingClient client, String name) {
        assertTrue(client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().isEmpty());
        assertTrue(client.describeLaunchConfigurations(request -> request.launchConfigurationNames(name))
                .launchConfigurations().isEmpty());
    }

    private AutoScalingClient client(String account, String region) {
        return AutoScalingClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    static final class OwnedGroup implements AutoCloseable {
        private final AutoScalingClient client;
        private final String name;
        private boolean configurationCreated;
        private boolean groupCreated;

        OwnedGroup(AutoScalingClient client, String name) {
            this.client = client;
            this.name = name;
        }

        void create(String region) {
            client.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
            configurationCreated = true;
            client.createAutoScalingGroup(request -> request.autoScalingGroupName(name).launchConfigurationName(name)
                    .minSize(0).maxSize(1).desiredCapacity(0).availabilityZones(region + "a"));
            groupCreated = true;
        }

        void deleteGroup() {
            if (groupCreated) {
                client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                groupCreated = false;
            }
        }

        @Override
        public void close() throws Exception {
            try (AutoCloseable configuration = this::deleteConfiguration) {
                deleteGroup();
            }
        }

        private void deleteConfiguration() {
            if (configurationCreated) {
                client.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
                configurationCreated = false;
            }
        }
    }

    private static void assertReady(AutoScalingClient client, String name, String account, String region) {
        AutoScalingGroup group = client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst();
        assertEquals(1, group.instances().size());
        assertEquals("InService", group.instances().getFirst().lifecycleStateAsString());
        assertTrue(group.autoScalingGroupARN().contains(":" + region + ":" + account + ":"));
    }
}

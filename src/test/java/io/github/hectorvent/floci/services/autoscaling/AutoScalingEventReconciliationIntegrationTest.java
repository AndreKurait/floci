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
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingEventReconciliationIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkMutationsConvergeWithoutManualReconcileAndKeepOwnersIsolated() {
        String name = "event-asg-" + UUID.randomUUID().toString().substring(0, 8);
        try (AutoScalingClient first = client("111111111111", "us-east-1");
             AutoScalingClient second = client("222222222222", "us-gov-west-1")) {
            create(first, name, "us-east-1");
            create(second, name, "us-gov-west-1");
            try {
                first.setDesiredCapacity(request -> request.autoScalingGroupName(name).desiredCapacity(1));
                second.setDesiredCapacity(request -> request.autoScalingGroupName(name).desiredCapacity(1));
                await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
                    assertReady(first, name, "111111111111", "us-east-1");
                    assertReady(second, name, "222222222222", "us-gov-west-1");
                });
                first.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                assertTrue(first.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                        .autoScalingGroups().isEmpty());
                assertReady(second, name, "222222222222", "us-gov-west-1");
            } finally {
                for (AutoScalingClient client : List.of(first, second)) {
                    if (!client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                            .autoScalingGroups().isEmpty()) {
                        client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                    }
                    client.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
                }
            }
        }
    }

    private AutoScalingClient client(String account, String region) {
        return AutoScalingClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private static void create(AutoScalingClient client, String name, String region) {
        client.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
        client.createAutoScalingGroup(request -> request.autoScalingGroupName(name).launchConfigurationName(name)
                .minSize(0).maxSize(1).desiredCapacity(0).availabilityZones(region + "a"));
    }

    private static void assertReady(AutoScalingClient client, String name, String account, String region) {
        AutoScalingGroup group = client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst();
        assertEquals(1, group.instances().size());
        assertEquals("InService", group.instances().getFirst().lifecycleStateAsString());
        assertTrue(group.autoScalingGroupARN().contains(":" + region + ":" + account + ":"));
    }
}

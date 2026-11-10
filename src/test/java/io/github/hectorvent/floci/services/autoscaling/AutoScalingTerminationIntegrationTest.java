package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingTerminationIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void terminationReturnsTheActivityCompletedByReconciliation(boolean decrement) {
        String name = "termination-" + UUID.randomUUID();
        try (AutoScalingClient client = AutoScalingClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("414141414141", "test"))).build()) {
            AutoScalingStandbyIntegrationTest.withStandbyGroup(client, name, () -> {
                client.updateAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .minSize(0).desiredCapacity(2));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertEquals(2, activeCount(group(client, name))));
                String instance = group(client, name).instances().getFirst().instanceId();
                Activity response = client.terminateInstanceInAutoScalingGroup(request -> request
                        .instanceId(instance).shouldDecrementDesiredCapacity(decrement)).activity();
                assertNotNull(response, "TerminateInstanceInAutoScalingGroup must return its scaling activity");
                assertFalse(response.activityId().isBlank());
                assertEquals(name, response.autoScalingGroupName());
                assertEquals("InProgress", response.statusCodeAsString());
                assertEquals(0, response.progress());
                assertNotNull(response.startTime());
                assertNull(response.endTime());
                assertTrue(response.description().contains(instance));
                assertTrue(response.cause().contains("user request"));

                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    AutoScalingGroup current = group(client, name);
                    assertEquals(decrement ? 1 : 2, current.desiredCapacity());
                    assertEquals(decrement ? 1 : 2, activeCount(current));
                    assertFalse(current.instances().stream().anyMatch(item -> instance.equals(item.instanceId())));
                    List<Activity> matching = client.describeScalingActivities(request -> request
                            .autoScalingGroupName(name).activityIds(response.activityId())).activities();
                    assertEquals(1, matching.size(), "The service must filter activities by the requested ID");
                    Activity completed = matching.getFirst();
                    assertEquals(response.activityId(), completed.activityId());
                    assertEquals("Successful", completed.statusCodeAsString());
                    assertEquals(100, completed.progress());
                    assertNotNull(completed.endTime());
                    assertEquals(response.startTime(), completed.startTime());
                    assertEquals(response.description(), completed.description());
                });
                List<String> requested = client.describeScalingActivities(request -> request.autoScalingGroupName(name))
                        .activities().stream().map(Activity::activityId).limit(2).toList();
                assertEquals(2, requested.size());
                List<String> returned = client.describeScalingActivities(request -> request
                        .autoScalingGroupName(name).activityIds(requested.get(0), requested.get(1), requested.get(0)))
                        .activities().stream().map(Activity::activityId).toList();
                assertEquals(2, returned.size());
                assertEquals(Set.copyOf(requested), Set.copyOf(returned));
                assertTrue(client.describeScalingActivities(request -> request.autoScalingGroupName(name)
                        .activityIds(UUID.randomUUID().toString())).activities().isEmpty());
            });
        }
    }

    @Test
    void sdkCanTerminateAStandbyInstanceWithoutReplacingIt() {
        String name = "termination-" + UUID.randomUUID();
        try (AutoScalingClient client = AutoScalingClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("414141414141", "test"))).build()) {
            AutoScalingStandbyIntegrationTest.withStandbyGroup(client, name, () -> {
                client.updateAutoScalingGroup(request -> request.autoScalingGroupName(name).minSize(0));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertEquals(1, activeCount(group(client, name))));
                String instance = group(client, name).instances().getFirst().instanceId();
                client.enterStandby(request -> request.autoScalingGroupName(name)
                        .instanceIds(instance).shouldDecrementDesiredCapacity(true));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("Standby",
                        group(client, name).instances().getFirst().lifecycleStateAsString()));

                Activity response = client.terminateInstanceInAutoScalingGroup(request -> request
                        .instanceId(instance).shouldDecrementDesiredCapacity(false)).activity();
                assertNotNull(response, "TerminateInstanceInAutoScalingGroup must return its scaling activity");
                assertFalse(response.activityId().isBlank());
                assertEquals(name, response.autoScalingGroupName());
                assertEquals("InProgress", response.statusCodeAsString());
                assertTrue(response.description().contains(instance));

                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    Activity completed = client.describeScalingActivities(request -> request
                            .autoScalingGroupName(name).activityIds(response.activityId())).activities().getFirst();
                    assertEquals("Successful", completed.statusCodeAsString());
                    assertEquals(100, completed.progress());
                    assertNotNull(completed.endTime());
                    AutoScalingGroup current = group(client, name);
                    assertEquals(0, current.desiredCapacity());
                    assertTrue(current.instances().isEmpty());
                });
            });
        }
    }

    private static AutoScalingGroup group(AutoScalingClient client, String name) {
        return client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst();
    }

    private static long activeCount(AutoScalingGroup group) {
        return group.instances().stream().filter(instance -> "InService".equals(instance.lifecycleStateAsString())).count();
    }
}

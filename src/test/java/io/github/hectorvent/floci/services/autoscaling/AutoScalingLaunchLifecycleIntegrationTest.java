package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class AutoScalingLaunchLifecycleIntegrationTest {
    private static final String ACCOUNT = "646464646464";
    private static final String REGION = "us-east-1";

    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkCompletionRequiresEveryLaunchHookAndHeartbeatValidatesTheAction() {
        String name = "launch-hooks-" + UUID.randomUUID();
        try (AutoScalingClient client = client(ACCOUNT, REGION)) {
            withGroup(client, name, REGION, () -> {
                hook(client, name, "prepare");
                hook(client, name, "verify");
                client.setDesiredCapacity(request -> request.autoScalingGroupName(name).desiredCapacity(1));
                String id = awaitInstance(client, name, "Pending:Wait");

                client.recordLifecycleActionHeartbeat(request -> request.autoScalingGroupName(name)
                        .lifecycleHookName("prepare").instanceId(id));
                assertValidation(() -> client.recordLifecycleActionHeartbeat(request ->
                        request.autoScalingGroupName(name).lifecycleHookName("absent").instanceId(id)));
                complete(client, name, "prepare", id, "CONTINUE");
                assertEquals("Pending:Wait", group(client, name).instances().getFirst().lifecycleStateAsString());
                assertValidation(() -> client.recordLifecycleActionHeartbeat(request ->
                        request.autoScalingGroupName(name).lifecycleHookName("prepare").instanceId(id)));

                complete(client, name, "verify", id, "CONTINUE");
                assertEquals(id, awaitInstance(client, name, "InService"));
                assertValidation(() -> complete(client, name, "verify", id, "ABANDON"));
            });
        }
    }

    @Test
    void abandonmentAndHookDeletionReplaceWaitingInstancesBeforeAdmission() {
        String name = "launch-abandon-" + UUID.randomUUID();
        try (AutoScalingClient client = client(ACCOUNT, REGION)) {
            withGroup(client, name, REGION, () -> {
                hook(client, name, "prepare");
                client.setDesiredCapacity(request -> request.autoScalingGroupName(name).desiredCapacity(1));
                String abandoned = awaitInstance(client, name, "Pending:Wait");
                complete(client, name, "prepare", abandoned, "ABANDON");
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    AutoScalingGroup replacement = group(client, name);
                    assertEquals(1, replacement.instances().size());
                    assertNotEquals(abandoned, replacement.instances().getFirst().instanceId());
                    assertEquals("Pending:Wait", replacement.instances().getFirst().lifecycleStateAsString());
                });
                String deleted = group(client, name).instances().getFirst().instanceId();

                client.deleteLifecycleHook(request -> request.autoScalingGroupName(name).lifecycleHookName("prepare"));

                String admitted = awaitInstance(client, name, "InService");
                assertNotEquals(abandoned, admitted);
                assertNotEquals(deleted, admitted);
                assertEquals(1, group(client, name).desiredCapacity());
                assertEquals(0, client.describeLifecycleHooks(request -> request.autoScalingGroupName(name))
                        .lifecycleHooks().size());
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"656565656565,us-east-1", "646464646464,us-west-2"})
    void matchingGroupNamesInOtherAccountsOrRegionsCannotCompleteTheAction(String account, String region) {
        String name = "launch-scope-" + UUID.randomUUID();
        try (AutoScalingClient owner = client(ACCOUNT, REGION);
             AutoScalingClient other = client(account, region)) {
            withGroup(owner, name, REGION, () -> withGroup(other, name, region, () -> {
                hook(owner, name, "prepare");
                hook(other, name, "prepare");
                owner.setDesiredCapacity(request -> request.autoScalingGroupName(name).desiredCapacity(1));
                String id = awaitInstance(owner, name, "Pending:Wait");

                assertValidation(() -> complete(other, name, "prepare", id, "CONTINUE"));
                assertValidation(() -> other.recordLifecycleActionHeartbeat(request ->
                        request.autoScalingGroupName(name).lifecycleHookName("prepare").instanceId(id)));
                assertEquals(id, awaitInstance(owner, name, "Pending:Wait"));
                assertEquals(0, group(other, name).instances().size());

                complete(owner, name, "prepare", id, "CONTINUE");
                assertEquals(id, awaitInstance(owner, name, "InService"));
            }));
        }
    }

    private AutoScalingClient client(String account, String region) {
        return AutoScalingClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private static void hook(AutoScalingClient client, String name, String hook) {
        client.putLifecycleHook(request -> request.autoScalingGroupName(name).lifecycleHookName(hook)
                .lifecycleTransition("autoscaling:EC2_INSTANCE_LAUNCHING").heartbeatTimeout(300)
                .defaultResult("ABANDON"));
    }

    private static void complete(AutoScalingClient client, String name, String hook, String id, String result) {
        client.completeLifecycleAction(request -> request.autoScalingGroupName(name).lifecycleHookName(hook)
                .instanceId(id).lifecycleActionResult(result));
    }

    private static AutoScalingGroup group(AutoScalingClient client, String name) {
        return client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst();
    }

    private static String awaitInstance(AutoScalingClient client, String name, String state) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            AutoScalingGroup group = group(client, name);
            assertEquals(1, group.instances().size());
            assertEquals(state, group.instances().getFirst().lifecycleStateAsString());
        });
        return group(client, name).instances().getFirst().instanceId();
    }

    private static void assertValidation(Runnable request) {
        AutoScalingException error = assertThrows(AutoScalingException.class, request::run);
        assertEquals(400, error.statusCode());
        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
        assertFalse(error.requestId().isBlank());
    }

    private static void withGroup(AutoScalingClient client, String name, String region, Runnable body) {
        try (Cleanup launch = () -> AutoScalingStandbyIntegrationTest.deleteOwned(
                () -> client.deleteLaunchConfiguration(request -> request.launchConfigurationName(name)),
                "Launch configuration '" + name + "' not found.")) {
            client.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
            try (Cleanup group = () -> AutoScalingStandbyIntegrationTest.deleteOwned(
                    () -> client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true)),
                    "Auto Scaling group '" + name + "' not found.")) {
                client.createAutoScalingGroup(request -> request.autoScalingGroupName(name).launchConfigurationName(name)
                        .minSize(0).maxSize(2).desiredCapacity(0).availabilityZones(region + "a"));
                body.run();
            }
        }
    }

    private interface Cleanup extends AutoCloseable {
        @Override
        void close();
    }
}

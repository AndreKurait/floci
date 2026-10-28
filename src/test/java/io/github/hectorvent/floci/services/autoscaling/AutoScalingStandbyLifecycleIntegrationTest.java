package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingInstanceDetails;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingStandbyLifecycleIntegrationTest {
    private static final String ACCOUNT = "575757575757";
    private static final String REGION = "us-east-1";

    @TestHTTPResource
    URI endpoint;

    private AutoScalingClient client;
    private String name;
    private String targetGroup;
    private List<String> instanceIds;
    private boolean configurationCreated;
    private boolean groupCreated;

    @BeforeEach
    void createGroup() {
        client = AutoScalingClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .build();
        name = "standby-" + UUID.randomUUID().toString().substring(0, 12);
        targetGroup = elb("CreateTargetGroup", "2015-12-01").formParam("Name", name)
                .formParam("Protocol", "HTTP").formParam("Port", "80")
                .formParam("VpcId", Ec2Service.defaultVpcId(REGION))
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        elb("CreateLoadBalancer", "2012-06-01").formParam("LoadBalancerName", name)
                .formParam("Listeners.member.1.Protocol", "HTTP")
                .formParam("Listeners.member.1.LoadBalancerPort", "80")
                .formParam("Listeners.member.1.InstancePort", "80")
                .formParam("AvailabilityZones.member.1", REGION + "a")
                .post("/").then().statusCode(200);
        client.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
        configurationCreated = true;
        client.createAutoScalingGroup(request -> request.autoScalingGroupName(name).launchConfigurationName(name)
                .minSize(0).maxSize(4).desiredCapacity(2).availabilityZones(REGION + "a")
                .targetGroupARNs(targetGroup).loadBalancerNames(name));
        groupCreated = true;
        awaitActiveInstances(2);
        instanceIds = group().instances().stream().map(instance -> instance.instanceId()).toList();
    }

    @AfterEach
    void cleanUp() {
        try {
            if (groupCreated) {
                client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
            }
            if (configurationCreated) {
                client.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
            }
            if (name != null) {
                elb("DeleteLoadBalancer", "2012-06-01").formParam("LoadBalancerName", name).post("/");
            }
            if (targetGroup != null) {
                elb("DeleteTargetGroup", "2015-12-01").formParam("TargetGroupArn", targetGroup).post("/");
            }
        } finally {
            client.close();
        }
    }

    @Test
    void instanceListingIncludesGroupNameAndEndpointSpecificHealth() {
        String id = instanceIds.getFirst();
        assertEquals(name, describeInstance(id).autoScalingGroupName());
        assertEquals("HEALTHY", describeInstance(id).healthStatus());
        client.suspendProcesses(request -> request.autoScalingGroupName(name)
                .scalingProcesses("HealthCheck", "ReplaceUnhealthy"));
        client.setInstanceHealth(request -> request.instanceId(id).healthStatus("Unhealthy"));
        assertEquals("UNHEALTHY", describeInstance(id).healthStatus());
        assertEquals("Unhealthy", group().instances().stream().filter(instance -> id.equals(instance.instanceId()))
                .findFirst().orElseThrow().healthStatus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void standbyChangesLifecycleTrafficAndDesiredCapacity(boolean decrement) {
        String id = instanceIds.getFirst();
        assertTrafficMembers(instanceIds);
        Activity entering = client.enterStandby(request -> request.autoScalingGroupName(name).instanceIds(id)
                .shouldDecrementDesiredCapacity(decrement)).activities().getFirst();
        assertEquals("InProgress", entering.statusCodeAsString());
        assertEquals(name, entering.autoScalingGroupName());
        assertEquals(50, entering.progress());
        awaitLifecycle(id, "Standby");
        assertEquals(name, describeInstance(id).autoScalingGroupName());
        assertEquals("HEALTHY", describeInstance(id).healthStatus());
        int desired = decrement ? 1 : 2;
        awaitActiveInstances(desired);
        assertEquals(desired, group().desiredCapacity());
        assertEquals(desired + 1, group().instances().size());
        assertTrafficMembers(activeInstanceIds());
        assertCompleted(entering.activityId());

        Activity exiting = client.exitStandby(request -> request.autoScalingGroupName(name).instanceIds(id))
                .activities().getFirst();
        assertEquals("PreInService", exiting.statusCodeAsString());
        assertEquals(30, exiting.progress());
        awaitLifecycle(id, "InService");
        assertEquals(desired + 1, group().desiredCapacity());
        assertTrafficMembers(activeInstanceIds());
        assertCompleted(exiting.activityId());
    }

    @Test
    void validationFailureDoesNotChangeLifecycleOrCapacity() {
        String id = instanceIds.getFirst();
        assertValidation(() -> client.enterStandby(request -> request.autoScalingGroupName(name)
                .instanceIds("i-00000000000000000").shouldDecrementDesiredCapacity(true)));
        assertValidation(() -> client.exitStandby(request -> request.autoScalingGroupName(name).instanceIds(id)));
        client.updateAutoScalingGroup(request -> request.autoScalingGroupName(name).minSize(2));
        assertValidation(() -> client.enterStandby(request -> request.autoScalingGroupName(name)
                .instanceIds(id).shouldDecrementDesiredCapacity(true)));
        assertEquals(2, group().desiredCapacity());
        assertEquals("InService", describeInstance(id).lifecycleState());
        assertTrafficMembers(instanceIds);
    }

    @Test
    void launchSuspensionRejectsStandbyExitUntilResumed() {
        String id = instanceIds.getFirst();
        client.enterStandby(request -> request.autoScalingGroupName(name).instanceIds(id)
                .shouldDecrementDesiredCapacity(true));
        awaitLifecycle(id, "Standby");
        client.suspendProcesses(request -> request.autoScalingGroupName(name).scalingProcesses("Launch"));

        assertValidation(() -> client.exitStandby(request -> request.autoScalingGroupName(name).instanceIds(id)));
        assertEquals("Standby", describeInstance(id).lifecycleState());
        assertEquals(1, group().desiredCapacity());
        assertTrafficMembers(activeInstanceIds());

        client.resumeProcesses(request -> request.autoScalingGroupName(name).scalingProcesses("Launch"));
        client.exitStandby(request -> request.autoScalingGroupName(name).instanceIds(id));
        awaitLifecycle(id, "InService");
        assertEquals(2, group().desiredCapacity());
        assertTrafficMembers(instanceIds);
    }

    @Test
    void stoppedStandbyInstanceIsPreservedUntilExitThenTerminatedAndReplaced() {
        String id = instanceIds.getFirst();
        client.enterStandby(request -> request.autoScalingGroupName(name).instanceIds(id)
                .shouldDecrementDesiredCapacity(true));
        awaitLifecycle(id, "Standby");
        try (Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .build()) {
            ec2.stopInstances(request -> request.instanceIds(id));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertEquals("stopped", ec2.describeInstances(request -> request.instanceIds(id))
                            .reservations().getFirst().instances().getFirst().state().nameAsString()));
            assertEquals("Standby", describeInstance(id).lifecycleState());
            assertEquals("HEALTHY", describeInstance(id).healthStatus());
            Activity exiting = client.exitStandby(request -> request.autoScalingGroupName(name).instanceIds(id))
                    .activities().getFirst();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertEquals(2, activeInstanceIds().size());
                assertFalse(group().instances().stream().anyMatch(instance -> id.equals(instance.instanceId())));
                assertEquals("terminated", ec2.describeInstances(request -> request.instanceIds(id))
                        .reservations().getFirst().instances().getFirst().state().nameAsString());
            });
            assertTrafficMembers(activeInstanceIds());
            Activity failed = client.describeScalingActivities(request -> request.autoScalingGroupName(name))
                    .activities().stream().filter(activity -> exiting.activityId().equals(activity.activityId()))
                    .findFirst().orElseThrow();
            assertEquals("Failed", failed.statusCodeAsString());
            assertNotNull(failed.endTime());
        }
    }

    private static void assertValidation(Runnable request) {
        AutoScalingException error = assertThrows(AutoScalingException.class, request::run);
        assertEquals(400, error.statusCode());
        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
        assertFalse(error.requestId().isBlank());
    }

    private void awaitLifecycle(String id, String state) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(state, describeInstance(id).lifecycleState()));
    }

    private void awaitActiveInstances(int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(count, activeInstanceIds().size()));
    }

    private List<String> activeInstanceIds() {
        return group().instances().stream().filter(instance -> "InService".equals(instance.lifecycleStateAsString()))
                .map(instance -> instance.instanceId()).toList();
    }

    private void assertCompleted(String activityId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Activity activity = client.describeScalingActivities(request -> request.autoScalingGroupName(name))
                    .activities().stream().filter(item -> activityId.equals(item.activityId()))
                    .findFirst().orElseThrow();
            assertEquals("Successful", activity.statusCodeAsString());
            assertEquals(100, activity.progress());
            assertNotNull(activity.endTime());
        });
    }

    private AutoScalingGroup group() {
        return client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst();
    }

    private AutoScalingInstanceDetails describeInstance(String id) {
        return client.describeAutoScalingInstances(request -> request.instanceIds(id))
                .autoScalingInstances().getFirst();
    }

    private void assertTrafficMembers(List<String> expected) {
        XmlPath targets = elb("DescribeTargetHealth", "2015-12-01").formParam("TargetGroupArn", targetGroup)
                .post("/").then().statusCode(200).extract().xmlPath();
        List<String> registered = targets.getList(
                "DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member.Target.Id");
        assertEquals(expected.size(), registered.size());
        assertTrue(registered.containsAll(expected));
        List<String> classicMembers = elb("DescribeLoadBalancers", "2012-06-01")
                .formParam("LoadBalancerNames.member.1", name).post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.Instances.member.InstanceId");
        assertEquals(expected.size(), classicMembers.size());
        assertTrue(classicMembers.containsAll(expected));
    }

    private static RequestSpecification elb(String action, String version) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261028/" + REGION + "/elasticloadbalancing/aws4_request")
                .formParam("Action", action).formParam("Version", version);
    }
}

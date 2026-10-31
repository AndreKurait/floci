package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingStandbyIntegrationTest {
    private static final String ACCOUNT = "575757575757";
    private static final String REGION = "us-east-1";
    private static final String GROUP_PATH =
            "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member";
    private static final String INSTANCE_PATH =
            "DescribeAutoScalingInstancesResponse.DescribeAutoScalingInstancesResult.AutoScalingInstances.member";
    private static final Duration RECONCILIATION_TIMEOUT = Duration.ofSeconds(60);
    private String name;
    private String targetGroup;
    private List<String> instanceIds;
    private boolean configurationCreated;
    private boolean groupCreated;

    @BeforeEach
    void createGroup() {
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
        asg("CreateLaunchConfiguration").formParam("LaunchConfigurationName", name)
                .formParam("ImageId", "ami-amazonlinux2023").formParam("InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
        configurationCreated = true;
        asg("CreateAutoScalingGroup").formParam("AutoScalingGroupName", name)
                .formParam("LaunchConfigurationName", name).formParam("MinSize", 0).formParam("MaxSize", 4)
                .formParam("DesiredCapacity", 2).formParam("AvailabilityZones.member.1", REGION + "a")
                .formParam("TargetGroupARNs.member.1", targetGroup).formParam("LoadBalancerNames.member.1", name)
                .post("/").then().statusCode(200);
        groupCreated = true;
        awaitActiveInstances(2);
        instanceIds = group().getList(GROUP_PATH + ".Instances.member.InstanceId");
    }

    @AfterEach
    void cleanUp() {
        if (groupCreated) {
            asg("DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name).formParam("ForceDelete", true)
                    .post("/").then().statusCode(200);
        }
        if (configurationCreated) {
            asg("DeleteLaunchConfiguration").formParam("LaunchConfigurationName", name)
                    .post("/").then().statusCode(200);
        }
        if (name != null) {
            elb("DeleteLoadBalancer", "2012-06-01").formParam("LoadBalancerName", name).post("/");
        }
        if (targetGroup != null) {
            elb("DeleteTargetGroup", "2015-12-01").formParam("TargetGroupArn", targetGroup).post("/");
        }
    }

    @Test
    void instanceListingIncludesGroupNameAndEndpointSpecificHealth() {
        String id = instanceIds.getFirst();
        assertEquals(name, describeInstance(id).getString(INSTANCE_PATH + ".AutoScalingGroupName"));
        assertEquals("HEALTHY", describeInstance(id).getString(INSTANCE_PATH + ".HealthStatus"));
        asg("SuspendProcesses").formParam("AutoScalingGroupName", name)
                .formParam("ScalingProcesses.member.1", "HealthCheck")
                .formParam("ScalingProcesses.member.2", "ReplaceUnhealthy").post("/").then().statusCode(200);
        asg("SetInstanceHealth").formParam("InstanceId", id).formParam("HealthStatus", "Unhealthy")
                .post("/").then().statusCode(200);
        assertEquals("UNHEALTHY", describeInstance(id).getString(INSTANCE_PATH + ".HealthStatus"));
        assertEquals("Unhealthy", group().getString(
                GROUP_PATH + ".Instances.member.find { it.InstanceId == '" + id + "' }.HealthStatus"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void standbyChangesLifecycleTrafficAndDesiredCapacity(boolean decrement) {
        String id = instanceIds.getFirst();
        assertTrafficMembers(instanceIds);
        XmlPath entering = transition("EnterStandby", id).formParam("ShouldDecrementDesiredCapacity", decrement)
                .post("/").then().statusCode(200).extract().xmlPath();
        String enteringPath = activityPath("EnterStandby");
        assertEquals("InProgress", entering.getString(enteringPath + ".StatusCode"));
        assertEquals(name, entering.getString(enteringPath + ".AutoScalingGroupName"));
        assertEquals(50, entering.getInt(enteringPath + ".Progress"));
        awaitLifecycle(id, "Standby");
        assertEquals(name, describeInstance(id).getString(INSTANCE_PATH + ".AutoScalingGroupName"));
        assertEquals("HEALTHY", describeInstance(id).getString(INSTANCE_PATH + ".HealthStatus"));
        int desired = decrement ? 1 : 2;
        awaitActiveInstances(desired);
        assertEquals(desired, desiredCapacity());
        assertEquals(desired + 1, group().getList(GROUP_PATH + ".Instances.member.InstanceId").size());
        assertTrafficMembers(activeInstanceIds());
        assertCompleted(entering.getString(enteringPath + ".ActivityId"));

        XmlPath exiting = transition("ExitStandby", id).post("/").then().statusCode(200).extract().xmlPath();
        String exitingPath = activityPath("ExitStandby");
        assertEquals("PreInService", exiting.getString(exitingPath + ".StatusCode"));
        assertEquals(30, exiting.getInt(exitingPath + ".Progress"));
        awaitLifecycle(id, "InService");
        assertEquals(desired + 1, desiredCapacity());
        assertTrafficMembers(activeInstanceIds());
        assertCompleted(exiting.getString(exitingPath + ".ActivityId"));
    }

    @Test
    void validationFailureDoesNotChangeLifecycleOrCapacity() {
        String id = instanceIds.getFirst();
        assertValidation(transition("EnterStandby", "i-00000000000000000")
                .formParam("ShouldDecrementDesiredCapacity", true));
        assertValidation(transition("ExitStandby", id));
        asg("UpdateAutoScalingGroup").formParam("AutoScalingGroupName", name).formParam("MinSize", 2)
                .post("/").then().statusCode(200);
        assertValidation(transition("EnterStandby", id).formParam("ShouldDecrementDesiredCapacity", true));
        assertEquals(2, desiredCapacity());
        assertEquals("InService", describeInstance(id).getString(INSTANCE_PATH + ".LifecycleState"));
        assertTrafficMembers(instanceIds);
    }

    @Test
    void launchSuspensionRejectsStandbyExitUntilResumed() {
        String id = instanceIds.getFirst();
        transition("EnterStandby", id).formParam("ShouldDecrementDesiredCapacity", true)
                .post("/").then().statusCode(200);
        awaitLifecycle(id, "Standby");
        asg("SuspendProcesses").formParam("AutoScalingGroupName", name).formParam("ScalingProcesses.member.1", "Launch")
                .post("/").then().statusCode(200);

        assertValidation(transition("ExitStandby", id));
        assertEquals("Standby", describeInstance(id).getString(INSTANCE_PATH + ".LifecycleState"));
        assertEquals(1, desiredCapacity());
        assertTrafficMembers(activeInstanceIds());

        asg("ResumeProcesses").formParam("AutoScalingGroupName", name).formParam("ScalingProcesses.member.1", "Launch")
                .post("/").then().statusCode(200);
        transition("ExitStandby", id).post("/").then().statusCode(200);
        awaitLifecycle(id, "InService");
        assertEquals(2, desiredCapacity());
        assertTrafficMembers(instanceIds);
    }

    @Test
    void stoppedStandbyInstanceIsPreservedUntilExitThenTerminatedAndReplaced() {
        String id = instanceIds.getFirst();
        transition("EnterStandby", id).formParam("ShouldDecrementDesiredCapacity", true)
                .post("/").then().statusCode(200);
        awaitLifecycle(id, "Standby");
        request("StopInstances", "2016-11-15", "ec2").formParam("InstanceId.1", id)
                .post("/").then().statusCode(200);
        await().atMost(RECONCILIATION_TIMEOUT).untilAsserted(() -> assertEquals("stopped", ec2State(id)));
        assertEquals("Standby", describeInstance(id).getString(INSTANCE_PATH + ".LifecycleState"));
        assertEquals("HEALTHY", describeInstance(id).getString(INSTANCE_PATH + ".HealthStatus"));
        XmlPath exiting = transition("ExitStandby", id).post("/").then().statusCode(200).extract().xmlPath();
        String activityId = exiting.getString(activityPath("ExitStandby") + ".ActivityId");
        await().atMost(RECONCILIATION_TIMEOUT).untilAsserted(() -> {
            assertEquals(2, activeInstanceIds().size());
            assertFalse(group().getList(GROUP_PATH + ".Instances.member.InstanceId").contains(id));
            assertEquals("terminated", ec2State(id));
        });
        assertTrafficMembers(activeInstanceIds());
        XmlPath activities = activities();
        String failedPath = activityPath("DescribeScalingActivities")
                + ".find { it.ActivityId == '" + activityId + "' }";
        assertEquals("Failed", activities.getString(failedPath + ".StatusCode"));
        assertFalse(activities.getString(failedPath + ".EndTime").isBlank());
    }

    private static void assertValidation(RequestSpecification request) {
        XmlPath error = request.post("/").then().statusCode(400).extract().xmlPath();
        assertEquals("ValidationError", error.getString("ErrorResponse.Error.Code"));
    }

    private void awaitLifecycle(String id, String state) {
        await().atMost(RECONCILIATION_TIMEOUT).untilAsserted(() ->
                assertEquals(state, describeInstance(id).getString(INSTANCE_PATH + ".LifecycleState")));
    }

    private void awaitActiveInstances(int count) {
        await().atMost(RECONCILIATION_TIMEOUT).untilAsserted(() -> assertEquals(count, activeInstanceIds().size()));
    }

    private List<String> activeInstanceIds() {
        return group().getList(GROUP_PATH + ".Instances.member"
                + ".findAll { it.LifecycleState == 'InService' }.InstanceId");
    }

    private void assertCompleted(String activityId) {
        await().atMost(RECONCILIATION_TIMEOUT).untilAsserted(() -> {
            XmlPath activities = activities();
            String path = activityPath("DescribeScalingActivities") + ".find { it.ActivityId == '" + activityId + "' }";
            assertEquals("Successful", activities.getString(path + ".StatusCode"));
            assertEquals(100, activities.getInt(path + ".Progress"));
            assertFalse(activities.getString(path + ".EndTime").isBlank());
        });
    }

    private XmlPath group() {
        return asg("DescribeAutoScalingGroups").formParam("AutoScalingGroupNames.member.1", name)
                .post("/").then().statusCode(200).extract().xmlPath();
    }

    private int desiredCapacity() {
        return group().getInt(GROUP_PATH + ".DesiredCapacity");
    }

    private static XmlPath describeInstance(String id) {
        return asg("DescribeAutoScalingInstances").formParam("InstanceIds.member.1", id)
                .post("/").then().statusCode(200).extract().xmlPath();
    }

    private XmlPath activities() {
        return asg("DescribeScalingActivities").formParam("AutoScalingGroupName", name)
                .post("/").then().statusCode(200).extract().xmlPath();
    }

    private static String activityPath(String action) {
        return action + "Response." + action + "Result.Activities.member";
    }

    private RequestSpecification transition(String action, String id) {
        return asg(action).formParam("AutoScalingGroupName", name).formParam("InstanceIds.member.1", id);
    }

    private static String ec2State(String id) {
        return request("DescribeInstances", "2016-11-15", "ec2").formParam("InstanceId.1", id)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name");
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
        return request(action, version, "elasticloadbalancing");
    }

    private static RequestSpecification asg(String action) {
        return request(action, "2011-01-01", "autoscaling");
    }

    private static RequestSpecification request(String action, String version, String service) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261029/" + REGION + "/" + service + "/aws4_request")
                .formParam("Action", action).formParam("Version", version);
    }
}

package io.floci.aws.services.autoscaling;

import io.floci.aws.testing.AlbFixtures;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
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
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingStandbyIntegrationTest {

    private static final String ACCOUNT = "616161616161";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261028/us-east-1/autoscaling/aws4_request";
    private static final String EC2_AUTH = AUTH.replace("/autoscaling/", "/ec2/");
    private static final String ELB_AUTH = AUTH.replace("/autoscaling/", "/elasticloadbalancing/");
    private static final String GROUP_PATH = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
            + ".AutoScalingGroups.member";
    private static final String ENTERED = "EnterStandbyResponse.EnterStandbyResult.Activities.member";
    private static final String EXITED = "ExitStandbyResponse.ExitStandbyResult.Activities.member";
    @TestHTTPResource
    URI endpoint;

    @Inject
    AutoScalingReconciler reconciler;

    @Test
    void anInstanceLeavesServiceForStandbyAndComesBack() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "standby-" + suffix;
        String loadBalancer = "sb-" + suffix;
        given().header("Authorization", EC2_AUTH).formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-12345678")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
        String targetGroup = given().header("Authorization", ELB_AUTH).formParam("Action", "CreateTargetGroup")
                .formParam("Version", "2015-12-01").formParam("Name", loadBalancer)
                .formParam("Protocol", "HTTP").formParam("Port", "80").formParam("VpcId", "vpc-00000001")
                .formParam("TargetType", "instance")
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        given().header("Authorization", ELB_AUTH).formParam("Action", "CreateLoadBalancer")
                .formParam("Version", "2012-06-01").formParam("LoadBalancerName", loadBalancer)
                .formParam("Listeners.member.1.Protocol", "TCP")
                .formParam("Listeners.member.1.LoadBalancerPort", "80")
                .formParam("Listeners.member.1.InstancePort", "80")
                .post("/").then().statusCode(200);
        given().header("Authorization", AUTH).formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", name)
                .formParam("LaunchTemplate.LaunchTemplateName", name)
                .formParam("LaunchTemplate.Version", "1")
                .formParam("MinSize", "2").formParam("MaxSize", "2").formParam("DesiredCapacity", "2")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("TargetGroupARNs.member.1", targetGroup)
                .formParam("LoadBalancerNames.member.1", loadBalancer)
                .post("/").then().statusCode(200);
        try {
            reconciler.reconcileAll();
            reconciler.reconcileAll();
            XmlPath launched = group(name);
            assertEquals(List.of("InService", "InService"), launched.getList(GROUP_PATH + ".Instances.member.LifecycleState"));
            List<String> members = launched.getList(GROUP_PATH + ".Instances.member.InstanceId");
            String standby = members.get(0);
            String serving = members.get(1);
            assertEquals(List.copyOf(members).stream().sorted().toList(),
                    AlbFixtures.targetIds(ACCOUNT, targetGroup).stream().sorted().toList());

            // MinSize 2 leaves no room to decrement, so the client lowers it first.
            XmlPath refused = given().header("Authorization", AUTH).formParam("Action", "EnterStandby")
                    .formParam("AutoScalingGroupName", name).formParam("InstanceIds.member.1", standby)
                    .formParam("ShouldDecrementDesiredCapacity", "true")
                    .post("/").then().statusCode(400).extract().xmlPath();
            assertEquals("ValidationError", refused.getString("ErrorResponse.Error.Code"));
            assertEquals("AutoScalingGroup " + name + " has min-size=2, max-size=2, and desired-size=2. To place"
                    + " into standby 1 instances, please update the AutoScalingGroup sizes appropriately.",
                    refused.getString("ErrorResponse.Error.Message"));
            given().header("Authorization", AUTH).formParam("Action", "UpdateAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name).formParam("MinSize", "0")
                    .post("/").then().statusCode(200);

            XmlPath entered = given().header("Authorization", AUTH).formParam("Action", "EnterStandby")
                    .formParam("AutoScalingGroupName", name).formParam("InstanceIds.member.1", standby)
                    .formParam("ShouldDecrementDesiredCapacity", "true")
                    .post("/").then().statusCode(200).extract().xmlPath();
            String enterActivity = entered.getString(ENTERED + ".ActivityId");
            assertAll(
                    () -> assertEquals(name, entered.getString(ENTERED + ".AutoScalingGroupName")),
                    () -> assertEquals("Moving EC2 instance to Standby: " + standby,
                            entered.getString(ENTERED + ".Description")),
                    () -> assertTrue(entered.getString(ENTERED + ".Cause").endsWith(" instance " + standby
                            + " was moved to standby in response to a user request, shrinking the capacity"
                            + " from 2 to 1.")),
                    () -> assertEquals("InProgress", entered.getString(ENTERED + ".StatusCode")),
                    () -> assertEquals("50", entered.getString(ENTERED + ".Progress")));

            reconciler.reconcileAll();
            assertEquals("Standby", given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeAutoScalingInstances").formParam("InstanceIds.member.1", standby)
                    .post("/").then().statusCode(200).extract().xmlPath().getString(
                            "DescribeAutoScalingInstancesResponse.DescribeAutoScalingInstancesResult"
                                    + ".AutoScalingInstances.member.LifecycleState"));
            XmlPath inStandby = group(name);
            assertEquals("1", inStandby.getString(GROUP_PATH + ".DesiredCapacity"));
            assertEquals("Standby", member(inStandby, standby, "LifecycleState"));
            assertEquals("InService", member(inStandby, serving, "LifecycleState"));
            assertEquals(List.of(serving), AlbFixtures.targetIds(ACCOUNT, targetGroup));
            assertEquals(List.of(serving), classicInstances(loadBalancer));
            assertEquals("Successful", activity(name, enterActivity, "StatusCode"));
            assertEquals("The instance " + serving + " is not in Standby.", given().header("Authorization", AUTH)
                    .formParam("Action", "ExitStandby")
                    .formParam("AutoScalingGroupName", name).formParam("InstanceIds.member.1", serving)
                    .post("/").then().statusCode(400).extract().xmlPath().getString("ErrorResponse.Error.Message"));

            reconciler.reconcileAll();
            assertEquals(2, group(name).getList(GROUP_PATH + ".Instances.member.InstanceId").size(),
                    "The group launches no replacement for an instance whose capacity it gave up");

            XmlPath exited = given().header("Authorization", AUTH).formParam("Action", "ExitStandby")
                    .formParam("AutoScalingGroupName", name).formParam("InstanceIds.member.1", standby)
                    .post("/").then().statusCode(200).extract().xmlPath();
            String exitActivity = exited.getString(EXITED + ".ActivityId");
            assertAll(
                    () -> assertEquals("Moving EC2 instance out of Standby: " + standby,
                            exited.getString(EXITED + ".Description")),
                    () -> assertTrue(exited.getString(EXITED + ".Cause").endsWith(" instance " + standby
                            + " was moved out of standby in response to a user request, increasing the capacity"
                            + " from 1 to 2.")),
                    () -> assertEquals("PreInService", exited.getString(EXITED + ".StatusCode")),
                    () -> assertEquals("30", exited.getString(EXITED + ".Progress")));

            reconciler.reconcileAll();
            XmlPath back = group(name);
            assertEquals("2", back.getString(GROUP_PATH + ".DesiredCapacity"));
            assertEquals(List.of("InService", "InService"), back.getList(GROUP_PATH + ".Instances.member.LifecycleState"));
            assertEquals(List.of(serving, standby).stream().sorted().toList(),
                    AlbFixtures.targetIds(ACCOUNT, targetGroup).stream().sorted().toList());
            assertEquals(List.of(serving, standby).stream().sorted().toList(),
                    classicInstances(loadBalancer).stream().sorted().toList());
            assertEquals("Successful", activity(name, exitActivity, "StatusCode"));
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name).formParam("ForceDelete", "true").post("/");
            given().header("Authorization", EC2_AUTH).formParam("Action", "DeleteLaunchTemplate")
                    .formParam("LaunchTemplateName", name).post("/");
            given().header("Authorization", ELB_AUTH).formParam("Action", "DeleteLoadBalancer")
                    .formParam("Version", "2012-06-01").formParam("LoadBalancerName", loadBalancer).post("/");
            given().header("Authorization", ELB_AUTH).formParam("Action", "DeleteTargetGroup")
                    .formParam("Version", "2015-12-01").formParam("TargetGroupArn", targetGroup).post("/");
        }
    }

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

    static void deleteOwned(Runnable delete, String missingMessage) {
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

    private static List<String> classicInstances(String loadBalancer) {
        return given().header("Authorization", ELB_AUTH).formParam("Action", "DescribeLoadBalancers")
                .formParam("Version", "2012-06-01").formParam("LoadBalancerNames.member.1", loadBalancer)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancerDescriptions"
                        + ".member.Instances.member.InstanceId", String.class);
    }

    private static String activity(String group, String activityId, String field) {
        return given().header("Authorization", AUTH).formParam("Action", "DescribeScalingActivities")
                .formParam("AutoScalingGroupName", group)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("DescribeScalingActivitiesResponse.DescribeScalingActivitiesResult.Activities.member"
                        + ".find { it.ActivityId == '" + activityId + "' }." + field);
    }

    private interface Cleanup extends AutoCloseable {
        @Override
        void close();
    }

    private static AutoScalingGroup group(AutoScalingClient client, String name) {
        return client.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name)).autoScalingGroups().getFirst();
    }
    private static XmlPath group(String name) {
        return given().header("Authorization", AUTH).formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", name)
                .post("/").then().statusCode(200).extract().xmlPath();
    }

    private static String member(XmlPath group, String instanceId, String field) {
        return group.getString(GROUP_PATH + ".Instances.member.find { it.InstanceId == '" + instanceId + "' }."
                + field);
    }


    @Test
    void errorsUseTheQueryErrorShape() {
        XmlPath missing = given().header("Authorization", AUTH).formParam("Action", "EnterStandby")
                .formParam("AutoScalingGroupName", "no-such-standby-group")
                .formParam("InstanceIds.member.1", "i-0123456789abcdef0")
                .formParam("ShouldDecrementDesiredCapacity", "false")
                .post("/").then().statusCode(400).extract().xmlPath();
        assertEquals("ValidationError", missing.getString("ErrorResponse.Error.Code"));
        assertEquals("Sender", missing.getString("ErrorResponse.Error.Type"));

        XmlPath noDecrement = given().header("Authorization", AUTH).formParam("Action", "EnterStandby")
                .formParam("AutoScalingGroupName", "no-such-standby-group")
                .post("/").then().statusCode(400).extract().xmlPath();
        assertEquals("1 validation error detected: Value null at 'shouldDecrementDesiredCapacity' failed to"
                + " satisfy constraint: Member must not be null", noDecrement.getString("ErrorResponse.Error.Message"));
    }
}

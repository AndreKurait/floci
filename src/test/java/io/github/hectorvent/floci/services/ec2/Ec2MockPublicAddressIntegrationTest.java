package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingReconciler;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2MockPublicAddressIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(722000000000L);
    private static final String REGION = "us-east-1";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";

    @Inject AutoScalingReconciler reconciler;
    @Inject AutoScalingService groups;

    @ParameterizedTest
    @CsvSource({
            "direct,false,true,true", "direct,true,false,false",
            "direct,true,unset,true", "direct,false,unset,false",
            "template,false,true,true", "template,true,false,false",
            "template,true,unset,true", "template,false,unset,false",
            "asg,false,true,true", "asg,true,false,false",
            "asg,true,unset,true", "asg,false,unset,false"
    })
    void publicIpUsesTheSelectedLaunchValueAndSurvivesOnlyTheRunningLifetime(
            String source, boolean subnetDefault, String override, boolean publicExpected) {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.86.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.86.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        request("ModifySubnetAttribute", owner).formParam("SubnetId", subnet)
                .formParam("MapPublicIpOnLaunch.Value", subnetDefault).post("/").then().statusCode(200);
        String volume = request("CreateVolume", owner).formParam("Size", 8)
                .formParam("AvailabilityZone", REGION + "a").post("/").then().statusCode(200)
                .extract().path("CreateVolumeResponse.volumeId");
        String snapshot = request("CreateSnapshot", owner).formParam("VolumeId", volume)
                .post("/").then().statusCode(200).extract().path("CreateSnapshotResponse.snapshotId");
        String image = request("RegisterImage", owner).formParam("Name", "public-address")
                .formParam("RootDeviceName", "/dev/sda1").formParam("Architecture", "x86_64")
                .formParam("BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                .formParam("BlockDeviceMapping.1.Ebs.SnapshotId", snapshot)
                .post("/").then().statusCode(200).extract().path("RegisterImageResponse.imageId");
        String template = null;
        if (!"direct".equals(source)) {
            RequestSpecification create = request("CreateLaunchTemplate", owner)
                    .formParam("LaunchTemplateName", "public-address")
                    .formParam("LaunchTemplateData.ImageId", image)
                    .formParam("LaunchTemplateData.InstanceType", "t3.micro");
            if (!"unset".equals(override)) {
                create.formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                        .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress",
                                !Boolean.parseBoolean(override));
            }
            template = create.post("/").then().statusCode(200)
                    .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
            RequestSpecification version = request("CreateLaunchTemplateVersion", owner)
                    .formParam("LaunchTemplateId", template).formParam("SourceVersion", "1")
                    .formParam("LaunchTemplateData.InstanceType", "t3.micro");
            if (!"unset".equals(override)) {
                version.formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                        .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress", override);
            }
            version.post("/").then().statusCode(200);
        }
        String instance;
        if ("asg".equals(source)) {
            request("CreateAutoScalingGroup", owner).formParam("AutoScalingGroupName", "public-address")
                    .formParam("MinSize", 0).formParam("MaxSize", 1).formParam("DesiredCapacity", 1)
                    .formParam("VPCZoneIdentifier", subnet)
                    .formParam("LaunchTemplate.LaunchTemplateId", template).formParam("LaunchTemplate.Version", "2")
                    .post("/").then().statusCode(200);
            RequestScopes.runAs(owner, () -> reconciler.reconcile(
                    groups.describeAutoScalingGroups(REGION, List.of("public-address")).getFirst()));
            instance = request("DescribeAutoScalingGroups", owner).post("/").then().statusCode(200).extract()
                    .path("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.Instances.member.InstanceId");
        } else {
            RequestSpecification launch = request("RunInstances", owner)
                    .formParam("MinCount", 1).formParam("MaxCount", 1);
            if (template != null) {
                launch.formParam("LaunchTemplate.LaunchTemplateId", template).formParam("LaunchTemplate.Version", "2")
                        .formParam("SubnetId", subnet);
            } else {
                launch.formParam("ImageId", image).formParam("InstanceType", "t3.micro");
                if (!"unset".equals(override)) {
                    launch.formParam("NetworkInterface.1.DeviceIndex", 0)
                            .formParam("NetworkInterface.1.SubnetId", subnet)
                            .formParam("NetworkInterface.1.AssociatePublicIpAddress", override);
                } else {
                    launch.formParam("SubnetId", subnet);
                }
            }
            instance = launch.post("/").then().statusCode(200)
                    .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        }
        ValidatableResponse first = describe(owner, instance);
        String privateIp = first.extract().path(INSTANCE + "privateIpAddress");
        String publicIp = first.extract().xmlPath().getString(INSTANCE + "ipAddress");
        assertEquals(publicExpected, !publicIp.isEmpty());
        if (publicExpected) {
            assertTrue(publicIp.matches("54\\.[0-9]+\\.[0-9]+\\.[0-9]+"));
        }
        assertEquals(publicIp, describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress"));
        if (!"asg".equals(source)) {
            request("StopInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
            assertEquals("", describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress"));
            request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
            ValidatableResponse restarted = describe(owner, instance);
            assertEquals(privateIp, restarted.extract().path(INSTANCE + "privateIpAddress"));
            String replacement = restarted.extract().xmlPath().getString(INSTANCE + "ipAddress");
            assertEquals(publicExpected, !replacement.isEmpty());
            if (publicExpected) {
                assertNotEquals(publicIp, replacement);
            }
        } else {
            request("UpdateAutoScalingGroup", owner).formParam("AutoScalingGroupName", "public-address")
                    .formParam("DesiredCapacity", 0).post("/").then().statusCode(200);
            request("DeleteAutoScalingGroup", owner).formParam("AutoScalingGroupName", "public-address")
                    .formParam("ForceDelete", true).post("/").then().statusCode(200);
        }
        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals("", describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress"));
        if (template != null) {
            request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", template).post("/").then().statusCode(200);
        }
        request("DeregisterImage", owner).formParam("ImageId", image).post("/").then().statusCode(200);
        request("DeleteSnapshot", owner).formParam("SnapshotId", snapshot).post("/").then().statusCode(200);
        request("DeleteVolume", owner).formParam("VolumeId", volume).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    private ValidatableResponse describe(String owner, String instance) {
        return request("DescribeInstances", owner).formParam("InstanceId.1", instance)
                .post("/").then().statusCode(200);
    }

    private RequestSpecification request(String action, String owner) {
        String service = action.contains("AutoScaling") ? "autoscaling" : "ec2";
        return given().formParam("Action", action).header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + owner + "/20261010/" + REGION + "/" + service + "/aws4_request");
    }
}

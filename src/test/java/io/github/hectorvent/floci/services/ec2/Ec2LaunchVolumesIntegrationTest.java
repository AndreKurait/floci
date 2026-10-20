package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingReconciler;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2LaunchVolumesIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(810000000000L);
    private static final String REGION = "us-east-1";
    @Inject AutoScalingService groups;
    @Inject AutoScalingReconciler reconciler;
    private String owner;
    private String recipient;
    private String image;
    private String snapshot;

    @BeforeEach
    void source() {
        owner = Long.toString(ACCOUNTS.addAndGet(2));
        recipient = Long.toString(Long.parseLong(owner) + 1);
        String volume = ec2("CreateVolume", owner).formParam("Size", 16)
                .formParam("AvailabilityZone", REGION + "a").formParam("VolumeType", "gp3")
                .post("/").then().statusCode(200).extract().path("CreateVolumeResponse.volumeId");
        snapshot = ec2("CreateSnapshot", owner).formParam("VolumeId", volume).post("/")
                .then().statusCode(200).extract().path("CreateSnapshotResponse.snapshotId");
        ec2("DeleteVolume", owner).formParam("VolumeId", volume).post("/").then().statusCode(200);
        image = ec2("RegisterImage", owner).formParam("Name", "launch-volume-source")
                .formParam("RootDeviceName", "/dev/sda1")
                .formParam("BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                .formParam("BlockDeviceMapping.1.Ebs.SnapshotId", snapshot)
                .formParam("BlockDeviceMapping.1.Ebs.VolumeSize", 16)
                .formParam("BlockDeviceMapping.1.Ebs.VolumeType", "gp3")
                .formParam("BlockDeviceMapping.1.Ebs.DeleteOnTermination", true)
                .post("/").then().statusCode(200).extract().path("RegisterImageResponse.imageId");
    }

    @Test
    void directLaunchInheritsSnapshotAndHonorsRootOverrideAndAllDeleteFlags() {
        RequestSpecification launch = launch(owner).formParam("BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                .formParam("BlockDeviceMapping.1.Ebs.VolumeSize", 20)
                .formParam("BlockDeviceMapping.1.Ebs.DeleteOnTermination", false);
        data(launch, "BlockDeviceMapping.2", true);
        ValidatableResponse response = launch.post("/").then().statusCode(200);
        String instance = response.extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        List<String> volumes = response.extract().xmlPath().getList(
                "RunInstancesResponse.instancesSet.item.blockDeviceMapping.item.ebs.volumeId", String.class);
        assertEquals(2, volumes.size());
        String root = volume(owner, deviceVolume(owner, instance, "/dev/sda1")).body("DescribeVolumesResponse.volumeSet.item.size", equalTo("20"))
                .body("DescribeVolumesResponse.volumeSet.item.snapshotId", equalTo(snapshot))
                .body("DescribeVolumesResponse.volumeSet.item.attachmentSet.item.deleteOnTermination", equalTo("false"))
                .extract().path("DescribeVolumesResponse.volumeSet.item.volumeId");
        assertDataVolume(owner, deviceVolume(owner, instance, "/dev/sdf"));
        ec2("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        volume(owner, root).body("DescribeVolumesResponse.volumeSet.item.status", equalTo("available"));
        assertEquals(List.of(root), volumeIds(owner));
        assertSourceUnchanged();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void autoScalingUsesTheSameAmiAndAdditionalVolumePlan(boolean template) {
        String name = "volumes-" + owner;
        RequestSpecification create;
        if (template) {
            create = ec2("CreateLaunchTemplate", owner).formParam("LaunchTemplateName", name)
                    .formParam("LaunchTemplateData.ImageId", image)
                    .formParam("LaunchTemplateData.InstanceType", "t3.micro");
            data(create, "LaunchTemplateData.BlockDeviceMapping.1", true);
            create.post("/").then().statusCode(200);
        } else {
            create = asg("CreateLaunchConfiguration", owner).formParam("LaunchConfigurationName", name)
                    .formParam("ImageId", image).formParam("InstanceType", "t3.micro");
            data(create, "BlockDeviceMappings.member.1", true);
            create.post("/").then().statusCode(200);
        }
        RequestSpecification group = asg("CreateAutoScalingGroup", owner).formParam("AutoScalingGroupName", name)
                .formParam("MinSize", 0).formParam("MaxSize", 1).formParam("DesiredCapacity", 1)
                .formParam("AvailabilityZones.member.1", REGION + "a");
        if (template) {
            group.formParam("LaunchTemplate.LaunchTemplateName", name).formParam("LaunchTemplate.Version", "$Latest");
        } else {
            group.formParam("LaunchConfigurationName", name);
        }
        group.post("/").then().statusCode(200);
        RequestScopes.runAs(owner, () -> reconciler.reconcile(
                groups.describeAutoScalingGroups(REGION, List.of(name)).getFirst()));
        ValidatableResponse found = asg("DescribeAutoScalingGroups", owner).formParam("AutoScalingGroupNames.member.1", name)
                .post("/").then().statusCode(200);
        String instance = found.extract().path("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.Instances.member.InstanceId");
        assertTrue(instance != null && instance.startsWith("i-"), found.extract().asString());
        ValidatableResponse launched = ec2("DescribeInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        List<String> volumes = launched.extract().xmlPath().getList(
                "DescribeInstancesResponse.reservationSet.item.instancesSet.item.blockDeviceMapping.item.ebs.volumeId", String.class);
        assertEquals(2, volumes.size());
        volume(owner, deviceVolume(owner, instance, "/dev/sda1")).body("DescribeVolumesResponse.volumeSet.item.size", equalTo("16"))
                .body("DescribeVolumesResponse.volumeSet.item.snapshotId", equalTo(snapshot));
        assertDataVolume(owner, deviceVolume(owner, instance, "/dev/sdf"));
        asg("UpdateAutoScalingGroup", owner).formParam("AutoScalingGroupName", name).formParam("DesiredCapacity", 0)
                .post("/").then().statusCode(200);
        ec2("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals(List.of(), volumeIds(owner));
        asg("DeleteAutoScalingGroup", owner).formParam("AutoScalingGroupName", name).formParam("ForceDelete", true)
                .post("/").then().statusCode(200);
        assertSourceUnchanged();
    }

    @Test
    void directRequestOverridesTemplateWithoutChangingTemplateOrAmi() {
        String name = "override-" + owner;
        RequestSpecification create = ec2("CreateLaunchTemplate", owner).formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", image)
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", 24);
        data(create, "LaunchTemplateData.BlockDeviceMapping.2", true);
        create.post("/").then().statusCode(200);
        ValidatableResponse launched = ec2("RunInstances", owner).formParam("LaunchTemplate.LaunchTemplateName", name)
                .formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                .formParam("BlockDeviceMapping.1.Ebs.VolumeSize", 20)
                .formParam("BlockDeviceMapping.2.DeviceName", "/dev/sdf")
                .formParam("BlockDeviceMapping.2.NoDevice", "")
                .post("/").then().statusCode(200);
        String instance = launched.extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        assertEquals(1, volumeIds(owner).size());
        volume(owner, volumeIds(owner).getFirst()).body("DescribeVolumesResponse.volumeSet.item.size", equalTo("20"))
                .body("DescribeVolumesResponse.volumeSet.item.snapshotId", equalTo(snapshot));
        ec2("DescribeLaunchTemplateVersions", owner).formParam("LaunchTemplateName", name)
                .post("/").then().statusCode(200)
                .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.blockDeviceMappingSet.item[0].ebs.volumeSize", equalTo("24"));
        ec2("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals(List.of(), volumeIds(owner));
        assertSourceUnchanged();
    }

    @Test
    void sharedAmiLaunchDoesNotGrantDirectSnapshotAccessAndRevocationRefusesLaunch() {
        ec2("ModifyImageAttribute", owner).formParam("ImageId", image)
                .formParam("LaunchPermission.Add.1.UserId", recipient).post("/").then().statusCode(200);
        ec2("CreateVolume", recipient).formParam("SnapshotId", snapshot).formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(400);
        String instance = launch(recipient).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        assertEquals(1, volumeIds(recipient).size());
        ec2("ModifyImageAttribute", owner).formParam("ImageId", image)
                .formParam("LaunchPermission.Remove.1.UserId", recipient).post("/").then().statusCode(200);
        launch(recipient).post("/").then().statusCode(400);
        assertEquals(1, volumeIds(recipient).size());
        ec2("TerminateInstances", recipient).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals(List.of(), volumeIds(recipient));
        assertSourceUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"small", "duplicate", "ambiguous", "missing-snapshot", "missing-root", "type", "throughput", "later-snapshot"})
    void invalidMapsHaveNoPartialLaunchResources(String failure) {
        RequestSpecification request = launch(owner);
        switch (failure) {
            case "small" -> request.formParam("BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                    .formParam("BlockDeviceMapping.1.Ebs.VolumeSize", 8);
            case "duplicate" -> {
                data(request, "BlockDeviceMapping.1", true);
                data(request, "BlockDeviceMapping.2", true);
            }
            case "ambiguous" -> data(request, "BlockDeviceMapping.1", true).formParam("BlockDeviceMapping.1.NoDevice", "");
            case "missing-snapshot" -> data(request, "BlockDeviceMapping.1", true)
                    .formParam("BlockDeviceMapping.1.Ebs.SnapshotId", "snap-fffffffffffffffff");
            case "later-snapshot" -> {
                data(request, "BlockDeviceMapping.1", true);
                request.formParam("BlockDeviceMapping.2.DeviceName", "/dev/sdg")
                        .formParam("BlockDeviceMapping.2.Ebs.SnapshotId", "snap-fffffffffffffffff");
            }
            case "missing-root" -> request.formParam("BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                    .formParam("BlockDeviceMapping.1.NoDevice", "");
            case "type" -> request.formParam("BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                    .formParam("BlockDeviceMapping.1.Ebs.VolumeType", "unknown");
            case "throughput" -> request.formParam("BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                    .formParam("BlockDeviceMapping.1.Ebs.VolumeType", "gp2")
                    .formParam("BlockDeviceMapping.1.Ebs.Throughput", 125);
        }
        request.post("/").then().statusCode(400);
        assertEquals(List.of(), volumeIds(owner));
        assertEquals(List.of(), ec2("DescribeInstances", owner).post("/").then().statusCode(200).extract()
                .xmlPath().getList("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId", String.class));
        assertEquals(List.of(), ec2("DescribeNetworkInterfaces", owner).post("/").then().statusCode(200).extract()
                .xmlPath().getList("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId", String.class));
        assertSourceUnchanged();
    }

    private void assertSourceUnchanged() {
        ec2("DescribeImages", owner).formParam("ImageId.1", image).post("/").then().statusCode(200)
                .body("DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item.ebs.volumeSize", equalTo("16"))
                .body("DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item.ebs.snapshotId", equalTo(snapshot));
        ec2("DescribeSnapshots", owner).formParam("SnapshotId.1", snapshot).post("/").then().statusCode(200)
                .body("DescribeSnapshotsResponse.snapshotSet.item.volumeSize", equalTo("16"))
                .body("DescribeSnapshotsResponse.snapshotSet.item.ownerId", equalTo(owner));
    }
    private RequestSpecification launch(String account) {
        return ec2("RunInstances", account).formParam("ImageId", image).formParam("InstanceType", "t3.micro")
                .formParam("MinCount", 1).formParam("MaxCount", 1);
    }
    private RequestSpecification data(RequestSpecification request, String prefix, boolean delete) {
        return request.formParam(prefix + ".DeviceName", "/dev/sdf").formParam(prefix + ".Ebs.VolumeSize", 10)
                .formParam(prefix + ".Ebs.VolumeType", "gp3").formParam(prefix + ".Ebs.Iops", 3000)
                .formParam(prefix + ".Ebs.Throughput", 125).formParam(prefix + ".Ebs.DeleteOnTermination", delete);
    }
    private String deviceVolume(String account, String instance, String device) {
        return ec2("DescribeInstances", account).formParam("InstanceId.1", instance).post("/")
                .then().statusCode(200).extract().xmlPath().getString(
                        "DescribeInstancesResponse.reservationSet.item.instancesSet.item.blockDeviceMapping.item.find { it.deviceName == '"
                                + device + "' }.ebs.volumeId");
    }
    private ValidatableResponse volume(String account, String id) {
        return ec2("DescribeVolumes", account).formParam("VolumeId.1", id).post("/").then().statusCode(200);
    }
    private void assertDataVolume(String account, String id) {
        volume(account, id).body("DescribeVolumesResponse.volumeSet.item.size", equalTo("10"))
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("gp3"))
                .body("DescribeVolumesResponse.volumeSet.item.iops", equalTo("3000"))
                .body("DescribeVolumesResponse.volumeSet.item.throughput", equalTo("125"))
                .body("DescribeVolumesResponse.volumeSet.item.attachmentSet.item.device", equalTo("/dev/sdf"));
    }
    private List<String> volumeIds(String account) {
        return ec2("DescribeVolumes", account).post("/").then().statusCode(200).extract()
                .xmlPath().getList("DescribeVolumesResponse.volumeSet.item.volumeId", String.class);
    }
    private RequestSpecification ec2(String action, String account) { return request(action, account, "ec2"); }
    private RequestSpecification asg(String action, String account) { return request(action, account, "autoscaling"); }
    private RequestSpecification request(String action, String account, String service) {
        return given().formParam("Action", action).header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + account + "/20261010/" + REGION + "/" + service + "/aws4_request");
    }
}

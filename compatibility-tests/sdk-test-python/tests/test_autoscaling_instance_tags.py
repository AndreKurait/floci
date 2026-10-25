"""ASG launch tags verified against live AWS on 2026-10-12."""

import time
import uuid
from contextlib import ExitStack, closing

import boto3
import pytest
from botocore.exceptions import ClientError


@pytest.mark.parametrize("group_tags", [False, True])
def test_launched_instances_include_group_name(ec2_client, aws_config, group_tags):
    name = "group-tags-" + uuid.uuid4().hex[:12]
    with closing(boto3.client("autoscaling", **aws_config)) as autoscaling, ExitStack() as cleanup:
        vpc = ec2_client.create_vpc(CidrBlock="10.236.0.0/24")["Vpc"]["VpcId"]
        cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
        subnet = ec2_client.create_subnet(
            VpcId=vpc, CidrBlock="10.236.0.0/26",
            AvailabilityZone=aws_config["region_name"] + "a")["Subnet"]["SubnetId"]
        cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
        image = ec2_client.register_image(
            Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1")["ImageId"]
        cleanup.callback(ec2_client.deregister_image, ImageId=image)
        template = ec2_client.create_launch_template(
            LaunchTemplateName=name,
            LaunchTemplateData={
                "ImageId": image, "InstanceType": "t3.nano",
                "TagSpecifications": [{"ResourceType": "instance", "Tags": [
                    {"Key": "TemplateOnly", "Value": "template"},
                    {"Key": "Shared", "Value": "template"},
                ]}],
            })["LaunchTemplate"]["LaunchTemplateId"]
        cleanup.callback(ec2_client.delete_launch_template, LaunchTemplateId=template)
        tags = [
            {"Key": "Shared", "Value": "group", "PropagateAtLaunch": True},
            {"Key": "GroupOnly", "Value": "group", "PropagateAtLaunch": True},
            {"Key": "NotPropagated", "Value": "hidden", "PropagateAtLaunch": False},
        ] if group_tags else []
        autoscaling.create_auto_scaling_group(
            AutoScalingGroupName=name, MinSize=0, MaxSize=1, DesiredCapacity=1,
            VPCZoneIdentifier=subnet,
            LaunchTemplate={"LaunchTemplateId": template, "Version": "1"}, Tags=tags)
        cleanup.callback(autoscaling.delete_auto_scaling_group,
                         AutoScalingGroupName=name, ForceDelete=True)
        deadline = time.monotonic() + 60
        while True:
            group = autoscaling.describe_auto_scaling_groups(
                AutoScalingGroupNames=[name])["AutoScalingGroups"][0]
            if group["Instances"] and group["Instances"][0]["LifecycleState"] == "InService":
                break
            assert time.monotonic() < deadline, group
            time.sleep(1)
        instance_id = group["Instances"][0]["InstanceId"]
        instance = ec2_client.describe_instances(
            InstanceIds=[instance_id])["Reservations"][0]["Instances"][0]
        observed = {tag["Key"]: tag["Value"] for tag in instance["Tags"]}
        assert observed["aws:autoscaling:groupName"] == name
        assert observed["TemplateOnly"] == "template"
        assert observed["Shared"] == ("group" if group_tags else "template")
        assert ("GroupOnly" in observed) == group_tags
        assert "NotPropagated" not in observed

        ec2_client.create_tags(Resources=[instance_id], Tags=[{"Key": "Kept", "Value": "after-detach"}])
        cleanup.callback(ec2_client.terminate_instances, InstanceIds=[instance_id])
        autoscaling.detach_instances(
            AutoScalingGroupName=name, InstanceIds=[instance_id], ShouldDecrementDesiredCapacity=True)
        detached = ec2_client.describe_instances(
            InstanceIds=[instance_id])["Reservations"][0]["Instances"][0]
        detached_tags = {tag["Key"]: tag["Value"] for tag in detached.get("Tags", [])}
        assert "aws:autoscaling:groupName" not in detached_tags
        assert detached_tags["Kept"] == "after-detach"
        assert not ec2_client.describe_tags(Filters=[
            {"Name": "resource-id", "Values": [instance_id]},
            {"Name": "key", "Values": ["aws:autoscaling:groupName"]},
        ])["Tags"]
        assert not ec2_client.describe_instances(Filters=[
            {"Name": "tag:aws:autoscaling:groupName", "Values": [name]},
        ])["Reservations"]

        autoscaling.attach_instances(AutoScalingGroupName=name, InstanceIds=[instance_id])
        attached = ec2_client.describe_instances(
            InstanceIds=[instance_id])["Reservations"][0]["Instances"][0]
        attached_tags = {tag["Key"]: tag["Value"] for tag in attached["Tags"]}
        assert attached_tags["aws:autoscaling:groupName"] == name
        assert attached_tags["Kept"] == "after-detach"
        assert ("GroupOnly" in attached_tags) == group_tags
        assert "NotPropagated" not in attached_tags
        with pytest.raises(ClientError) as error:
            autoscaling.attach_instances(AutoScalingGroupName=name, InstanceIds=[instance_id])
        assert error.value.response["Error"]["Code"] == "ValidationError"
        assert error.value.response["Error"]["Message"] == (
            f"[The Instance: {instance_id} is already part of AutoScalingGroup:{name}]"
        )
        ec2_client.terminate_instances(InstanceIds=[instance_id])
        assert ec2_client.describe_tags(Filters=[
            {"Name": "resource-id", "Values": [instance_id]},
            {"Name": "key", "Values": ["aws:autoscaling:groupName"]},
        ])["Tags"][0]["Value"] == name

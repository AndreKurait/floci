"""ASG launch tags verified against live AWS on 2026-10-12."""

import time
import uuid
from contextlib import ExitStack, closing

import boto3
import pytest


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
            if group["Instances"]:
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

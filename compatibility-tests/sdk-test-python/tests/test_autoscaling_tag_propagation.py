"""CreateAutoScalingGroup tag defaults observed on AWS, 2026-10-29."""

import uuid
from contextlib import ExitStack, closing

import boto3


def test_create_group_defaults_tag_propagation_to_true(ec2_client, aws_config, client_config):
    name = "tag-propagation-" + uuid.uuid4().hex[:12]
    with closing(boto3.client("autoscaling", config=client_config, **aws_config)) as autoscaling:
        with ExitStack() as cleanup:
            image = ec2_client.register_image(
                Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1"
            )["ImageId"]
            cleanup.callback(ec2_client.deregister_image, ImageId=image)
            template = ec2_client.create_launch_template(
                LaunchTemplateName=name,
                LaunchTemplateData={"ImageId": image, "InstanceType": "t3.micro"},
            )["LaunchTemplate"]["LaunchTemplateId"]
            cleanup.callback(ec2_client.delete_launch_template, LaunchTemplateId=template)
            autoscaling.create_auto_scaling_group(
                AutoScalingGroupName=name, MinSize=0, MaxSize=0, DesiredCapacity=0,
                AvailabilityZones=[aws_config["region_name"] + "a"],
                LaunchTemplate={"LaunchTemplateId": template},
                Tags=[
                    {"Key": "default", "Value": "yes"},
                    {"Key": "enabled", "Value": "yes", "PropagateAtLaunch": True},
                    {"Key": "disabled", "Value": "no", "PropagateAtLaunch": False},
                ],
            )
            cleanup.callback(autoscaling.delete_auto_scaling_group,
                             AutoScalingGroupName=name, ForceDelete=True)
            group = autoscaling.describe_auto_scaling_groups(
                AutoScalingGroupNames=[name])["AutoScalingGroups"][0]
            assert {tag["Key"]: tag["PropagateAtLaunch"] for tag in group["Tags"]} == {
                "default": True, "enabled": True, "disabled": False,
            }
            assert group["Instances"] == []

"""Synchronous placement inputs, checked against live AWS on 2026-10-12."""

import uuid
from contextlib import ExitStack, closing

import boto3
import pytest
from botocore.exceptions import ClientError


def test_subnet_counts_track_address_allocation(ec2_client):
    with ExitStack() as cleanup:
        vpc = ec2_client.create_vpc(CidrBlock="10.98.0.0/16")["Vpc"]["VpcId"]
        cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
        for cidr, capacity in [("10.98.0.0/28", 11), ("10.98.1.0/24", 251)]:
            subnet = ec2_client.create_subnet(VpcId=vpc, CidrBlock=cidr)["Subnet"]
            subnet_id = subnet["SubnetId"]
            cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet_id)
            assert subnet["AvailableIpAddressCount"] == capacity
            interface = ec2_client.create_network_interface(SubnetId=subnet_id)["NetworkInterface"]
            try:
                described = ec2_client.describe_subnets(SubnetIds=[subnet_id])["Subnets"][0]
                assert described["AvailableIpAddressCount"] == capacity - 1
            finally:
                ec2_client.delete_network_interface(NetworkInterfaceId=interface["NetworkInterfaceId"])
            described = ec2_client.describe_subnets(SubnetIds=[subnet_id])["Subnets"][0]
            assert described["AvailableIpAddressCount"] == capacity


def test_zone_only_groups_validate_create_and_update_atomically(
    ec2_client, aws_config, client_config
):
    name = "zone-validation-" + uuid.uuid4().hex[:12]
    zones = [
        zone["ZoneName"]
        for zone in ec2_client.describe_availability_zones()["AvailabilityZones"]
    ]
    unknown_zone = aws_config["region_name"] + "z"
    assert unknown_zone not in zones
    with closing(boto3.client("autoscaling", config=client_config, **aws_config)) as autoscaling:
        with ExitStack() as cleanup:
            image = ec2_client.register_image(
                Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1"
            )["ImageId"]
            cleanup.callback(ec2_client.deregister_image, ImageId=image)
            template = ec2_client.create_launch_template(
                LaunchTemplateName=name,
                LaunchTemplateData={"ImageId": image, "InstanceType": "t3.nano"},
            )["LaunchTemplate"]["LaunchTemplateId"]
            cleanup.callback(ec2_client.delete_launch_template, LaunchTemplateId=template)
            for requested in [[unknown_zone], [zones[0], unknown_zone]]:
                with pytest.raises(ClientError) as error:
                    autoscaling.create_auto_scaling_group(
                        AutoScalingGroupName=name,
                        MinSize=0, MaxSize=0, DesiredCapacity=0,
                        LaunchTemplate={"LaunchTemplateId": template},
                        AvailabilityZones=requested,
                    )
                assert error.value.response["Error"]["Code"] == "ValidationError"
                assert unknown_zone in error.value.response["Error"]["Message"]
                assert autoscaling.describe_auto_scaling_groups(
                    AutoScalingGroupNames=[name]
                )["AutoScalingGroups"] == []
            autoscaling.create_auto_scaling_group(
                AutoScalingGroupName=name,
                MinSize=0, MaxSize=0, DesiredCapacity=0,
                LaunchTemplate={"LaunchTemplateId": template},
                AvailabilityZones=zones[:2],
            )
            cleanup.callback(
                autoscaling.delete_auto_scaling_group,
                AutoScalingGroupName=name, ForceDelete=True,
            )
            with pytest.raises(ClientError) as error:
                autoscaling.update_auto_scaling_group(
                    AutoScalingGroupName=name, MaxSize=3,
                    AvailabilityZones=[zones[0], unknown_zone],
                )
            assert error.value.response["Error"]["Code"] == "ValidationError"
            group = autoscaling.describe_auto_scaling_groups(
                AutoScalingGroupNames=[name]
            )["AutoScalingGroups"][0]
            assert group["AvailabilityZones"] == zones[:2]
            assert group["MaxSize"] == 0
            assert group["DesiredCapacity"] == 0
            assert group["Instances"] == []

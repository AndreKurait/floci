"""ASG scale-out placement, checked against live AWS on 2026-10-12."""

import time
import uuid
from collections import Counter
from contextlib import ExitStack, closing

import boto3
import pytest
from botocore.exceptions import ClientError


def _members_at_capacity(autoscaling, name, desired):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        group = autoscaling.describe_auto_scaling_groups(
            AutoScalingGroupNames=[name]
        )["AutoScalingGroups"][0]
        members = group["Instances"]
        if len(members) == desired and all(
            member["LifecycleState"] == "InService" for member in members
        ):
            return members
        time.sleep(0.5)
    raise AssertionError(f"ASG did not reach capacity {desired}: {group}")


def test_scale_out_balances_zones_and_prefers_more_subnet_addresses(
    ec2_client, aws_config, client_config
):
    """The compatibility server uses EC2 mock mode; placement is still real."""
    name = "placement-" + uuid.uuid4().hex[:12]
    region = aws_config["region_name"]
    zones = [region + "a", region + "b"]
    with closing(boto3.client("autoscaling", config=client_config, **aws_config)) as autoscaling:
        with ExitStack() as cleanup:
            vpc = ec2_client.create_vpc(CidrBlock="10.98.0.0/16")["Vpc"]["VpcId"]
            cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
            subnets = []
            for cidr, zone in [
                ("10.98.0.0/28", zones[0]),
                ("10.98.1.0/24", zones[0]),
                ("10.98.2.0/24", zones[1]),
            ]:
                created = ec2_client.create_subnet(
                    VpcId=vpc, CidrBlock=cidr, AvailabilityZone=zone
                )["Subnet"]
                subnet = created["SubnetId"]
                cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
                subnets.append(subnet)
                assert created["AvailableIpAddressCount"] == (11 if cidr.endswith("/28") else 251)
            image = ec2_client.register_image(
                Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1"
            )["ImageId"]
            cleanup.callback(ec2_client.deregister_image, ImageId=image)
            template = ec2_client.create_launch_template(
                LaunchTemplateName=name,
                LaunchTemplateData={"ImageId": image, "InstanceType": "t3.nano"},
            )["LaunchTemplate"]["LaunchTemplateId"]
            cleanup.callback(ec2_client.delete_launch_template, LaunchTemplateId=template)
            autoscaling.create_auto_scaling_group(
                AutoScalingGroupName=name,
                MinSize=0,
                MaxSize=4,
                DesiredCapacity=2,
                LaunchTemplate={"LaunchTemplateId": template, "Version": "1"},
                VPCZoneIdentifier=",".join(subnets),
            )
            cleanup.callback(
                autoscaling.delete_auto_scaling_group,
                AutoScalingGroupName=name,
                ForceDelete=True,
            )
            for desired in [2, 3, 4]:
                if desired > 2:
                    autoscaling.set_desired_capacity(
                        AutoScalingGroupName=name, DesiredCapacity=desired, HonorCooldown=False
                    )
                members = _members_at_capacity(autoscaling, name, desired)
                reservations = ec2_client.describe_instances(
                    InstanceIds=[member["InstanceId"] for member in members]
                )["Reservations"]
                actual = {
                    instance["InstanceId"]: instance
                    for reservation in reservations
                    for instance in reservation["Instances"]
                }
                counts = Counter(
                    instance["Placement"]["AvailabilityZone"] for instance in actual.values()
                )
                assert set(counts) == set(zones)
                assert sorted(counts.values()) == [desired // 2, (desired + 1) // 2]
                for member in members:
                    instance = actual[member["InstanceId"]]
                    assert member["AvailabilityZone"] == instance["Placement"]["AvailabilityZone"]
                    assert instance["SubnetId"] in subnets[1:]
            free_ips = {
                subnet["SubnetId"]: subnet["AvailableIpAddressCount"]
                for subnet in ec2_client.describe_subnets(SubnetIds=subnets)["Subnets"]
            }
            assert free_ips == {subnets[0]: 11, subnets[1]: 249, subnets[2]: 249}


def test_zone_only_groups_reject_unknown_zones_and_scale_in_advertised_zones(
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
            autoscaling.update_auto_scaling_group(
                AutoScalingGroupName=name, MaxSize=2, DesiredCapacity=2,
            )
            members = _members_at_capacity(autoscaling, name, 2)
            assert Counter(member["AvailabilityZone"] for member in members) == Counter(zones[:2])
            instances = [
                instance
                for reservation in ec2_client.describe_instances(
                    InstanceIds=[member["InstanceId"] for member in members]
                )["Reservations"]
                for instance in reservation["Instances"]
            ]
            assert Counter(
                instance["Placement"]["AvailabilityZone"] for instance in instances
            ) == Counter(zones[:2])

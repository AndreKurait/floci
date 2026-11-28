"""Attaching an instance registers it with the group's load balancers."""

import time
import uuid
from contextlib import ExitStack, closing

import boto3
import pytest


@pytest.mark.parametrize("backend", ["elbv2", "elb"])
def test_attached_instance_is_registered_before_in_service(ec2_client, aws_config, backend):
    name = "attach-lb-" + uuid.uuid4().hex[:12]
    zone = aws_config["region_name"] + "a"
    with closing(boto3.client("autoscaling", **aws_config)) as autoscaling, \
            closing(boto3.client(backend, **aws_config)) as balancer, ExitStack() as cleanup:
        vpc = ec2_client.create_vpc(CidrBlock="10.237.0.0/24")["Vpc"]["VpcId"]
        cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
        subnet = ec2_client.create_subnet(
            VpcId=vpc, CidrBlock="10.237.0.0/26", AvailabilityZone=zone)["Subnet"]["SubnetId"]
        cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
        image = ec2_client.register_image(
            Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1")["ImageId"]
        cleanup.callback(ec2_client.deregister_image, ImageId=image)
        template = ec2_client.create_launch_template(
            LaunchTemplateName=name, LaunchTemplateData={
                "ImageId": image, "InstanceType": "t3.nano",
            })["LaunchTemplate"]["LaunchTemplateId"]
        cleanup.callback(ec2_client.delete_launch_template, LaunchTemplateId=template)
        if backend == "elbv2":
            target_group = balancer.create_target_group(
                Name=name, Protocol="HTTP", Port=80, VpcId=vpc,
                TargetType="instance")["TargetGroups"][0]["TargetGroupArn"]
            cleanup.callback(balancer.delete_target_group, TargetGroupArn=target_group)
            registration = {"TargetGroupARNs": [target_group]}

            def registered():
                return [target["Target"]["Id"] for target in balancer.describe_target_health(
                    TargetGroupArn=target_group)["TargetHealthDescriptions"]]
        else:
            balancer.create_load_balancer(
                LoadBalancerName=name, Subnets=[subnet],
                Listeners=[{"Protocol": "HTTP", "LoadBalancerPort": 80,
                            "InstanceProtocol": "HTTP", "InstancePort": 80}])
            cleanup.callback(balancer.delete_load_balancer, LoadBalancerName=name)
            registration = {"LoadBalancerNames": [name]}

            def registered():
                return [instance["InstanceId"] for instance in balancer.describe_load_balancers(
                    LoadBalancerNames=[name])["LoadBalancerDescriptions"][0]["Instances"]]

        instance = ec2_client.run_instances(
            ImageId=image, InstanceType="t3.nano", SubnetId=subnet,
            MinCount=1, MaxCount=1)["Instances"][0]["InstanceId"]
        cleanup.callback(ec2_client.terminate_instances, InstanceIds=[instance])
        ec2_client.get_waiter("instance_running").wait(
            InstanceIds=[instance], WaiterConfig={"Delay": 1, "MaxAttempts": 120})
        autoscaling.create_auto_scaling_group(
            AutoScalingGroupName=name, MinSize=0, MaxSize=1, DesiredCapacity=0,
            VPCZoneIdentifier=subnet,
            LaunchTemplate={"LaunchTemplateId": template, "Version": "1"}, **registration)
        cleanup.callback(autoscaling.delete_auto_scaling_group,
                         AutoScalingGroupName=name, ForceDelete=True)
        assert registered() == []

        autoscaling.attach_instances(AutoScalingGroupName=name, InstanceIds=[instance])
        deadline = time.monotonic() + 60
        while True:
            group = autoscaling.describe_auto_scaling_groups(
                AutoScalingGroupNames=[name])["AutoScalingGroups"][0]
            assert group["DesiredCapacity"] == 1
            assert [member["InstanceId"] for member in group["Instances"]] == [instance]
            if group["Instances"][0]["LifecycleState"] == "InService":
                break
            assert time.monotonic() < deadline, group
            time.sleep(1)
        assert registered() == [instance]
        assert ec2_client.describe_instances(
            InstanceIds=[instance])["Reservations"][0]["Instances"][0]["State"]["Name"] == "running"

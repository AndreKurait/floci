"""Shared capacity admission through real RAM and EC2 SDK protocols."""
import time
import uuid

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError

from ec2_capacity_fixtures import client, describe

OWNER = "111111111111"
RECIPIENT = "222222222222"


def ram(aws_config, client_config, account):
    return boto3.client(
        "ram", **dict(aws_config, region_name="us-east-1", aws_access_key_id=account),
        config=client_config.merge(Config(proxies={}, retries={"total_max_attempts": 1})),
    )


@pytest.fixture
def shared(client, aws_config, client_config):
    owner = client()
    recipient = client(RECIPIENT)
    owner_ram = ram(aws_config, client_config, OWNER)
    recipient_ram = ram(aws_config, client_config, RECIPIENT)
    capacity = owner.create_capacity_reservation(
        InstanceType="t3.micro", InstancePlatform="Linux/UNIX",
        AvailabilityZone="us-east-1a", InstanceCount=1,
        InstanceMatchCriteria="targeted",
    )["CapacityReservation"]
    share = None
    instances = []
    try:
        share = owner_ram.create_resource_share(
            name="capacity-" + uuid.uuid4().hex, allowExternalPrincipals=True,
            principals=[RECIPIENT], resourceArns=[capacity["CapacityReservationArn"]],
        )["resourceShare"]["resourceShareArn"]
        yield owner, recipient, owner_ram, recipient_ram, capacity, share, instances
    finally:
        if instances:
            recipient.terminate_instances(InstanceIds=instances)
        if share:
            status = owner_ram.get_resource_shares(resourceOwner="SELF", resourceShareArns=[share])["resourceShares"][0]["status"]
            if status != "DELETED":
                owner_ram.delete_resource_share(resourceShareArn=share)
        owner.cancel_capacity_reservation(CapacityReservationId=capacity["CapacityReservationId"])
        owner.close()
        recipient.close()
        owner_ram.close()
        recipient_ram.close()


def accept(recipient_ram, share):
    invitation = recipient_ram.get_resource_share_invitations(
        resourceShareArns=[share])["resourceShareInvitations"][0]
    recipient_ram.accept_resource_share_invitation(
        resourceShareInvitationArn=invitation["resourceShareInvitationArn"])


def launch(recipient, capacity_id, **kwargs):
    return recipient.run_instances(
        ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
        Placement={"AvailabilityZone": recipient.meta.region_name + "a"},
        CapacityReservationSpecification={"CapacityReservationTarget": {
            "CapacityReservationId": capacity_id}}, **kwargs,
    )["Instances"][0]


def test_acceptance_target_and_release_after_share_deletion(shared):
    owner, recipient, owner_ram, recipient_ram, capacity, share, instances = shared
    capacity_id = capacity["CapacityReservationId"]
    with pytest.raises(ClientError) as failure:
        launch(recipient, capacity_id)
    assert failure.value.response["Error"]["Code"] == "InvalidCapacityReservationId.NotFound"
    assert describe(owner, capacity_id)["AvailableInstanceCount"] == 1
    accept(recipient_ram, share)
    instance = launch(recipient, capacity_id)
    instances.append(instance["InstanceId"])
    assert instance["CapacityReservationId"] == capacity_id
    assert describe(owner, capacity_id)["AvailableInstanceCount"] == 0
    assert describe(recipient, capacity_id)["OwnerId"] == OWNER
    owner_ram.delete_resource_share(resourceShareArn=share)
    current = recipient.describe_instances(InstanceIds=instances)["Reservations"][0]["Instances"][0]
    assert current["CapacityReservationId"] == capacity_id
    recipient.stop_instances(InstanceIds=instances)
    assert describe(owner, capacity_id)["AvailableInstanceCount"] == 1
    with pytest.raises(ClientError):
        recipient.start_instances(InstanceIds=instances)
    assert describe(owner, capacity_id)["AvailableInstanceCount"] == 1


@pytest.mark.parametrize("action", ["cancel", "modify", "move", "region", "uninvited"])
def test_shared_visibility_does_not_grant_owner_or_other_scope_access(shared, client, action):
    owner, recipient, _, recipient_ram, capacity, share, _ = shared
    capacity_id = capacity["CapacityReservationId"]
    accept(recipient_ram, share)
    with pytest.raises(ClientError) as failure:
        if action == "cancel":
            recipient.cancel_capacity_reservation(CapacityReservationId=capacity_id)
        elif action == "modify":
            recipient.modify_capacity_reservation(CapacityReservationId=capacity_id, InstanceCount=2)
        elif action == "move":
            recipient.move_capacity_reservation_instances(SourceCapacityReservationId=capacity_id,
                                                          DestinationCapacityReservationId="cr-11111111111111111",
                                                          InstanceCount=1)
        else:
            other = client("333333333333" if action == "uninvited" else RECIPIENT,
                           "us-west-2" if action == "region" else "us-east-1")
            try:
                launch(other, capacity_id)
            finally:
                other.close()
    assert failure.value.response["Error"]["Code"] == "InvalidCapacityReservationId.NotFound"
    assert describe(owner, capacity_id)["AvailableInstanceCount"] == 1


@pytest.mark.parametrize("consumer", ["fleet", "autoscaling"])
def test_shared_target_from_launch_template_consumers(shared, aws_config, client_config, consumer):
    owner, recipient, _, recipient_ram, capacity, share, instances = shared
    capacity_id = capacity["CapacityReservationId"]
    accept(recipient_ram, share)
    template = recipient.create_launch_template(
        LaunchTemplateName="shared-" + uuid.uuid4().hex,
        LaunchTemplateData={"ImageId": "ami-test", "InstanceType": "t3.micro",
                            "Placement": {"AvailabilityZone": "us-east-1a"},
                            "CapacityReservationSpecification": {"CapacityReservationTarget": {
                                "CapacityReservationId": capacity_id}}},
    )["LaunchTemplate"]["LaunchTemplateId"]
    group_name = "shared-" + uuid.uuid4().hex
    autoscaling = boto3.client(
        "autoscaling", **dict(aws_config, region_name="us-east-1", aws_access_key_id=RECIPIENT),
        config=client_config.merge(Config(proxies={}, retries={"total_max_attempts": 1})),
    )
    try:
        if consumer == "autoscaling":
            autoscaling.create_auto_scaling_group(
                AutoScalingGroupName=group_name, MinSize=1, MaxSize=1, DesiredCapacity=1,
                AvailabilityZones=["us-east-1a"],
                LaunchTemplate={"LaunchTemplateId": template, "Version": "$Latest"},
            )
            deadline = time.monotonic() + 30
            while not instances:
                group = autoscaling.describe_auto_scaling_groups(
                    AutoScalingGroupNames=[group_name])["AutoScalingGroups"][0]
                instances.extend(i["InstanceId"] for i in group["Instances"])
                if time.monotonic() >= deadline:
                    pytest.fail("Auto Scaling did not admit the shared-capacity instance")
                if not instances:
                    time.sleep(0.1)
        else:
            result = recipient.create_fleet(
                Type="instant", LaunchTemplateConfigs=[{"LaunchTemplateSpecification": {
                    "LaunchTemplateId": template, "Version": "$Latest"}}],
                TargetCapacitySpecification={"TotalTargetCapacity": 1, "DefaultTargetCapacityType": "on-demand"},
            )
            instances.extend(i for group in result["Instances"] for i in group["InstanceIds"])
        assert len(instances) == 1
        current = recipient.describe_instances(InstanceIds=instances)["Reservations"][0]["Instances"][0]
        assert current["CapacityReservationId"] == capacity_id
        assert describe(owner, capacity_id)["AvailableInstanceCount"] == 0
    finally:
        if consumer == "autoscaling":
            autoscaling.delete_auto_scaling_group(AutoScalingGroupName=group_name, ForceDelete=True)
        autoscaling.close()
        recipient.delete_launch_template(LaunchTemplateId=template)


def test_accepting_open_share_adopts_existing_recipient_instance(shared):
    owner, recipient, _, recipient_ram, capacity, share, instances = shared
    capacity_id = capacity["CapacityReservationId"]
    owner.modify_capacity_reservation(CapacityReservationId=capacity_id, InstanceMatchCriteria="open")
    instance = recipient.run_instances(
        ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
        Placement={"AvailabilityZone": "us-east-1a"},
        CapacityReservationSpecification={"CapacityReservationPreference": "open"},
    )["Instances"][0]
    instances.append(instance["InstanceId"])
    assert "CapacityReservationId" not in instance
    running = recipient.describe_instances(InstanceIds=instances)["Reservations"][0]["Instances"][0]
    assert running["State"]["Name"] == "running"
    assert describe(owner, capacity_id)["AvailableInstanceCount"] == 1
    accept(recipient_ram, share)
    assert describe(owner, capacity_id)["AvailableInstanceCount"] == 0
    current = recipient.describe_instances(InstanceIds=instances)["Reservations"][0]["Instances"][0]
    assert current["CapacityReservationId"] == capacity_id

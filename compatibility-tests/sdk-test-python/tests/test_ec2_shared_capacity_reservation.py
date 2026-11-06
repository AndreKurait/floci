"""Shared capacity admission through RAM and EC2 SDKs with issued account credentials."""
from contextlib import ExitStack, closing
from types import SimpleNamespace
import time
import uuid

import boto3
import pytest
from botocore.exceptions import ClientError

from test_lambda_autoscaling_accounts import member_account_configs


def describe(ec2, capacity_id):
    return ec2.describe_capacity_reservations(
        CapacityReservationIds=[capacity_id])["CapacityReservations"][0]


def cancel(ec2, capacity_id):
    if describe(ec2, capacity_id)["State"] == "active":
        ec2.cancel_capacity_reservation(CapacityReservationId=capacity_id)
    assert describe(ec2, capacity_id)["State"] == "cancelled"


def remove_share(ram, share):
    records = ram.get_resource_shares(
        resourceOwner="SELF", resourceShareArns=[share])["resourceShares"]
    if records and records[0]["status"] != "DELETED":
        ram.delete_resource_share(resourceShareArn=share)


@pytest.fixture
def shared(aws_config, client_config, unique_name):
    with ExitStack() as clients:
        configs = clients.enter_context(member_account_configs(aws_config, client_config, unique_name))
        owner_id, recipient_id = tuple(configs)

        def client(service, config):
            return clients.enter_context(closing(boto3.client(
                service, config=client_config, **config)))

        owner = client("ec2", configs[owner_id])
        recipient = client("ec2", configs[recipient_id])
        owner_ram = client("ram", configs[owner_id])
        recipient_ram = client("ram", configs[recipient_id])
        scope = SimpleNamespace(
            owner=owner, recipient=recipient, owner_ram=owner_ram, recipient_ram=recipient_ram,
            owner_id=owner_id, recipient_id=recipient_id, recipient_config=configs[recipient_id],
            uninvited_config=aws_config, client=client, instances=[], images=[], capacities=[],
            shares=[], groups=[], group_client=client("resource-groups", configs[recipient_id]),
        )
        try:
            zone = next(zone for zone in recipient.describe_availability_zones()["AvailabilityZones"]
                        if zone["State"] == "available" and zone["ZoneType"] == "availability-zone")
            image = recipient.register_image(
                Name=unique_name, Architecture="x86_64", RootDeviceName="/dev/sda1")["ImageId"]
            scope.images.append((recipient, image))
            scope.launch = dict(
                ImageId=image, InstanceType="t3.micro", MinCount=1, MaxCount=1,
                Placement={"AvailabilityZone": zone["ZoneName"]},
            )
            scope.capacity = owner.create_capacity_reservation(
                InstanceType="t3.micro", InstancePlatform="Linux/UNIX",
                AvailabilityZoneId=zone["ZoneId"], InstanceCount=1, InstanceMatchCriteria="targeted",
            )["CapacityReservation"]
            assert scope.capacity["OwnerId"] == owner_id
            scope.capacities.append((owner, scope.capacity["CapacityReservationId"]))
            scope.share = owner_ram.create_resource_share(
                name=unique_name, allowExternalPrincipals=True, principals=[recipient_id],
                resourceArns=[scope.capacity["CapacityReservationArn"]],
            )["resourceShare"]["resourceShareArn"]
            scope.shares.append(scope.share)
            yield scope
        finally:
            with ExitStack() as cleanup:
                for ec2, image in scope.images:
                    cleanup.callback(ec2.deregister_image, ImageId=image)
                for ec2, capacity_id in scope.capacities:
                    cleanup.callback(cancel, ec2, capacity_id)
                for share in scope.shares:
                    cleanup.callback(remove_share, owner_ram, share)
                for group in scope.groups:
                    cleanup.callback(scope.group_client.delete_group, Group=group)
                if scope.instances:
                    cleanup.callback(recipient.get_waiter("instance_terminated").wait,
                                     InstanceIds=scope.instances,
                                     WaiterConfig={"Delay": 1, "MaxAttempts": 30})
                    cleanup.callback(recipient.terminate_instances, InstanceIds=scope.instances)


def accept(recipient_ram, share):
    invitation = recipient_ram.get_resource_share_invitations(
        resourceShareArns=[share])["resourceShareInvitations"][0]
    assert invitation["status"] == "PENDING"
    recipient_ram.accept_resource_share_invitation(
        resourceShareInvitationArn=invitation["resourceShareInvitationArn"])


def launch(recipient, capacity_id, arguments):
    return recipient.run_instances(
        **arguments, CapacityReservationSpecification={
            "CapacityReservationTarget": {"CapacityReservationId": capacity_id}},
    )["Instances"][0]


def test_acceptance_target_and_release_after_share_deletion(shared):
    capacity_id = shared.capacity["CapacityReservationId"]
    with pytest.raises(ClientError) as failure:
        launch(shared.recipient, capacity_id, shared.launch)
    assert failure.value.response["Error"]["Code"] == "InvalidCapacityReservationId.NotFound"
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 1
    accept(shared.recipient_ram, shared.share)
    instance = launch(shared.recipient, capacity_id, shared.launch)
    shared.instances.append(instance["InstanceId"])
    assert instance["CapacityReservationId"] == capacity_id
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 0
    assert describe(shared.recipient, capacity_id)["OwnerId"] == shared.owner_id
    shared.owner_ram.delete_resource_share(resourceShareArn=shared.share)
    current = shared.recipient.describe_instances(
        InstanceIds=shared.instances)["Reservations"][0]["Instances"][0]
    assert current["CapacityReservationId"] == capacity_id
    shared.recipient.stop_instances(InstanceIds=shared.instances)
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 1
    with pytest.raises(ClientError):
        shared.recipient.start_instances(InstanceIds=shared.instances)
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 1


@pytest.mark.parametrize("action", ["cancel", "modify", "move", "region", "uninvited"])
def test_shared_visibility_does_not_grant_owner_or_other_scope_access(shared, action):
    capacity_id = shared.capacity["CapacityReservationId"]
    accept(shared.recipient_ram, shared.share)
    with pytest.raises(ClientError) as failure:
        if action == "cancel":
            shared.recipient.cancel_capacity_reservation(CapacityReservationId=capacity_id)
        elif action == "modify":
            shared.recipient.modify_capacity_reservation(CapacityReservationId=capacity_id, InstanceCount=2)
        elif action == "move":
            shared.recipient.move_capacity_reservation_instances(
                SourceCapacityReservationId=capacity_id,
                DestinationCapacityReservationId="cr-11111111111111111", InstanceCount=1)
        else:
            config = dict(shared.uninvited_config if action == "uninvited" else shared.recipient_config)
            if action == "region":
                config["region_name"] = "us-west-2" if config["region_name"] != "us-west-2" else "us-east-1"
            other = shared.client("ec2", config)
            image = other.register_image(
                Name="unshared-" + uuid.uuid4().hex, Architecture="x86_64", RootDeviceName="/dev/sda1")["ImageId"]
            shared.images.append((other, image))
            zone = other.describe_availability_zones()["AvailabilityZones"][0]["ZoneName"]
            launch(other, capacity_id, dict(shared.launch, ImageId=image, Placement={"AvailabilityZone": zone}))
    assert failure.value.response["Error"]["Code"] == "InvalidCapacityReservationId.NotFound"
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 1


@pytest.mark.parametrize("consumer", ["fleet", "autoscaling"])
def test_shared_target_from_launch_template_consumers(shared, consumer):
    capacity_id = shared.capacity["CapacityReservationId"]
    accept(shared.recipient_ram, shared.share)
    with ExitStack() as cleanup:
        template = shared.recipient.create_launch_template(
            LaunchTemplateName="shared-" + uuid.uuid4().hex,
            LaunchTemplateData={
                **{key: shared.launch[key] for key in ("ImageId", "InstanceType", "Placement")},
                "CapacityReservationSpecification": {
                    "CapacityReservationTarget": {"CapacityReservationId": capacity_id}}},
        )["LaunchTemplate"]["LaunchTemplateId"]
        cleanup.callback(shared.recipient.delete_launch_template, LaunchTemplateId=template)
        if consumer == "autoscaling":
            autoscaling = shared.client("autoscaling", shared.recipient_config)
            group_name = "shared-" + uuid.uuid4().hex
            autoscaling.create_auto_scaling_group(
                AutoScalingGroupName=group_name, MinSize=1, MaxSize=1, DesiredCapacity=1,
                AvailabilityZones=[shared.launch["Placement"]["AvailabilityZone"]],
                LaunchTemplate={"LaunchTemplateId": template, "Version": "$Latest"},
            )
            cleanup.callback(autoscaling.delete_auto_scaling_group,
                             AutoScalingGroupName=group_name, ForceDelete=True)
            deadline = time.monotonic() + 30
            while not shared.instances:
                group = autoscaling.describe_auto_scaling_groups(
                    AutoScalingGroupNames=[group_name])["AutoScalingGroups"][0]
                shared.instances.extend(instance["InstanceId"] for instance in group["Instances"])
                assert time.monotonic() < deadline, "Auto Scaling did not admit the shared-capacity instance"
                if not shared.instances:
                    time.sleep(0.1)
        else:
            result = shared.recipient.create_fleet(
                Type="instant", LaunchTemplateConfigs=[{"LaunchTemplateSpecification": {
                    "LaunchTemplateId": template, "Version": "$Latest"}}],
                TargetCapacitySpecification={"TotalTargetCapacity": 1, "DefaultTargetCapacityType": "on-demand"},
            )
            shared.instances.extend(instance for group in result["Instances"] for instance in group["InstanceIds"])
        assert len(shared.instances) == 1
        current = shared.recipient.describe_instances(
            InstanceIds=shared.instances)["Reservations"][0]["Instances"][0]
        assert current["CapacityReservationId"] == capacity_id
        assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 0


def test_accepting_open_share_adopts_existing_recipient_instance(shared):
    capacity_id = shared.capacity["CapacityReservationId"]
    shared.owner.modify_capacity_reservation(CapacityReservationId=capacity_id, InstanceMatchCriteria="open")
    instance = shared.recipient.run_instances(
        **shared.launch, CapacityReservationSpecification={"CapacityReservationPreference": "open"},
    )["Instances"][0]
    shared.instances.append(instance["InstanceId"])
    assert "CapacityReservationId" not in instance
    running = shared.recipient.describe_instances(
        InstanceIds=shared.instances)["Reservations"][0]["Instances"][0]
    assert running["State"]["Name"] == "running"
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 1
    accept(shared.recipient_ram, shared.share)
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 0
    current = shared.recipient.describe_instances(
        InstanceIds=shared.instances)["Reservations"][0]["Instances"][0]
    assert current["CapacityReservationId"] == capacity_id


def test_accepted_open_share_does_not_capture_a_group_targeted_instance(shared):
    capacity_id = shared.capacity["CapacityReservationId"]
    shared.owner.modify_capacity_reservation(CapacityReservationId=capacity_id, InstanceMatchCriteria="open")
    group = shared.group_client.create_group(
        Name="shared-" + uuid.uuid4().hex, Configuration=[
            {"Type": "AWS::EC2::CapacityReservationPool"},
            {"Type": "AWS::ResourceGroups::Generic", "Parameters": [
                {"Name": "allowed-resource-types", "Values": ["AWS::EC2::CapacityReservation"]},
            ]},
        ],
    )["Group"]["GroupArn"]
    shared.groups.append(group)
    instance = shared.recipient.run_instances(
        **shared.launch, CapacityReservationSpecification={
            "CapacityReservationTarget": {"CapacityReservationResourceGroupArn": group}},
    )["Instances"][0]
    shared.instances.append(instance["InstanceId"])
    accept(shared.recipient_ram, shared.share)
    current = shared.recipient.describe_instances(
        InstanceIds=shared.instances)["Reservations"][0]["Instances"][0]
    assert current["State"]["Name"] == "running"
    assert "CapacityReservationId" not in current
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 1


def test_owned_open_reservation_does_not_double_count_a_shared_member(shared):
    capacity_id = shared.capacity["CapacityReservationId"]
    shared.owner.modify_capacity_reservation(CapacityReservationId=capacity_id, InstanceMatchCriteria="open")
    accept(shared.recipient_ram, shared.share)
    instance = shared.recipient.run_instances(
        **shared.launch, CapacityReservationSpecification={
            "CapacityReservationPreference": "capacity-reservations-only"},
    )["Instances"][0]
    shared.instances.append(instance["InstanceId"])
    assert instance["CapacityReservationId"] == capacity_id
    owned = shared.recipient.create_capacity_reservation(
        InstanceType="t3.micro", InstancePlatform="Linux/UNIX", InstanceCount=1,
        AvailabilityZone=shared.launch["Placement"]["AvailabilityZone"], InstanceMatchCriteria="open",
    )["CapacityReservation"]
    shared.capacities.append((shared.recipient, owned["CapacityReservationId"]))
    assert owned["AvailableInstanceCount"] == 1
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 0

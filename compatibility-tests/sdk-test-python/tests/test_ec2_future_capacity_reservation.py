"""Future reservations retain their scheduling metadata and client-token identity."""

from datetime import datetime, timedelta, timezone

import pytest
from botocore.exceptions import ClientError


def test_future_reservation_is_scheduled_and_replayed(ec2_client, unique_name):
    zone = ec2_client.describe_availability_zones()["AvailabilityZones"][0]["ZoneName"]
    start = (datetime.now(timezone.utc) + timedelta(days=7)).replace(microsecond=0)
    request = {
        "InstanceType": "t3.micro", "InstancePlatform": "Linux/UNIX", "InstanceCount": 2,
        "AvailabilityZone": zone, "ClientToken": unique_name,
        "StartDate": start, "CommitmentDuration": 86400, "DeliveryPreference": "incremental",
        "TagSpecifications": [
            {"ResourceType": "capacity-reservation", "Tags": [{"Key": "Name", "Value": unique_name}]}
        ],
    }
    created = ec2_client.create_capacity_reservation(**request)["CapacityReservation"]
    identifier = created["CapacityReservationId"]
    try:
        assert created["State"] == "scheduled"
        assert created["StartDate"] == start
        assert created["AvailableInstanceCount"] == 0
        assert created["DeliveryPreference"] == "incremental"
        assert created["CommitmentInfo"] == {
            "CommitmentDuration": 86400, "CommitmentEndDate": start + timedelta(days=1),
            "CommittedInstanceCount": 2,
        }
        replay = ec2_client.create_capacity_reservation(**request)["CapacityReservation"]
        assert replay == created
        with pytest.raises(ClientError) as error:
            ec2_client.create_capacity_reservation(**{**request, "CommitmentDuration": 86401})
        assert error.value.response["Error"]["Code"] == "IdempotentParameterMismatch"
        described = ec2_client.describe_capacity_reservations(
            Filters=[{"Name": "tag:Name", "Values": [unique_name]}]
        )["CapacityReservations"]
        assert described == [created]
    finally:
        ec2_client.cancel_capacity_reservation(CapacityReservationId=identifier)


def test_invalid_schedule_does_not_create_capacity(ec2_client, unique_name):
    request = {
        "InstanceType": "t3.micro", "InstancePlatform": "Linux/UNIX", "InstanceCount": 1,
        "AvailabilityZone": ec2_client.describe_availability_zones()["AvailabilityZones"][0]["ZoneName"],
        "ClientToken": unique_name,
        "StartDate": datetime.now(timezone.utc) + timedelta(days=7),
        "CommitmentDuration": 86400, "DeliveryPreference": "fixed",
        "TagSpecifications": [
            {"ResourceType": "capacity-reservation", "Tags": [{"Key": "Name", "Value": unique_name}]}
        ],
    }
    with pytest.raises(ClientError) as error:
        ec2_client.create_capacity_reservation(**request)
    assert error.value.response["Error"]["Code"] == "InvalidParameterValue"
    assert ec2_client.describe_capacity_reservations(
        Filters=[{"Name": "tag:Name", "Values": [unique_name]}]
    )["CapacityReservations"] == []

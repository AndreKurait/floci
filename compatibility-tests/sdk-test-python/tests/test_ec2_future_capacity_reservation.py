"""Unmodeled future reservations must not silently reserve immediate capacity."""

from datetime import datetime, timedelta, timezone

import pytest
from botocore.exceptions import ClientError


def test_future_reservation_is_rejected_before_capacity_changes(ec2_client, unique_name):
    zone = ec2_client.describe_availability_zones()["AvailabilityZones"][0]["ZoneName"]
    request = {
        "InstanceType": "t3.micro",
        "InstancePlatform": "Linux/UNIX",
        "InstanceCount": 1,
        "AvailabilityZone": zone,
        "ClientToken": unique_name,
        "TagSpecifications": [
            {"ResourceType": "capacity-reservation", "Tags": [{"Key": "Name", "Value": unique_name}]}
        ],
    }
    future = {**request, "StartDate": datetime.now(timezone.utc) + timedelta(days=7)}

    def selected():
        return ec2_client.describe_capacity_reservations(
            Filters=[{"Name": "tag:Name", "Values": [unique_name]}]
        )["CapacityReservations"]

    def refused():
        with pytest.raises(ClientError) as error:
            ec2_client.create_capacity_reservation(**future)
        assert error.value.response["Error"]["Code"] == "Unsupported"
        assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400

    assert selected() == []
    refused()
    assert selected() == []
    immediate = ec2_client.create_capacity_reservation(**request)["CapacityReservation"]
    try:
        assert immediate["State"] == "active"
        before = selected()
        refused()
        assert selected() == before
    finally:
        ec2_client.cancel_capacity_reservation(
            CapacityReservationId=immediate["CapacityReservationId"]
        )

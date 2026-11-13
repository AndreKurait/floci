"""Capacity reservation retries retain identity without replaying old mutations."""

from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Lock

import pytest
from botocore.exceptions import ClientError


@pytest.fixture
def reservation_requests(ec2_client, unique_name):
    zone = ec2_client.describe_availability_zones(
        Filters=[{"Name": "state", "Values": ["available"]}]
    )["AvailabilityZones"][0]["ZoneName"]
    request = {
        "InstanceType": "t3.nano",
        "InstancePlatform": "Linux/UNIX",
        "AvailabilityZone": zone,
        "InstanceCount": 2,
        "InstanceMatchCriteria": "targeted",
        "ClientToken": unique_name,
        "TagSpecifications": [{
            "ResourceType": "capacity-reservation",
            "Tags": [{"Key": "purpose", "Value": "original"}],
        }],
    }
    created = set()
    lock = Lock()

    def create(**changes):
        result = ec2_client.create_capacity_reservation(
            **{**request, **changes}
        )["CapacityReservation"]
        with lock:
            created.add(result["CapacityReservationId"])
        return result

    try:
        yield create
    finally:
        for reservation_id in created:
            ec2_client.cancel_capacity_reservation(CapacityReservationId=reservation_id)


def test_identical_retry_returns_same_reservation(reservation_requests):
    original = reservation_requests()
    replay = reservation_requests()
    for key in ("CapacityReservationId", "CapacityReservationArn", "CreateDate"):
        assert replay[key] == original[key]
    assert replay["TotalInstanceCount"] == 2
    assert replay["Tags"] == [{"Key": "purpose", "Value": "original"}]


def test_replay_preserves_later_count_and_tag_changes(ec2_client, reservation_requests):
    original = reservation_requests()
    reservation_id = original["CapacityReservationId"]
    ec2_client.modify_capacity_reservation(
        CapacityReservationId=reservation_id, InstanceCount=3
    )
    ec2_client.create_tags(
        Resources=[reservation_id], Tags=[{"Key": "purpose", "Value": "updated"}]
    )
    replay = reservation_requests()
    assert replay["CapacityReservationId"] == reservation_id
    described = ec2_client.describe_capacity_reservations(
        CapacityReservationIds=[reservation_id]
    )["CapacityReservations"][0]
    assert described["TotalInstanceCount"] == 3
    assert described["Tags"] == [{"Key": "purpose", "Value": "updated"}]


@pytest.mark.parametrize("changes", [
    {"InstanceCount": 3},
    {"InstanceType": "t3.micro"},
    {"TagSpecifications": [{
        "ResourceType": "capacity-reservation",
        "Tags": [{"Key": "purpose", "Value": "different"}],
    }]},
])
def test_reused_token_rejects_changed_parameters(ec2_client, reservation_requests, changes):
    original = reservation_requests()
    with pytest.raises(ClientError) as error:
        reservation_requests(**changes)
    assert error.value.response["Error"]["Code"] == "IdempotentParameterMismatch"
    assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
    described = ec2_client.describe_capacity_reservations(
        CapacityReservationIds=[original["CapacityReservationId"]]
    )["CapacityReservations"][0]
    assert described["TotalInstanceCount"] == 2
    assert described["Tags"] == [{"Key": "purpose", "Value": "original"}]


def test_concurrent_retries_create_one_reservation(reservation_requests):
    start = Barrier(8)

    def create(_):
        start.wait(timeout=10)
        return reservation_requests()["CapacityReservationId"]

    with ThreadPoolExecutor(max_workers=8) as executor:
        ids = list(executor.map(create, range(8)))
    assert len(set(ids)) == 1

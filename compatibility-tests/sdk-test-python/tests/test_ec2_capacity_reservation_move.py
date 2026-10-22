"""Exercise capacity transfers through the EC2 SDK against an isolated Floci image."""
import json
import os
import subprocess
import time
import urllib.request
import uuid

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError

IMAGE = os.environ.get("FLOCI_CAPACITY_MOVE_TEST_IMAGE")
pytestmark = pytest.mark.skipif(not IMAGE, reason="Set FLOCI_CAPACITY_MOVE_TEST_IMAGE for Docker checks")


def docker(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


@pytest.fixture(scope="module")
def endpoint():
    name = "floci-capacity-move-" + uuid.uuid4().hex[:12]
    container = docker(
        "run", "-d", "--name", name, "--publish", "127.0.0.1::4566",
        "--env", "FLOCI_SERVICES_EC2_MOCK=true",
        "--env", "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED=false",
        "--env", "FLOCI_STORAGE_MODE=memory", IMAGE,
    )
    try:
        port = json.loads(docker("inspect", container))[0]["NetworkSettings"]["Ports"]["4566/tcp"][0]["HostPort"]
        url = "http://127.0.0.1:" + port
        deadline = time.monotonic() + 60
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while True:
            try:
                with opener.open(url + "/_localstack/health", timeout=2) as response:
                    json.load(response)
                break
            except (OSError, ValueError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)
        yield url
    finally:
        docker("rm", "-f", container)
        assert not docker("ps", "-aq", "--filter", "id=" + container)


def client(endpoint, account="111111111111", region="us-east-1"):
    return boto3.client(
        "ec2", endpoint_url=endpoint, region_name=region,
        aws_access_key_id=account, aws_secret_access_key="test",
        config=Config(proxies={}, connect_timeout=2, read_timeout=5,
                      retries={"total_max_attempts": 1}),
    )


@pytest.fixture
def reservations(endpoint):
    ec2 = client(endpoint)
    created = []

    def create(count, **overrides):
        arguments = dict(InstanceType="t3.micro", InstancePlatform="Linux/UNIX",
                         AvailabilityZone="us-east-1a", InstanceCount=count)
        arguments.update(overrides)
        if "AvailabilityZoneId" in overrides:
            arguments.pop("AvailabilityZone")
        reservation = ec2.create_capacity_reservation(**arguments)["CapacityReservation"]
        created.append(reservation["CapacityReservationId"])
        return reservation["CapacityReservationId"]

    try:
        yield ec2, create
    finally:
        for reservation_id in created:
            record = describe(ec2, reservation_id)
            if record["State"] == "active":
                ec2.cancel_capacity_reservation(CapacityReservationId=reservation_id)
            assert describe(ec2, reservation_id)["State"] == "cancelled"


def describe(ec2, reservation_id):
    return ec2.describe_capacity_reservations(
        CapacityReservationIds=[reservation_id])["CapacityReservations"][0]


def test_move_counts_and_cancels_empty_source(reservations):
    ec2, create = reservations
    source, destination = create(3), create(2, InstanceMatchCriteria="targeted")
    for count, source_count, destination_count in ((1, 2, 3), (2, 0, 5)):
        response = ec2.move_capacity_reservation_instances(
            SourceCapacityReservationId=source, DestinationCapacityReservationId=destination,
            InstanceCount=count, ClientToken=uuid.uuid4().hex)
        assert response["InstanceCount"] == count
        for key, reservation_id, expected in (
                ("SourceCapacityReservation", source, source_count),
                ("DestinationCapacityReservation", destination, destination_count)):
            record = response[key]
            assert record["CapacityReservationId"] == reservation_id
            assert record["TotalInstanceCount"] == expected
            assert record["AvailableInstanceCount"] == expected
            assert record["State"] == ("active" if expected else "cancelled")
            assert record == describe(ec2, reservation_id)
    assert describe(ec2, destination)["InstanceMatchCriteria"] == "targeted"


def test_retry_after_cancellation_does_not_move_twice(reservations):
    ec2, create = reservations
    source, destination = create(2), create(1)
    arguments = dict(SourceCapacityReservationId=source,
                     DestinationCapacityReservationId=destination,
                     InstanceCount=2, ClientToken=uuid.uuid4().hex)
    first = ec2.move_capacity_reservation_instances(**arguments)
    again = ec2.move_capacity_reservation_instances(**arguments)
    for key in ("SourceCapacityReservation", "DestinationCapacityReservation", "InstanceCount"):
        assert again[key] == first[key]
    with pytest.raises(ClientError) as mismatch:
        ec2.move_capacity_reservation_instances(**{**arguments, "InstanceCount": 1})
    assert mismatch.value.response["Error"]["Code"] == "IdempotentParameterMismatch"
    assert describe(ec2, destination)["TotalInstanceCount"] == 3


@pytest.mark.parametrize("overrides", [
    {"InstanceType": "t3.small"}, {"InstancePlatform": "Windows"},
    {"AvailabilityZone": "us-east-1b"}, {"Tenancy": "dedicated"},
    {"PlacementGroupArn": "arn:aws:ec2:us-east-1:111111111111:placement-group/other"},
])
def test_incompatible_reservations_leave_counts_unchanged(reservations, overrides):
    ec2, create = reservations
    source, destination = create(2), create(1, **overrides)
    before = [describe(ec2, resource) for resource in (source, destination)]
    with pytest.raises(ClientError) as rejected:
        ec2.move_capacity_reservation_instances(
            SourceCapacityReservationId=source, DestinationCapacityReservationId=destination,
            InstanceCount=1)
    assert rejected.value.response["Error"]["Code"] == "InvalidParameterValue"
    assert [describe(ec2, resource) for resource in (source, destination)] == before


def test_dry_run_preserves_capacity_and_does_not_consume_token(reservations):
    ec2, create = reservations
    source, destination = create(2), create(1)
    arguments = dict(SourceCapacityReservationId=source,
                     DestinationCapacityReservationId=destination,
                     InstanceCount=1, ClientToken=uuid.uuid4().hex)
    with pytest.raises(ClientError) as dry_run:
        ec2.move_capacity_reservation_instances(**arguments, DryRun=True)
    assert dry_run.value.response["Error"]["Code"] == "DryRunOperation"
    assert describe(ec2, source)["TotalInstanceCount"] == 2
    assert describe(ec2, destination)["TotalInstanceCount"] == 1
    ec2.move_capacity_reservation_instances(**arguments)
    assert describe(ec2, destination)["TotalInstanceCount"] == 2


def test_zone_name_and_zone_id_identify_same_zone(reservations):
    ec2, create = reservations
    source, destination = create(2), create(1, AvailabilityZoneId="use1-az1")
    response = ec2.move_capacity_reservation_instances(
        SourceCapacityReservationId=source, DestinationCapacityReservationId=destination,
        InstanceCount=1)
    assert response["DestinationCapacityReservation"]["TotalInstanceCount"] == 2


@pytest.mark.parametrize("account,region", [
    ("222222222222", "us-east-1"), ("111111111111", "us-west-2"),
])
def test_cannot_move_another_account_or_regions_capacity(endpoint, reservations, account, region):
    ec2, create = reservations
    source, destination = create(2), create(1)
    other = client(endpoint, account, region)
    with pytest.raises(ClientError) as missing:
        other.move_capacity_reservation_instances(
            SourceCapacityReservationId=source, DestinationCapacityReservationId=destination,
            InstanceCount=1)
    assert missing.value.response["Error"]["Code"] == "InvalidCapacityReservationId.NotFound"
    assert describe(ec2, source)["TotalInstanceCount"] == 2
    assert describe(ec2, destination)["TotalInstanceCount"] == 1

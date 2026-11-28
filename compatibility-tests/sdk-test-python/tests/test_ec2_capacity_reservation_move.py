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
        docker("rm", "-f", "-v", container)
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
        for specification in arguments.get("TagSpecifications", []):
            if specification["ResourceType"] == "capacity-reservation":
                assert reservation["Tags"] == specification["Tags"]
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


@pytest.mark.parametrize("count,code", [
    (0, "DryRunOperation"),
    (-1, "DryRunOperation"),
    (1001, "DryRunOperation"),
    (2147483647, "DryRunOperation"),
    (2147483648, "InvalidParameterValue"),
    (-2147483649, "InvalidParameterValue"),
])
def test_dry_run_distinguishes_integer_parsing_from_domain_validation(endpoint, count, code):
    with pytest.raises(ClientError) as error:
        client(endpoint).move_capacity_reservation_instances(
            SourceCapacityReservationId="bad", DestinationCapacityReservationId="bad",
            InstanceCount=count, DryRun=True)
    assert error.value.response["Error"]["Code"] == code


def test_move_counts_and_cancels_empty_source(reservations):
    ec2, create = reservations
    tags = [{"ResourceType": "capacity-reservation", "Tags": [{"Key": "probe", "Value": "move"}]}]
    source = create(3, AvailabilityZoneId="use1-az1", TagSpecifications=tags)
    destination = create(2, AvailabilityZoneId="use1-az1",
                         InstanceMatchCriteria="targeted", TagSpecifications=tags)
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
            described = describe(ec2, reservation_id)
            for omitted in ("CapacityReservationArn", "AvailabilityZoneId", "Tags"):
                assert omitted not in record
                assert described[omitted]
            assert record == {key: value for key, value in described.items()
                              if key not in ("CapacityReservationArn", "AvailabilityZoneId", "Tags")}
    assert describe(ec2, destination)["InstanceMatchCriteria"] == "targeted"


def test_retry_after_cancellation_is_unsupported_without_moving_twice(reservations):
    ec2, create = reservations
    source, destination = create(2), create(1)
    arguments = dict(SourceCapacityReservationId=source,
                     DestinationCapacityReservationId=destination,
                     InstanceCount=2, ClientToken=uuid.uuid4().hex)
    first = ec2.move_capacity_reservation_instances(**arguments)
    assert first["SourceCapacityReservation"]["State"] == "cancelled"
    with pytest.raises(ClientError) as inactive:
        ec2.move_capacity_reservation_instances(**arguments)
    assert inactive.value.response["Error"]["Code"] == "Unsupported"
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
    assert rejected.value.response["Error"]["Code"] == "Unsupported"
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


@pytest.mark.parametrize("account,region", [
    ("222222222222", "us-east-1"), ("111111111111", "us-east-2"),
])
def test_completed_tokens_are_scoped_to_account_and_region(endpoint, reservations, account, region):
    ec2, create = reservations
    source, destination = create(2), create(1)
    token = uuid.uuid4().hex
    arguments = dict(SourceCapacityReservationId=source,
                     DestinationCapacityReservationId=destination,
                     InstanceCount=1, ClientToken=token)
    ec2.move_capacity_reservation_instances(**arguments)
    other = client(endpoint, account, region)
    other_ids = []
    try:
        for count in (2, 1):
            record = other.create_capacity_reservation(
                InstanceType="t3.micro", InstancePlatform="Linux/UNIX",
                AvailabilityZone=region + "a", InstanceCount=count,
            )["CapacityReservation"]
            other_ids.append(record["CapacityReservationId"])
        other_arguments = dict(SourceCapacityReservationId=other_ids[0],
                               DestinationCapacityReservationId=other_ids[1],
                               InstanceCount=1, ClientToken=token)
        other.move_capacity_reservation_instances(**other_arguments)
        for scoped_client, scoped_arguments in ((ec2, arguments), (other, other_arguments)):
            replay = scoped_client.move_capacity_reservation_instances(**scoped_arguments)
            assert replay["SourceCapacityReservation"]["TotalInstanceCount"] == 1
            assert replay["DestinationCapacityReservation"]["TotalInstanceCount"] == 2
        before = [describe(ec2, resource) for resource in (source, destination)]
        other_before = [describe(other, resource) for resource in other_ids]
        for overrides, code in (
            (dict(SourceCapacityReservationId=other_ids[0]), "InvalidCapacityReservationId.NotFound"),
            (dict(DestinationCapacityReservationId=other_ids[1]), "IdempotentParameterMismatch"),
        ):
            with pytest.raises(ClientError) as rejected:
                ec2.move_capacity_reservation_instances(**(arguments | overrides))
            assert rejected.value.response["Error"]["Code"] == code
        assert [describe(ec2, resource) for resource in (source, destination)] == before
        assert [describe(other, resource) for resource in other_ids] == other_before
    finally:
        for reservation_id in other_ids:
            if describe(other, reservation_id)["State"] == "active":
                other.cancel_capacity_reservation(CapacityReservationId=reservation_id)
            assert describe(other, reservation_id)["State"] == "cancelled"
        other.close()


@pytest.mark.parametrize("destination_kind,count,code", [
    ("distinct", 0, "InvalidParameterValue"),
    ("distinct", 1001, "InvalidParameterValue"),
    ("malformed", 0, "InvalidCapacityReservationId.Malformed"),
    ("identical", 0, "InvalidParameterCombination"),
])
def test_foreign_source_preserves_input_validation_order(
        endpoint, reservations, destination_kind, count, code):
    ec2, create = reservations
    source, destination = create(2), create(2)
    before = [describe(ec2, rid) for rid in (source, destination)]
    requested_destination = {
        "distinct": destination, "malformed": "bad", "identical": source,
    }[destination_kind]
    other = client(endpoint, "222222222222", "us-east-1")
    try:
        with pytest.raises(ClientError) as rejected:
            other.move_capacity_reservation_instances(
                SourceCapacityReservationId=source,
                DestinationCapacityReservationId=requested_destination,
                InstanceCount=count)
        assert rejected.value.response["Error"]["Code"] == code
        assert [describe(ec2, rid) for rid in (source, destination)] == before
    finally:
        other.close()


@pytest.mark.parametrize("overrides", [
    {"AvailabilityZone": "us-east-1b"},
    {"InstanceType": "t3.small"},
    {"InstancePlatform": "Windows"},
    {"Tenancy": "dedicated"},
    {"PlacementGroupArn": "arn:aws:ec2:us-east-1:111111111111:placement-group/other"},
])
def test_insufficient_capacity_precedes_attribute_mismatch(reservations, overrides):
    ec2, create = reservations
    source, destination = create(2), create(1, **overrides)
    before = [describe(ec2, resource) for resource in (source, destination)]
    with pytest.raises(ClientError) as rejected:
        ec2.move_capacity_reservation_instances(
            SourceCapacityReservationId=source, DestinationCapacityReservationId=destination,
            InstanceCount=3)
    assert rejected.value.response["Error"]["Code"] == "InvalidParameterValue"
    assert [describe(ec2, resource) for resource in (source, destination)] == before


@pytest.mark.parametrize("source,destination,count,error", [
    ("bad", "cr-00000000000000002", 1, "InvalidCapacityReservationId.Malformed"),
    ("", "cr-00000000000000002", 1, "InvalidCapacityReservationId.Malformed"),
    ("cr-00000000000000000", "bad", 0, "InvalidCapacityReservationId.Malformed"),
    ("cr-12345678", "bad", 1, "InvalidCapacityReservationId.Malformed"),
    ("cr-00000000000000001", "", 1, "InvalidCapacityReservationId.NotFound"),
    ("cr-00000000000000001", "cr-00000000000000001", 0, "InvalidCapacityReservationId.NotFound"),
    ("cr-00000000000000001", "bad", 1001, "InvalidCapacityReservationId.NotFound"),
    ("cr-0910846388FB08593", "bad", 1, "InvalidCapacityReservationId.NotFound"),
])
def test_move_id_error_precedence(endpoint, source, destination, count, error):
    with pytest.raises(ClientError) as failure:
        client(endpoint).move_capacity_reservation_instances(
            SourceCapacityReservationId=source, DestinationCapacityReservationId=destination,
            InstanceCount=count)
    assert failure.value.response["Error"]["Code"] == error


def test_move_id_validation_precedes_used_token(reservations):
    ec2, create = reservations
    source, destination = create(3), create(1)
    request = dict(SourceCapacityReservationId=source, DestinationCapacityReservationId=destination,
                   InstanceCount=1, ClientToken="id-precedence")
    ec2.move_capacity_reservation_instances(**request)
    for change, error in [
        ({"SourceCapacityReservationId": "bad"}, "InvalidCapacityReservationId.Malformed"),
        ({"SourceCapacityReservationId": "cr-00000000000000001"}, "InvalidCapacityReservationId.NotFound"),
        ({"DestinationCapacityReservationId": ""}, "InvalidCapacityReservationId.Malformed"),
        ({"DestinationCapacityReservationId": "bad"}, "InvalidCapacityReservationId.Malformed"),
        ({"InstanceCount": 0}, "InvalidParameterValue"),
        ({"DestinationCapacityReservationId": source}, "InvalidParameterCombination"),
        ({"InstanceCount": 2}, "IdempotentParameterMismatch"),
    ]:
        with pytest.raises(ClientError) as failure:
            ec2.move_capacity_reservation_instances(**{**request, **change})
        assert failure.value.response["Error"]["Code"] == error
    ec2.move_capacity_reservation_instances(**request)
    assert describe(ec2, source)["TotalInstanceCount"] == 2
    assert describe(ec2, destination)["TotalInstanceCount"] == 2


def test_token_covers_other_sources_and_rejected_move_does_not_consume_it(reservations):
    ec2, create = reservations
    source, destination = create(3), create(1)
    other_source, other_destination = create(2), create(1, Tenancy="dedicated")
    completed_token, rejected_token = uuid.uuid4().hex, uuid.uuid4().hex

    def move(source_id, destination_id, token):
        return ec2.move_capacity_reservation_instances(
            SourceCapacityReservationId=source_id, DestinationCapacityReservationId=destination_id,
            InstanceCount=1, ClientToken=token)

    move(source, destination, completed_token)
    before = [describe(ec2, resource) for resource in (
        source, destination, other_source, other_destination)]
    for source_id, destination_id in ((destination, source), (other_source, other_destination)):
        with pytest.raises(ClientError) as mismatch:
            move(source_id, destination_id, completed_token)
        assert mismatch.value.response["Error"]["Code"] == "IdempotentParameterMismatch"
    with pytest.raises(ClientError) as rejected:
        move(other_source, other_destination, rejected_token)
    assert rejected.value.response["Error"]["Code"] == "Unsupported"
    assert [describe(ec2, resource) for resource in (
        source, destination, other_source, other_destination)] == before
    move(source, destination, rejected_token)
    assert describe(ec2, source)["TotalInstanceCount"] == 1
    assert describe(ec2, destination)["TotalInstanceCount"] == 3


def test_open_consumed_move_reassigns_instance_and_releases_capacity(reservations):
    ec2, create = reservations
    source = create(1)
    instance_id = None
    try:
        instance = ec2.run_instances(
            ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
            Placement={"AvailabilityZone": "us-east-1a"})["Instances"][0]
        instance_id = instance["InstanceId"]
        assert instance["CapacityReservationId"] == source
        assert describe(ec2, source)["AvailableInstanceCount"] == 0
        destination = create(2)
        ec2.move_capacity_reservation_instances(
            SourceCapacityReservationId=source, DestinationCapacityReservationId=destination,
            InstanceCount=1, ClientToken=uuid.uuid4().hex)
        current = ec2.describe_instances(InstanceIds=[instance_id])["Reservations"][0]["Instances"][0]
        assert current["State"]["Name"] == "running"
        assert current["CapacityReservationId"] == destination
        assert describe(ec2, source)["State"] == "cancelled"
        assert describe(ec2, destination)["AvailableInstanceCount"] == 2
        ec2.stop_instances(InstanceIds=[instance_id])
        assert describe(ec2, destination)["AvailableInstanceCount"] == 3
        stopped = ec2.describe_instances(InstanceIds=[instance_id])["Reservations"][0]["Instances"][0]
        assert "CapacityReservationId" not in stopped
        ec2.start_instances(InstanceIds=[instance_id])
        assert describe(ec2, destination)["AvailableInstanceCount"] == 2
        ec2.terminate_instances(InstanceIds=[instance_id])
        assert describe(ec2, destination)["AvailableInstanceCount"] == 3
    finally:
        if instance_id:
            ec2.terminate_instances(InstanceIds=[instance_id])


def test_explicit_target_cannot_move_between_open_reservations(reservations):
    ec2, create = reservations
    source, destination = create(1), create(1)
    instance_id = None
    try:
        instance_id = ec2.run_instances(
            ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
            CapacityReservationSpecification={"CapacityReservationTarget": {"CapacityReservationId": source}},
        )["Instances"][0]["InstanceId"]
        ec2.describe_instances(InstanceIds=[instance_id])
        with pytest.raises(ClientError) as failure:
            ec2.move_capacity_reservation_instances(
                SourceCapacityReservationId=source, DestinationCapacityReservationId=destination,
                InstanceCount=1, ClientToken="explicit-target-move",
            )
        assert failure.value.response["Error"]["Code"] == "Unsupported"
        assert describe(ec2, source)["AvailableInstanceCount"] == 0
        assert describe(ec2, destination)["AvailableInstanceCount"] == 1
        instance = ec2.describe_instances(InstanceIds=[instance_id])["Reservations"][0]["Instances"][0]
        assert instance["CapacityReservationId"] == source
    finally:
        if instance_id:
            ec2.terminate_instances(InstanceIds=[instance_id])


def test_foreign_destination_consumed_capacity_precedes_lookup(endpoint, reservations):
    ec2, create = reservations
    other = client(endpoint, "222222222222")
    source = create(2)
    instance_id = foreign_id = None
    try:
        instance_id = ec2.run_instances(
            ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
            Placement={"AvailabilityZone": "us-east-1a"},
        )["Instances"][0]["InstanceId"]
        foreign_id = other.create_capacity_reservation(
            InstanceType="t3.micro", InstancePlatform="Linux/UNIX",
            AvailabilityZone="us-east-1a", InstanceCount=1,
        )["CapacityReservation"]["CapacityReservationId"]
        before = describe(ec2, source)
        foreign_before = describe(other, foreign_id)
        token = uuid.uuid4().hex
        for count, code in ((1, "InvalidCapacityReservationId.NotFound"), (2, "InvalidParameterValue")):
            with pytest.raises(ClientError) as rejected:
                ec2.move_capacity_reservation_instances(
                    SourceCapacityReservationId=source, DestinationCapacityReservationId=foreign_id,
                    InstanceCount=count, ClientToken=token,
                )
            assert rejected.value.response["Error"]["Code"] == code
            assert describe(ec2, source) == before
            assert describe(other, foreign_id) == foreign_before
            current = ec2.describe_instances(InstanceIds=[instance_id])["Reservations"][0]["Instances"][0]
            assert current["CapacityReservationId"] == source
        destination = create(1)
        ec2.move_capacity_reservation_instances(
            SourceCapacityReservationId=source, DestinationCapacityReservationId=destination,
            InstanceCount=2, ClientToken=token,
        )
        assert describe(ec2, source)["State"] == "cancelled"
        assert describe(ec2, destination)["AvailableInstanceCount"] == 2
    finally:
        if instance_id:
            ec2.terminate_instances(InstanceIds=[instance_id])
        if foreign_id:
            other.cancel_capacity_reservation(CapacityReservationId=foreign_id)
        other.close()

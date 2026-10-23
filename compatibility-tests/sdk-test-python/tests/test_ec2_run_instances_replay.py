"""RunInstances request replay through the EC2 SDK, independent of membership replay."""
from concurrent.futures import ThreadPoolExecutor
import uuid

import pytest
from botocore.exceptions import ClientError

from test_ec2_capacity_reservation_association import client, endpoint, pytestmark
from test_ec2_shared_capacity_reservation import shared


@pytest.fixture
def launches(endpoint):
    clients = []
    owned = []

    def connect(account="111111111111", region="us-east-1"):
        ec2 = client(endpoint, account, region)
        clients.append(ec2)
        return ec2

    def launch(ec2, request):
        result = ec2.run_instances(**request)
        owned.append((ec2, [instance["InstanceId"] for instance in result["Instances"]]))
        return result

    try:
        yield connect, launch
    finally:
        for ec2, ids in owned:
            ec2.terminate_instances(InstanceIds=ids)
        for ec2 in clients:
            ec2.close()


def request(**overrides):
    parameters = dict(ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
                      ClientToken=uuid.uuid4().hex)
    parameters.update(overrides)
    return parameters


def assert_mismatch(ec2, parameters):
    with pytest.raises(ClientError) as error:
        ec2.run_instances(**parameters)
    assert error.value.response["Error"]["Code"] == "IdempotentParameterMismatch"


def test_replay_returns_original_reservation_and_current_state(launches):
    connect, launch = launches
    ec2 = connect()
    parameters = request()
    first = launch(ec2, parameters)
    replay = launch(ec2, parameters)
    assert replay["ReservationId"] == first["ReservationId"]
    assert replay["Instances"][0]["InstanceId"] == first["Instances"][0]["InstanceId"]
    ec2.terminate_instances(InstanceIds=[first["Instances"][0]["InstanceId"]])
    assert launch(ec2, parameters)["Instances"][0]["State"]["Name"] == "terminated"


@pytest.mark.parametrize("override", [
    {"InstanceType": "t3.small"}, {"MaxCount": 2}, {"MinCount": 2, "MaxCount": 2},
    {"Monitoring": {"Enabled": False}}, {"DisableApiTermination": False},
    {"EbsOptimized": False}, {"UserData": "changed"}, {"KeyName": "missing"},
    {"MetadataOptions": {"HttpPutResponseHopLimit": 2}},
    {"MetadataOptions": {"HttpTokens": "required"}},
    {"TagSpecifications": [{"ResourceType": "instance", "Tags": [{"Key": "changed", "Value": "true"}]}]},
    {"CapacityReservationSpecification": {"CapacityReservationPreference": "none"}},
])
def test_parameter_changes_and_explicit_defaults_mismatch(launches, override):
    connect, launch = launches
    ec2 = connect()
    parameters = request()
    launch(ec2, parameters)
    assert_mismatch(ec2, dict(parameters, **override))


def test_metadata_effective_default_is_equivalent(launches):
    connect, launch = launches
    ec2 = connect()
    parameters = request()
    first = launch(ec2, parameters)
    replay = launch(ec2, dict(parameters, MetadataOptions={"HttpTokens": "optional"}))
    assert replay["ReservationId"] == first["ReservationId"]


def test_account_region_and_zonal_scope(launches):
    connect, launch = launches
    ec2 = connect()
    parameters = request()
    first = launch(ec2, parameters)
    selected = first["Instances"][0]["Placement"]["AvailabilityZone"]
    assert_mismatch(ec2, dict(parameters, Placement={"AvailabilityZone": selected}))
    other = "us-east-1b" if selected == "us-east-1a" else "us-east-1a"
    zonal = launch(ec2, dict(parameters, Placement={"AvailabilityZone": other}))
    assert zonal["ReservationId"] != first["ReservationId"]
    assert launch(ec2, dict(parameters, Placement={"AvailabilityZone": other}))["ReservationId"] == zonal["ReservationId"]
    assert launch(connect(region="us-west-2"), parameters)["ReservationId"] != first["ReservationId"]
    assert launch(connect(account="222222222222"), parameters)["ReservationId"] != first["ReservationId"]


def test_concurrent_replay_admits_once(launches):
    connect, launch = launches
    ec2 = connect()
    parameters = request()
    with ThreadPoolExecutor(max_workers=8) as workers:
        replies = list(workers.map(lambda _: launch(ec2, parameters), range(8)))
    assert len({reply["ReservationId"] for reply in replies}) == 1
    assert len({reply["Instances"][0]["InstanceId"] for reply in replies}) == 1


def test_replay_does_not_reapply_network_interface_tags(launches):
    connect, launch = launches
    ec2 = connect()
    parameters = request(TagSpecifications=[{
        "ResourceType": "network-interface", "Tags": [{"Key": "purpose", "Value": "original"}]}])
    first = launch(ec2, parameters)
    eni = first["Instances"][0]["NetworkInterfaces"][0]["NetworkInterfaceId"]
    ec2.create_tags(Resources=[eni], Tags=[{"Key": "purpose", "Value": "updated"}])
    assert launch(ec2, parameters)["ReservationId"] == first["ReservationId"]
    interface = ec2.describe_network_interfaces(NetworkInterfaceIds=[eni])["NetworkInterfaces"][0]
    assert {tag["Key"]: tag["Value"] for tag in interface["TagSet"]}["purpose"] == "updated"


def test_replay_survives_deleted_launch_template(launches):
    connect, launch = launches
    ec2 = connect()
    template = ec2.create_launch_template(
        LaunchTemplateName="replay-" + uuid.uuid4().hex,
        LaunchTemplateData={"ImageId": "ami-test", "InstanceType": "t3.micro"})["LaunchTemplate"]["LaunchTemplateId"]
    deleted = False
    try:
        parameters = dict(LaunchTemplate={"LaunchTemplateId": template, "Version": "1"},
                          MinCount=1, MaxCount=1, ClientToken=uuid.uuid4().hex)
        first = launch(ec2, parameters)
        ec2.delete_launch_template(LaunchTemplateId=template)
        deleted = True
        assert launch(ec2, parameters)["ReservationId"] == first["ReservationId"]
    finally:
        if not deleted:
            ec2.delete_launch_template(LaunchTemplateId=template)


def test_fleet_token_does_not_collapse_distinct_member_launches(launches):
    connect, _ = launches
    ec2 = connect()
    template = ec2.create_launch_template(
        LaunchTemplateName="fleet-replay-" + uuid.uuid4().hex,
        LaunchTemplateData={"ImageId": "ami-test", "InstanceType": "t3.micro"})["LaunchTemplate"]["LaunchTemplateId"]
    ids = []
    try:
        result = ec2.create_fleet(Type="instant", ClientToken=uuid.uuid4().hex,
            LaunchTemplateConfigs=[{"LaunchTemplateSpecification": {"LaunchTemplateId": template, "Version": "1"}}],
            TargetCapacitySpecification={"TotalTargetCapacity": 3, "DefaultTargetCapacityType": "on-demand"})
        ids = [instance_id for group in result["Instances"] for instance_id in group["InstanceIds"]]
        assert len(ids) == len(set(ids)) == 3
    finally:
        if ids:
            ec2.terminate_instances(InstanceIds=ids)
        ec2.delete_launch_template(LaunchTemplateId=template)


def test_shared_capacity_replay_survives_share_revocation(shared):
    from test_ec2_shared_capacity_reservation import accept
    from test_ec2_capacity_reservation_association import describe

    owner, recipient, owner_ram, recipient_ram, capacity, share, instances = shared
    accept(recipient_ram, share)
    capacity_id = capacity["CapacityReservationId"]
    parameters = request(Placement={"AvailabilityZone": "us-east-1a"},
        CapacityReservationSpecification={"CapacityReservationTarget": {"CapacityReservationId": capacity_id}})
    first = recipient.run_instances(**parameters)
    instances.append(first["Instances"][0]["InstanceId"])
    assert describe(owner, capacity_id)["AvailableInstanceCount"] == 0
    owner_ram.delete_resource_share(resourceShareArn=share)
    replay = recipient.run_instances(**parameters)
    assert replay["ReservationId"] == first["ReservationId"]
    assert replay["Instances"][0]["InstanceId"] == instances[0]
    assert describe(owner, capacity_id)["AvailableInstanceCount"] == 0


@pytest.mark.parametrize("mode", ["persistent", "hybrid", "wal"])
def test_replay_survives_abrupt_process_restart(mode):
    import json
    import time
    import urllib.request
    from test_ec2_capacity_reservation_association import docker, IMAGE

    container = docker("run", "-d", "--name", "floci-replay-restart-" + uuid.uuid4().hex[:12],
        "--publish", "127.0.0.1::4566", "--env", "FLOCI_SERVICES_EC2_MOCK=true",
        "--env", "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED=false",
        "--env", "FLOCI_STORAGE_MODE=" + mode, IMAGE)

    def ready():
        port = json.loads(docker("inspect", container))[0]["NetworkSettings"]["Ports"]["4566/tcp"][0]["HostPort"]
        url = "http://127.0.0.1:" + port
        deadline = time.monotonic() + 60
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while True:
            try:
                with opener.open(url + "/_localstack/health", timeout=2):
                    return url
            except OSError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.2)

    try:
        url = ready()
        parameters = request()
        first = {}
        for account in ("111111111111", "222222222222"):
            ec2 = client(url, account)
            try:
                first[account] = ec2.run_instances(**parameters)
            finally:
                ec2.close()
        docker("kill", container)
        docker("start", container)
        url = ready()
        for account in first:
            ec2 = client(url, account)
            try:
                replay = ec2.run_instances(**parameters)
                assert replay["ReservationId"] == first[account]["ReservationId"]
                assert replay["Instances"][0]["InstanceId"] == first[account]["Instances"][0]["InstanceId"]
                ec2.terminate_instances(InstanceIds=[replay["Instances"][0]["InstanceId"]])
            finally:
                ec2.close()
    finally:
        docker("rm", "-f", "-v", container)

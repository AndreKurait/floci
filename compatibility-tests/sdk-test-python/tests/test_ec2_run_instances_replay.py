"""RunInstances request replay through issued account sessions and the public EC2 SDK."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack, closing
import json
import os
import subprocess
from threading import Lock
import time
import urllib.request
import uuid

import boto3
import pytest
from botocore.exceptions import ClientError

from test_lambda_autoscaling_accounts import member_account_configs
from test_ec2_shared_capacity_reservation import accept, describe, shared


@pytest.fixture
def launches(aws_config, client_config, unique_name):
    with ExitStack() as clients:
        configs = clients.enter_context(member_account_configs(aws_config, client_config, unique_name))
        primary, other = tuple(configs)
        accounts = {"primary": configs[primary], "other": configs[other]}
        owned = {}
        images = {}
        cache = {}
        lock = Lock()
        with ExitStack() as cleanup:
            def connect(account="primary", region=None):
                config = dict(accounts[account])
                if region is not None:
                    config["region_name"] = region
                key = account, config["region_name"]
                if key not in cache:
                    ec2 = clients.enter_context(closing(boto3.client(
                        "ec2", config=client_config, **config)))
                    cache[key] = ec2
                    images[ec2] = ec2.register_image(
                        Name=unique_name, Architecture="x86_64", RootDeviceName="/dev/sda1")["ImageId"]
                    cleanup.callback(ec2.deregister_image, ImageId=images[ec2])
                return cache[key]

            def parameters(ec2, **overrides):
                values = dict(ImageId=images[ec2], InstanceType="t3.micro", MinCount=1, MaxCount=1,
                              ClientToken=uuid.uuid4().hex)
                values.update(overrides)
                return values

            def launch(ec2, request):
                result = ec2.run_instances(**request)
                with lock:
                    owned.setdefault(ec2, set()).update(item["InstanceId"] for item in result["Instances"])
                return result

            try:
                yield connect, launch, parameters
            finally:
                for ec2, ids in owned.items():
                    cleanup.callback(ec2.get_waiter("instance_terminated").wait,
                                     InstanceIds=sorted(ids), WaiterConfig={"Delay": 1, "MaxAttempts": 30})
                    cleanup.callback(ec2.terminate_instances, InstanceIds=sorted(ids))


def assert_mismatch(ec2, parameters):
    with pytest.raises(ClientError) as error:
        ec2.run_instances(**parameters)
    assert error.value.response["Error"]["Code"] == "IdempotentParameterMismatch"


def test_replay_returns_original_reservation_and_current_state(launches):
    connect, launch, request = launches
    ec2 = connect()
    parameters = request(ec2)
    first = launch(ec2, parameters)
    replay = launch(ec2, parameters)
    assert replay["ReservationId"] == first["ReservationId"]
    assert replay["Instances"][0]["InstanceId"] == first["Instances"][0]["InstanceId"]
    ec2.terminate_instances(InstanceIds=[first["Instances"][0]["InstanceId"]])
    ec2.get_waiter("instance_terminated").wait(
        InstanceIds=[first["Instances"][0]["InstanceId"]], WaiterConfig={"Delay": 1, "MaxAttempts": 30})
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
    connect, launch, request = launches
    ec2 = connect()
    parameters = request(ec2)
    launch(ec2, parameters)
    assert_mismatch(ec2, dict(parameters, **override))


def test_metadata_effective_default_is_equivalent(launches):
    connect, launch, request = launches
    ec2 = connect()
    parameters = request(ec2)
    first = launch(ec2, parameters)
    replay = launch(ec2, dict(parameters, MetadataOptions={"HttpTokens": "optional"}))
    assert replay["ReservationId"] == first["ReservationId"]


def test_account_region_and_zonal_scope(launches):
    connect, launch, request = launches
    ec2 = connect()
    parameters = request(ec2)
    first = launch(ec2, parameters)
    selected = first["Instances"][0]["Placement"]["AvailabilityZone"]
    assert_mismatch(ec2, dict(parameters, Placement={"AvailabilityZone": selected}))
    other = next(zone["ZoneName"] for zone in ec2.describe_availability_zones()["AvailabilityZones"]
                 if zone["State"] == "available" and zone["ZoneName"] != selected)
    zonal = launch(ec2, dict(parameters, Placement={"AvailabilityZone": other}))
    assert zonal["ReservationId"] != first["ReservationId"]
    assert launch(ec2, dict(parameters, Placement={"AvailabilityZone": other}))["ReservationId"] == zonal["ReservationId"]
    other_region = connect(region="us-west-2" if ec2.meta.region_name != "us-west-2" else "us-east-1")
    other_account = connect(account="other")
    for caller in (other_region, other_account):
        isolated = request(caller, ClientToken=parameters["ClientToken"])
        assert launch(caller, isolated)["ReservationId"] != first["ReservationId"]
    assert launch(ec2, parameters)["ReservationId"] == first["ReservationId"]


def test_concurrent_replay_admits_once(launches):
    connect, launch, request = launches
    ec2 = connect()
    parameters = request(ec2)
    with ThreadPoolExecutor(max_workers=8) as workers:
        replies = list(workers.map(lambda _: launch(ec2, parameters), range(8)))
    assert len({reply["ReservationId"] for reply in replies}) == 1
    assert len({reply["Instances"][0]["InstanceId"] for reply in replies}) == 1


@pytest.mark.parametrize("placement_source", ["request", "template", "request-overrides-template"])
def test_zone_ids_select_independent_replay_scopes(launches, placement_source):
    connect, launch, request = launches
    ec2 = connect()
    zones = [zone for zone in ec2.describe_availability_zones()["AvailabilityZones"]
             if zone["State"] == "available"][:2]
    assert len(zones) == 2
    token = uuid.uuid4().hex
    reservations = []
    with ExitStack() as cleanup:
        for index, zone in enumerate(zones):
            parameters = request(ec2, ClientToken=token)
            if placement_source != "request":
                placement = ({"AvailabilityZoneId": zone["ZoneId"]} if placement_source == "template"
                             else {"AvailabilityZone": zones[1 - index]["ZoneName"]})
                template = ec2.create_launch_template(
                    LaunchTemplateName="zone-replay-" + uuid.uuid4().hex,
                    LaunchTemplateData={"ImageId": parameters["ImageId"],
                                        "InstanceType": parameters["InstanceType"],
                                        "Placement": placement})["LaunchTemplate"]["LaunchTemplateId"]
                cleanup.callback(ec2.delete_launch_template, LaunchTemplateId=template)
                parameters["LaunchTemplate"] = {"LaunchTemplateId": template, "Version": "1"}
            if placement_source != "template":
                parameters["Placement"] = {"AvailabilityZoneId": zone["ZoneId"]}
            first = launch(ec2, parameters)
            assert first["Instances"][0]["Placement"]["AvailabilityZone"] == zone["ZoneName"]
            reservations.append(first["ReservationId"])
            assert launch(ec2, parameters)["ReservationId"] == first["ReservationId"]
            assert_mismatch(ec2, dict(parameters, InstanceType="t3.small"))
    assert len(set(reservations)) == 2


def test_replay_does_not_reapply_network_interface_tags(launches):
    connect, launch, request = launches
    ec2 = connect()
    parameters = request(ec2, TagSpecifications=[{
        "ResourceType": "network-interface", "Tags": [{"Key": "purpose", "Value": "original"}]}])
    first = launch(ec2, parameters)
    eni = first["Instances"][0]["NetworkInterfaces"][0]["NetworkInterfaceId"]
    ec2.create_tags(Resources=[eni], Tags=[{"Key": "purpose", "Value": "updated"}])
    assert launch(ec2, parameters)["ReservationId"] == first["ReservationId"]
    interface = ec2.describe_network_interfaces(NetworkInterfaceIds=[eni])["NetworkInterfaces"][0]
    assert {tag["Key"]: tag["Value"] for tag in interface["TagSet"]}["purpose"] == "updated"


def test_deleted_supplied_interface_precedes_replay_and_mismatch(launches):
    connect, launch, request = launches
    ec2 = connect()
    subnet = ec2.describe_subnets()["Subnets"][0]["SubnetId"]
    eni = ec2.create_network_interface(SubnetId=subnet)["NetworkInterface"]["NetworkInterfaceId"]
    deleted = False
    ids = []
    try:
        parameters = request(ec2, NetworkInterfaces=[{
            "NetworkInterfaceId": eni, "DeviceIndex": 0, "DeleteOnTermination": False}])
        first = launch(ec2, parameters)
        ids = [instance["InstanceId"] for instance in first["Instances"]]
        assert launch(ec2, parameters)["ReservationId"] == first["ReservationId"]
        assert_mismatch(ec2, dict(parameters, InstanceType="t3.small"))
        ec2.terminate_instances(InstanceIds=ids)
        ec2.get_waiter("instance_terminated").wait(
            InstanceIds=ids, WaiterConfig={"Delay": 1, "MaxAttempts": 30})
        ec2.delete_network_interface(NetworkInterfaceId=eni)
        deleted = True
        for overrides in ({}, {"InstanceType": "t3.small"}, {"ClientToken": uuid.uuid4().hex}):
            with pytest.raises(ClientError) as error:
                ec2.run_instances(**dict(parameters, **overrides))
            assert error.value.response["Error"]["Code"] == "InvalidNetworkInterfaceID.NotFound"
    finally:
        if ids:
            ec2.terminate_instances(InstanceIds=ids)
            ec2.get_waiter("instance_terminated").wait(
                InstanceIds=ids, WaiterConfig={"Delay": 1, "MaxAttempts": 30})
        if not deleted:
            ec2.delete_network_interface(NetworkInterfaceId=eni)


def test_replay_survives_deleted_launch_template(launches):
    connect, launch, request = launches
    ec2 = connect()
    template = ec2.create_launch_template(
        LaunchTemplateName="replay-" + uuid.uuid4().hex,
        LaunchTemplateData={key: request(ec2)[key] for key in ("ImageId", "InstanceType")})["LaunchTemplate"]["LaunchTemplateId"]
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
    connect, _, request = launches
    ec2 = connect()
    template = ec2.create_launch_template(
        LaunchTemplateName="fleet-replay-" + uuid.uuid4().hex,
        LaunchTemplateData={key: request(ec2)[key] for key in ("ImageId", "InstanceType")})["LaunchTemplate"]["LaunchTemplateId"]
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
            ec2.get_waiter("instance_terminated").wait(
                InstanceIds=ids, WaiterConfig={"Delay": 1, "MaxAttempts": 30})
        ec2.delete_launch_template(LaunchTemplateId=template)


def test_shared_capacity_replay_survives_share_revocation(shared):
    accept(shared.recipient_ram, shared.share)
    capacity_id = shared.capacity["CapacityReservationId"]
    parameters = dict(shared.launch, ClientToken=uuid.uuid4().hex,
        CapacityReservationSpecification={"CapacityReservationTarget": {"CapacityReservationId": capacity_id}})
    first = shared.recipient.run_instances(**parameters)
    shared.instances.append(first["Instances"][0]["InstanceId"])
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 0
    shared.owner_ram.delete_resource_share(resourceShareArn=shared.share)
    replay = shared.recipient.run_instances(**parameters)
    assert replay["ReservationId"] == first["ReservationId"]
    assert replay["Instances"][0]["InstanceId"] == shared.instances[0]
    assert describe(shared.owner, capacity_id)["AvailableInstanceCount"] == 0


RESTART_IMAGE = os.environ.get("FLOCI_REPLAY_RESTART_IMAGE")


def docker(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


@pytest.mark.skipif(not RESTART_IMAGE, reason="Set FLOCI_REPLAY_RESTART_IMAGE for isolated process-restart checks")
@pytest.mark.parametrize("mode", ["persistent", "hybrid", "wal"])
def test_replay_survives_abrupt_process_restart(mode, client_config, unique_name):
    # The optional bind and endpoint admit a dedicated nested daemon with a forwarded port.
    bind = os.environ.get("FLOCI_REPLAY_RESTART_BIND", "127.0.0.1:")
    container = docker("run", "-d", "--name", "floci-replay-restart-" + uuid.uuid4().hex[:12],
        "--publish", bind + ":4566", "--env", "FLOCI_SERVICES_EC2_MOCK=true",
        "--env", "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED=false",
        "--env", "FLOCI_AUTH_VALIDATE_SIGNATURES=true",
        "--env", "FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true",
        "--env", "FLOCI_STORAGE_MODE=" + mode, RESTART_IMAGE)

    def ready():
        port = json.loads(docker("inspect", container))[0]["NetworkSettings"]["Ports"]["4566/tcp"][0]["HostPort"]
        url = os.environ.get("FLOCI_REPLAY_RESTART_ENDPOINT", "http://127.0.0.1:" + port)
        deadline = time.monotonic() + 60
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while True:
            try:
                with opener.open(url + "/_localstack/health", timeout=2):
                    return url
            except OSError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)

    try:
        url = ready()
        root = dict(endpoint_url=url, region_name="us-east-1",
                    aws_access_key_id="test", aws_secret_access_key="test")
        with member_account_configs(root, client_config, unique_name) as configs:
            with ExitStack() as clients, ExitStack() as cleanup:
                requests = []
                token = uuid.uuid4().hex
                for account, config in configs.items():
                    ec2 = clients.enter_context(closing(boto3.client("ec2", config=client_config, **config)))
                    image = ec2.register_image(
                        Name=unique_name, Architecture="x86_64", RootDeviceName="/dev/sda1")["ImageId"]
                    cleanup.callback(ec2.deregister_image, ImageId=image)
                    requests.append((account, ec2, dict(ImageId=image, InstanceType="t3.micro",
                                                        MinCount=1, MaxCount=1, ClientToken=token)))
                # Commit identity/image prerequisites before testing EC2's checked durability boundary.
                docker("restart", container)
                assert ready() == url
                first = {}
                for account, ec2, parameters in requests:
                    first[account] = ec2.run_instances(**parameters)
                    ids = [item["InstanceId"] for item in first[account]["Instances"]]
                    cleanup.callback(ec2.get_waiter("instance_terminated").wait,
                                     InstanceIds=ids, WaiterConfig={"Delay": 1, "MaxAttempts": 30})
                    cleanup.callback(ec2.terminate_instances, InstanceIds=ids)
                docker("kill", container)
                docker("start", container)
                assert ready() == url
                for account, ec2, parameters in requests:
                    replay = ec2.run_instances(**parameters)
                    assert replay["OwnerId"] == account
                    assert replay["ReservationId"] == first[account]["ReservationId"]
                    assert replay["Instances"][0]["InstanceId"] == first[account]["Instances"][0]["InstanceId"]
    finally:
        docker("rm", "-f", "-v", container)
        assert not docker("ps", "-aq", "--filter", "id=" + container)

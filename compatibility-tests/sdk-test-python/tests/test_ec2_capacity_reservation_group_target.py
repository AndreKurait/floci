"""Exercise capacity group targets through EC2, Resource Groups and Auto Scaling SDKs."""
import json
import os
import subprocess
import urllib.request
import time
import uuid

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError

pytestmark = pytest.mark.skipif(
    not os.environ.get("FLOCI_CAPACITY_ASSOCIATION_TEST_IMAGE"),
    reason="Set FLOCI_CAPACITY_ASSOCIATION_TEST_IMAGE for Docker checks",
)
ACCOUNT = "111111111111"
REGION = "us-east-1"


def docker(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


@pytest.fixture(scope="module")
def endpoint():
    name = "floci-capacity-group-" + uuid.uuid4().hex[:12]
    container = docker(
        "run", "-d", "--name", name, "--publish", "127.0.0.1::4566",
        "--env", "FLOCI_SERVICES_EC2_MOCK=true",
        "--env", "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED=false",
        "--env", "FLOCI_STORAGE_MODE=memory", os.environ["FLOCI_CAPACITY_ASSOCIATION_TEST_IMAGE"],
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


def client(endpoint, service, account=ACCOUNT, region=REGION):
    return boto3.client(
        service, endpoint_url=endpoint, region_name=region,
        aws_access_key_id=account, aws_secret_access_key="test",
        config=Config(proxies={}, connect_timeout=2, read_timeout=5,
                      retries={"total_max_attempts": 1}),
    )


@pytest.fixture
def pool(endpoint):
    clients = {name: client(endpoint, name) for name in ("ec2", "resource-groups", "autoscaling")}
    ec2, groups = clients["ec2"], clients["resource-groups"]
    created = {"reservations": [], "instances": [], "templates": [], "asgs": []}
    group = groups.create_group(Name="pool-" + uuid.uuid4().hex, Configuration=[
        {"Type": "AWS::EC2::CapacityReservationPool"},
        {"Type": "AWS::ResourceGroups::Generic", "Parameters": [
            {"Name": "allowed-resource-types", "Values": ["AWS::EC2::CapacityReservation"]},
        ]},
    ])["Group"]["GroupArn"]

    def reserve(count=1, zone=REGION + "a", member=True):
        reservation = ec2.create_capacity_reservation(
            InstanceType="t3.micro", InstancePlatform="Linux/UNIX", AvailabilityZone=zone,
            InstanceCount=count, InstanceMatchCriteria="targeted" if member else "open",
        )["CapacityReservation"]
        created["reservations"].append(reservation["CapacityReservationId"])
        if member:
            reply = groups.group_resources(Group=group, ResourceArns=[reservation["CapacityReservationArn"]])
            assert reply["Succeeded"] == [reservation["CapacityReservationArn"]]
            assert not reply["Failed"] and not reply["Pending"]
        return reservation["CapacityReservationId"]

    try:
        yield clients, group, reserve, created
    finally:
        for name in created["asgs"]:
            clients["autoscaling"].delete_auto_scaling_group(AutoScalingGroupName=name, ForceDelete=True)
        if created["instances"]:
            ec2.terminate_instances(InstanceIds=created["instances"])
        for template in created["templates"]:
            ec2.delete_launch_template(LaunchTemplateId=template)
        groups.delete_group(Group=group)
        for reservation in created["reservations"]:
            ec2.cancel_capacity_reservation(CapacityReservationId=reservation)
        for sdk in clients.values():
            sdk.close()


@pytest.mark.parametrize("template", [False, True])
def test_group_round_trip_matching_and_strict_fallback(pool, template):
    clients, group, reserve, created = pool
    ec2 = clients["ec2"]
    members = {reserve(), reserve()}
    outside = reserve(4, member=False)
    specification = {
        "CapacityReservationPreference": "capacity-reservations-only",
        "CapacityReservationTarget": {"CapacityReservationResourceGroupArn": group},
    }
    request = {"MinCount": 2, "MaxCount": 2}
    data = {"ImageId": "ami-test", "InstanceType": "t3.micro",
            "CapacityReservationSpecification": specification}
    if template:
        selected = ec2.create_launch_template(
            LaunchTemplateName="pool-template-" + uuid.uuid4().hex,
            LaunchTemplateData=data)["LaunchTemplate"]["LaunchTemplateId"]
        created["templates"].append(selected)
        request["LaunchTemplate"] = {"LaunchTemplateId": selected}
    else:
        request.update(data)
    launched = ec2.run_instances(**request)["Instances"]
    created["instances"].extend(row["InstanceId"] for row in launched)
    assert {row["CapacityReservationId"] for row in launched} == members
    described = ec2.describe_instances(InstanceIds=created["instances"])["Reservations"]
    assert all(instance["CapacityReservationSpecification"] == specification
               for reservation in described for instance in reservation["Instances"])
    with pytest.raises(ClientError) as error:
        ec2.run_instances(**request)
    assert error.value.response["Error"]["Code"] == "InsufficientInstanceCapacity"
    assert ec2.describe_capacity_reservations(CapacityReservationIds=[outside])[
        "CapacityReservations"][0]["AvailableInstanceCount"] == 4


def test_group_default_uses_on_demand_without_outside_reservations(pool):
    clients, group, reserve, created = pool
    ec2 = clients["ec2"]
    outside = reserve(2, member=False)
    instance = ec2.run_instances(
        ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
        CapacityReservationSpecification={
            "CapacityReservationTarget": {"CapacityReservationResourceGroupArn": group}},
    )["Instances"][0]
    created["instances"].append(instance["InstanceId"])
    assert "CapacityReservationId" not in instance
    assert instance["CapacityReservationSpecification"]["CapacityReservationTarget"] == {
        "CapacityReservationResourceGroupArn": group}
    assert ec2.describe_capacity_reservations(CapacityReservationIds=[outside])[
        "CapacityReservations"][0]["AvailableInstanceCount"] == 2


@pytest.mark.parametrize("account,region", [("222222222222", REGION), (ACCOUNT, "us-west-2")])
def test_group_target_cannot_cross_account_or_region(endpoint, pool, account, region):
    _, group, _, _ = pool
    ec2 = client(endpoint, "ec2", account, region)
    try:
        with pytest.raises(ClientError) as error:
            ec2.run_instances(
                ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
                CapacityReservationSpecification={
                    "CapacityReservationTarget": {"CapacityReservationResourceGroupArn": group}},
            )
        assert error.value.response["Error"]["Code"] == "InvalidParameterValue"
        assert not ec2.describe_instances()["Reservations"]
    finally:
        ec2.close()


def test_autoscaling_launches_group_members_in_their_matching_zones(pool):
    clients, group, reserve, created = pool
    ec2, autoscaling = clients["ec2"], clients["autoscaling"]
    members = {reserve(zone=REGION + "a"), reserve(zone=REGION + "b")}
    selected = ec2.create_launch_template(
        LaunchTemplateName="asg-pool-template-" + uuid.uuid4().hex,
        LaunchTemplateData={
            "ImageId": "ami-test", "InstanceType": "t3.micro",
            "CapacityReservationSpecification": {
                "CapacityReservationTarget": {"CapacityReservationResourceGroupArn": group}},
        },
    )["LaunchTemplate"]["LaunchTemplateId"]
    created["templates"].append(selected)
    name = "group-capacity-" + uuid.uuid4().hex
    autoscaling.create_auto_scaling_group(
        AutoScalingGroupName=name, MinSize=2, MaxSize=2, DesiredCapacity=2,
        AvailabilityZones=[REGION + "a", REGION + "b"], LaunchTemplate={"LaunchTemplateId": selected},
    )
    created["asgs"].append(name)
    deadline = time.monotonic() + 60
    while True:
        row = autoscaling.describe_auto_scaling_groups(AutoScalingGroupNames=[name])["AutoScalingGroups"][0]
        if len(row["Instances"]) == 2 and all(i["LifecycleState"] == "InService" for i in row["Instances"]):
            break
        assert time.monotonic() < deadline, autoscaling.describe_scaling_activities(AutoScalingGroupName=name)
        time.sleep(0.5)
    ids = [row["InstanceId"] for row in row["Instances"]]
    created["instances"].extend(ids)
    instances = [instance for reservation in ec2.describe_instances(InstanceIds=ids)["Reservations"]
                 for instance in reservation["Instances"]]
    assert {instance["CapacityReservationId"] for instance in instances} == members
    assert {instance["Placement"]["AvailabilityZone"] for instance in instances} == {REGION + "a", REGION + "b"}

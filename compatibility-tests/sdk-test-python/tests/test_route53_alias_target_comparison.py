"""Alias deletion compares equivalent DNS spellings while retaining value checks."""
import copy
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

IMAGE = os.environ.get("FLOCI_ALIAS_DELETE_TEST_IMAGE")
pytestmark = pytest.mark.skipif(not IMAGE, reason="Set FLOCI_ALIAS_DELETE_TEST_IMAGE for Docker checks")


def docker(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


@pytest.fixture(scope="module")
def route53():
    name = "floci-alias-delete-" + uuid.uuid4().hex[:12]
    container = docker(
        "run", "-d", "--name", name, "--publish", "127.0.0.1::4566",
        "--env", "FLOCI_SERVICES_EC2_MOCK=true",
        "--env", "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED=false",
        "--env", "FLOCI_STORAGE_MODE=memory", IMAGE,
    )
    try:
        port = json.loads(docker("inspect", container))[0]["NetworkSettings"]["Ports"]["4566/tcp"][0]["HostPort"]
        endpoint = "http://127.0.0.1:" + port
        deadline = time.monotonic() + 60
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while True:
            try:
                with opener.open(endpoint + "/_localstack/health", timeout=2) as response:
                    json.load(response)
                break
            except (OSError, ValueError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)
        yield boto3.client(
            "route53", endpoint_url=endpoint, region_name="us-east-1",
            aws_access_key_id="111111111111", aws_secret_access_key="test",
            config=Config(proxies={}, connect_timeout=2, read_timeout=5,
                          retries={"total_max_attempts": 1}),
        )
    finally:
        docker("rm", "-f", "-v", container)
        assert not docker("ps", "-aq", "--filter", "id=" + container)


@pytest.fixture
def zone(route53):
    name = "alias-" + uuid.uuid4().hex[:12] + ".example.test"
    zone_id = route53.create_hosted_zone(Name=name, CallerReference=name)["HostedZone"]["Id"]
    try:
        yield zone_id, name
    finally:
        records = route53.list_resource_record_sets(HostedZoneId=zone_id)["ResourceRecordSets"]
        changes = [{"Action": "DELETE", "ResourceRecordSet": record}
                   for record in records if record["Type"] not in ("NS", "SOA")]
        if changes:
            route53.change_resource_record_sets(HostedZoneId=zone_id, ChangeBatch={"Changes": changes})
        route53.delete_hosted_zone(Id=zone_id)
        with pytest.raises(ClientError) as missing:
            route53.get_hosted_zone(Id=zone_id)
        assert missing.value.response["Error"]["Code"] == "NoSuchHostedZone"


@pytest.mark.parametrize("variant", ["add_dot", "remove_dot", "case_and_dot", "different_name",
                                    "different_zone", "different_health"])
def test_alias_delete_dns_spelling_and_atomic_mismatch_rejection(route53, zone, variant):
    zone_id, zone_name = zone
    target_name = "target." + zone_name
    target = {"Name": target_name, "Type": "A", "TTL": 60,
              "ResourceRecords": [{"Value": "192.0.2.21"}]}
    alias = {"Name": "alias." + zone_name, "Type": "A", "SetIdentifier": "blue", "Weight": 1,
             "AliasTarget": {"HostedZoneId": zone_id.rsplit("/", 1)[-1],
                             "DNSName": target_name + ("." if variant == "remove_dot" else ""),
                             "EvaluateTargetHealth": False}}
    route53.change_resource_record_sets(
        HostedZoneId=zone_id,
        ChangeBatch={"Changes": [{"Action": "CREATE", "ResourceRecordSet": record}
                                 for record in (target, alias)]},
    )
    requested = copy.deepcopy(alias)
    if variant == "add_dot":
        requested["AliasTarget"]["DNSName"] += "."
    elif variant == "remove_dot":
        requested["AliasTarget"]["DNSName"] = target_name
    elif variant == "case_and_dot":
        requested["AliasTarget"]["DNSName"] = target_name.upper() + "."
    elif variant == "different_name":
        requested["AliasTarget"]["DNSName"] = "other." + zone_name
    elif variant == "different_zone":
        requested["AliasTarget"]["HostedZoneId"] = "ZOTHER"
    else:
        requested["AliasTarget"]["EvaluateTargetHealth"] = True
    marker = {"Name": "marker." + zone_name, "Type": "TXT", "TTL": 60,
              "ResourceRecords": [{"Value": '"propagation-marker"'}]}
    batch = {"Changes": [{"Action": "UPSERT", "ResourceRecordSet": marker},
                         {"Action": "DELETE", "ResourceRecordSet": requested}]}
    before = route53.list_resource_record_sets(HostedZoneId=zone_id)["ResourceRecordSets"]
    if variant.startswith("different_"):
        with pytest.raises(ClientError) as rejected:
            route53.change_resource_record_sets(HostedZoneId=zone_id, ChangeBatch=batch)
        assert rejected.value.response["Error"]["Code"] == "InvalidChangeBatch"
        assert rejected.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
        assert route53.list_resource_record_sets(HostedZoneId=zone_id)["ResourceRecordSets"] == before
    else:
        route53.change_resource_record_sets(HostedZoneId=zone_id, ChangeBatch=batch)
        after = route53.list_resource_record_sets(HostedZoneId=zone_id)["ResourceRecordSets"]
        assert all(record["Name"].rstrip(".") != alias["Name"] for record in after)
        assert any(record["Name"].rstrip(".") == marker["Name"] for record in after)
        assert any(record["Name"].rstrip(".") == target_name for record in after)
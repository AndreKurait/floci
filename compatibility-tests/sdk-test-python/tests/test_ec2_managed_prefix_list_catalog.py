"""Verify a configured AWS-managed prefix list through the EC2 SDK."""
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

IMAGE = os.environ.get("FLOCI_PREFIX_LIST_CATALOG_TEST_IMAGE")
pytestmark = pytest.mark.skipif(not IMAGE, reason="Set FLOCI_PREFIX_LIST_CATALOG_TEST_IMAGE for Docker checks")
PREFIX = "pl-0123456789abcdef0"


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, timeout=60).strip()


@pytest.fixture(scope="module")
def endpoint(tmp_path_factory):
    catalog = tmp_path_factory.mktemp("prefix-catalog") / "prefix-lists.json"
    catalog.write_text(json.dumps({"eu-west-1": [{
        "prefixListId": PREFIX, "prefixListName": "example-managed-service", "cidrs": ["192.0.2.19/24"],
    }]}))
    name = "floci-prefix-catalog-" + uuid.uuid4().hex[:12]
    container = docker(
        "create", "--name", name, "--publish", "127.0.0.1::4566",
        "--env", "FLOCI_SERVICES_EC2_MOCK=true",
        "--env", "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED=false",
        "--env", "FLOCI_STORAGE_MODE=memory",
        "--env", "FLOCI_SERVICES_EC2_MANAGED_PREFIX_LIST_CATALOG_PATH=/tmp/prefix-lists.json", IMAGE,
    )
    try:
        docker("cp", str(catalog), container + ":/tmp/prefix-lists.json")
        docker("start", container)
        port = json.loads(docker("inspect", container))[0]["NetworkSettings"]["Ports"]["4566/tcp"][0]["HostPort"]
        url = "http://127.0.0.1:" + port
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 60
        while True:
            try:
                with opener.open(url + "/_floci/health", timeout=2) as response:
                    assert response.status == 200
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)
        yield url
    finally:
        docker("rm", "-f", "-v", container)
        assert not docker("ps", "-aq", "--filter", "id=" + container)


def client(endpoint, account="111111111111", region="eu-west-1"):
    return boto3.client(
        "ec2", endpoint_url=endpoint, region_name=region,
        aws_access_key_id=account, aws_secret_access_key="test",
        config=Config(proxies={}, connect_timeout=2, read_timeout=5, retries={"total_max_attempts": 1}),
    )


@pytest.mark.parametrize("account", ["111111111111", "222222222222"])
def test_configured_list_is_regional_aws_owned_and_read_only(endpoint, account):
    ec2 = client(endpoint, account)
    row, = ec2.describe_managed_prefix_lists(PrefixListIds=[PREFIX])["PrefixLists"]
    assert row["OwnerId"] == "AWS"
    assert row["PrefixListArn"] == f"arn:aws:ec2:eu-west-1:aws:prefix-list/{PREFIX}"
    assert row["AddressFamily"] == "IPv4"
    legacy, = ec2.describe_prefix_lists(PrefixListIds=[PREFIX])["PrefixLists"]
    assert legacy["Cidrs"] == ["192.0.2.0/24"]
    entry, = ec2.get_managed_prefix_list_entries(PrefixListId=PREFIX)["Entries"]
    assert entry["Cidr"] == "192.0.2.0/24"
    for code, call in (
        ("InvalidPrefixListID.NotFound",
         lambda: client(endpoint, account, "us-east-1").describe_managed_prefix_lists(PrefixListIds=[PREFIX])),
        ("UnsupportedOperation", lambda: ec2.delete_managed_prefix_list(PrefixListId=PREFIX)),
        ("UnsupportedOperation",
         lambda: ec2.modify_managed_prefix_list(PrefixListId=PREFIX, PrefixListName="changed")),
    ):
        with pytest.raises(ClientError) as error:
            call()
        assert error.value.response["Error"]["Code"] == code


def test_security_group_resolves_configured_list_and_rejects_missing_one(endpoint):
    ec2 = client(endpoint)
    group = ec2.create_security_group(GroupName="catalog-consumer", Description="catalog consumer")["GroupId"]
    permission = {"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443,
                  "PrefixListIds": [{"PrefixListId": PREFIX}]}
    try:
        rule, = ec2.authorize_security_group_ingress(
            GroupId=group, IpPermissions=[permission])["SecurityGroupRules"]
        assert rule["PrefixListId"] == PREFIX
        permission["PrefixListIds"] = [{"PrefixListId": "pl-99999999999999999"}]
        with pytest.raises(ClientError) as error:
            ec2.authorize_security_group_ingress(GroupId=group, IpPermissions=[permission])
        assert error.value.response["Error"]["Code"] == "InvalidPrefixListID.NotFound"
        rules = ec2.describe_security_group_rules(Filters=[{"Name": "group-id", "Values": [group]}])
        assert [row["PrefixListId"] for row in rules["SecurityGroupRules"] if row.get("PrefixListId")] == [PREFIX]
    finally:
        ec2.delete_security_group(GroupId=group)

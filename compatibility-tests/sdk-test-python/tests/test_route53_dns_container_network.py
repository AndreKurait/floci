"""Route53 DNS must advertise the network explicitly selected for sibling services."""
import ipaddress
import json
import os
import socket
import struct
import subprocess
import time
import urllib.request
import uuid

import boto3
import pytest
from botocore.config import Config

IMAGE = os.environ.get("FLOCI_DNS_NETWORK_TEST_IMAGE")
pytestmark = pytest.mark.skipif(not IMAGE, reason="Set FLOCI_DNS_NETWORK_TEST_IMAGE for Docker checks")


def docker(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def skip_name(packet, offset):
    while packet[offset]:
        if packet[offset] & 0xC0 == 0xC0:
            return offset + 2
        offset += packet[offset] + 1
    return offset + 1


def query(address, name, kind):
    identity = 19427
    qname = b"".join(bytes([len(label)]) + label.encode() for label in name.rstrip(".").split(".")) + b"\0"
    packet = struct.pack("!6H", identity, 0x100, 1, 0, 0, 0) + qname + struct.pack("!2H", kind, 1)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
        connection.settimeout(3)
        connection.sendto(packet, (address, 53))
        response, _ = connection.recvfrom(4096)
    response_id, flags, questions, answers, _, _ = struct.unpack("!6H", response[:12])
    assert response_id == identity and flags & 0xF == 0
    offset = 12
    for _ in range(questions):
        offset = skip_name(response, offset) + 4
    result = []
    for _ in range(answers):
        offset = skip_name(response, offset)
        record_type, _, _, length = struct.unpack("!HHIH", response[offset:offset + 10])
        offset += 10
        data = response[offset:offset + length]
        offset += length
        if record_type == 1:
            result.append(socket.inet_ntoa(data))
        elif record_type == 16:
            result.append(data[1:1 + data[0]].decode())
    return result


@pytest.mark.parametrize("selector", ["name", "id"])
def test_advertised_nameserver_uses_selected_network_and_serves_sdk_txt(selector):
    name = "floci-dns-" + uuid.uuid4().hex[:12]
    networks = []
    container = None
    route53 = None
    zone = None
    record = None
    try:
        for suffix in ("first", "selected"):
            temporary = docker("network", "create", "--internal", name + "-" + suffix)
            subnet = json.loads(docker("network", "inspect", temporary))[0]["IPAM"]["Config"][0]["Subnet"]
            docker("network", "rm", temporary)
            networks.append(docker("network", "create", "--internal", "--subnet", subnet, name + "-" + suffix))
        first = json.loads(docker("network", "inspect", networks[0]))[0]
        selected = json.loads(docker("network", "inspect", networks[1]))[0]
        first_ip = str(ipaddress.ip_network(first["IPAM"]["Config"][0]["Subnet"])[10])
        selected_ip = str(ipaddress.ip_network(selected["IPAM"]["Config"][0]["Subnet"])[10])
        setting = selected["Name"] if selector == "name" else selected["Id"]
        container = docker(
            "create", "--name", name, "--hostname", name,
            "--network", first["Name"], "--ip", first_ip,
            "--add-host", name + ":" + first_ip,
            "--volume", "/var/run/docker.sock:/var/run/docker.sock",
            "--env", "FLOCI_SERVICES_DOCKER_NETWORK=" + setting,
            "--env", "FLOCI_SERVICES_EC2_MOCK=true",
            "--env", "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED=false",
            "--env", "FLOCI_SERVICES_UI_ENABLED=false",
            "--env", "FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER1=ns1.localhost.floci.io",
            "--env", "FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER2=ns2.localhost.floci.io",
            "--env", "FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER3=ns3.localhost.floci.io",
            "--env", "FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER4=ns4.localhost.floci.io",
            "--env", "FLOCI_STORAGE_MODE=memory", IMAGE,
        )
        docker("network", "connect", "--ip", selected_ip, selected["Name"], container)
        docker("start", container)
        endpoint = "http://" + selected_ip + ":4566"
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
        route53 = boto3.client(
            "route53", endpoint_url=endpoint, region_name="eu-west-1",
            aws_access_key_id="111111111111", aws_secret_access_key="test",
            config=Config(proxies={}, connect_timeout=2, read_timeout=5,
                          retries={"total_max_attempts": 1}),
        )
        created = route53.create_hosted_zone(Name="network.example.test", CallerReference=name)
        zone = created["HostedZone"]["Id"]
        record = {"Name": "marker.network.example.test", "Type": "TXT", "TTL": 60,
                  "ResourceRecords": [{"Value": '"native-propagation-marker"'}]}
        route53.change_resource_record_sets(
            HostedZoneId=zone, ChangeBatch={"Changes": [{"Action": "CREATE", "ResourceRecordSet": record}]}
        )
        for nameserver in created["DelegationSet"]["NameServers"]:
            addresses = query(selected_ip, nameserver, 1)
            assert addresses == [selected_ip], (
                f"{nameserver} advertised {addresses}; service network is {selected_ip}, "
                f"unselected network is {first_ip}"
            )
            assert query(addresses[0], record["Name"], 16) == ["native-propagation-marker"]
    finally:
        try:
            if zone:
                if record:
                    route53.change_resource_record_sets(
                        HostedZoneId=zone,
                        ChangeBatch={"Changes": [{"Action": "DELETE", "ResourceRecordSet": record}]},
                    )
                route53.delete_hosted_zone(Id=zone)
                assert not route53.list_hosted_zones()["HostedZones"]
        finally:
            if container:
                docker("rm", "-f", "-v", container)
                assert not docker("ps", "-aq", "--filter", "id=" + container)
            for network in reversed(networks):
                docker("network", "rm", network)
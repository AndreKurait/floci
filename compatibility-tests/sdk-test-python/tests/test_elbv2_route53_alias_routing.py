"""Real ALB listener and shared-edge routing through SDK-created DNS aliases."""
import json
import os
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from contextlib import ExitStack, closing, contextmanager

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError

IMAGE = os.environ.get("FLOCI_ALB_ALIAS_TEST_IMAGE")
ECHO_IMAGE = os.environ.get("FLOCI_ALB_ALIAS_ECHO_IMAGE", "python:3.12-slim")
pytestmark = pytest.mark.skipif(not IMAGE, reason="Set FLOCI_ALB_ALIAS_TEST_IMAGE for isolated Docker checks")
PORT = 18123
API_HOST = "api.routing.example.test"


def docker(*args):
    return subprocess.run(
        ["docker", *args], capture_output=True, text=True, check=True, timeout=60
    ).stdout.strip()


def fetch(fleet, port, host, path="/probe?value=hello", authority_port=PORT):
    authority = host if authority_port is None else f"{host}:{authority_port}"
    request = urllib.request.Request(
        f"http://{fleet['address']}:{port}{path}", headers={"Host": authority}
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=5) as response:
            return response.status, response.read(4096).decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read(4096).decode()


def alias_record(name, balancer):
    return {
        "Name": name, "Type": "A",
        "AliasTarget": {
            "HostedZoneId": balancer["CanonicalHostedZoneId"],
            "DNSName": balancer["DNSName"], "EvaluateTargetHealth": False,
        },
    }


def change(fleet, action, record):
    fleet["dns"].change_resource_record_sets(
        HostedZoneId=fleet["zone"],
        ChangeBatch={"Changes": [{"Action": action, "ResourceRecordSet": record}]},
    )


@pytest.fixture
def fleet():
    name = "floci-alb-alias-" + uuid.uuid4().hex[:12]
    shared_network = os.environ.get("FLOCI_ALB_ALIAS_TEST_NETWORK")
    network = shared_network or docker("network", "create", "--internal", name)
    network_name = json.loads(docker("network", "inspect", network))[0]["Name"]
    container = None
    result = {}
    cleanup = ExitStack()
    try:
        container = docker(
            "run", "-d", "--name", name, "--network", network,
            "--env", "FLOCI_SERVICES_EC2_MOCK=true",
            "--env", "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED=false",
            "--env", "FLOCI_SERVICES_ELBV2_MOCK=false",
            "--env", "FLOCI_AUTH_VALIDATE_SIGNATURES=true",
            "--env", "FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true",
            # Keep generated ELB names outside the customer zone while naming the API.
            "--env", f"FLOCI_BASE_URL=http://{API_HOST}:4566",
            "--env", "FLOCI_SERVICES_UI_ENABLED=false",
            "--env", "FLOCI_STORAGE_MODE=memory", IMAGE,
        )
        row, = json.loads(docker("inspect", container))
        address = row["NetworkSettings"]["Networks"][network_name]["IPAddress"]
        endpoint = f"http://{address}:4566"
        deadline = time.monotonic() + 60
        while True:
            try:
                with urllib.request.urlopen(endpoint + "/_localstack/health", timeout=2) as response:
                    json.load(response)
                break
            except (OSError, ValueError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)

        def client(service, credentials):
            return cleanup.enter_context(closing(boto3.client(
                service, endpoint_url=endpoint, region_name="eu-west-1",
                **credentials,
                config=Config(proxies={}, connect_timeout=2, read_timeout=5,
                              retries={"mode": "standard", "total_max_attempts": 3}),
            )))

        root = {"aws_access_key_id": "test", "aws_secret_access_key": "test"}
        organizations = client("organizations", root)
        organizations.create_organization(FeatureSet="ALL")
        cleanup.callback(organizations.delete_organization)
        account = organizations.create_account(
            AccountName=name, Email=name + "@example.test",
        )["CreateAccountStatus"]
        deadline = time.monotonic() + 30
        while account["State"] == "IN_PROGRESS":
            assert time.monotonic() < deadline, account
            time.sleep(0.25)
            account = organizations.describe_create_account_status(
                CreateAccountRequestId=account["Id"],
            )["CreateAccountStatus"]
        assert account["State"] == "SUCCEEDED", account
        account_id = account["AccountId"]
        cleanup.callback(organizations.remove_account_from_organization, AccountId=account_id)
        partition = organizations.describe_account(AccountId=account_id)["Account"]["Arn"].split(":")[1]
        sts = client("sts", root)
        assert sts.get_caller_identity()["Account"] != account_id
        session = sts.assume_role(
            RoleArn=f"arn:{partition}:iam::{account_id}:role/OrganizationAccountAccessRole",
            RoleSessionName=name,
        )["Credentials"]
        member = {
            "aws_access_key_id": session["AccessKeyId"],
            "aws_secret_access_key": session["SecretAccessKey"],
            "aws_session_token": session["SessionToken"],
        }
        assert client("sts", member).get_caller_identity()["Account"] == account_id
        ec2, elb, dns = (client(service, member) for service in ("ec2", "elbv2", "route53"))
        vpc = ec2.create_vpc(CidrBlock="10.64.0.0/16")["Vpc"]["VpcId"]
        subnets = [
            ec2.create_subnet(VpcId=vpc, CidrBlock=f"10.64.{i}.0/24", AvailabilityZone="eu-west-1" + az)
            ["Subnet"]["SubnetId"] for i, az in enumerate(("a", "b"))
        ]
        balancers = {}
        for label in ("one", "two"):
            balancer = elb.create_load_balancer(
                Name="alias-" + label, Subnets=subnets, Scheme="internet-facing", Type="application"
            )["LoadBalancers"][0]
            balancers[label] = balancer
            elb.create_listener(
                LoadBalancerArn=balancer["LoadBalancerArn"], Protocol="HTTP", Port=PORT,
                DefaultActions=[{"Type": "fixed-response", "FixedResponseConfig": {
                    "StatusCode": "200", "ContentType": "text/plain", "MessageBody": label,
                }}],
            )
        zone = dns.create_hosted_zone(Name="routing.example.test", CallerReference=name)["HostedZone"]["Id"]
        result = {
            "address": address, "elb": elb, "dns": dns, "s3": client("s3", member),
            "zone": zone, "balancers": balancers, "vpc": vpc,
            "network": network, "network_name": network_name,
        }
        for label, balancer in balancers.items():
            change(result, "CREATE", alias_record(label + ".routing.example.test", balancer))
        yield result

        # Ordinary APIs must remove their resources before the isolated server is retired.
        for balancer in balancers.values():
            try:
                elb.delete_load_balancer(LoadBalancerArn=balancer["LoadBalancerArn"])
            except ClientError as error:
                if error.response["Error"]["Code"] != "LoadBalancerNotFound":
                    raise
        assert not elb.describe_load_balancers()["LoadBalancers"]
        for group in elb.describe_target_groups()["TargetGroups"]:
            elb.delete_target_group(TargetGroupArn=group["TargetGroupArn"])
        assert not elb.describe_target_groups()["TargetGroups"]
        for record in dns.list_resource_record_sets(HostedZoneId=zone)["ResourceRecordSets"]:
            if record["Type"] not in ("NS", "SOA"):
                change(result, "DELETE", record)
        dns.delete_hosted_zone(Id=zone)
        assert not dns.list_hosted_zones()["HostedZones"]
        for subnet in subnets:
            ec2.delete_subnet(SubnetId=subnet)
        ec2.delete_vpc(VpcId=vpc)
    finally:
        try:
            cleanup.close()
        finally:
            if container:
                docker("rm", "-f", "-v", container)
                assert not docker("ps", "-aq", "--filter", "id=" + container)
            if not shared_network:
                docker("network", "rm", network)


@contextmanager
def echo_target(fleet):
    script = """
import http.server, json, socketserver, threading
class HttpEcho(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"healthy")
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        response = json.dumps({"path": self.path, "host": self.headers["Host"], "body": body.decode()}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)
    def log_message(self, *_args):
        pass
class TcpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        while data := self.request.recv(4096):
            self.request.sendall(data)
tcp = socketserver.ThreadingTCPServer(("0.0.0.0", 9090), TcpEcho)
threading.Thread(target=tcp.serve_forever, daemon=True).start()
http.server.ThreadingHTTPServer(("0.0.0.0", 8080), HttpEcho).serve_forever()
"""
    container = docker(
        "run", "-d", "--network", fleet["network"], "--read-only",
        "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--entrypoint", "python3.12", ECHO_IMAGE, "-B", "-u", "-c", script,
    )
    try:
        row, = json.loads(docker("inspect", container))
        address = row["NetworkSettings"]["Networks"][fleet["network_name"]]["IPAddress"]
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 30
        while True:
            try:
                with opener.open(f"http://{address}:8080/", timeout=2) as response:
                    assert response.read() == b"healthy"
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.1)
        yield address
    finally:
        docker("rm", "-f", "-v", container)
        assert not docker("ps", "-aq", "--filter", "id=" + container)


@pytest.mark.parametrize("port", [PORT, 4566])
def test_public_alias_selects_its_load_balancer_on_a_shared_listener(fleet, port):
    for label, balancer in fleet["balancers"].items():
        assert fetch(fleet, PORT, balancer["DNSName"]) == (200, label)
        assert fetch(fleet, port, label + ".routing.example.test") == (200, label)
    # Creating listeners must not capture normal AWS management requests.
    assert fleet["s3"].list_buckets()["Buckets"] == []


@pytest.mark.parametrize("port", [PORT, 4566])
def test_dns_alias_selection_precedes_other_load_balancer_host_rules(fleet, port):
    first = fleet["balancers"]["one"]
    second = fleet["balancers"]["two"]
    listener = fleet["elb"].describe_listeners(LoadBalancerArn=second["LoadBalancerArn"])["Listeners"][0]
    fleet["elb"].create_rule(
        ListenerArn=listener["ListenerArn"], Priority=10,
        Conditions=[{"Field": "host-header", "HostHeaderConfig": {"Values": ["one.routing.example.test"]}}],
        Actions=[{"Type": "fixed-response", "FixedResponseConfig": {
            "StatusCode": "200", "ContentType": "text/plain", "MessageBody": "wrong-load-balancer",
        }}],
    )
    assert fetch(fleet, port, "one.routing.example.test") == (200, "one")
    first_listener = fleet["elb"].describe_listeners(LoadBalancerArn=first["LoadBalancerArn"])["Listeners"][0]
    fleet["elb"].create_rule(
        ListenerArn=first_listener["ListenerArn"], Priority=10,
        Conditions=[{"Field": "host-header", "HostHeaderConfig": {"Values": ["one.routing.example.test"]}}],
        Actions=[{"Type": "fixed-response", "FixedResponseConfig": {
            "StatusCode": "200", "ContentType": "text/plain", "MessageBody": "original-host-preserved",
        }}],
    )
    assert fetch(fleet, port, "one.routing.example.test") == (200, "original-host-preserved")


def test_alias_update_and_deleted_balancer_do_not_use_a_stale_or_unrelated_listener(fleet):
    host = "one.routing.example.test"
    change(fleet, "UPSERT", alias_record(host, fleet["balancers"]["two"]))
    for port in (PORT, 4566):
        assert fetch(fleet, port, host) == (200, "two")
    fleet["elb"].delete_load_balancer(LoadBalancerArn=fleet["balancers"]["two"]["LoadBalancerArn"])
    for port in (PORT, 4566):
        status, body = fetch(fleet, port, host)
        assert status >= 400 and body not in ("one", "two")
    assert fetch(fleet, PORT, fleet["balancers"]["one"]["DNSName"]) == (200, "one")


def test_cname_chain_to_dualstack_alias_preserves_the_customer_host(fleet):
    target = alias_record("dual.routing.example.test", fleet["balancers"]["two"])
    target["AliasTarget"]["DNSName"] = "dualstack." + target["AliasTarget"]["DNSName"] + "."
    change(fleet, "CREATE", target)
    change(fleet, "CREATE", {
        "Name": "customer.routing.example.test", "Type": "CNAME", "TTL": 60,
        "ResourceRecords": [{"Value": "dual.routing.example.test."}],
    })
    for port in (PORT, 4566):
        assert fetch(fleet, port, "CUSTOMER.ROUTING.EXAMPLE.TEST.") == (200, "two")


@pytest.mark.parametrize("types", [("AAAA",), ("A", "AAAA")])
def test_ipv6_and_dual_family_aliases_accept_hosted_zone_prefixes(fleet, types):
    for record_type in types:
        target = alias_record("dual-family.routing.example.test", fleet["balancers"]["two"])
        target["Type"] = record_type
        target["AliasTarget"]["HostedZoneId"] = "/hostedzone/" + target["AliasTarget"]["HostedZoneId"]
        change(fleet, "CREATE", target)
    for port in (PORT, 4566):
        assert fetch(fleet, port, "dual-family.routing.example.test") == (200, "two")


@pytest.mark.parametrize("fallback", ["single-listener", "host-rule"])
def test_broken_cname_cannot_reach_an_unrelated_listener(fleet, fallback):
    host = "missing-chain.routing.example.test"
    change(fleet, "CREATE", {
        "Name": host, "Type": "CNAME", "TTL": 60,
        "ResourceRecords": [{"Value": "absent.routing.example.test."}],
    })
    if fallback == "single-listener":
        fleet["elb"].delete_load_balancer(LoadBalancerArn=fleet["balancers"]["two"]["LoadBalancerArn"])
    else:
        listener = fleet["elb"].describe_listeners(
            LoadBalancerArn=fleet["balancers"]["two"]["LoadBalancerArn"],
        )["Listeners"][0]
        fleet["elb"].create_rule(
            ListenerArn=listener["ListenerArn"], Priority=10,
            Conditions=[{"Field": "host-header", "HostHeaderConfig": {"Values": [host]}}],
            Actions=[{"Type": "fixed-response", "FixedResponseConfig": {
                "StatusCode": "200", "ContentType": "text/plain", "MessageBody": "wrong-fallback",
            }}],
        )
    for port in (PORT, 4566):
        status, body = fetch(fleet, port, host)
        assert status >= 400 and body not in ("one", "two", "wrong-fallback")


def test_trailing_dot_host_evaluates_the_matching_rule_after_alias_selection(fleet):
    listener = fleet["elb"].describe_listeners(
        LoadBalancerArn=fleet["balancers"]["one"]["LoadBalancerArn"],
    )["Listeners"][0]
    fleet["elb"].create_rule(
        ListenerArn=listener["ListenerArn"], Priority=10,
        Conditions=[{"Field": "host-header", "HostHeaderConfig": {"Values": ["one.routing.example.test"]}}],
        Actions=[{"Type": "fixed-response", "FixedResponseConfig": {
            "StatusCode": "200", "ContentType": "text/plain", "MessageBody": "matched-rule",
        }}],
    )
    for port in (PORT, 4566):
        assert fetch(fleet, port, "ONE.ROUTING.EXAMPLE.TEST.") == (200, "matched-rule")


def test_wrong_canonical_zone_cannot_select_an_unrelated_listener(fleet):
    target = alias_record("wrong-zone.routing.example.test", fleet["balancers"]["one"])
    target["AliasTarget"]["HostedZoneId"] = fleet["zone"].split("/")[-1]
    change(fleet, "CREATE", target)
    fleet["elb"].delete_load_balancer(LoadBalancerArn=fleet["balancers"]["two"]["LoadBalancerArn"])
    for port in (PORT, 4566):
        status, body = fetch(fleet, port, "wrong-zone.routing.example.test")
        assert status >= 400 and body not in ("one", "two")


def test_shared_edge_uses_http_listener_port_for_an_authority_without_a_port(fleet):
    for label, balancer in fleet["balancers"].items():
        fleet["elb"].create_listener(
            LoadBalancerArn=balancer["LoadBalancerArn"], Protocol="HTTP", Port=80,
            DefaultActions=[{"Type": "fixed-response", "FixedResponseConfig": {
                "StatusCode": "200", "ContentType": "text/plain", "MessageBody": label + "-http",
            }}],
        )
        assert fetch(fleet, 4566, label + ".routing.example.test", authority_port=None) == (200, label + "-http")
    assert fleet["s3"].list_buckets()["Buckets"] == []


def test_shared_edge_preserves_the_configured_api_host_after_alias_creation(fleet):
    fleet["elb"].create_listener(
        LoadBalancerArn=fleet["balancers"]["one"]["LoadBalancerArn"], Protocol="HTTP", Port=80,
        DefaultActions=[{"Type": "fixed-response", "FixedResponseConfig": {
            "StatusCode": "200", "ContentType": "text/plain", "MessageBody": "captured-api-host",
        }}],
    )
    status, body = fetch(fleet, 4566, API_HOST, "/_localstack/health", authority_port=None)
    assert status == 200 and "services" in json.loads(body)
    change(fleet, "CREATE", alias_record(API_HOST, fleet["balancers"]["one"]))
    status, body = fetch(fleet, 4566, API_HOST, "/_localstack/health", authority_port=None)
    assert status == 200 and "services" in json.loads(body)
    assert fetch(fleet, PORT, API_HOST) == (200, "one")


def test_unsigned_forwarding_uses_the_load_balancer_owners_target_group(fleet):
    with echo_target(fleet) as address:
        group = fleet["elb"].create_target_group(
            Name="owner-forward", Protocol="HTTP", Port=8080,
            VpcId=fleet["vpc"], TargetType="ip",
        )["TargetGroups"][0]
        fleet["elb"].register_targets(
            TargetGroupArn=group["TargetGroupArn"],
            Targets=[{"Id": address, "Port": 8080}],
        )
        balancer = fleet["balancers"]["one"]
        listener = fleet["elb"].describe_listeners(LoadBalancerArn=balancer["LoadBalancerArn"])["Listeners"][0]
        fleet["elb"].modify_listener(
            ListenerArn=listener["ListenerArn"],
            DefaultActions=[{"Type": "forward", "TargetGroupArn": group["TargetGroupArn"]}],
        )
        fleet["elb"].modify_load_balancer_attributes(
            LoadBalancerArn=balancer["LoadBalancerArn"],
            Attributes=[{"Key": "routing.http.preserve_host_header.enabled", "Value": "true"}],
        )
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for port, host in [(PORT, balancer["DNSName"]), (PORT, "one.routing.example.test"),
                           (4566, "one.routing.example.test")]:
            authority = f"{host}:{PORT}"
            request = urllib.request.Request(
                f"http://{fleet['address']}:{port}/index/_doc/1?refresh=true",
                data=b'{"value":"retained"}', headers={"Host": authority, "Content-Type": "application/json"},
            )
            with opener.open(request, timeout=5) as response:
                assert response.status == 200
                assert json.load(response) == {
                    "path": "/index/_doc/1?refresh=true", "host": authority,
                    "body": '{"value":"retained"}',
                }
        assert fleet["s3"].list_buckets()["Buckets"] == []


def test_unsigned_tcp_forwarding_uses_the_network_load_balancer_owners_target_group(fleet):
    with echo_target(fleet) as address:
        group = fleet["elb"].create_target_group(
            Name="owner-tcp", Protocol="TCP", Port=9090, VpcId=fleet["vpc"], TargetType="ip",
        )["TargetGroups"][0]
        fleet["elb"].register_targets(TargetGroupArn=group["TargetGroupArn"], Targets=[{"Id": address, "Port": 9090}])
        subnet = fleet["balancers"]["one"]["AvailabilityZones"][0]["SubnetId"]
        balancer = fleet["elb"].create_load_balancer(
            Name="owner-tcp", Subnets=[subnet], Scheme="internet-facing", Type="network",
        )["LoadBalancers"][0]
        try:
            listener = fleet["elb"].create_listener(
                LoadBalancerArn=balancer["LoadBalancerArn"], Protocol="TCP", Port=18124,
                DefaultActions=[{"Type": "forward", "TargetGroupArn": group["TargetGroupArn"]}],
            )["Listeners"][0]
            fleet["elb"].modify_listener_attributes(
                ListenerArn=listener["ListenerArn"],
                Attributes=[{"Key": "tcp.idle_timeout.seconds", "Value": "120"}],
            )
            payload = bytes([0, 0, 0, 8, 4, 210, 22, 47])
            with socket.create_connection((fleet["address"], 18124), timeout=5) as connection:
                connection.sendall(payload)
                received = b""
                while len(received) < len(payload):
                    data = connection.recv(len(payload) - len(received))
                    assert data, "The nondefault owner's TCP connection closed before its target replied"
                    received += data
                assert received == payload
        finally:
            fleet["elb"].delete_load_balancer(LoadBalancerArn=balancer["LoadBalancerArn"])

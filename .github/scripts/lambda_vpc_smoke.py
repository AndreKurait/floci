"""Native Lambda, CloudFormation security groups and NAT packet checks."""
import ipaddress
import json
import sys
import time
import uuid

LAMBDA_IMAGE = (
    "public.ecr.aws/lambda/python@sha256:"
    "0fe974382dcb297e80e7feb8c7620d7e8ece5b42b4d01a5a9ffe939c654bf8b0"
)
PORT = 8765
HANDLER = """
import errno
import http.client
import os
import socket
import time
from urllib.parse import urlparse
import boto3
from botocore.config import Config

def handler(event, context):
    if event.get("action") == "identity":
        client = boto3.client("sts", config=Config(
            connect_timeout=2, read_timeout=2, retries={"max_attempts": 0}))
        return {"identity": client.get_caller_identity(), "endpoint": client.meta.endpoint_url,
                "addresses": sorted(set(socket.gethostbyname_ex(
                    urlparse(client.meta.endpoint_url).hostname)[2])),
                "endpoint_override": os.environ.get("AWS_ENDPOINT_URL")}
    started = time.monotonic()
    connection = http.client.HTTPConnection(event["host"], event["port"], timeout=2)
    address, stage = None, "resolve"
    try:
        address = socket.gethostbyname(event["host"])
        stage = "connect"
        connection.connect()
        stage = "response"
        connection.request("GET", "/" + event["nonce"], headers={"Connection": "close"})
        response = connection.getresponse()
        response.read()
        return {"outcome": "received", "status": response.status, "address": address,
                "seconds": time.monotonic() - started}
    except TimeoutError:
        return {"outcome": "timeout", "stage": stage, "address": address,
                "seconds": time.monotonic() - started}
    except OSError as error:
        if error.errno != errno.ENETUNREACH:
            raise
        return {"outcome": "unreachable", "stage": stage, "address": address,
                "seconds": time.monotonic() - started}
    finally:
        connection.close()
"""


def template(smoke):
    vpc = smoke.aws("ec2", "describe-vpcs", VpcIds=[smoke.created["vpc"]])["Vpcs"][0]
    subnets = smoke.aws(
        "ec2", "describe-subnets",
        Filters=[{"Name": "vpc-id", "Values": [smoke.created["vpc"]]}])["Subnets"]
    occupied = [ipaddress.ip_network(row["CidrBlock"]) for row in subnets]
    available = next(network for network in ipaddress.ip_network(vpc["CidrBlock"]).subnets(
        new_prefix=max(network.prefixlen for network in occupied))
        if not any(network.overlaps(existing) for existing in occupied))
    api_address, = smoke.record["protected_vpc_path"]["dns_addresses"]

    def ref(name):
        return {"Ref": name}

    def resource(kind, properties, **options):
        return {"Type": "AWS::" + kind, "Properties": properties, **options}

    resources = {
        "Subnet": resource("EC2::Subnet", {
            "VpcId": smoke.created["vpc"], "CidrBlock": str(available)}),
        "Routes": resource("EC2::RouteTable", {"VpcId": smoke.created["vpc"]}),
        "Association": resource("EC2::SubnetRouteTableAssociation", {
            "SubnetId": ref("Subnet"), "RouteTableId": ref("Routes")}),
        "NatRoute": resource("EC2::Route", {
            "RouteTableId": ref("Routes"), "DestinationCidrBlock": "0.0.0.0/0",
            "NatGatewayId": smoke.created["nat"]}),
        "CallerGroup": resource("EC2::SecurityGroup", {
            "VpcId": smoke.created["vpc"], "GroupDescription": "Explicit Lambda test egress",
            "SecurityGroupEgress": [
                {"IpProtocol": "tcp", "FromPort": PORT, "ToPort": PORT, "CidrIp": "0.0.0.0/0"},
                {"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443,
                 "CidrIp": api_address + "/32"}]}),
        # The serving VPC intentionally reuses the caller VPC's private address space.
        "ServerVpc": resource("EC2::VPC", {"CidrBlock": vpc["CidrBlock"]}),
        "ServerSubnet": resource("EC2::Subnet", {
            "VpcId": ref("ServerVpc"), "CidrBlock": str(occupied[0])}),
        "Gateway": resource("EC2::InternetGateway", {}),
        "GatewayAttachment": resource("EC2::VPCGatewayAttachment", {
            "VpcId": ref("ServerVpc"), "InternetGatewayId": ref("Gateway")}),
        "ServerRoutes": resource("EC2::RouteTable", {"VpcId": ref("ServerVpc")}),
        "ServerAssociation": resource("EC2::SubnetRouteTableAssociation", {
            "SubnetId": ref("ServerSubnet"), "RouteTableId": ref("ServerRoutes")}),
        "PublicRoute": resource("EC2::Route", {
            "RouteTableId": ref("ServerRoutes"), "DestinationCidrBlock": "0.0.0.0/0",
            "GatewayId": ref("Gateway")}, DependsOn=["GatewayAttachment"]),
        "BackendGroup": resource("EC2::SecurityGroup", {
            "VpcId": ref("ServerVpc"), "GroupDescription": "Unattached backend group"}),
        "FrontendGroup": resource("EC2::SecurityGroup", {
            "VpcId": ref("ServerVpc"), "GroupDescription": "NAT source on the frontend",
            "SecurityGroupIngress": [{
                "IpProtocol": "tcp", "FromPort": PORT, "ToPort": PORT,
                "CidrIp": smoke.record["nat_public_ip"] + "/32"}],
            "SecurityGroupEgress": [{
                "IpProtocol": "tcp", "FromPort": PORT, "ToPort": PORT,
                "DestinationSecurityGroupId": ref("BackendGroup")}]}),
        "Frontend": resource("ElasticLoadBalancing::LoadBalancer", {
            "Subnets": [ref("ServerSubnet")], "SecurityGroups": [ref("FrontendGroup")],
            "Scheme": "internet-facing",
            "Listeners": [{"Protocol": "HTTP", "LoadBalancerPort": PORT,
                           "InstanceProtocol": "HTTP", "InstancePort": PORT}]},
            DependsOn=["PublicRoute", "ServerAssociation"]),
        "Role": resource("IAM::Role", {
            "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [{
                "Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"},
                "Action": "sts:AssumeRole"}]},
            "ManagedPolicyArns": [{"Fn::Sub":
                "arn:${AWS::Partition}:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"}]}),
        "Function": resource("Lambda::Function", {
            "Runtime": "python3.12", "Architectures": ["x86_64"], "Timeout": 15,
            "Role": {"Fn::GetAtt": ["Role", "Arn"]}, "Handler": "index.handler",
            "Code": {"ZipFile": HANDLER},
            "VpcConfig": {"SubnetIds": [ref("Subnet")], "SecurityGroupIds": [ref("CallerGroup")]}},
            DependsOn=["NatRoute", "Association", "Frontend"]),
    }
    return {
        "AWSTemplateFormatVersion": "2010-09-09", "Resources": resources,
        "Outputs": {
            **{name: {"Value": ref(name)} for name in (
                "Function", "Role", "Subnet", "Routes", "CallerGroup", "Frontend",
                "FrontendGroup", "BackendGroup")},
            "FrontendDns": {"Value": {"Fn::GetAtt": ["Frontend", "DNSName"]}},
        },
    }


def exercise_lambda_vpc(smoke, docker):
    print("Checking native VPC Lambda identity, frontend ingress, egress and NAT recovery", flush=True)
    started = time.monotonic()
    result = smoke.record["lambda_vpc"] = {
        "status": "running", "probes": [], "stack_deleted": False,
        "scope": "An empty Classic frontend's 503 proves packet delivery, not application health. "
                 "The fixture uses fresh TCP connections and a two-second client deadline.",
    }

    def require(condition, description, observed):
        if not condition:
            raise ValueError(description + ": " + json.dumps(observed))

    body = template(smoke)
    (smoke.output / "lambda-vpc-template.json").write_text(json.dumps(body, indent=2) + "\n")
    stack = smoke.aws(
        "cloudformation", "create-stack", StackName=smoke.owner + "-lambda",
        TemplateBody=json.dumps(body), Capabilities=["CAPABILITY_IAM"])["StackId"]
    result["stack"] = stack
    try:
        smoke.aws("cloudformation", "wait", "stack-create-complete", StackName=stack, timeout=150)
        outputs = {row["OutputKey"]: row["OutputValue"] for row in smoke.aws(
            "cloudformation", "describe-stacks", StackName=stack)["Stacks"][0]["Outputs"]}
        result["outputs"] = outputs
        configuration = smoke.aws(
            "lambda", "get-function-configuration", FunctionName=outputs["Function"])
        require(configuration["VpcConfig"]["SubnetIds"] == [outputs["Subnet"]]
                and configuration["VpcConfig"]["SecurityGroupIds"] == [outputs["CallerGroup"]],
                "Lambda did not retain the declared VPC attachment", configuration["VpcConfig"])
        frontend = smoke.aws(
            "elb", "describe-load-balancers",
            LoadBalancerNames=[outputs["Frontend"]])["LoadBalancerDescriptions"][0]
        require(frontend["SecurityGroups"] == [outputs["FrontendGroup"]] and not frontend["Instances"],
                "The packet probe needs an empty frontend with its own security group", frontend)
        permissions = smoke.aws(
            "ec2", "describe-security-groups",
            GroupIds=[outputs["CallerGroup"]])["SecurityGroups"][0]["IpPermissionsEgress"]
        actual = {
            (row["IpProtocol"], row.get("FromPort"), row.get("ToPort"),
             tuple(item["CidrIp"] for item in row.get("IpRanges", [])))
            for row in permissions
        }
        api_address, = smoke.record["protected_vpc_path"]["dns_addresses"]
        require(len(permissions) == 2 and actual == {
            ("tcp", PORT, PORT, ("0.0.0.0/0",)),
            ("tcp", 443, 443, (api_address + "/32",)),
        }, "CloudFormation did not replace implicit egress with the two declared rules", permissions)
        result["initial_egress"] = permissions

        def invoke(event):
            path = "/tmp/" + smoke.owner + "-lambda-response.json"
            payload_path = "/tmp/" + smoke.owner + "-lambda-event.json"
            docker("exec", smoke.cli, "/bin/sh", "-c", 'printf "%s" "$2" > "$1"',
                   "lambda-event", payload_path, json.dumps(event))
            response = smoke.aws(
                "lambda", "invoke", "--function-name", outputs["Function"],
                "--payload", "fileb://" + payload_path, path, timeout=90)
            payload = json.loads(docker("exec", smoke.cli, "cat", path).stdout)
            require("FunctionError" not in response, "The native VPC Lambda failed", payload)
            return payload

        identity = invoke({"action": "identity"})
        account = smoke.aws("sts", "get-caller-identity")["Account"]
        require(identity["identity"]["Account"] == account and not identity["endpoint_override"]
                and (":assumed-role/" + outputs["Role"] + "/") in identity["identity"]["Arn"],
                "Lambda did not use its native execution-role identity", identity)
        require(identity["endpoint"].startswith("https://")
                and identity["addresses"] == [api_address],
                "Lambda did not reach the modeled public AWS API through verified HTTPS", identity)
        result["native_identity"] = identity
        print("Lambda verified native AWS identity over HTTPS", flush=True)

        def probe(phase, *expected):
            observed = invoke({"host": outputs["FrontendDns"], "port": PORT, "nonce": uuid.uuid4().hex})
            result["probes"].append({"phase": phase, "observed": observed})
            print("Lambda " + phase + ": " + json.dumps(observed), flush=True)
            require(observed["outcome"] in expected, "Unexpected packet outcome in " + phase, observed)
            if observed["outcome"] == "received":
                require(observed["status"] == 503, "The empty frontend did not answer with 503", observed)
            else:
                require(observed["stage"] == "connect"
                        and observed["address"] == result["probes"][0]["observed"]["address"],
                        "The probe failed outside the intended frontend connection boundary", observed)
                if observed["outcome"] == "timeout":
                    require(observed["seconds"] >= 1.8, "The blocked connection did not wait", observed)

        probe("allowed", "received")
        ingress = {"IpProtocol": "tcp", "FromPort": PORT, "ToPort": PORT,
                   "IpRanges": [{"CidrIp": smoke.record["nat_public_ip"] + "/32"}]}
        smoke.aws("ec2", "revoke-security-group-ingress",
                  GroupId=outputs["FrontendGroup"], IpPermissions=[ingress])
        smoke.aws("ec2", "authorize-security-group-ingress",
                  GroupId=outputs["BackendGroup"], IpPermissions=[ingress])
        probe("wrong-group-allowlisted", "timeout")
        smoke.aws("ec2", "authorize-security-group-ingress",
                  GroupId=outputs["FrontendGroup"], IpPermissions=[ingress])
        probe("frontend-restored", "received")
        egress = {**ingress, "IpRanges": [{"CidrIp": "0.0.0.0/0"}]}
        smoke.aws("ec2", "revoke-security-group-egress",
                  GroupId=outputs["CallerGroup"], IpPermissions=[egress])
        probe("egress-revoked", "timeout")
        smoke.aws("ec2", "authorize-security-group-egress",
                  GroupId=outputs["CallerGroup"], IpPermissions=[egress])
        probe("egress-restored", "received")
        smoke.aws("ec2", "delete-route", RouteTableId=outputs["Routes"], DestinationCidrBlock="0.0.0.0/0")
        probe("nat-route-removed", "timeout", "unreachable")
        smoke.aws("ec2", "create-route", RouteTableId=outputs["Routes"],
                  DestinationCidrBlock="0.0.0.0/0", NatGatewayId=smoke.created["nat"])
        probe("nat-route-restored", "received")
        result["status"] = "passed"
    except Exception as error:
        result.update(status="failed", error=str(error))
        raise
    finally:
        primary_error = sys.exception()
        try:
            try:
                events = smoke.aws("cloudformation", "describe-stack-events", StackName=stack)
                (smoke.output / "lambda-vpc-events.json").write_text(json.dumps(events, indent=2) + "\n")
            finally:
                smoke.aws("cloudformation", "delete-stack", StackName=stack)
                smoke.aws("cloudformation", "wait", "stack-delete-complete", StackName=stack, timeout=150)
                result["stack_deleted"] = True
        except Exception as error:
            result.update(status="failed", cleanup_error=str(error))
            if primary_error is None:
                raise
            primary_error.add_note("Lambda stack cleanup also failed: " + str(error))
        finally:
            result["seconds"] = time.monotonic() - started

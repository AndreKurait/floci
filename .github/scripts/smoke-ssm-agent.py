#!/usr/bin/env python3
"""Exercise an unchanged SSM agent against a published Floci image."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import urllib.request
import uuid

from lambda_vpc_smoke import LAMBDA_IMAGE, exercise_lambda_vpc


AGENT_VERSION = "3.3.5700.0"
AGENT_SHA256 = "fd6c483dfb6f0752c09c9823a617cf5de60aecf5a18cd4d6acbbb454836342a5"
AGENT_URL = (
    "https://amazon-ssm-us-east-1.s3.us-east-1.amazonaws.com/"
    + AGENT_VERSION + "/linux_amd64/amazon-ssm-agent.rpm"
)
BASE_IMAGE = (
    "public.ecr.aws/amazonlinux/amazonlinux@sha256:"
    "957ff255a4ca9c60138ea636b8ae09e29ba146ea7bc95d0dae3923a5faeeb122"
)
AWS_CLI_IMAGE = (
    "public.ecr.aws/aws-cli/aws-cli@sha256:"
    "1c2d7a51b1ff4f460bc3f1e1ee46a6cef47c7429ad9089ef99012a95e472a1a5"
)
AMI = "ami-00000000000000001"
REGION = "ap-southeast-7"
POLICY = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"


def docker(*arguments, timeout=30, check=True):
    return subprocess.run(
        ["docker", *arguments], text=True, capture_output=True, timeout=timeout, check=check
    )


def inspect(name):
    return json.loads(docker("inspect", name).stdout)[0]


def footprint():
    return {
        "containers": sorted(docker("ps", "-aq", "--no-trunc").stdout.splitlines()),
        "networks": sorted(docker("network", "ls", "--no-trunc", "--format", "{{.ID}}").stdout.splitlines()),
        "volumes": sorted(docker("volume", "ls", "-q").stdout.splitlines()),
    }


def wait_for(description, observe, seconds=120):
    deadline = time.monotonic() + seconds
    while True:
        value = observe()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise TimeoutError(description)
        time.sleep(1)


class Smoke:
    def __init__(self, image, helper, output, work):
        self.image, self.helper, self.output, self.work = image, helper, output, work
        self.owner = "floci-agent-smoke-" + uuid.uuid4().hex[:12]
        self.server, self.guest_image = self.owner + "-server", self.owner + "-guest"
        self.cli = self.owner + "-cli"
        self.network = self.owner + "-network"
        self.guest = None
        self.created = {}
        self.command_documents = []
        self.before = footprint()
        self.daemon = docker("info", "--format", "{{.ID}}").stdout.strip()
        self.record = {
            "status": "running", "image": image, "network_helper": helper,
            "agent_version": AGENT_VERSION, "started_at": datetime.now(timezone.utc).isoformat(),
            "agent_executed": False, "association_executed": False,
            "sdk_registration_or_inventory_injection": False,
            "full_vm_boot_qualified": False, "command_transport_qualified": False,
            "cleanup_errors": [],
        }

    def aws(self, service, operation, *arguments, timeout=30, **parameters):
        options = ("--cli-input-json", json.dumps(parameters)) if parameters else ()
        result = docker(
            "exec", "-e", "AWS_ACCESS_KEY_ID=test", "-e", "AWS_SECRET_ACCESS_KEY=test",
            "-e", "AWS_SESSION_TOKEN=", "-e", "AWS_EC2_METADATA_DISABLED=true",
            "-e", "AWS_CONFIG_FILE=/dev/null", "-e", "AWS_SHARED_CREDENTIALS_FILE=/dev/null",
            "-e", "AWS_CA_BUNDLE=/floci-root-ca.crt", "-e", "AWS_PAGER=",
            self.cli, "aws", "--endpoint-url", "https://localhost:4566",
            "--region", REGION, "--output", "json", "--no-paginate",
            service, operation, *arguments, *options,
            timeout=timeout,
        )
        return json.loads(result.stdout) if result.stdout.strip() else {}

    def registered_agent(self, instance):
        try:
            nodes = self.aws(
                "ssm", "describe-instance-information",
                Filters=[{"Key": "InstanceIds", "Values": [instance]}],
            ).get("InstanceInformationList", [])
        except subprocess.CalledProcessError as error:
            if "(InvalidInstanceId) when calling the DescribeInstanceInformation operation:" in (error.stderr or ""):
                return None
            raise
        if not nodes:
            return None
        if (len(nodes) != 1 or nodes[0].get("InstanceId") != instance
                or nodes[0].get("AgentVersion") != AGENT_VERSION):
            raise ValueError("SSM did not observe the expected stock agent")
        return nodes[0] if nodes[0].get("PingStatus") == "Online" else None

    def prepare(self):
        print("Preparing the pinned stock-agent guest", flush=True)
        architecture = docker("info", "--format", "{{.Architecture}}").stdout.strip()
        if architecture not in ("x86_64", "amd64"):
            raise ValueError("This smoke currently qualifies the x86_64 agent")
        socket = os.environ.get("DOCKER_HOST", "unix:///var/run/docker.sock")
        if not socket.startswith("unix://"):
            raise ValueError("Run this smoke on a local Docker daemon")
        if "@sha256:" not in self.image or "@sha256:" not in self.helper:
            raise ValueError("Published image and helper digests are required")
        began = time.monotonic()
        with urllib.request.urlopen(AGENT_URL, timeout=45) as response:
            package = response.read(64 * 1024 * 1024 + 1)
        if len(package) > 64 * 1024 * 1024 or hashlib.sha256(package).hexdigest() != AGENT_SHA256:
            raise ValueError("The downloaded stock agent does not match its pinned checksum")
        (self.work / "amazon-ssm-agent.rpm").write_bytes(package)
        (self.work / "Dockerfile").write_text(
            f"FROM {BASE_IMAGE}\n"
            "COPY amazon-ssm-agent.rpm /tmp/amazon-ssm-agent.rpm\n"
            "RUN rpm --install /tmp/amazon-ssm-agent.rpm && rm /tmp/amazon-ssm-agent.rpm\n"
            f'LABEL floci.smoke.owner="{self.owner}"\n'
            'ENTRYPOINT ["/bin/sh","-ec","trap \'exit 0\' TERM; sleep 2147483647 & wait"]\n'
        )
        built = docker("build", "--platform", "linux/amd64", "--tag", self.guest_image,
                       str(self.work), timeout=240)
        self.created["guest_image"] = True
        (self.output / "guest-build.log").write_text(built.stdout + built.stderr)
        guest_image = json.loads(docker("image", "inspect", self.guest_image).stdout)[0]
        self.record["guest_image_id"] = guest_image["Id"]
        self.record["agent_rpm_sha256"] = AGENT_SHA256
        self.record["guest_preparation_seconds"] = time.monotonic() - began
        catalog = {"images": [{
            "imageId": AMI, "dockerImage": self.guest_image, "name": "stock-agent-smoke",
            "description": "Generic AL2 container for the stock SSM agent",
            "architecture": "x86_64", "guestRuntime": "image",
            "creationDate": "2026-01-01T00:00:00.000Z",
        }], "defaultDockerImage": self.guest_image}
        (self.work / "catalog.json").write_text(json.dumps(catalog))
        docker("pull", "--platform", "linux/amd64", self.image, timeout=240)
        docker("pull", "--platform", "linux/amd64", self.helper, timeout=240)
        docker("pull", "--platform", "linux/amd64", LAMBDA_IMAGE, timeout=240)
        docker("pull", "--platform", "linux/amd64", AWS_CLI_IMAGE, timeout=240)
        self.record["lambda_runtime_image"] = LAMBDA_IMAGE
        self.record["aws_cli_image"] = AWS_CLI_IMAGE
        image = json.loads(docker("image", "inspect", self.image).stdout)[0]
        configured = dict(value.split("=", 1) for value in image["Config"]["Env"] if "=" in value)
        for key in ("FLOCI_SERVICES_EC2_METADATA_HELPER_IMAGE",
                    "FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_HELPER_IMAGE",
                    "FLOCI_SERVICES_ECS_TASK_ROLE_CREDENTIALS_PROXY_IMAGE"):
            if configured.get(key) != self.helper:
                raise ValueError(f"The published image does not declare the expected helper: {key}")
        self.record["image_id"] = image["Id"]
        self.record["source_commit"] = image["Config"].get("Labels", {}).get("org.opencontainers.image.revision")
        docker("network", "create", "--label", "floci.smoke.owner=" + self.owner, self.network)
        settings = {
            "FLOCI_DOCKER_RESOURCE_NAMESPACE": self.owner,
            "FLOCI_TLS_ENABLED": "true", "FLOCI_TLS_SELF_SIGNED": "true",
            "FLOCI_BASE_URL": "https://localhost:4566",
            "FLOCI_STORAGE_MODE": "memory", "FLOCI_STORAGE_PERSISTENT_PATH": "/app/data",
            "FLOCI_DNS_SPOOF_AWS_ENDPOINTS": "true",
            "FLOCI_DNS_CONTAINER_FALLBACK_ENABLED": "false",
            "FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK": self.network,
            "FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE": self.server,
            "FLOCI_SERVICES_LAMBDA_RUNTIME_IMAGES__PYTHON3_12_": LAMBDA_IMAGE,
            "FLOCI_SERVICES_EC2_IMAGE_CATALOG_PATH": "/catalog.json",
            "FLOCI_SERVICES_EC2_MOCK": "false", "FLOCI_SERVICES_EC2_PRIVILEGED": "false",
            "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED": "true",
            "FLOCI_SERVICES_EC2_VPC_NETWORKS_INTERNAL": "false",
            "FLOCI_SERVICES_EC2_VPC_NETWORKS_ISOLATE_ADDRESS_SPACES": "true",
            "FLOCI_SERVICES_EC2_AWS_FAITHFUL_PRIVATE_IP": "true",
            "FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_ENABLED": "true",
            "FLOCI_NETWORK_VPC_ROUTING_ENABLED": "true",
            "FLOCI_NETWORK_VPC_ROUTING_PUBLIC_AWS_API": "true",
            "FLOCI_SERVICES_ELB_VPC_FRONTENDS": "true",
            "FLOCI_SERVICES_EC2_VOLUME_BLOCK_DEVICES": "false",
            "FLOCI_SERVICES_EC2_PUBLISH_SECURITY_GROUP_PORTS": "false",
            "FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED": "true",
            "FLOCI_AUTH_VALIDATE_SIGNATURES": "true", "FLOCI_DEFAULT_REGION": REGION,
            "JAVA_TOOL_OPTIONS": "-Xmx2g -XX:ActiveProcessorCount=4",
        }
        print("Starting the published image through its standard launcher", flush=True)
        began = time.monotonic()
        docker(
            "run", "--detach", "--name", self.server, "--network", self.network,
            "--label", "floci.smoke.owner=" + self.owner,
            "--volume", socket.removeprefix("unix://") + ":/var/run/docker.sock",
            "--mount", f"type=bind,source={self.work / 'catalog.json'},target=/catalog.json,readonly",
            *[part for key, value in settings.items() for part in ("--env", key + "=" + value)],
            self.image,
        )
        self.created["server"] = True
        def healthy():
            state = inspect(self.server)["State"]
            if not state["Running"]:
                raise ValueError(f"The published image exited during startup: {state['ExitCode']}")
            return state.get("Health", {}).get("Status") == "healthy"
        wait_for("Published Floci image did not become healthy", healthy)
        runtime = inspect(self.server)
        for field in ("Entrypoint", "Cmd", "Healthcheck"):
            if runtime["Config"][field] != image["Config"][field]:
                raise ValueError("The stock image launch changed: " + field)
        self.record["healthy_ready_seconds"] = time.monotonic() - began
        ca = self.work / "floci-root-ca.crt"
        docker("cp", self.server + ":/app/data/tls/floci-root-ca.crt", str(ca))
        docker(
            "run", "--detach", "--name", self.cli, "--network", "container:" + self.server,
            "--label", "floci.smoke.owner=" + self.owner, "--read-only",
            "--tmpfs", "/tmp:rw,nosuid,nodev,size=16m",
            "--mount", f"type=bind,source={ca},target=/floci-root-ca.crt,readonly",
            "--entrypoint", "/bin/sh", AWS_CLI_IMAGE,
            "-ec", "exec sleep 2147483647",
        )
        self.record["aws_cli_version"] = docker("exec", self.cli, "aws", "--version").stdout.strip()

    def prepare_vpc(self):
        print("Preparing private and public subnets with a routed NAT gateway", flush=True)
        self.created["vpc"] = self.aws("ec2", "create-vpc", CidrBlock="10.73.0.0/24")["Vpc"]["VpcId"]
        for key, cidr in (("subnet", "10.73.0.0/26"), ("public_subnet", "10.73.0.64/26")):
            self.created[key] = self.aws(
                "ec2", "create-subnet", VpcId=self.created["vpc"], CidrBlock=cidr
            )["Subnet"]["SubnetId"]
        self.created["gateway"] = self.aws(
            "ec2", "create-internet-gateway"
        )["InternetGateway"]["InternetGatewayId"]
        self.aws("ec2", "attach-internet-gateway", InternetGatewayId=self.created["gateway"],
                 VpcId=self.created["vpc"])
        self.created["gateway_attachment"] = True
        for prefix, subnet in (("private", "subnet"), ("public", "public_subnet")):
            routes = self.aws("ec2", "create-route-table", VpcId=self.created["vpc"])["RouteTable"]["RouteTableId"]
            self.created[prefix + "_routes"] = routes
            self.created[prefix + "_association"] = self.aws(
                "ec2", "associate-route-table", RouteTableId=routes, SubnetId=self.created[subnet]
            )["AssociationId"]
        self.aws("ec2", "create-route", RouteTableId=self.created["public_routes"],
                 DestinationCidrBlock="0.0.0.0/0", GatewayId=self.created["gateway"])
        address = self.aws("ec2", "allocate-address", Domain="vpc")
        self.created["allocation"] = address["AllocationId"]
        nat = self.aws("ec2", "create-nat-gateway", SubnetId=self.created["public_subnet"],
                       AllocationId=address["AllocationId"])["NatGateway"]
        self.created["nat"] = nat["NatGatewayId"]
        if nat["State"] != "available":
            raise ValueError("The modeled NAT did not become available: " + json.dumps(nat))
        self.record["nat_public_ip"] = address["PublicIp"]
        self.aws("ec2", "create-route", RouteTableId=self.created["private_routes"],
                 DestinationCidrBlock="0.0.0.0/0", NatGatewayId=self.created["nat"])
        self.created["group"] = self.aws(
            "ec2", "create-security-group", VpcId=self.created["vpc"], GroupName=self.owner,
            Description="Stock SSM agent through a private subnet and public NAT",
        )["GroupId"]
        permissions = self.aws("ec2", "describe-security-groups",
                               GroupIds=[self.created["group"]])["SecurityGroups"][0]["IpPermissionsEgress"]
        if permissions:
            self.aws("ec2", "revoke-security-group-egress", GroupId=self.created["group"],
                     IpPermissions=permissions)
        self.aws("ec2", "authorize-security-group-egress", GroupId=self.created["group"], IpPermissions=[{
            "IpProtocol": "tcp", "FromPort": 443, "ToPort": 443,
            "IpRanges": [{"CidrIp": "192.0.2.1/32", "Description": "Modeled public AWS HTTPS APIs"}],
        }])

    def verify_vpc_path(self):
        guest = inspect(self.guest)
        mode = guest["HostConfig"]["NetworkMode"]
        if not mode.startswith("container:"):
            raise ValueError("The guest does not use a protected network namespace")
        namespace = inspect(mode.split(":", 1)[1])
        if namespace["Config"]["Labels"].get("io.floci.security-group.helper") != "true":
            raise ValueError("The guest network namespace has no security-group enforcement")
        settings = dict(value.split("=", 1) for value in guest["Config"]["Env"] if "=" in value)
        if settings.get("AWS_ENDPOINT_URL"):
            raise ValueError("The private guest received an AWS endpoint override")
        hostname = "ssm." + REGION + ".amazonaws.com"
        addresses = set(line.split()[0] for line in docker(
            "exec", self.guest, "getent", "ahostsv4", hostname).stdout.splitlines())
        if addresses != {"192.0.2.1"}:
            raise ValueError("The private guest did not resolve the modeled public AWS destination")
        bridges = docker("ps", "-q", "--filter", "label=floci_namespace=" + self.owner,
                         "--filter", "label=io.floci.service=vpc-aws-api").stdout.split()
        if len(bridges) != 1:
            raise ValueError("Expected one owned public AWS API bridge")
        observed = docker("logs", bridges[0])
        if "AF=2 " + self.record["nat_public_ip"] + ":" not in observed.stdout + observed.stderr:
            raise ValueError("The public AWS endpoint did not observe the NAT Elastic IP")
        self.record["protected_vpc_path"] = {
            "qualified": True, "namespace": namespace["Id"], "hostname": hostname,
            "dns_addresses": sorted(addresses), "endpoint_override_absent": True,
            "nat_source_observed": self.record["nat_public_ip"],
        }

    def exercise(self):
        print("Creating the instance role and tag-targeted inventory association", flush=True)
        name = self.owner
        trust = {"Version": "2012-10-17", "Statement": [{
            "Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole",
        }]}
        self.aws("iam", "create-role", RoleName=name, AssumeRolePolicyDocument=json.dumps(trust))
        self.created["role"] = True
        self.aws("iam", "attach-role-policy", RoleName=name, PolicyArn=POLICY)
        self.created["policy"] = True
        self.aws("iam", "create-instance-profile", InstanceProfileName=name)
        self.created["profile"] = True
        self.aws("iam", "add-role-to-instance-profile", InstanceProfileName=name, RoleName=name)
        self.created["profile_role"] = True
        self.prepare_vpc()
        document = {"schemaVersion": "2.2", "mainSteps": [{
            "action": "aws:softwareInventory", "name": "software",
            "inputs": {"applications": "Enabled", "awsComponents": "Enabled"},
        }]}
        self.aws("ssm", "create-document", Name=name, DocumentType="Command",
                 Content=json.dumps(document))
        self.created["document"] = True
        self.created["association"] = self.aws(
            "ssm", "create-association", Name=name,
            Targets=[{"Key": "tag:FlociAgentSmoke", "Values": [name]}],
            ScheduleExpression="rate(12 hours)",
        )["AssociationDescription"]["AssociationId"]
        ca = docker("exec", self.server, "cat", "/app/data/tls/floci-root-ca.crt").stdout
        if "-----END CERTIFICATE-----" not in ca:
            raise ValueError("Published runtime did not generate its CA")
        user_data = (
            "#!/bin/sh\nset -eu\n"
            "mkdir -p /etc/pki/ca-trust/source/anchors /var/log/amazon/ssm\n"
            "cat > /etc/pki/ca-trust/source/anchors/floci.crt <<'FLOCI_CA'\n"
            + ca.rstrip() + "\nFLOCI_CA\nupdate-ca-trust\n"
            "nohup env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN "
            "-u AWS_ENDPOINT_URL -u AWS_REGION -u AWS_DEFAULT_REGION -u AWS_CA_BUNDLE -u SSL_CERT_FILE "
            "/usr/bin/amazon-ssm-agent </dev/null >/var/log/amazon/ssm/launch.log 2>&1 &\n"
        )
        began = time.monotonic()
        instance = self.aws(
            "ec2", "run-instances", ImageId=AMI, InstanceType="t3.nano", MinCount=1, MaxCount=1,
            SubnetId=self.created["subnet"], SecurityGroupIds=[self.created["group"]],
            IamInstanceProfile={"Name": name}, UserData=user_data,
            MetadataOptions={"HttpTokens": "required", "HttpEndpoint": "enabled"},
            TagSpecifications=[{"ResourceType": "instance", "Tags": [
                {"Key": "FlociAgentSmoke", "Value": name},
            ]}],
        )["Instances"][0]["InstanceId"]
        self.created["instance"] = instance
        self.record["instance_id"] = instance
        print("Waiting for the stock agent to register and execute its association", flush=True)
        self.guest = wait_for("EC2 did not launch the agent guest", lambda: docker(
            "ps", "-q", "--filter", "ancestor=" + self.guest_image).stdout.strip())
        if inspect(self.guest)["Image"] != self.record["guest_image_id"]:
            raise ValueError("EC2 launched a different guest image")
        wait_for("The stock agent did not register",
                 lambda: self.registered_agent(instance), seconds=180)
        self.record["agent_executed"] = True

        def completed():
            association = self.aws(
                "ssm", "describe-association", AssociationId=self.created["association"]
            )["AssociationDescription"]
            status = association.get("Overview", {}).get("Status")
            if status == "Failed":
                raise ValueError("The stock agent reported a failed association")
            return association if status == "Success" and association.get("LastExecutionDate") else None

        self.record["association"] = wait_for("The agent did not execute its association", completed, seconds=180)
        entries, tokens, token = [], set(), None
        while True:
            page = self.aws(
                "ssm", "list-inventory-entries", InstanceId=instance, TypeName="AWS:Application",
                MaxResults=50, **({"NextToken": token} if token else {}),
            )
            entries.extend(page.get("Entries", []))
            token = page.get("NextToken")
            if not token:
                break
            if token in tokens:
                raise ValueError("Inventory pagination repeated a token")
            tokens.add(token)
        installed = next((item for item in entries if item.get("Name") == "amazon-ssm-agent"), None)
        if installed is None or installed["Version"] != AGENT_VERSION:
            raise ValueError("The agent did not report its installed package")
        self.record.update(
            association_executed=True, application_count=len(entries),
            installed_agent=installed, agent_and_inventory_seconds=time.monotonic() - began,
        )
        self.verify_vpc_path()

    def cleanup(self):
        print("Cleaning up the API resources and owned runtime", flush=True)
        def attempt(name, operation):
            try:
                operation()
            except Exception as error:
                self.record["cleanup_errors"].append({"resource": name, "error": str(error)})

        if self.guest:
            for name in ("amazon-ssm-agent.log", "errors.log", "launch.log"):
                def capture(log_name=name):
                    result = docker("exec", self.guest, "cat",
                                    "/var/log/amazon/ssm/" + log_name, check=False)
                    (self.output / log_name).write_text(result.stdout + result.stderr)
                attempt("agent-log:" + name, capture)
        if "instance" in self.created:
            def terminate():
                instance = self.created["instance"]
                self.aws("ec2", "terminate-instances", InstanceIds=[instance])
                wait_for("EC2 guest did not terminate", lambda: self.aws(
                    "ec2", "describe-instances", InstanceIds=[instance],
                )["Reservations"][0]["Instances"][0]["State"]["Name"] == "terminated")
                if self.guest and docker("inspect", self.guest, check=False).returncode == 0:
                    raise ValueError("EC2 termination left its guest container")
            attempt("instance", terminate)
        actions = [
            ("association", "ssm", "delete-association", {"AssociationId": self.created.get("association")}),
            ("document", "ssm", "delete-document", {"Name": self.owner}),
            ("group", "ec2", "delete-security-group", {"GroupId": self.created.get("group")}),
            ("private_association", "ec2", "disassociate-route-table",
             {"AssociationId": self.created.get("private_association")}),
            ("private_routes", "ec2", "delete-route-table", {"RouteTableId": self.created.get("private_routes")}),
            ("nat", "ec2", "delete-nat-gateway", {"NatGatewayId": self.created.get("nat")}),
            ("allocation", "ec2", "release-address", {"AllocationId": self.created.get("allocation")}),
            ("public_association", "ec2", "disassociate-route-table",
             {"AssociationId": self.created.get("public_association")}),
            ("public_routes", "ec2", "delete-route-table", {"RouteTableId": self.created.get("public_routes")}),
            ("gateway_attachment", "ec2", "detach-internet-gateway",
             {"InternetGatewayId": self.created.get("gateway"), "VpcId": self.created.get("vpc")}),
            ("gateway", "ec2", "delete-internet-gateway", {"InternetGatewayId": self.created.get("gateway")}),
            ("public_subnet", "ec2", "delete-subnet", {"SubnetId": self.created.get("public_subnet")}),
            ("subnet", "ec2", "delete-subnet", {"SubnetId": self.created.get("subnet")}),
            ("vpc", "ec2", "delete-vpc", {"VpcId": self.created.get("vpc")}),
            ("profile_role", "iam", "remove-role-from-instance-profile",
             {"InstanceProfileName": self.owner, "RoleName": self.owner}),
            ("profile", "iam", "delete-instance-profile", {"InstanceProfileName": self.owner}),
            ("policy", "iam", "detach-role-policy", {"RoleName": self.owner, "PolicyArn": POLICY}),
            ("role", "iam", "delete-role", {"RoleName": self.owner}),
        ]
        for name, service, operation, parameters in actions:
            if name in self.created:
                attempt(name, lambda s=service, op=operation, p=parameters: self.aws(s, op, **p))
        def remove_cli():
            observed = docker("inspect", self.cli, check=False)
            if observed.returncode:
                return
            if json.loads(observed.stdout)[0]["Config"]["Labels"].get("floci.smoke.owner") != self.owner:
                raise ValueError("The CLI name belongs to a different owner")
            docker("rm", "--force", "--volumes", self.cli)
        attempt("cli-remove", remove_cli)
        def remove_server():
            observed = docker("inspect", self.server, check=False)
            if observed.returncode:
                return
            if json.loads(observed.stdout)[0]["Config"]["Labels"].get("floci.smoke.owner") != self.owner:
                raise ValueError("The server name belongs to a different owner")
            def capture():
                result = docker("logs", self.server, check=False)
                (self.output / "floci.log").write_text(result.stdout + result.stderr)
            attempt("server-log", capture)
            attempt("server-stop", lambda: docker("stop", "--time", "70", self.server, timeout=90))
            docker("rm", "--volumes", self.server)
        attempt("server-remove", remove_server)
        for kind, list_args, remove_args in (
            ("container", ("ps", "-aq"), ("rm", "-fv")),
            ("network", ("network", "ls", "-q"), ("network", "rm")),
            ("volume", ("volume", "ls", "-q"), ("volume", "rm")),
        ):
            def retire_owned(kind=kind, list_args=list_args, remove_args=remove_args):
                remaining = docker(*list_args, "--filter", "label=floci_namespace=" + self.owner).stdout.split()
                for identifier in remaining:
                    self.record["cleanup_errors"].append({
                        "resource": kind + ":" + identifier, "error": "Normal shutdown left this owned resource",
                    })
                    attempt(kind + ":" + identifier, lambda i=identifier: docker(*remove_args, i))
            attempt(kind + "-postflight", retire_owned)
        def remove_network():
            observed = docker("network", "inspect", self.network, check=False)
            if observed.returncode:
                return
            if json.loads(observed.stdout)[0].get("Labels", {}).get("floci.smoke.owner") != self.owner:
                raise ValueError("The network name belongs to a different owner")
            docker("network", "rm", self.network)
        attempt("smoke-network", remove_network)
        if self.created.get("guest_image"):
            attempt("guest-image", lambda: docker("image", "rm", self.guest_image))
        def unchanged():
            if docker("info", "--format", "{{.ID}}").stdout.strip() != self.daemon or footprint() != self.before:
                raise ValueError("Docker footprint changed")
        attempt("daemon", unchanged)
        self.record["cleanup_passed"] = not self.record["cleanup_errors"]


AGENT_PROCESSES = r"""
for entry in /proc/[0-9]*/exe; do
    executable=$(readlink "$entry") || continue
    case "$executable" in
        /usr/bin/amazon-ssm-agent|/usr/bin/ssm-agent-worker|/usr/bin/ssm-document-worker|/usr/bin/ssm-session-worker)
            pid=${entry#/proc/}
            pid=${pid%/exe}
            printf '%s %s\n' "$pid" "$executable"
            ;;
    esac
done
"""


def gateway_open_count(smoke):
    expected = "region=" + REGION + " node=" + smoke.created["instance"]
    result = docker("logs", smoke.server)
    return sum("SSM control channel opened" in line and expected in line
               for line in (result.stdout + result.stderr).splitlines())


def wait_for_gateway(smoke, after_count=0, seconds=45):
    def opened():
        count = gateway_open_count(smoke)
        return count if count > after_count else None
    count = wait_for("The real agent did not authenticate a new message gateway channel", opened, seconds=seconds)
    smoke.record["native_gateway_authenticated"] = True
    smoke.record["native_gateway_connection_count"] = count
    return count


def gateway_execution(smoke, command_id):
    def recorded():
        log = docker("exec", smoke.guest, "cat", "/var/log/amazon/ssm/amazon-ssm-agent.log").stdout
        for line in log.splitlines():
            marker = "[DataBackend] {"
            if marker not in line:
                continue
            try:
                document = json.loads("{" + line.split(marker, 1)[1])
            except json.JSONDecodeError:
                # The agent may still be writing the final line of this log snapshot.
                continue
            if document.get("DocumentInformation", {}).get("CommandID") == command_id:
                transport = document.get("UpstreamServiceName")
                if transport != "MessageGatewayService":
                    raise ValueError("The command used a fallback transport: " + str(transport))
                return {"command_id": command_id, "upstream_service": transport}
        return None

    return wait_for("The native agent did not record the command's execution transport",
                    recorded, seconds=30)


def exercise_commands(smoke):
    print("Executing a versioned custom document through the native gateway", flush=True)
    instance = smoke.created["instance"]
    marker = uuid.uuid4().hex
    path = "/tmp/floci-native-command-" + marker
    name = smoke.owner + "-command"
    document = {
        "schemaVersion": "2.2",
        "parameters": {"Marker": {"type": "String"}},
        "mainSteps": [
            {"action": "aws:runShellScript", "name": "writeMarker", "inputs": {
                "runCommand": ["printf 'version-one:%s\\n' '{{ Marker }}' > " + path,
                               "printf 'first:%s\\n' '{{ Marker }}'"],
                "timeoutSeconds": "30",
            }},
            {"action": "aws:runShellScript", "name": "readMarker", "inputs": {
                "runCommand": ["cat " + path], "timeoutSeconds": "30",
            }},
        ],
    }
    smoke.aws("ssm", "create-document", Name=name, DocumentType="Command", Content=json.dumps(document))
    smoke.command_documents.append(name)
    second = json.loads(json.dumps(document).replace("version-one:", "version-two:"))
    smoke.aws("ssm", "update-document", Name=name, DocumentVersion="$LATEST", Content=json.dumps(second))
    began = time.monotonic()
    command = smoke.aws("ssm", "send-command", InstanceIds=[instance], DocumentName=name,
                        DocumentVersion="$DEFAULT", Parameters={"Marker": [marker]}, TimeoutSeconds=30)["Command"]
    smoke.record["native_command_id"] = command["CommandId"]
    smoke.aws("ssm", "update-document-default-version", Name=name, DocumentVersion="2")

    def complete(plugin, command_id=command["CommandId"]):
        result = smoke.aws("ssm", "get-command-invocation", CommandId=command_id,
                           InstanceId=instance, PluginName=plugin)
        return result if result["Status"] not in ("Pending", "InProgress", "Delayed") else None

    first = wait_for("The agent did not finish the first custom step", lambda: complete("writeMarker"))
    second = wait_for("The agent did not finish the second custom step", lambda: complete("readMarker"))
    smoke.record["custom_command"] = {
        "seconds": time.monotonic() - began, "selected_version": command["DocumentVersion"],
        "first": first, "second": second,
    }
    if first["Status"] != "Success" or first["ResponseCode"] != 0:
        raise ValueError("The real agent failed the first document step: " + json.dumps(first))
    if second["Status"] != "Success" or second["ResponseCode"] != 0:
        raise ValueError("The real agent failed the second document step: " + json.dumps(second))
    if first["StandardOutputContent"].strip() != "first:" + marker:
        raise ValueError("The first step did not execute its parameterized command")
    if second["StandardOutputContent"].strip() != "version-one:" + marker:
        raise ValueError("The command did not execute the selected document version")
    smoke.record["custom_command"]["transport"] = gateway_execution(smoke, command["CommandId"])
    if command["DocumentVersion"] != "1":
        raise ValueError("SendCommand did not resolve the default version at submission")
    listed = smoke.aws("ssm", "list-command-invocations", CommandId=command["CommandId"], Details=True)
    plugins = listed["CommandInvocations"][0]["CommandPlugins"]
    if {item["Name"] for item in plugins} != {"writeMarker", "readMarker"}:
        raise ValueError("Command history did not retain both plugin results")

    failure_name = smoke.owner + "-failure"
    failure_document = {"schemaVersion": "2.2", "mainSteps": [{
        "action": "aws:runShellScript", "name": "failStep",
        "inputs": {"runCommand": ["printf 'native-failure\\n' >&2", "exit 23"], "timeoutSeconds": "30"},
    }]}
    smoke.aws("ssm", "create-document", Name=failure_name, DocumentType="Command",
              Content=json.dumps(failure_document))
    smoke.command_documents.append(failure_name)
    began = time.monotonic()
    failed = smoke.aws("ssm", "send-command", InstanceIds=[instance], DocumentName=failure_name,
                       TimeoutSeconds=30)["Command"]
    failure = wait_for("The agent did not report the failed script",
                             lambda: complete("failStep", failed["CommandId"]))
    smoke.record["failed_command"] = {"seconds": time.monotonic() - began, "invocation": failure}
    if failure["Status"] != "Failed" or failure["ResponseCode"] != 23:
        raise ValueError("The real plugin failure was not preserved: " + json.dumps(failure))
    if "native-failure" not in failure["StandardErrorContent"]:
        raise ValueError("The native plugin stderr was lost")
    smoke.record["failed_command"]["transport"] = gateway_execution(smoke, failed["CommandId"])
    smoke.record["native_custom_document_executed"] = True


def exercise_cancellation(smoke):
    print("Cancelling a real agent command blocked on a FIFO", flush=True)
    prefix = "/tmp/floci-cancel-" + uuid.uuid4().hex
    fifo, ready, escaped = prefix + ".fifo", prefix + ".ready", prefix + ".escaped"
    instance = smoke.created["instance"]
    command = smoke.aws("ssm", "send-command", InstanceIds=[instance],
                        DocumentName="AWS-RunShellScript", TimeoutSeconds=30,
                        Parameters={"commands": ["mkfifo " + fifo, "printf ready > " + ready,
                                                 "cat " + fifo + " >/dev/null",
                                                 "printf escaped > " + escaped],
                                    "executionTimeout": ["120"]})["Command"]

    def running():
        return docker("exec", smoke.guest, "/bin/sh", "-ec",
                            'test -p "$1" && test -f "$2"', "ready-check", fifo, ready,
                            check=False).returncode == 0

    wait_for("The real command did not reach its blocking operation", running, seconds=45)
    transport = gateway_execution(smoke, command["CommandId"])
    began = time.monotonic()
    smoke.aws("ssm", "cancel-command", CommandId=command["CommandId"], InstanceIds=[instance])

    def completed():
        result = smoke.aws("ssm", "get-command-invocation", CommandId=command["CommandId"], InstanceId=instance)
        return result if result["Status"] not in ("Pending", "InProgress", "Delayed", "Cancelling") else None

    invocation = wait_for("The real agent did not finish cancellation", completed, seconds=30)
    smoke.record["cancelled_command"] = {
        "seconds": time.monotonic() - began, "invocation": invocation, "transport": transport,
    }
    if invocation["Status"] != "Cancelled":
        raise ValueError("The real agent did not report cancellation: " + json.dumps(invocation))
    writer = docker("exec", smoke.guest, "timeout", "2", "/bin/sh", "-ec",
                          'printf release > "$1"', "reader-check", fifo, check=False)
    smoke.record["cancelled_command"]["fifo_writer_exit_code"] = writer.returncode
    if writer.returncode != 124:
        raise ValueError("The cancelled command still had a process reading its FIFO")
    docker("exec", smoke.guest, "/bin/sh", "-ec", 'test ! -e "$1"', "escape-check", escaped)
    smoke.record["cancelled_command"]["process_stopped_and_side_effect_absent"] = True
    smoke.record["native_cancellation_qualified"] = True


def agent_processes(smoke):
    return docker("exec", smoke.guest, "/bin/sh", "-ec",
                  AGENT_PROCESSES).stdout.strip().splitlines()


def stop_agent(smoke):
    before = agent_processes(smoke)
    if not any(line.endswith(" /usr/bin/amazon-ssm-agent") for line in before):
        raise ValueError("The real agent was not running before the negative check")
    pids = [line.split()[0] for line in before]
    if not all(pid.isdigit() and int(pid) > 1 for pid in pids):
        raise ValueError("Refusing to signal an invalid process identifier")
    docker("exec", smoke.guest, "/bin/sh", "-c",
                 'for pid do kill -TERM "$pid" 2>/dev/null || true; done',
                 "stop-owned-stock-agent", *pids)
    wait_for("The real agent did not stop", lambda: not agent_processes(smoke), seconds=45)
    return before


def start_agent(smoke):
    docker("exec", smoke.guest, "/bin/sh", "-ec", """
nohup env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
  -u AWS_ENDPOINT_URL -u AWS_REGION -u AWS_DEFAULT_REGION -u AWS_CA_BUNDLE -u SSL_CERT_FILE \
  /usr/bin/amazon-ssm-agent </dev/null >/var/log/amazon/ssm/restart.log 2>&1 &
""")
    wait_for("The unchanged agent did not restart", lambda: agent_processes(smoke), seconds=45)


def exercise_delivery_outage(smoke, *, remove_route=False):
    reason = "missing NAT route" if remove_route else "stopped agent"
    print("Observing delivery expiry and recovery with a " + reason, flush=True)
    before = stop_agent(smoke)
    process_prefix = "route_agent" if remove_route else "agent"
    smoke.record[process_prefix + "_processes_before_stop"] = before
    smoke.record[process_prefix + "_processes_after_stop"] = agent_processes(smoke)
    previous_connections = gateway_open_count(smoke)
    if remove_route:
        smoke.aws("ec2", "delete-route", RouteTableId=smoke.created["private_routes"],
                  DestinationCidrBlock="0.0.0.0/0")
        start_agent(smoke)
    instance = smoke.created["instance"]
    marker = uuid.uuid4().hex
    path = "/tmp/floci-undelivered-" + marker
    began = time.monotonic()
    command = smoke.aws("ssm", "send-command", InstanceIds=[instance],
                        DocumentName="AWS-RunShellScript", TimeoutSeconds=30,
                        Parameters={"commands": ["printf '%s\\n' '" + marker + "' > " + path],
                                    "executionTimeout": ["1"]})["Command"]

    def completed(command_id):
        invocation = smoke.aws("ssm", "get-command-invocation", CommandId=command_id,
                               InstanceId=instance)
        return invocation if invocation["Status"] not in ("Pending", "InProgress", "Delayed") else None

    invocation = wait_for("The undelivered command did not expire",
                                lambda: completed(command["CommandId"]), seconds=70)
    key = "missing_nat_route" if remove_route else "stopped_agent"
    smoke.record[key] = {
        "seconds": time.monotonic() - began, "command": command, "invocation": invocation,
        "processes_after_command": agent_processes(smoke),
    }
    if invocation["Status"] != "TimedOut" or invocation["StatusDetails"] != "Delivery Timed Out":
        raise ValueError("A command with a " + reason + " did not expire as undelivered")
    if invocation["ResponseCode"] != -1:
        raise ValueError("The undelivered command unexpectedly executed")
    if bool(agent_processes(smoke)) != remove_route:
        raise ValueError("The agent's process state changed during the " + reason + " check")
    if gateway_open_count(smoke) != previous_connections:
        raise ValueError("The agent opened a control channel during the " + reason + " check")
    docker("exec", smoke.guest, "/bin/sh", "-ec", 'test ! -e "$1"', "marker-check", path)
    smoke.record[key]["side_effect_absent"] = True
    restored = time.monotonic()
    if remove_route:
        smoke.aws("ec2", "create-route", RouteTableId=smoke.created["private_routes"],
                  DestinationCidrBlock="0.0.0.0/0", NatGatewayId=smoke.created["nat"])
    else:
        start_agent(smoke)
    gateway_connections = wait_for_gateway(
        smoke, after_count=previous_connections, seconds=180 if remove_route else 45)
    smoke.record[key]["reconnection_seconds"] = time.monotonic() - restored
    began = time.monotonic()
    recovery = smoke.aws("ssm", "send-command", InstanceIds=[instance],
                         DocumentName="AWS-RunShellScript", TimeoutSeconds=60,
                         Parameters={"commands": ["printf 'recovered:%s\\n' '" + marker + "'"],
                                     "executionTimeout": ["30"]})["Command"]
    recovered = wait_for("The restarted agent did not execute the recovery command",
                               lambda: completed(recovery["CommandId"]), seconds=100)
    if recovered["Status"] != "Success" or recovered["ResponseCode"] != 0:
        raise ValueError("The recovery command failed: " + json.dumps(recovered))
    if recovered["StandardOutputContent"].strip() != "recovered:" + marker:
        raise ValueError("The recovered agent did not return the submitted unique output")
    docker("exec", smoke.guest, "/bin/sh", "-ec", 'test ! -e "$1"', "marker-check", path)
    smoke.record["recovered_network" if remove_route else "recovered_agent"] = {
        "seconds": time.monotonic() - began, "command": recovery, "invocation": recovered,
        "expired_command_side_effect_still_absent": True,
        "gateway_connections_before_restart": previous_connections,
        "gateway_connections_after_restart": gateway_connections,
        "transport": gateway_execution(smoke, recovery["CommandId"]),
    }
    qualification = "nat_route_delivery_timeout_and_recovery" if remove_route else (
        "stopped_agent_delivery_timeout_and_recovery")
    smoke.record[qualification] = True



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image")
    parser.add_argument("helper_image")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="floci-agent-smoke-") as directory:
        smoke = Smoke(args.image, args.helper_image, args.output, Path(directory))
        try:
            smoke.prepare()
            smoke.exercise()
            wait_for_gateway(smoke)
            exercise_commands(smoke)
            exercise_cancellation(smoke)
            exercise_delivery_outage(smoke)
            exercise_delivery_outage(smoke, remove_route=True)
            exercise_lambda_vpc(smoke, docker)
            smoke.record.update(status="passed", command_transport_qualified=True)
        except Exception as error:
            smoke.record.update(status="failed", error=str(error))
            if isinstance(error, subprocess.CalledProcessError):
                smoke.record["command_error"] = error.stderr
        finally:
            for name in reversed(smoke.command_documents):
                try:
                    smoke.aws("ssm", "delete-document", Name=name)
                except Exception as error:
                    smoke.record["cleanup_errors"].append({"resource": name, "error": str(error)})
            smoke.cleanup()
            if not smoke.record["cleanup_passed"]:
                smoke.record["status"] = "failed"
            smoke.record["elapsed_seconds"] = time.monotonic() - started
            smoke.record["ended_at"] = datetime.now(timezone.utc).isoformat()
            (args.output / "result.json").write_text(json.dumps(smoke.record, indent=2) + "\n")
            print(json.dumps(smoke.record, indent=2))
    return 0 if smoke.record["status"] == "passed" else 1


if __name__ == "__main__":
    def interrupted(_signal, _frame):
        raise InterruptedError("Stock-agent smoke was interrupted")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    raise SystemExit(main())

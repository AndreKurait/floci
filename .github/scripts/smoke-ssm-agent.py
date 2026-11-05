#!/usr/bin/env python3
"""Exercise an unchanged SSM agent against the published Floci JVM image."""
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
AMI = "ami-00000000000000001"
REGION = "us-east-1"
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
        self.network = self.owner + "-network"
        self.guest = None
        self.created = {}
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

    def aws(self, service, operation, **parameters):
        result = docker(
            "exec", "-e", "AWS_ACCESS_KEY_ID=test", "-e", "AWS_SECRET_ACCESS_KEY=test",
            "-e", "AWS_SESSION_TOKEN=", "-e", "AWS_EC2_METADATA_DISABLED=true",
            "-e", "AWS_CONFIG_FILE=/dev/null", "-e", "AWS_SHARED_CREDENTIALS_FILE=/dev/null",
            "-e", "AWS_CA_BUNDLE=/app/data/tls/floci-root-ca.crt", "-e", "AWS_PAGER=",
            self.server, "aws", "--endpoint-url", "https://localhost:4566",
            "--region", REGION, "--output", "json", "--no-paginate",
            service, operation, "--cli-input-json", json.dumps(parameters),
        )
        return json.loads(result.stdout) if result.stdout.strip() else {}

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
        (self.output / "guest-build.log").write_text(built.stdout + built.stderr)
        self.created["guest_image"] = True
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
            "FLOCI_SERVICES_EC2_IMAGE_CATALOG_PATH": "/catalog.json",
            "FLOCI_SERVICES_EC2_MOCK": "false", "FLOCI_SERVICES_EC2_PRIVILEGED": "false",
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
        self.created["vpc"] = self.aws("ec2", "create-vpc", CidrBlock="10.73.0.0/24")["Vpc"]["VpcId"]
        self.created["subnet"] = self.aws(
            "ec2", "create-subnet", VpcId=self.created["vpc"], CidrBlock="10.73.0.0/26"
        )["Subnet"]["SubnetId"]
        self.created["group"] = self.aws(
            "ec2", "create-security-group", VpcId=self.created["vpc"], GroupName=name,
            Description="Stock SSM agent inventory smoke",
        )["GroupId"]
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
        nodes = wait_for("The stock agent did not register", lambda: self.aws(
            "ssm", "describe-instance-information",
            Filters=[{"Key": "InstanceIds", "Values": [instance]}],
        ).get("InstanceInformationList"), seconds=180)
        if len(nodes) != 1 or nodes[0]["AgentVersion"] != AGENT_VERSION:
            raise ValueError("SSM did not observe the expected stock agent")
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
            smoke.record["status"] = "passed"
        except Exception as error:
            smoke.record.update(status="failed", error=str(error))
            if isinstance(error, subprocess.CalledProcessError):
                smoke.record["command_error"] = error.stderr
        finally:
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

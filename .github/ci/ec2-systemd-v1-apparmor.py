"""Boot original AL2 init under both native AppArmor selections on an enforcing host."""
import argparse
from contextlib import ExitStack
import json
from pathlib import Path
import signal
import subprocess
import time
import traceback
import uuid

import boto3
from botocore.config import Config


def docker(*args, check=True):
    return subprocess.run(["docker", *args], text=True, capture_output=True, check=check, timeout=90)


def inspect(name):
    return json.loads(docker("inspect", name).stdout)[0]


def wait_for(description, predicate, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.5)
    raise TimeoutError(description)


def containers(namespace, service=None, resource_id=None):
    filters = ["--filter", "label=floci_namespace=" + namespace]
    if service:
        filters += ["--filter", "label=io.floci.service=" + service]
    if resource_id:
        filters += ["--filter", "label=io.floci.resource-id=" + resource_id]
    return docker("ps", "--all", "--quiet", *filters).stdout.split()


def diagnostics(namespace, output):
    for identifier in containers(namespace):
        current = inspect(identifier)
        (output / (identifier + "-inspect.json")).write_text(json.dumps(current, indent=2))
        logs = docker("logs", identifier, check=False)
        (output / (identifier + ".log")).write_text(logs.stdout + logs.stderr)


def assert_profile(container, profile):
    actual = docker("exec", container, "cat", "/proc/self/attr/current").stdout.strip()
    assert actual == (profile + " (enforce)" if profile != "unconfined" else profile), actual
    if profile != "unconfined":
        denied = docker("exec", container, "sh", "-c",
                        "printf denied > /floci-native-apparmor-denied", check=False)
        assert denied.returncode != 0 and "Permission denied" in denied.stderr, denied
        assert docker("exec", container, "test", "!", "-e",
                      "/floci-native-apparmor-denied", check=False).returncode == 0


def terminate(ec2, instance, namespace):
    ec2.terminate_instances(InstanceIds=[instance])
    wait_for("Native instance termination", lambda: ec2.describe_instances(
        InstanceIds=[instance])["Reservations"][0]["Instances"][0]["State"]["Name"] == "terminated")
    wait_for("Native workload and helper cleanup", lambda:
             not containers(namespace, resource_id=instance))
    wait_for("Root volume cleanup", lambda: not ec2.describe_volumes()["Volumes"])


def boot_case(image, guest_image, profile, output):
    namespace = "floci-native-" + uuid.uuid4().hex[:16]
    server = namespace + "-server"
    network = namespace + "-network"
    record = {"profile": profile or "unconfined", "namespace": namespace, "status": "running"}
    output.mkdir()
    catalog = output / "catalog.json"
    catalog.write_text(json.dumps({"defaultDockerImage": guest_image, "images": [{
        "imageId": "ami-00000000000000001", "dockerImage": guest_image,
        "name": "Original AL2 native init", "description": "Vendor init qualification fixture",
        "architecture": "x86_64", "guestRuntime": "systemd-v1", "cloudInit": True,
        "creationDate": "2026-11-06T00:00:00.000Z",
    }]}))
    settings = {
        "FLOCI_DOCKER_RESOURCE_NAMESPACE": namespace,
        "FLOCI_AUTH_VALIDATE_SIGNATURES": "true",
        "FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED": "true",
        "FLOCI_SERVICES_EC2_MOCK": "false",
        "FLOCI_SERVICES_EC2_PRIVILEGED": "false",
        "FLOCI_SERVICES_EC2_IMAGE_CATALOG_PATH": "/catalog.json",
        "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED": "true",
        "FLOCI_SERVICES_EC2_PUBLISH_SECURITY_GROUP_PORTS": "false",
        "FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_ENABLED": "true",
        "FLOCI_SERVICES_EC2_METADATA_HELPER_IMAGE": "floci/network-helper:local",
        "FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_HELPER_IMAGE": "floci/network-helper:local",
        "FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK": network,
        "FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE": server,
    }
    if profile:
        settings["FLOCI_SERVICES_EC2_NATIVE_INIT_APPARMOR_PROFILE"] = profile
    docker("network", "create", "--label", "io.floci.ci.run=" + namespace, network)
    try:
        docker("run", "--detach", "--name", server, "--network", network,
               "--publish", "127.0.0.1::4566", "--label", "io.floci.ci.run=" + namespace,
               "--volume", "/var/run/docker.sock:/var/run/docker.sock",
               "--group-add", str(Path("/var/run/docker.sock").stat().st_gid),
               "--mount", "type=bind,source=" + str(catalog.resolve()) + ",target=/catalog.json,readonly",
               *[arg for key, value in settings.items() for arg in ("--env", key + "=" + value)], image)
        wait_for("Floci health", lambda: inspect(server)["State"].get("Health", {}).get("Status") == "healthy")
        port = inspect(server)["NetworkSettings"]["Ports"]["4566/tcp"][0]["HostPort"]
        with ExitStack() as cleanup:
            ec2 = boto3.client("ec2", endpoint_url="http://127.0.0.1:" + port, region_name="us-east-1",
                               aws_access_key_id="test", aws_secret_access_key="test",
                               config=Config(retries={"max_attempts": 0}, connect_timeout=3,
                                             read_timeout=30, proxies={}))
            cleanup.callback(ec2.close)
            vpc = ec2.create_vpc(CidrBlock="10.86.0.0/16")["Vpc"]["VpcId"]
            cleanup.callback(ec2.delete_vpc, VpcId=vpc)
            subnet = ec2.create_subnet(VpcId=vpc, CidrBlock="10.86.0.0/24",
                                      AvailabilityZone="us-east-1a")["Subnet"]["SubnetId"]
            cleanup.callback(ec2.delete_subnet, SubnetId=subnet)
            group = ec2.create_security_group(VpcId=vpc, GroupName=namespace,
                                               Description="Native init qualification")["GroupId"]
            cleanup.callback(ec2.delete_security_group, GroupId=group)
            userdata = """#!/bin/sh
set -eu
mkdir -p /var/lib/floci-native-check
printf 'ran\\n' >> /var/lib/floci-native-check/runs
: > /var/lib/floci-native-check/shutdowns
cat > /etc/systemd/system/floci-native-shutdown-check.service <<'NATIVE_SHUTDOWN_SERVICE'
[Unit]
Description=Native shutdown qualification
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/true
ExecStop=/bin/sh -c 'echo stopped >> /var/lib/floci-native-check/shutdowns'
[Install]
WantedBy=multi-user.target
NATIVE_SHUTDOWN_SERVICE
systemctl daemon-reload
systemctl enable floci-native-shutdown-check.service
systemctl start floci-native-shutdown-check.service
token=$(curl --fail --silent --show-error --noproxy '*' --max-time 10 -X PUT \
-H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
curl --fail --silent --show-error --noproxy '*' --max-time 10 \
-H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/instance-id \
> /var/lib/floci-native-check/instance-id
"""
            began = time.monotonic()
            instance = ec2.run_instances(
                ImageId="ami-00000000000000001", InstanceType="t3.small", MinCount=1, MaxCount=1,
                SubnetId=subnet, SecurityGroupIds=[group], UserData=userdata,
                MetadataOptions={"HttpTokens": "required"})["Instances"][0]["InstanceId"]
            cleanup.callback(terminate, ec2, instance, namespace)
            cleanup.callback(diagnostics, namespace, output)

            def booted():
                identifiers = docker("ps", "--all", "--quiet",
                                     "--filter", "label=floci_namespace=" + namespace,
                                     "--filter", "label=io.floci.service=ec2",
                                     "--filter", "label=io.floci.ec2.init-runtime=systemd-v1").stdout.split()
                guests = [inspect(identifier) for identifier in identifiers]
                assert len(guests) <= 1, guests
                if not guests or not guests[0]["State"]["Running"]:
                    return False
                guest = guests[0]
                ready = docker("exec", guest["Id"], "sh", "-ec", """
                    test "$(cat /proc/1/comm)" = systemd
                    test -f /var/lib/cloud/instance/boot-finished
                    test "$(cat /var/lib/floci-native-check/instance-id)" = "$1"
                    test "$(wc -l < /var/lib/floci-native-check/runs)" = 1
                    systemctl is-active --quiet sshd crond floci-native-shutdown-check.service
                    """, "native-ready", instance, check=False)
                return guest if ready.returncode == 0 else False

            guest = wait_for("Original AL2 cloud-init, IMDSv2, sshd and crond", booted)
            record["boot_seconds"] = time.monotonic() - began
            assert not guest["HostConfig"]["Privileged"]
            assert "NET_ADMIN" in guest["HostConfig"]["CapDrop"]
            assert "SYS_TIME" not in guest["HostConfig"]["CapAdd"]
            assert "SYS_PTRACE" not in guest["HostConfig"]["CapAdd"]
            assert_profile(guest["Id"], profile or "unconfined")
            helpers = containers(namespace, "ec2-init")
            assert len(helpers) == 1, helpers
            helper = inspect(helpers[0])
            assert not helper["HostConfig"]["Privileged"]
            assert helper["HostConfig"]["NetworkMode"] == "none"
            assert set(helper["HostConfig"]["CapAdd"]) == {"SYS_ADMIN", "SYS_TIME", "SYS_PTRACE"}
            assert_profile(helper["Id"], profile or "unconfined")
            scope = helper["Config"]["Labels"]["io.floci.ec2.init-scope"]
            docker("exec", helper["Id"], "test", "-d", "/run/floci-cgroups/systemd/" + scope)
            remount = docker("exec", guest["Id"], "sh", "-c",
                            "printf bad > /sys/hypervisor/uuid", check=False)
            assert remount.returncode != 0 and "Read-only file system" in remount.stderr, remount
            record["guest"] = guest
            record["helper"] = helper
            began = time.monotonic()
            docker("exec", guest["Id"], "systemctl", "reboot", check=False)
            wait_for("Native OS reboot", lambda: inspect(guest["Id"])["State"]["StartedAt"]
                     != guest["State"]["StartedAt"])
            rebooted = wait_for("Native boot after OS reboot", booted)
            assert rebooted["Id"] == guest["Id"]
            assert_profile(rebooted["Id"], profile or "unconfined")
            record["reboot_seconds"] = time.monotonic() - began
            shutdowns_before = int(docker("exec", guest["Id"], "sh", "-ec",
                                         "wc -l < /var/lib/floci-native-check/shutdowns").stdout)
            began = time.monotonic()
            ec2.stop_instances(InstanceIds=[instance])
            wait_for("Native EC2 stop", lambda: ec2.describe_instances(
                InstanceIds=[instance])["Reservations"][0]["Instances"][0]["State"]["Name"] == "stopped")
            stopped = inspect(guest["Id"])
            record["stop_state"] = stopped["State"]
            assert not stopped["State"]["Running"] and not stopped["State"]["OOMKilled"], stopped["State"]
            assert stopped["State"]["ExitCode"] in (0, 130), stopped["State"]
            assert stopped["Config"]["StopSignal"] == "SIGRTMIN+3"
            ec2.start_instances(InstanceIds=[instance])
            restarted = wait_for("Native boot after EC2 stop/start", booted)
            assert restarted["Id"] == guest["Id"]
            assert restarted["State"]["StartedAt"] != rebooted["State"]["StartedAt"]
            assert_profile(restarted["Id"], profile or "unconfined")
            shutdowns_after = int(docker("exec", guest["Id"], "sh", "-ec",
                                        "wc -l < /var/lib/floci-native-check/shutdowns").stdout)
            assert shutdowns_after == shutdowns_before + 1, (shutdowns_before, shutdowns_after)
            record["stop_start_seconds"] = time.monotonic() - began
            record["native_service_shutdown_passed"] = True
        record["sdk_cleanup_passed"] = True
        record["status"] = "passed"
    except BaseException:
        record["status"] = "failed"
        record["traceback"] = traceback.format_exc()
        raise
    finally:
        try:
            logs = docker("logs", server, check=False)
            (output / "server.log").write_text(logs.stdout + logs.stderr)
            diagnostics(namespace, output)
            docker("stop", "--time", "20", server, check=False)
            removed = docker("rm", "--volumes", server, check=False)
            if record["status"] == "passed":
                assert removed.returncode == 0, removed
            remaining = containers(namespace)
            record["remaining_containers"] = remaining
            for identifier in remaining:
                assert inspect(identifier)["Config"]["Labels"]["floci_namespace"] == namespace
                docker("rm", "--force", identifier)
            volumes = docker("volume", "ls", "-q", "--filter",
                             "label=floci_namespace=" + namespace).stdout.split()
            for volume in volumes:
                data = json.loads(docker("volume", "inspect", volume).stdout)[0]
                assert data["Labels"]["floci_namespace"] == namespace
                assert data["Driver"] == "local" and not data.get("Options"), data
                docker("run", "--rm", "--label", "io.floci.ci.run=" + namespace,
                       "--mount", "type=volume,source=" + volume + ",target=/backing,readonly",
                       "--entrypoint", "/bin/sh", guest_image, "-ec",
                       'test -z "$(find /backing -mindepth 1 -print -quit)"')
                docker("volume", "rm", volume)
            docker("network", "rm", network)
            assert not remaining, remaining
            record["cleanup_passed"] = True
        except BaseException:
            record.update(status="failed", cleanup_passed=False, cleanup_traceback=traceback.format_exc())
            raise
        finally:
            (output / "result.json").write_text(json.dumps(record, indent=2) + "\n")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--guest-image", required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    def interrupted(number, _frame):
        raise InterruptedError("Qualification interrupted: " + str(number))
    signal.signal(signal.SIGTERM, interrupted)
    args.output.mkdir(parents=True, exist_ok=False)
    result = {"status": "running", "cases": [], "cleanup_passed": False}
    try:
        options = json.loads(docker("info", "--format", "{{json .SecurityOptions}}").stdout)
        assert "name=apparmor" in options, ("An AppArmor-enforcing Docker host is required", options)
        for profile in ("docker-default", "unconfined", args.profile):
            probe = docker("run", "--rm", "--cap-add", "SYS_ADMIN",
                           "--security-opt", "apparmor=" + profile, "--entrypoint", "/bin/sh",
                           args.guest_image, "-ec",
                           "cat /proc/self/attr/current; mkdir /probe; mount -t tmpfs tmpfs /probe",
                           check=False)
            if profile == "docker-default":
                assert probe.returncode != 0 and "docker-default (enforce)" in probe.stdout, probe
                # AL2 mount can report this denial as a failed read-only retry.
                # The identical mount must succeed under both allowed profiles below.
            else:
                assert probe.returncode == 0, probe
            result["cases"].append({"mount_probe_profile": profile, "exit_code": probe.returncode,
                                    "stdout": probe.stdout, "stderr": probe.stderr})
        assert docker("image", "inspect", "floci/network-helper:local", check=False).returncode != 0
        result["cases"].append(boot_case(args.image, args.guest_image, None, args.output / "default"))
        result["cases"].append(boot_case(args.image, args.guest_image, args.profile, args.output / "confined"))
        result.update(status="passed", cleanup_passed=True)
    except BaseException:
        result.update(status="failed", traceback=traceback.format_exc())
        raise
    finally:
        (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()

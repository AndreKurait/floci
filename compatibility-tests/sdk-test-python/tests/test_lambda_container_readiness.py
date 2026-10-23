"""Opt-in real Docker admission tests: FLOCI_READINESS_TEST_IMAGE=<built image>."""
from concurrent.futures import ThreadPoolExecutor
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
import urllib.request
import uuid
import zipfile

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError
import pytest

pytestmark = pytest.mark.skipif(
    not os.environ.get("FLOCI_READINESS_TEST_IMAGE"),
    reason="requires an explicitly selected local Floci image and Docker socket",
)


def docker(*arguments):
    return subprocess.run(
        ["docker", *arguments], check=True, capture_output=True, text=True, timeout=120
    ).stdout.strip()


def wait_for(predicate, seconds=90):
    deadline = time.monotonic() + seconds
    while True:
        value = predicate()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise AssertionError("Readiness test observation deadline expired")
        time.sleep(0.1)


@pytest.fixture
def emulator(tmp_path, request):
    name = "floci-readiness-" + uuid.uuid4().hex[:12]
    gate = tmp_path / "gate"
    gate.mkdir(mode=0o777)
    gate.chmod(0o777)  # The runtime UID writes observations into this disposable test mount.
    network = docker("network", "create", name)
    parent = None
    try:
        probe = shlex.join(("/bin/sh", "-c", "touch /gate/probed; test -f /gate/ready"))
        parent = docker(
            "run", "-d", "--name", name, "--network", network,
            "-p", "127.0.0.1::4566", "-v", "/var/run/docker.sock:/var/run/docker.sock",
            "-e", "FLOCI_DOCKER_RESOURCE_NAMESPACE=" + name,
            "-e", "FLOCI_SERVICES_DOCKER_NETWORK=" + name,
            "-e", "FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK=" + name,
            "-e", "FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE=" + name,
            "-e", "FLOCI_SERVICES_LAMBDA_DOCKER_FLAGS=" + shlex.join(("--volume", f"{gate}:/gate")),
            "-e", "FLOCI_SERVICES_LAMBDA_CONTAINER_READINESS_COMMAND=" + probe,
            "-e", "FLOCI_SERVICES_LAMBDA_CONTAINER_READINESS_TIMEOUT_SECONDS=" + str(getattr(request, "param", 60)),
            "-e", "FLOCI_SERVICES_IAM_ISSUED_SESSION_VERIFICATION_ENABLED=true",
            "-e", "FLOCI_SERVICES_IAM_LAMBDA_IDENTITY_VERIFICATION_ENABLED=true",
            "-e", "FLOCI_STORAGE_MODE=memory",
            "-e", "FLOCI_SERVICES_UI_ENABLED=false",
            "-e", "JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 -Xmx2g",
            os.environ["FLOCI_READINESS_TEST_IMAGE"],
        )
        row = json.loads(docker("inspect", parent))[0]
        port = row["NetworkSettings"]["Ports"]["4566/tcp"][0]["HostPort"]
        endpoint = "http://127.0.0.1:" + port

        def healthy():
            try:
                with urllib.request.urlopen(endpoint + "/_floci/health", timeout=2) as response:
                    return json.load(response)
            except OSError:
                return None

        health = wait_for(healthy)
        assert health["features"]["lambda_container_readiness"] == 1
        client = boto3.client(
            "lambda", endpoint_url=endpoint, region_name="us-east-1",
            aws_access_key_id="test", aws_secret_access_key="test",
            config=Config(read_timeout=90, retries={"total_max_attempts": 1}, proxies={}),
        )
        yield client, gate, name
    finally:
        if parent:
            (tmp_path / "floci.log").write_text(docker("logs", parent))
            docker("stop", "-t", "30", parent)
            docker("rm", "-v", parent)
        # Any leftovers are recorded before cleanup, so a failed teardown remains visible.
        leftovers = docker("ps", "-aq", "--filter", "label=floci_namespace=" + name).split()
        for identity in leftovers:
            docker("rm", "-f", "-v", identity)
        docker("network", "rm", network)
        assert not leftovers, "Floci left managed containers after stopping"


def create_function(client, name):
    iam = boto3.client(
        "iam", endpoint_url=client.meta.endpoint_url, region_name="us-east-1",
        aws_access_key_id="test", aws_secret_access_key="test",
        config=Config(retries={"total_max_attempts": 1}, proxies={}),
    )
    role = iam.create_role(RoleName=name, AssumeRolePolicyDocument=json.dumps({
        "Version": "2012-10-17", "Statement": [{
            "Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"},
            "Action": "sts:AssumeRole",
        }],
    }))["Role"]["Arn"]
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr(
            "handler.py",
            "from pathlib import Path\n"
            "def handler(event, context):\n"
            "    Path('/gate/invoked').write_text('invoked')\n"
            "    return {'gate': Path('/gate/ready').exists()}\n",
        )
    return client.create_function(
        FunctionName=name, Runtime="python3.12",
        Role=role,
        Handler="handler.handler", Timeout=30, MemorySize=128,
        Code={"ZipFile": archive.getvalue()}, Publish=True,
    )


def delete_function(client, name):
    # Docker AutoRemove can still be finishing when deletion first observes it.
    # Keep bounded SDK retries for cleanup, and verify both API and container absence.
    cleanup = boto3.client(
        "lambda", endpoint_url=client.meta.endpoint_url, region_name="us-east-1",
        aws_access_key_id="test", aws_secret_access_key="test",
        config=Config(read_timeout=30, retries={"mode": "standard", "total_max_attempts": 3}, proxies={}),
    )
    cleanup.delete_function(FunctionName=name)
    with pytest.raises(ClientError) as missing:
        cleanup.get_function(FunctionName=name)
    assert missing.value.response["Error"]["Code"] == "ResourceNotFoundException"
    wait_for(lambda: not docker("ps", "-aq", "--filter", "label=floci_namespace=" + name,
                                "--filter", "label=io.floci.service=lambda"), seconds=30)


@pytest.mark.parametrize("provisioned", [False, True])
def test_real_container_cannot_invoke_until_probe_passes(emulator, provisioned):
    client, gate, name = emulator
    function = create_function(client, name)
    qualifier = function["Version"] if provisioned else "$LATEST"
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            if provisioned:
                client.put_provisioned_concurrency_config(
                    FunctionName=name, Qualifier=qualifier, ProvisionedConcurrentExecutions=1,
                )
                future = None
            else:
                future = pool.submit(client.invoke, FunctionName=name, Payload=b"{}")
            wait_for(lambda: (gate / "probed").exists())
            assert not (gate / "invoked").exists()
            identities = docker("ps", "-q", "--filter", "label=floci_namespace=" + name,
                                "--filter", "label=io.floci.service=lambda").split()
            assert len(identities) == 1
            container = json.loads(docker("inspect", identities[0]))[0]
            environment = dict(item.split("=", 1) for item in container["Config"]["Env"] if "=" in item)
            request = urllib.request.Request(
                client.meta.endpoint_url + "/_floci/lambda/issued-identities/lookup",
                data=json.dumps({"correlationId": str(uuid.uuid4()),
                                 "accessKeyId": environment["AWS_ACCESS_KEY_ID"]}).encode(),
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(request, timeout=2) as response:
                identity = json.load(response)
            assert identity["lambda"]["containerId"] == container["Id"]
            assert identity["lambda"]["containerStartedAt"] == container["State"]["StartedAt"]
            if future is not None:
                assert not future.done()
            else:
                assert client.get_provisioned_concurrency_config(
                    FunctionName=name, Qualifier=qualifier,
                )["Status"] == "IN_PROGRESS"
            (gate / "ready").touch()
            if provisioned:
                wait_for(lambda: client.get_provisioned_concurrency_config(
                    FunctionName=name, Qualifier=qualifier)["Status"] == "READY")
                response = client.invoke(FunctionName=name, Qualifier=qualifier, Payload=b"{}")
            else:
                response = future.result(timeout=60)
            assert "FunctionError" not in response
            assert json.loads(response["Payload"].read()) == {"gate": True}
            assert (gate / "invoked").read_text() == "invoked"
    finally:
        delete_function(client, name)


@pytest.mark.parametrize("emulator", [10], indirect=True)
def test_probe_timeout_never_runs_handler_and_removes_container(emulator):
    client, gate, name = emulator
    create_function(client, name)
    try:
        try:
            response = client.invoke(FunctionName=name, Payload=b"{}")
        except ClientError as error:
            assert "readiness" in str(error).lower()
        else:
            assert response.get("FunctionError")
            assert "readiness" in response["Payload"].read().decode().lower()
        assert (gate / "probed").exists()
        assert not (gate / "invoked").exists()
        wait_for(lambda: not docker("ps", "-aq", "--filter", "label=floci_namespace=" + name,
                                    "--filter", "label=io.floci.service=lambda"), seconds=30)
    finally:
        delete_function(client, name)


@pytest.mark.parametrize("emulator", [1], indirect=True)
def test_one_second_budget_admits_an_already_ready_container(emulator):
    client, gate, name = emulator
    create_function(client, name)
    (gate / "ready").touch()
    try:
        response = client.invoke(FunctionName=name, Payload=b"{}")
        assert "FunctionError" not in response
        assert json.loads(response["Payload"].read()) == {"gate": True}
        assert (gate / "probed").exists()
        assert (gate / "invoked").read_text() == "invoked"
    finally:
        delete_function(client, name)

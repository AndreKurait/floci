"""Real Lambda signal forwarding and final-log delivery."""
import io
import json
import time
import zipfile

import pytest


def delete_log_group_if_present(logs_client, name):
    try:
        logs_client.delete_log_group(logGroupName=f"/aws/lambda/{name}")
    except logs_client.exceptions.ResourceNotFoundException:
        pass  # A failed create or invoke may never have created the log group.


def test_runtime_shutdown_preserves_sigterm_handler_logs(
    lambda_client, logs_client, unique_name
):
    name = f"pytest-shutdown-{unique_name}"
    marker = f"shutdown-complete-{unique_name}"
    code = f"""
import os
import signal

def shutdown(signum, frame):
    print({marker!r}, flush=True)
    os._exit(0)

signal.signal(signal.SIGTERM, shutdown)

def handler(event, context):
    return {{"pid": os.getpid()}}
"""
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr("handler.py", code)
    created = False
    try:
        lambda_client.create_function(
            FunctionName=name, Runtime="python3.12",
            Role="arn:aws:iam::000000000000:role/lambda-role",
            Handler="handler.handler", Timeout=10,
            Code={"ZipFile": archive.getvalue()},
        )
        created = True
        response = lambda_client.invoke(FunctionName=name, Payload=b"{}")
        result = json.loads(response["Payload"].read())
        assert "FunctionError" not in response, result
        assert result["pid"] > 1, "PID 1 suppresses default Unix signal actions"
        lambda_client.delete_function(FunctionName=name)
        created = False
        deadline = time.monotonic() + 10
        while True:
            events = logs_client.filter_log_events(logGroupName=f"/aws/lambda/{name}")["events"]
            if any(marker in event["message"] for event in events):
                break
            assert time.monotonic() < deadline, events
            time.sleep(0.05)
    finally:
        if created:
            lambda_client.delete_function(FunctionName=name)
        delete_log_group_if_present(logs_client, name)


@pytest.mark.parametrize("subscriptions", [["INVOKE", "SHUTDOWN"], ["INVOKE"], []])
def test_external_extension_finishes_shutdown_before_container_removal(
    lambda_client, logs_client, unique_name, subscriptions
):
    name = f"pytest-extension-shutdown-{unique_name}"
    marker = f"extension-shutdown-complete-{unique_name}"
    runtime_marker = f"runtime-after-extension-{unique_name}"
    extension = f"""#!/var/lang/bin/python3.12
import json
import os
import time
import urllib.request

endpoint = "http://" + os.environ["AWS_LAMBDA_RUNTIME_API"]
request = urllib.request.Request(
    endpoint + "/2020-01-01/extension/register",
    data=json.dumps({{"events": {subscriptions!r}}}).encode(),
    headers={{"Lambda-Extension-Name": "shutdown-test"}})
with urllib.request.urlopen(request) as response:
    identifier = response.headers["Lambda-Extension-Identifier"]
while True:
    request = urllib.request.Request(
        endpoint + "/2020-01-01/extension/event/next",
        headers={{"Lambda-Extension-Identifier": identifier}})
    with urllib.request.urlopen(request) as response:
        if response.status == 204:
            break
        event = json.load(response)
    if event["eventType"] == "SHUTDOWN":
        time.sleep(1.2)
        print({marker!r}, flush=True)
        break
"""
    layer_zip = io.BytesIO()
    with zipfile.ZipFile(layer_zip, "w") as output:
        entry = zipfile.ZipInfo("extensions/shutdown-test")
        entry.create_system = 3
        entry.external_attr = 0o100755 << 16
        output.writestr(entry, extension)
    layer = lambda_client.publish_layer_version(
        LayerName=name, Content={"ZipFile": layer_zip.getvalue()},
        CompatibleRuntimes=["python3.12"],
    )
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr("handler.py", f"""
import os
import signal
import time

def shutdown(signum, frame):
    time.sleep(0.2)
    print({runtime_marker!r}, flush=True)
    os._exit(0)

signal.signal(signal.SIGTERM, shutdown)

def handler(event, context):
    return event
""")
    created = False
    try:
        lambda_client.create_function(
            FunctionName=name, Runtime="python3.12",
            Role="arn:aws:iam::000000000000:role/lambda-role",
            Handler="handler.handler", Timeout=10,
            Code={"ZipFile": archive.getvalue()}, Layers=[layer["LayerVersionArn"]],
        )
        created = True
        response = lambda_client.invoke(FunctionName=name, Payload=b"{}")
        assert json.loads(response["Payload"].read()) == {}
        assert "FunctionError" not in response
        lambda_client.delete_function(FunctionName=name)
        created = False
        deadline = time.monotonic() + 5
        while True:
            events = logs_client.filter_log_events(logGroupName=f"/aws/lambda/{name}")["events"]
            messages = [event["message"] for event in events]
            if (any(runtime_marker in message for message in messages)
                    and ("SHUTDOWN" not in subscriptions
                         or any(marker in message for message in messages))):
                break
            assert time.monotonic() < deadline, events
            time.sleep(0.05)
    finally:
        if created:
            lambda_client.delete_function(FunctionName=name)
        lambda_client.delete_layer_version(LayerName=name, VersionNumber=layer["Version"])
        delete_log_group_if_present(logs_client, name)

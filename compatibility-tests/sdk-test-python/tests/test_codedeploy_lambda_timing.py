"""Lambda traffic shifts honor configured intervals and remain stoppable."""

import io
import json
import time
import uuid
import zipfile
from contextlib import ExitStack, closing

import boto3
import pytest


def wait_for(read, accept, seconds=10):
    deadline = time.monotonic() + seconds
    while True:
        result = read()
        if accept(result):
            return result
        assert time.monotonic() < deadline, result
        time.sleep(0.1)


@pytest.fixture
def rollout(aws_config, client_config):
    with ExitStack() as cleanup:
        clients = {
            name: cleanup.enter_context(
                closing(boto3.client(name, config=client_config, **aws_config))
            )
            for name in ("iam", "lambda", "codedeploy")
        }
        iam, functions, deployments = (clients[name] for name in ("iam", "lambda", "codedeploy"))
        name = "lambda-timing-" + uuid.uuid4().hex[:12]
        role = iam.create_role(
            RoleName=name,
            AssumeRolePolicyDocument=json.dumps({
                "Version": "2012-10-17",
                "Statement": [{
                    "Effect": "Allow",
                    "Principal": {"Service": ["lambda.amazonaws.com", "codedeploy.amazonaws.com"]},
                    "Action": "sts:AssumeRole",
                }],
            }),
        )["Role"]["Arn"]
        cleanup.callback(iam.delete_role, RoleName=name)

        def code(version):
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("index.py", f"def handler(event, context):\n    return {version}\n")
            return archive.getvalue()

        original = functions.create_function(
            FunctionName=name, Runtime="python3.12", Handler="index.handler",
            Role=role, Code={"ZipFile": code(1)}, Publish=True,
        )["Version"]
        cleanup.callback(functions.delete_function, FunctionName=name)
        target = functions.update_function_code(
            FunctionName=name, ZipFile=code(2), Publish=True,
        )["Version"]
        assert original != target
        functions.create_alias(FunctionName=name, Name="live", FunctionVersion=original)
        deployments.create_application(applicationName=name, computePlatform="Lambda")
        cleanup.callback(deployments.delete_application, applicationName=name)
        deployments.create_deployment_group(
            applicationName=name, deploymentGroupName=name, serviceRoleArn=role,
            deploymentConfigName="CodeDeployDefault.LambdaAllAtOnce",
            deploymentStyle={"deploymentType": "BLUE_GREEN", "deploymentOption": "WITH_TRAFFIC_CONTROL"},
        )

        def start(routing_type):
            config = name + "-config"
            field = "timeBasedCanary" if routing_type == "TimeBasedCanary" else "timeBasedLinear"
            prefix = "canary" if routing_type == "TimeBasedCanary" else "linear"
            deployments.create_deployment_config(
                deploymentConfigName=config, computePlatform="Lambda",
                trafficRoutingConfig={
                    "type": routing_type,
                    field: {prefix + "Percentage": 50, prefix + "Interval": 1},
                },
            )
            cleanup.callback(deployments.delete_deployment_config, deploymentConfigName=config)
            response = deployments.create_deployment(
                applicationName=name, deploymentGroupName=name, deploymentConfigName=config,
                revision={"revisionType": "AppSpecContent", "appSpecContent": {
                    "content": json.dumps({
                        "version": 0.0,
                        "Resources": [{"Function": {"Type": "AWS::Lambda::Function", "Properties": {
                            "Name": name, "Alias": "live",
                            "CurrentVersion": original, "TargetVersion": target,
                        }}}],
                    }),
                }},
            )
            identifier = response["deploymentId"]

            def stop_if_running():
                state = deployments.get_deployment(deploymentId=identifier)["deploymentInfo"]["status"]
                if state not in ("Succeeded", "Failed", "Stopped"):
                    deployments.stop_deployment(deploymentId=identifier)
                    wait_for(
                        lambda: deployments.get_deployment(deploymentId=identifier)["deploymentInfo"],
                        lambda value: value["status"] in ("Succeeded", "Failed", "Stopped"),
                    )

            cleanup.callback(stop_if_running)
            return identifier

        yield {
            "start": start, "deployments": deployments, "original": original, "target": target,
            "name": name, "functions": functions, "role": role,
            "alias": lambda: functions.get_alias(FunctionName=name, Name="live"),
        }


@pytest.mark.parametrize("routing_type", ["TimeBasedCanary", "TimeBasedLinear"])
def test_configured_interval_is_not_shortened(rollout, routing_type, record_property):
    started = time.monotonic()
    identifier = rollout["start"](routing_type)
    info = wait_for(
        lambda: rollout["deployments"].get_deployment(deploymentId=identifier)["deploymentInfo"],
        lambda value: value["status"] in ("Succeeded", "Failed", "Stopped"),
        seconds=90,
    )
    elapsed = time.monotonic() - started
    record_property("configured_interval_seconds", 60)
    record_property("elapsed_seconds", elapsed)
    assert info["status"] == "Succeeded", info
    assert elapsed >= 59.5, f"{routing_type} shortened its one-minute interval to {elapsed:.3f}s"
    assert rollout["alias"]()["FunctionVersion"] == rollout["target"]
    assert not rollout["alias"]().get("RoutingConfig", {}).get("AdditionalVersionWeights")


@pytest.mark.parametrize("routing_type", ["TimeBasedCanary", "TimeBasedLinear"])
def test_stop_during_interval_does_not_wait_for_next_shift(rollout, routing_type, record_property):
    identifier = rollout["start"](routing_type)
    wait_for(
        rollout["alias"],
        lambda value: value.get("RoutingConfig", {}).get("AdditionalVersionWeights")
        == {rollout["target"]: 0.5},
    )
    started = time.monotonic()
    rollout["deployments"].stop_deployment(deploymentId=identifier)
    info = wait_for(
        lambda: rollout["deployments"].get_deployment(deploymentId=identifier)["deploymentInfo"],
        lambda value: value["status"] in ("Succeeded", "Failed", "Stopped"),
        seconds=3,
    )
    record_property("stop_seconds", time.monotonic() - started)
    assert info["status"] == "Stopped", info
    assert rollout["alias"]()["FunctionVersion"] == rollout["original"]

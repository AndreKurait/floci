"""CloudFormation must observe real AppConfig deployment progress and preserve rollback."""
from contextlib import ExitStack
import json
import time

import boto3
import pytest
from botocore.exceptions import ClientError

WAITER = {"Delay": 1, "MaxAttempts": 180}


def _until(read, predicate, timeout=60):
    deadline = time.monotonic() + timeout
    while True:
        value = read()
        if predicate(value):
            return value
        assert time.monotonic() < deadline, f"Timed out; last observation: {value}"
        time.sleep(0.25)


@pytest.fixture
def deployment_scope(aws_config, client_config, unique_name):
    with ExitStack() as cleanup:
        client = boto3.client("appconfig", config=client_config, **aws_config)
        data = boto3.client("appconfigdata", config=client_config, **aws_config)
        cleanup.callback(client.close)
        cleanup.callback(data.close)
        app = client.create_application(Name=unique_name)["Id"]
        cleanup.callback(client.delete_application, ApplicationId=app)
        profile = client.create_configuration_profile(
            ApplicationId=app, Name="settings", LocationUri="hosted")["Id"]
        cleanup.callback(client.delete_configuration_profile, ApplicationId=app, ConfigurationProfileId=profile)
        strategies = {}
        for name, duration, bake in (("instant", 0, 0), ("gradual", 1, 1)):
            strategies[name] = client.create_deployment_strategy(
                Name=name + "-" + unique_name, DeploymentDurationInMinutes=duration,
                FinalBakeTimeInMinutes=bake, GrowthFactor=20, GrowthType="LINEAR", ReplicateTo="NONE")["Id"]
            cleanup.callback(client.delete_deployment_strategy, DeploymentStrategyId=strategies[name])
        environment = client.create_environment(ApplicationId=app, Name="test")["Id"]
        cleanup.callback(client.delete_environment, ApplicationId=app, EnvironmentId=environment)
        client.create_hosted_configuration_version(
            ApplicationId=app, ConfigurationProfileId=profile, Content=b"baseline", ContentType="text/plain")
        client.start_deployment(ApplicationId=app, EnvironmentId=environment, ConfigurationProfileId=profile,
                                ConfigurationVersion="1", DeploymentStrategyId=strategies["instant"])
        yield {"client": client, "data": data, "app": app, "environment": environment,
               "profile": profile, "strategies": strategies}


def _template(scope, content, strategy):
    return {
        "Resources": {
            "Config": {
                "Type": "AWS::AppConfig::HostedConfigurationVersion",
                "DeletionPolicy": "Retain",
                "UpdateReplacePolicy": "Retain",
                "Properties": {"ApplicationId": scope["app"], "ConfigurationProfileId": scope["profile"],
                               "Content": content, "ContentType": "text/plain"},
            },
            "Deployment": {
                "Type": "AWS::AppConfig::Deployment",
                "Properties": {"ApplicationId": scope["app"], "EnvironmentId": scope["environment"],
                               "ConfigurationProfileId": scope["profile"], "ConfigurationVersion": {"Ref": "Config"},
                               "DeploymentStrategyId": scope["strategies"][strategy]},
            },
        },
        "Outputs": {
            "Reference": {"Value": {"Ref": "Deployment"}},
            "Number": {"Value": {"Fn::GetAtt": ["Deployment", "DeploymentNumber"]}},
            "State": {"Value": {"Fn::GetAtt": ["Deployment", "State"]}},
            "Version": {"Value": {"Ref": "Config"}},
        },
    }


def _stack(cfn, name):
    return cfn.describe_stacks(StackName=name)["Stacks"][0]


def _outputs(cfn, name):
    return {row["OutputKey"]: row["OutputValue"] for row in _stack(cfn, name)["Outputs"]}


def _latest(scope):
    rows = scope["client"].list_deployments(
        ApplicationId=scope["app"], EnvironmentId=scope["environment"])["Items"]
    return scope["client"].get_deployment(
        ApplicationId=scope["app"], EnvironmentId=scope["environment"],
        DeploymentNumber=max(row["DeploymentNumber"] for row in rows))


def _starting_deployment(cfn, scope, name):
    stack = _stack(cfn, name)
    if "ROLLBACK" in stack["StackStatus"] or stack["StackStatus"].endswith("FAILED"):
        failures = [event for event in cfn.describe_stack_events(StackName=name)["StackEvents"]
                    if event["ResourceStatus"].endswith("FAILED")]
        pytest.fail(f"Stack failed before deployment: {stack['StackStatus']}; {failures}")
    return _latest(scope)


def _configuration(scope):
    token = scope["data"].start_configuration_session(
        ApplicationIdentifier=scope["app"], EnvironmentIdentifier=scope["environment"],
        ConfigurationProfileIdentifier=scope["profile"])["InitialConfigurationToken"]
    response = scope["data"].get_latest_configuration(ConfigurationToken=token)
    with response["Configuration"] as content:
        return content.read()


def _stop(scope, deployment):
    return scope["client"].stop_deployment(
        ApplicationId=scope["app"], EnvironmentId=scope["environment"],
        DeploymentNumber=deployment["DeploymentNumber"], AllowRevert=True)


def _delete_stack(cfn, scope, name):
    try:
        _stack(cfn, name)
    except ClientError as missing:
        assert missing.response["Error"]["Code"] == "ValidationError"
        assert "does not exist" in missing.response["Error"]["Message"]
        return
    current = _latest(scope)
    if current["State"] in ("DEPLOYING", "BAKING"):
        _stop(scope, current)
    _until(lambda: _stack(cfn, name), lambda value: not value["StackStatus"].endswith("IN_PROGRESS"))
    cfn.delete_stack(StackName=name)
    cfn.get_waiter("stack_delete_complete").wait(StackName=name, WaiterConfig=WAITER)


@pytest.mark.timeout(240)
def test_cfn_deployment_waits_for_actual_rollout_and_final_bake(
        cloudformation_client, deployment_scope, unique_name, record_property):
    cfn, scope = cloudformation_client, deployment_scope
    name = "deployment-" + unique_name
    with ExitStack() as cleanup:
        began = time.monotonic()
        cfn.create_stack(StackName=name, TemplateBody=json.dumps(_template(scope, "updated", "gradual")))
        cleanup.callback(_delete_stack, cfn, scope, name)
        started = _until(lambda: _starting_deployment(cfn, scope, name),
                         lambda value: value["ConfigurationVersion"] == "2")
        assert started["State"] == "DEPLOYING"
        assert started["DeploymentDurationInMinutes"] == started["FinalBakeTimeInMinutes"] == 1
        assert _stack(cfn, name)["StackStatus"] == "CREATE_IN_PROGRESS"

        baking = _until(lambda: _starting_deployment(cfn, scope, name),
                        lambda value: value["State"] == "BAKING", timeout=90)
        record_property("rollout_seconds_observed", time.monotonic() - began)
        assert baking["PercentageComplete"] == 100
        assert _stack(cfn, name)["StackStatus"] == "CREATE_IN_PROGRESS"
        assert cfn.describe_stack_resource(StackName=name, LogicalResourceId="Deployment")[
            "StackResourceDetail"]["ResourceStatus"] == "CREATE_IN_PROGRESS"

        cfn.get_waiter("stack_create_complete").wait(StackName=name, WaiterConfig=WAITER)
        elapsed = time.monotonic() - began
        completed = _latest(scope)
        record_property("rollout_and_bake_seconds_observed", elapsed)
        assert elapsed >= 119
        assert completed["State"] == "COMPLETE"
        assert (completed["CompletedAt"] - completed["StartedAt"]).total_seconds() == 120
        outputs = _outputs(cfn, name)
        assert outputs["Number"] == str(completed["DeploymentNumber"])
        assert outputs["State"] == "COMPLETE" and outputs["Version"] == "2"
        assert outputs["Reference"] == cfn.describe_stack_resource(
            StackName=name, LogicalResourceId="Deployment")["StackResourceDetail"]["PhysicalResourceId"]
        assert _configuration(scope) == b"updated"

        _delete_stack(cfn, scope, name)
        assert _configuration(scope) == b"baseline"
        assert _latest(scope)["State"] == "REVERTED"


def test_cfn_stopped_deployment_fails_creation_and_restores_the_prior_configuration(
        cloudformation_client, deployment_scope, unique_name):
    cfn, scope = cloudformation_client, deployment_scope
    name = "stopped-" + unique_name
    with ExitStack() as cleanup:
        cfn.create_stack(StackName=name, TemplateBody=json.dumps(_template(scope, "stopped", "gradual")))
        cleanup.callback(_delete_stack, cfn, scope, name)
        started = _until(lambda: _starting_deployment(cfn, scope, name),
                         lambda value: value["ConfigurationVersion"] == "2")
        assert started["State"] == "DEPLOYING"
        assert _stop(scope, started)["State"] == "ROLLED_BACK"

        _until(lambda: _stack(cfn, name), lambda value: value["StackStatus"] == "ROLLBACK_COMPLETE")

        failed = [event for event in cfn.describe_stack_events(StackName=name)["StackEvents"]
                  if event["LogicalResourceId"] == "Deployment" and event["ResourceStatus"] == "CREATE_FAILED"]
        assert failed and "ROLLED_BACK" in failed[0]["ResourceStatusReason"]
        assert _configuration(scope) == b"baseline"


def test_cfn_replacement_cleanup_preserves_a_newer_external_deployment(
        cloudformation_client, deployment_scope, unique_name):
    cfn, scope = cloudformation_client, deployment_scope
    name = "replaced-" + unique_name
    with ExitStack() as cleanup:
        cfn.create_stack(StackName=name, TemplateBody=json.dumps(_template(scope, "second", "instant")))
        cleanup.callback(_delete_stack, cfn, scope, name)
        cfn.get_waiter("stack_create_complete").wait(StackName=name, WaiterConfig=WAITER)
        original = _outputs(cfn, name)
        cfn.create_change_set(StackName=name, ChangeSetName="replace", ChangeSetType="UPDATE",
                              TemplateBody=json.dumps(_template(scope, "third", "instant")))
        cfn.get_waiter("change_set_create_complete").wait(
            StackName=name, ChangeSetName="replace", WaiterConfig=WAITER)
        changes = cfn.describe_change_set(StackName=name, ChangeSetName="replace")["Changes"]
        deployment_change = next(row["ResourceChange"] for row in changes
                                 if row["ResourceChange"]["LogicalResourceId"] == "Deployment")
        assert deployment_change["Replacement"] == "True"
        cfn.execute_change_set(StackName=name, ChangeSetName="replace")
        cfn.get_waiter("stack_update_complete").wait(StackName=name, WaiterConfig=WAITER)
        assert _outputs(cfn, name)["Reference"] != original["Reference"]
        assert _configuration(scope) == b"third"

        version = scope["client"].create_hosted_configuration_version(
            ApplicationId=scope["app"], ConfigurationProfileId=scope["profile"],
            Content=b"external", ContentType="text/plain")["VersionNumber"]
        external = scope["client"].start_deployment(
            ApplicationId=scope["app"], EnvironmentId=scope["environment"], ConfigurationProfileId=scope["profile"],
            ConfigurationVersion=str(version), DeploymentStrategyId=scope["strategies"]["instant"])
        _delete_stack(cfn, scope, name)
        assert _configuration(scope) == b"external"
        assert _latest(scope)["DeploymentNumber"] == external["DeploymentNumber"]
        assert _latest(scope)["State"] == "COMPLETE"


def test_cfn_stopped_replacement_restores_the_previous_stack_and_configuration(
        cloudformation_client, deployment_scope, unique_name):
    cfn, scope = cloudformation_client, deployment_scope
    name = "update-rollback-" + unique_name
    with ExitStack() as cleanup:
        cfn.create_stack(StackName=name, TemplateBody=json.dumps(_template(scope, "second", "instant")))
        cleanup.callback(_delete_stack, cfn, scope, name)
        cfn.get_waiter("stack_create_complete").wait(StackName=name, WaiterConfig=WAITER)
        original = _outputs(cfn, name)
        cfn.update_stack(StackName=name, TemplateBody=json.dumps(_template(scope, "third", "gradual")))
        started = _until(lambda: _starting_deployment(cfn, scope, name),
                         lambda value: value["ConfigurationVersion"] == "3")
        assert started["State"] == "DEPLOYING"
        _stop(scope, started)

        _until(lambda: _stack(cfn, name), lambda value: value["StackStatus"] == "UPDATE_ROLLBACK_COMPLETE")

        assert _outputs(cfn, name) == original
        assert _configuration(scope) == b"second"
        assert _latest(scope)["State"] == "ROLLED_BACK"

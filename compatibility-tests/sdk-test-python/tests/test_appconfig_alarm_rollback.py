"""AppConfig monitors real CloudWatch alarms without requiring deployment-status polling."""
from contextlib import ExitStack
from datetime import datetime, timezone
import json
import time

import boto3
import pytest
from botocore.exceptions import ClientError


def _until(read, predicate, timeout=40):
    deadline = time.monotonic() + timeout
    while True:
        value = read()
        if predicate(value):
            return value
        assert time.monotonic() < deadline, f"Timed out; last observation: {value}"
        time.sleep(0.25)


def _configuration(scope):
    result = scope["appconfig"].get_configuration(
        Application=scope["app"], Environment=scope["env"],
        Configuration=scope["profile"], ClientId="alarm-monitor-sdk")
    try:
        return result["ConfigurationVersion"], result["Content"].read()
    finally:
        result["Content"].close()


def _stop(scope, number):
    client = scope["appconfig"]
    identity = dict(ApplicationId=scope["app"], EnvironmentId=scope["env"], DeploymentNumber=number)
    if client.get_deployment(**identity)["State"] not in ("ROLLED_BACK", "REVERTED"):
        client.stop_deployment(**identity, AllowRevert=True)


def _strategy(scope, rollout, bake):
    client = scope["appconfig"]
    strategy = client.create_deployment_strategy(
        Name=scope["name"] + f"-{rollout}-{bake}", DeploymentDurationInMinutes=rollout,
        FinalBakeTimeInMinutes=bake, GrowthFactor=100, GrowthType="LINEAR", ReplicateTo="NONE")["Id"]
    scope["cleanup"].callback(client.delete_deployment_strategy, DeploymentStrategyId=strategy)
    return strategy


def _deploy(scope, version="2", rollout=5, bake=5):
    strategy = _strategy(scope, rollout, bake)
    result = scope["appconfig"].start_deployment(
        ApplicationId=scope["app"], EnvironmentId=scope["env"],
        ConfigurationProfileId=scope["profile"], ConfigurationVersion=version,
        DeploymentStrategyId=strategy)
    scope["cleanup"].callback(_stop, scope, result["DeploymentNumber"])
    return result


def _metric(scope, value):
    timestamp = datetime.fromtimestamp(int(time.time()) // 60 * 60 - 60, timezone.utc)
    scope["cloudwatch"].put_metric_data(Namespace=scope["namespace"], MetricData=[
        {"MetricName": "Errors", "Value": value, "Timestamp": timestamp},
    ])


def _alarm(scope):
    return scope["cloudwatch"].describe_alarms(AlarmNames=[scope["name"]])["MetricAlarms"][0]


def _assert_rollback(scope, deployment, state):
    assert _until(lambda: _configuration(scope), lambda value: value[0] == "1") == (
        "1", b'{"value":"baseline"}')
    result = scope["appconfig"].get_deployment(
        ApplicationId=scope["app"], EnvironmentId=scope["env"],
        DeploymentNumber=deployment["DeploymentNumber"])
    assert result["State"] == "ROLLED_BACK"
    events = result["EventLog"]
    assert [event["EventType"] for event in events][-2:] == ["ROLLBACK_STARTED", "ROLLBACK_COMPLETED"]
    assert all(event["TriggeredBy"] == "CLOUDWATCH_ALARM" for event in events[-2:])
    assert all(scope["monitor_arn"] in event["Description"] and state in event["Description"]
               for event in events[-2:])


@pytest.fixture
def alarm_monitor_scope(aws_config, client_config, unique_name):
    with ExitStack() as cleanup:
        def create(kind="metric"):
            clients = {service: boto3.client(service, config=client_config, **aws_config)
                       for service in ("appconfig", "cloudwatch", "iam", "cloudformation")}
            for client in clients.values():
                cleanup.callback(client.close)
            scope = {**clients, "name": unique_name, "namespace": "AppConfig/" + unique_name,
                     "cleanup": cleanup}
            cloudwatch = scope["cloudwatch"]
            scope["alarm_request"] = dict(
                AlarmName=unique_name, Namespace=scope["namespace"], MetricName="Errors",
                Statistic="Sum", Period=60, EvaluationPeriods=1, Threshold=10,
                ComparisonOperator="GreaterThanThreshold", TreatMissingData="notBreaching")
            cloudwatch.put_metric_alarm(**scope["alarm_request"])
            cleanup.callback(cloudwatch.delete_alarms, AlarmNames=[unique_name])
            _metric(scope, 0)
            initial = _until(lambda: _alarm(scope), lambda alarm: alarm["StateValue"] == "OK")
            scope["monitor_arn"] = initial["AlarmArn"]
            if kind == "composite":
                composite = unique_name + "-aggregate"
                cloudwatch.put_composite_alarm(AlarmName=composite, AlarmRule=f'ALARM("{unique_name}")')
                cleanup.callback(cloudwatch.delete_alarms, AlarmNames=[composite])
                scope["monitor_arn"] = cloudwatch.describe_alarms(
                    AlarmNames=[composite], AlarmTypes=["CompositeAlarm"])["CompositeAlarms"][0]["AlarmArn"]

            iam = scope["iam"]
            trust = {"Version": "2012-10-17", "Statement": [
                {"Effect": "Allow", "Principal": {"Service": "appconfig.amazonaws.com"},
                 "Action": "sts:AssumeRole"}]}
            role = iam.create_role(RoleName=unique_name, AssumeRolePolicyDocument=json.dumps(trust))["Role"]
            cleanup.callback(iam.delete_role, RoleName=unique_name)
            iam.put_role_policy(RoleName=unique_name, PolicyName="monitor", PolicyDocument=json.dumps({
                "Version": "2012-10-17", "Statement": [
                    {"Effect": "Allow", "Action": "cloudwatch:DescribeAlarms", "Resource": "*"}]}))
            cleanup.callback(iam.delete_role_policy, RoleName=unique_name, PolicyName="monitor")

            appconfig = scope["appconfig"]
            scope["app"] = appconfig.create_application(Name=unique_name)["Id"]
            cleanup.callback(appconfig.delete_application, ApplicationId=scope["app"])
            scope["profile"] = appconfig.create_configuration_profile(
                ApplicationId=scope["app"], Name="settings", LocationUri="hosted", Type="AWS.Freeform")["Id"]
            cleanup.callback(appconfig.delete_configuration_profile, ApplicationId=scope["app"],
                             ConfigurationProfileId=scope["profile"], DeletionProtectionCheck="BYPASS")
            for content in (b'{"value":"baseline"}', b'{"value":"candidate"}'):
                version = appconfig.create_hosted_configuration_version(
                    ApplicationId=scope["app"], ConfigurationProfileId=scope["profile"],
                    Content=content, ContentType="application/json")["VersionNumber"]
                cleanup.callback(appconfig.delete_hosted_configuration_version,
                                 ApplicationId=scope["app"], ConfigurationProfileId=scope["profile"],
                                 VersionNumber=version)
            scope["env"] = appconfig.create_environment(
                ApplicationId=scope["app"], Name="test",
                Monitors=[{"AlarmArn": scope["monitor_arn"], "AlarmRoleArn": role["Arn"]}])["Id"]
            cleanup.callback(appconfig.delete_environment, ApplicationId=scope["app"],
                             EnvironmentId=scope["env"], DeletionProtectionCheck="BYPASS")
            assert _deploy(scope, version="1", rollout=0, bake=0)["State"] == "COMPLETE"
            assert _configuration(scope) == ("1", b'{"value":"baseline"}')
            return scope
        yield create


@pytest.mark.parametrize("kind", ["metric", "composite"])
def test_alarm_monitor_rolls_back_without_status_polling(alarm_monitor_scope, kind, record_property):
    scope = alarm_monitor_scope(kind)
    deployment = _deploy(scope)
    assert deployment["State"] == "DEPLOYING"
    assert _configuration(scope)[0] == "2"
    began = time.monotonic()
    _metric(scope, 100)
    alarm = _until(lambda: _alarm(scope), lambda alarm: alarm["StateValue"] == "ALARM")
    record_property("observed_metric_alarm_state", alarm["StateValue"])
    record_property("metric_to_alarm_seconds", time.monotonic() - began)
    _assert_rollback(scope, deployment, "ALARM")
    record_property("metric_to_rollback_seconds", time.monotonic() - began)


def test_alarm_monitor_insufficient_data_rolls_back_during_bake(alarm_monitor_scope):
    scope = alarm_monitor_scope()
    deployment = _deploy(scope, rollout=0)
    assert deployment["State"] == "BAKING"
    assert _configuration(scope)[0] == "2"
    scope["cloudwatch"].put_metric_alarm(
        **{**scope["alarm_request"], "MetricName": "Unreported", "TreatMissingData": "missing"})
    _until(lambda: _alarm(scope), lambda alarm: alarm["StateValue"] == "INSUFFICIENT_DATA")
    _assert_rollback(scope, deployment, "INSUFFICIENT_DATA")


def test_alarm_monitor_disabled_actions_suppress_rollback(alarm_monitor_scope):
    scope = alarm_monitor_scope()
    scope["cloudwatch"].put_metric_alarm(**scope["alarm_request"], ActionsEnabled=False)
    deployment = _deploy(scope)
    _metric(scope, 100)
    _until(lambda: _alarm(scope), lambda alarm: alarm["StateValue"] == "ALARM")
    assert _configuration(scope)[0] == "2"
    assert scope["appconfig"].get_deployment(
        ApplicationId=scope["app"], EnvironmentId=scope["env"],
        DeploymentNumber=deployment["DeploymentNumber"])["State"] == "DEPLOYING"
    scope["cloudwatch"].put_metric_alarm(**scope["alarm_request"], ActionsEnabled=True)
    _assert_rollback(scope, deployment, "ALARM")


@pytest.mark.parametrize("refusal", ["permission", "trust"])
def test_alarm_monitor_rejects_unreadable_monitor_before_deployment(alarm_monitor_scope, refusal):
    scope = alarm_monitor_scope()
    iam = scope["iam"]
    if refusal == "permission":
        iam.put_role_policy(RoleName=scope["name"], PolicyName="monitor", PolicyDocument=json.dumps({
            "Version": "2012-10-17", "Statement": [
                {"Effect": "Deny", "Action": "cloudwatch:DescribeAlarms", "Resource": "*"}]}))
    else:
        iam.update_assume_role_policy(RoleName=scope["name"], PolicyDocument=json.dumps({
            "Version": "2012-10-17", "Statement": [
                {"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"},
                 "Action": "sts:AssumeRole"}]}))
    with pytest.raises(ClientError) as error:
        _deploy(scope)
    assert error.value.response["Error"]["Code"] == "BadRequestException"
    deployments = scope["appconfig"].list_deployments(ApplicationId=scope["app"], EnvironmentId=scope["env"])["Items"]
    assert [item["ConfigurationVersion"] for item in deployments] == ["1"]
    assert _configuration(scope)[0] == "1"


def test_alarm_monitor_fails_cloudformation_creation_and_restores_configuration(alarm_monitor_scope):
    scope = alarm_monitor_scope()
    cfn = scope["cloudformation"]
    template = {"Resources": {"Deployment": {"Type": "AWS::AppConfig::Deployment", "Properties": {
        "ApplicationId": scope["app"], "EnvironmentId": scope["env"],
        "ConfigurationProfileId": scope["profile"], "ConfigurationVersion": "2",
        "DeploymentStrategyId": _strategy(scope, 5, 5),
    }}}}
    cfn.create_stack(StackName=scope["name"], TemplateBody=json.dumps(template))

    def delete_stack():
        cfn.delete_stack(StackName=scope["name"])
        cfn.get_waiter("stack_delete_complete").wait(
            StackName=scope["name"], WaiterConfig={"Delay": 1, "MaxAttempts": 60})

    scope["cleanup"].callback(delete_stack)
    _until(lambda: _configuration(scope), lambda value: value[0] == "2")
    _metric(scope, 100)
    _until(lambda: _configuration(scope), lambda value: value[0] == "1")
    stack = _until(lambda: cfn.describe_stacks(StackName=scope["name"])["Stacks"][0],
                   lambda value: value["StackStatus"] == "ROLLBACK_COMPLETE")
    assert stack["StackStatus"] == "ROLLBACK_COMPLETE"
    events = cfn.describe_stack_events(StackName=scope["name"])["StackEvents"]
    assert any(event["LogicalResourceId"] == "Deployment" and event["ResourceStatus"] == "CREATE_FAILED"
               and "ROLLED_BACK" in event.get("ResourceStatusReason", "") for event in events)

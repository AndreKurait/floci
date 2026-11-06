"""AppConfig monitors real CloudWatch alarms without requiring deployment-status polling."""
import json
import time

import pytest

from appconfig_alarm_fixtures import (
    _alarm, _assert_rollback, _configuration, _deploy, _metric, _strategy, _until,
    alarm_monitor_scope,
)


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

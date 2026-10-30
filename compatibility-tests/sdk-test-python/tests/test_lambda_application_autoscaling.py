"""Application Auto Scaling changes real Lambda provisioned environments."""

from datetime import datetime, timedelta, timezone
from contextlib import closing
import json
import time

import boto3
import pytest
from botocore.exceptions import ClientError

from test_cloudformation_provisioned_concurrency import remove_stack, settled, template


@pytest.fixture
def scaling_client(aws_config, client_config):
    with closing(boto3.client("application-autoscaling", config=client_config, **aws_config)) as client:
        yield client


def target(function):
    return {
        "ServiceNamespace": "lambda",
        "ScalableDimension": "lambda:function:ProvisionedConcurrency",
        "ResourceId": "function:" + function + ":live",
    }


def capacity(client, function, count):
    deadline = time.monotonic() + 60
    observed = None
    while time.monotonic() < deadline:
        try:
            observed = client.get_provisioned_concurrency_config(
                FunctionName=function, Qualifier="live"
            )
        except ClientError as error:
            if error.response["Error"]["Code"] != "ProvisionedConcurrencyConfigNotFoundException":
                raise
        else:
            if observed["Status"] == "READY" and observed["AllocatedProvisionedConcurrentExecutions"] == count:
                assert observed["RequestedProvisionedConcurrentExecutions"] == count
                return observed
        time.sleep(0.3)
    pytest.fail(f"Expected {count} initialized environments, observed {observed}")


def invoke_provisioned(client, function):
    response = client.invoke(
        FunctionName=function, Qualifier="live", Payload=b'{"value":42}'
    )
    assert "FunctionError" not in response
    with response["Payload"] as payload:
        assert json.load(payload) == {"initialization": "provisioned-concurrency", "value": 42}


def deregister(client, function):
    try:
        client.deregister_scalable_target(**target(function))
    except ClientError as error:
        if error.response["Error"]["Code"] != "ObjectNotFoundException":
            raise


def test_registration_reconciles_bounds_and_deregistration_preserves_capacity(
    cloudformation_client, lambda_client, scaling_client, unique_name
):
    stack, function = unique_name + "-stack", unique_name + "-function"
    cloudformation_client.create_stack(
        StackName=stack, TemplateBody=template(function, False), Capabilities=["CAPABILITY_IAM"]
    )
    try:
        assert settled(cloudformation_client, stack)["StackStatus"] == "CREATE_COMPLETE"
        scaling_client.register_scalable_target(**target(function), MinCapacity=2, MaxCapacity=4)
        capacity(lambda_client, function, 2)
        invoke_provisioned(lambda_client, function)
        lambda_client.put_provisioned_concurrency_config(
            FunctionName=function, Qualifier="live", ProvisionedConcurrentExecutions=1
        )
        capacity(lambda_client, function, 1)
        # Omitted bounds retain their previous values and still reconcile drift.
        scaling_client.register_scalable_target(**target(function))
        capacity(lambda_client, function, 2)
        scaling_client.register_scalable_target(**target(function), MinCapacity=1, MaxCapacity=1)
        capacity(lambda_client, function, 1)
        scaling_client.deregister_scalable_target(**target(function))
        capacity(lambda_client, function, 1)
    finally:
        deregister(scaling_client, function)
        remove_stack(cloudformation_client, stack)


def test_lambda_metric_alarms_drive_real_capacity(
    cloudformation_client, lambda_client, scaling_client, cloudwatch_client, unique_name
):
    stack, function = unique_name + "-stack", unique_name + "-function"
    cloudformation_client.create_stack(
        StackName=stack, TemplateBody=template(function, False), Capabilities=["CAPABILITY_IAM"]
    )
    alarm_names = []
    try:
        assert settled(cloudformation_client, stack)["StackStatus"] == "CREATE_COMPLETE"
        scaling_client.register_scalable_target(**target(function), MinCapacity=1, MaxCapacity=2)
        policy = scaling_client.put_scaling_policy(
            **target(function), PolicyName=unique_name + "-policy", PolicyType="TargetTrackingScaling",
            TargetTrackingScalingPolicyConfiguration={
                "TargetValue": 0.5,
                "PredefinedMetricSpecification": {
                    "PredefinedMetricType": "LambdaProvisionedConcurrencyUtilization"
                },
            },
        )
        alarm_names = [alarm["AlarmName"] for alarm in policy["Alarms"]]
        dimensions = [
            {"Name": "FunctionName", "Value": function},
            {"Name": "Resource", "Value": function + ":live"},
        ]
        alarms = cloudwatch_client.describe_alarms(AlarmNames=alarm_names)["MetricAlarms"]
        assert len(alarms) == 2
        for alarm in alarms:
            assert alarm["Namespace"] == "AWS/Lambda"
            assert alarm["MetricName"] == "ProvisionedConcurrencyUtilization"
            assert alarm["Statistic"] == "Average"
            assert alarm["Period"] == 60
            assert alarm["EvaluationPeriods"] == 3
            assert sorted(alarm["Dimensions"], key=lambda row: row["Name"]) == dimensions
            expected = 0.5 if alarm["ComparisonOperator"] == "GreaterThanThreshold" else 0.45
            assert alarm["Threshold"] == pytest.approx(expected)
        now = datetime.now(timezone.utc)
        cloudwatch_client.put_metric_data(
            Namespace="AWS/Lambda",
            MetricData=[
                {"MetricName": "ProvisionedConcurrencyUtilization", "Dimensions": dimensions,
                 "Timestamp": now - timedelta(seconds=60 * minute), "Value": 0.9}
                for minute in range(1, 7)
            ],
        )
        capacity(lambda_client, function, 2)
        invoke_provisioned(lambda_client, function)
        activities = scaling_client.describe_scaling_activities(**target(function))["ScalingActivities"]
        assert activities
        cloudwatch_client.put_metric_data(
            Namespace="AWS/Lambda",
            MetricData=[
                {"MetricName": "ProvisionedConcurrencyUtilization", "Dimensions": dimensions,
                 "Timestamp": now - timedelta(seconds=60 * minute),
                 "StatisticValues": {"SampleCount": 100, "Sum": 0, "Minimum": 0, "Maximum": 0}}
                for minute in range(1, 7)
            ],
        )
        capacity(lambda_client, function, 1)
        invoke_provisioned(lambda_client, function)
    finally:
        deregister(scaling_client, function)
        remove_stack(cloudformation_client, stack)
    assert not cloudwatch_client.describe_alarms(AlarmNames=alarm_names)["MetricAlarms"]


def test_cloudformation_target_provisions_capacity_without_alias_override(
    cloudformation_client, lambda_client, scaling_client, unique_name
):
    stack, function = unique_name + "-stack", unique_name + "-function"
    document = json.loads(template(function, False, provisioned=False))
    document["Resources"]["Capacity"] = {
        "Type": "AWS::ApplicationAutoScaling::ScalableTarget",
        "DependsOn": "Alias",
        "Properties": {
            "ServiceNamespace": "lambda", "ScalableDimension": "lambda:function:ProvisionedConcurrency",
            "ResourceId": {"Fn::Join": ["", ["function:", {"Ref": "Function"}, ":live"]]},
            "MinCapacity": {"Ref": "WarmCount"}, "MaxCapacity": 2,
        },
    }
    body = json.dumps(document)
    cloudformation_client.create_stack(
        StackName=stack, TemplateBody=body, Capabilities=["CAPABILITY_IAM"]
    )
    try:
        assert settled(cloudformation_client, stack)["StackStatus"] == "CREATE_COMPLETE"
        capacity(lambda_client, function, 1)
        invoke_provisioned(lambda_client, function)
        cloudformation_client.update_stack(
            StackName=stack, TemplateBody=body, Capabilities=["CAPABILITY_IAM"],
            Parameters=[{"ParameterKey": "WarmCount", "ParameterValue": "2"}],
        )
        assert settled(cloudformation_client, stack)["StackStatus"] == "UPDATE_COMPLETE"
        capacity(lambda_client, function, 2)
    finally:
        remove_stack(cloudformation_client, stack)
    assert not scaling_client.describe_scalable_targets(
        ServiceNamespace="lambda", ResourceIds=[target(function)["ResourceId"]]
    )["ScalableTargets"]

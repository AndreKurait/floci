"""CloudFormation aliases own real, initialized provisioned Lambda capacity."""

import json
import time

import pytest
from botocore.exceptions import ClientError


def template(name, sam, *, provisioned=True, broken=False):
    code = (
        "raise RuntimeError('intentional initialization failure')"
        if broken
        else "import os\n"
        "def handler(event, context):\n"
        "    return {'initialization': os.environ.get('AWS_LAMBDA_INITIALIZATION_TYPE'), 'value': event['value']}\n"
    )
    properties = {
        "FunctionName": name, "Runtime": "python3.12", "Handler": "index.handler",
        "Role": {"Fn::GetAtt": ["Role", "Arn"]}, "MemorySize": 128,
    }
    resources = {
        "Role": {
            "Type": "AWS::IAM::Role",
            "Properties": {"AssumeRolePolicyDocument": {
                "Version": "2012-10-17",
                "Statement": [{"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"},
                               "Action": "sts:AssumeRole"}],
            }},
        },
        "Function": {"Type": "AWS::Serverless::Function" if sam else "AWS::Lambda::Function",
                     "Properties": properties},
    }
    result = {"Parameters": {"WarmCount": {"Type": "Number", "Default": 1}}, "Resources": resources}
    if sam:
        result["Transform"] = "AWS::Serverless-2016-10-31"
        properties.update(InlineCode=code, AutoPublishAlias="live")
        alias_properties = properties
    else:
        properties["Code"] = {"ZipFile": code}
        resources["Version"] = {"Type": "AWS::Lambda::Version",
                                "Properties": {"FunctionName": {"Ref": "Function"}}}
        alias_properties = {"FunctionName": {"Ref": "Function"}, "Name": "live",
                            "FunctionVersion": {"Fn::GetAtt": ["Version", "Version"]}}
        resources["Alias"] = {"Type": "AWS::Lambda::Alias", "Properties": alias_properties}
    if provisioned:
        alias_properties["ProvisionedConcurrencyConfig"] = {
            "ProvisionedConcurrentExecutions": {"Ref": "WarmCount"}
        }
    return json.dumps(result)


def settled(client, name):
    deadline = time.monotonic() + 150
    while True:
        stack = client.describe_stacks(StackName=name)["Stacks"][0]
        status = stack["StackStatus"]
        if not status.endswith("_IN_PROGRESS"):
            return stack
        assert time.monotonic() < deadline, stack
        time.sleep(0.2)


def remove_stack(client, name):
    client.delete_stack(StackName=name)
    client.get_waiter("stack_delete_complete").wait(
        StackName=name, WaiterConfig={"Delay": 1, "MaxAttempts": 90}
    )


@pytest.mark.parametrize("sam", [False, True], ids=["lambda-alias", "sam"])
def test_stack_initializes_resizes_and_removes_declared_capacity(
    cloudformation_client, lambda_client, unique_name, sam
):
    stack, function = unique_name + "-stack", unique_name + "-function"
    cloudformation_client.create_stack(
        StackName=stack, TemplateBody=template(function, sam), Capabilities=["CAPABILITY_IAM"]
    )
    try:
        assert settled(cloudformation_client, stack)["StackStatus"] == "CREATE_COMPLETE"
        for count in (1, 2):
            if count == 2:
                cloudformation_client.update_stack(
                    StackName=stack, TemplateBody=template(function, sam),
                    Parameters=[{"ParameterKey": "WarmCount", "ParameterValue": "2"}],
                    Capabilities=["CAPABILITY_IAM"],
                )
                assert settled(cloudformation_client, stack)["StackStatus"] == "UPDATE_COMPLETE"
            allocation = lambda_client.get_provisioned_concurrency_config(
                FunctionName=function, Qualifier="live"
            )
            assert allocation["Status"] == "READY", allocation
            assert allocation["RequestedProvisionedConcurrentExecutions"] == count
            assert allocation["AllocatedProvisionedConcurrentExecutions"] == count
            response = lambda_client.invoke(
                FunctionName=function, Qualifier="live", Payload=json.dumps({"value": count}).encode()
            )
            assert "FunctionError" not in response
            with response["Payload"] as stream:
                assert json.load(stream) == {"initialization": "provisioned-concurrency", "value": count}
        cloudformation_client.update_stack(
            StackName=stack, TemplateBody=template(function, sam, provisioned=False),
            Capabilities=["CAPABILITY_IAM"],
        )
        assert settled(cloudformation_client, stack)["StackStatus"] == "UPDATE_COMPLETE"
        with pytest.raises(ClientError) as missing:
            lambda_client.get_provisioned_concurrency_config(FunctionName=function, Qualifier="live")
        assert missing.value.response["Error"]["Code"] == "ProvisionedConcurrencyConfigNotFoundException"
        assert lambda_client.get_alias(FunctionName=function, Name="live")["FunctionVersion"] == "1"
    finally:
        remove_stack(cloudformation_client, stack)
    with pytest.raises(ClientError) as missing:
        lambda_client.get_function(FunctionName=function)
    assert missing.value.response["Error"]["Code"] == "ResourceNotFoundException"


@pytest.mark.parametrize("sam", [False, True], ids=["lambda-alias", "sam"])
def test_failed_initialization_rolls_back_the_declaring_stack(
    cloudformation_client, lambda_client, unique_name, sam
):
    stack, function = unique_name + "-stack", unique_name + "-function"
    cloudformation_client.create_stack(
        StackName=stack, TemplateBody=template(function, sam, broken=True),
        Capabilities=["CAPABILITY_IAM"],
    )
    try:
        assert settled(cloudformation_client, stack)["StackStatus"] == "ROLLBACK_COMPLETE"
        events = cloudformation_client.describe_stack_events(StackName=stack)["StackEvents"]
        assert any(event["ResourceType"] == "AWS::Lambda::Alias"
                   and event["ResourceStatus"] == "CREATE_FAILED"
                   and "initialization" in event.get("ResourceStatusReason", "").lower()
                   for event in events), events
        with pytest.raises(ClientError) as missing:
            lambda_client.get_function(FunctionName=function)
        assert missing.value.response["Error"]["Code"] == "ResourceNotFoundException"
    finally:
        remove_stack(cloudformation_client, stack)

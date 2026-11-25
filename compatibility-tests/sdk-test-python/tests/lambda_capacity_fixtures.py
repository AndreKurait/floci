"""Shared Lambda stack fixtures for provisioned capacity and scaling checks."""

import json
import time


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

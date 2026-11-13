"""Hosted AppConfig versions use independent physical identities and normal CFN lifecycle."""
from contextlib import ExitStack
import json

import boto3
import pytest
from botocore.exceptions import ClientError

WAITER = {"Delay": 1, "MaxAttempts": 60}


def _delete_stack(client, stack):
    try:
        client.describe_stacks(StackName=stack)
    except ClientError as missing:
        assert missing.response["Error"]["Code"] == "ValidationError"
        assert "does not exist" in missing.response["Error"]["Message"]
        return
    client.delete_stack(StackName=stack)
    client.get_waiter("stack_delete_complete").wait(StackName=stack, WaiterConfig=WAITER)


def _outputs(client, stack):
    return {row["OutputKey"]: row["OutputValue"]
            for row in client.describe_stacks(StackName=stack)["Stacks"][0]["Outputs"]}


def _content(client, app, profile, version):
    response = client.get_hosted_configuration_version(
        ApplicationId=app, ConfigurationProfileId=profile, VersionNumber=version)
    with response["Content"] as stream:
        return stream.read()


def _template(name):
    resources = {"App": {"Type": "AWS::AppConfig::Application", "Properties": {"Name": name}}}
    outputs = {"App": {"Value": {"Ref": "App"}}}
    for label in ("First", "Second"):
        profile = label + "Profile"
        resources[profile] = {
            "Type": "AWS::AppConfig::ConfigurationProfile",
            "Properties": {"ApplicationId": {"Ref": "App"}, "Name": label, "LocationUri": "hosted"},
        }
        resources[label] = {
            "Type": "AWS::AppConfig::HostedConfigurationVersion",
            "Properties": {"ApplicationId": {"Ref": "App"}, "ConfigurationProfileId": {"Ref": profile},
                           "Content": f"  café-{label}\n", "ContentType": "text/plain"},
        }
        outputs[profile] = {"Value": {"Ref": profile}}
        outputs[label] = {"Value": {"Ref": label}}
        outputs[label + "Number"] = {"Value": {"Fn::GetAtt": [label, "VersionNumber"]}}
    outputs["Both"] = {"Value": {"Fn::Sub": "${First}-${Second}"}}
    return {"Resources": resources, "Outputs": outputs}


def test_hosted_versions_keep_profile_identity_across_replacement(
        cloudformation_client, aws_config, client_config, unique_name):
    cfn = cloudformation_client
    stack = "hosted-" + unique_name
    template = _template(unique_name)
    with ExitStack() as cleanup:
        client = boto3.client("appconfig", config=client_config, **aws_config)
        cleanup.callback(client.close)
        cfn.create_stack(StackName=stack, TemplateBody=json.dumps(template))
        cleanup.callback(_delete_stack, cfn, stack)
        cfn.get_waiter("stack_create_complete").wait(StackName=stack, WaiterConfig=WAITER)
        outputs = _outputs(cfn, stack)
        assert outputs["Both"] == "1-1"
        physical = {}
        for label in ("First", "Second"):
            assert outputs[label] == outputs[label + "Number"] == "1"
            assert _content(client, outputs["App"], outputs[label + "Profile"], 1) == f"  café-{label}\n".encode()
            physical[label] = cfn.describe_stack_resource(
                StackName=stack, LogicalResourceId=label)["StackResourceDetail"]["PhysicalResourceId"]
        assert physical["First"] != physical["Second"]

        template["Resources"]["First"]["Properties"]["Content"] = "replacement\n"
        cfn.create_change_set(StackName=stack, ChangeSetName="content", ChangeSetType="UPDATE",
                              TemplateBody=json.dumps(template))
        cfn.get_waiter("change_set_create_complete").wait(
            StackName=stack, ChangeSetName="content", WaiterConfig=WAITER)
        changes = cfn.describe_change_set(StackName=stack, ChangeSetName="content")["Changes"]
        changed = next(row["ResourceChange"] for row in changes
                       if row["ResourceChange"]["LogicalResourceId"] == "First")
        assert changed["Replacement"] == "True"
        cfn.execute_change_set(StackName=stack, ChangeSetName="content")
        cfn.get_waiter("stack_update_complete").wait(StackName=stack, WaiterConfig=WAITER)

        updated = _outputs(cfn, stack)
        assert updated["Both"] == "2-1"
        assert updated["FirstNumber"] == "2"
        assert _content(client, outputs["App"], outputs["FirstProfile"], 2) == b"replacement\n"
        assert _content(client, outputs["App"], outputs["SecondProfile"], 1) == "  café-Second\n".encode()
        assert cfn.describe_stack_resource(StackName=stack, LogicalResourceId="Second")[
            "StackResourceDetail"]["PhysicalResourceId"] == physical["Second"]
        with pytest.raises(ClientError) as missing:
            _content(client, outputs["App"], outputs["FirstProfile"], 1)
        assert missing.value.response["Error"]["Code"] == "ResourceNotFoundException"


def test_hosted_versions_respect_retention_when_their_parent_is_outside_the_stack(
        cloudformation_client, aws_config, client_config, unique_name):
    cfn = cloudformation_client
    stack = "retained-" + unique_name
    with ExitStack() as cleanup:
        client = boto3.client("appconfig", config=client_config, **aws_config)
        cleanup.callback(client.close)
        app = client.create_application(Name=unique_name)["Id"]
        cleanup.callback(client.delete_application, ApplicationId=app)
        profile = client.create_configuration_profile(
            ApplicationId=app, Name="settings", LocationUri="hosted")["Id"]
        cleanup.callback(client.delete_configuration_profile,
                         ApplicationId=app, ConfigurationProfileId=profile)
        template = {"Resources": {"Config": {
            "Type": "AWS::AppConfig::HostedConfigurationVersion",
            "DeletionPolicy": "Retain", "UpdateReplacePolicy": "Retain",
            "Properties": {"ApplicationId": app, "ConfigurationProfileId": profile,
                           "Content": "first", "ContentType": "text/plain"},
        }}, "Outputs": {"Version": {"Value": {"Ref": "Config"}}}}
        cfn.create_stack(StackName=stack, TemplateBody=json.dumps(template))
        cleanup.callback(_delete_stack, cfn, stack)
        cfn.get_waiter("stack_create_complete").wait(StackName=stack, WaiterConfig=WAITER)
        assert _outputs(cfn, stack)["Version"] == "1"

        template["Resources"]["Config"]["Properties"]["Content"] = "second"
        cfn.update_stack(StackName=stack, TemplateBody=json.dumps(template))
        cfn.get_waiter("stack_update_complete").wait(StackName=stack, WaiterConfig=WAITER)
        assert _outputs(cfn, stack)["Version"] == "2"
        _delete_stack(cfn, stack)

        assert _content(client, app, profile, 1) == b"first"
        assert _content(client, app, profile, 2) == b"second"

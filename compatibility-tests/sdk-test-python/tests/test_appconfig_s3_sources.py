"""Deploy and retain versioned S3 configurations through the public AppConfig API."""
import json

import pytest

from appconfig_s3_fixtures import _assert_rejected_deployment, _deploy, _read, s3_source_scope


def test_s3_source_deploys_the_selected_version(s3_source_scope):
    scope = s3_source_scope
    scope["s3"].put_object(Bucket=scope["bucket"], Key=scope["key"],
                           Body=b'{"limit":20}', ContentType="application/json")
    deployment = _deploy(scope, scope["version"])
    assert deployment["State"] == "COMPLETE"
    assert deployment["ConfigurationVersion"] == scope["version"]
    assert _read(scope) == scope["content"]
    assert _read(scope, legacy=True) == scope["content"]
    profile = scope["appconfig"].get_configuration_profile(
        ApplicationId=scope["app"], ConfigurationProfileId=scope["profile"])
    assert profile["RetrievalRoleArn"] == scope["role"]


def test_s3_source_failed_fetch_preserves_active_configuration(s3_source_scope):
    scope = s3_source_scope
    _deploy(scope, scope["version"])
    version = scope["s3"].put_object(
        Bucket=scope["bucket"], Key=scope["key"], Body=b'{"limit":20}',
        ContentType="application/json")["VersionId"]
    scope["s3"].delete_object(Bucket=scope["bucket"], Key=scope["key"], VersionId=version)
    _assert_rejected_deployment(scope, version)


@pytest.mark.parametrize("size", [1024 * 1024, 1024 * 1024 + 1])
def test_s3_source_size_boundary_preserves_active_configuration(s3_source_scope, size):
    scope = s3_source_scope
    _deploy(scope, scope["version"])
    content = b'"' + b"x" * (size - 2) + b'"'
    version = scope["s3"].put_object(
        Bucket=scope["bucket"], Key=scope["key"], Body=content,
        ContentType="application/json")["VersionId"]
    if size > 1024 * 1024:
        _assert_rejected_deployment(scope, version)
    else:
        assert _deploy(scope, version)["State"] == "COMPLETE"
        assert _read(scope) == content
        assert _read(scope, legacy=True) == content


def test_s3_source_reads_and_revert_survive_deleted_source_versions(s3_source_scope):
    scope = s3_source_scope
    _deploy(scope, scope["version"])
    version = scope["s3"].put_object(
        Bucket=scope["bucket"], Key=scope["key"], Body=b'{"limit":20}',
        ContentType="application/json")["VersionId"]
    newer = _deploy(scope, version)
    for source_version in (scope["version"], version):
        scope["s3"].delete_object(
            Bucket=scope["bucket"], Key=scope["key"], VersionId=source_version)
    assert _read(scope) == b'{"limit":20}'
    scope["appconfig"].stop_deployment(
        ApplicationId=scope["app"], EnvironmentId=scope["env"],
        DeploymentNumber=newer["DeploymentNumber"], AllowRevert=True)
    assert _read(scope) == scope["content"]
    assert _read(scope, legacy=True) == scope["content"]


def _delete_stack(client, name):
    client.delete_stack(StackName=name)
    client.get_waiter("stack_delete_complete").wait(
        StackName=name, WaiterConfig={"Delay": 1, "MaxAttempts": 60})


@pytest.mark.parametrize("object_arn", [False, True])
def test_s3_source_cloudformation_deploys_real_content(s3_source_scope, object_arn):
    scope = s3_source_scope
    client = scope["cloudformation"]
    stack = scope["name"] + "-stack"
    location = (scope["bucket_arn"] if object_arn else f"s3://{scope['bucket']}") + "/" + scope["key"]
    template = {"Resources": {
        "Profile": {"Type": "AWS::AppConfig::ConfigurationProfile", "Properties": {
            "ApplicationId": scope["app"], "Name": "stack-settings", "Type": "AWS.Freeform",
            "LocationUri": location,
            "RetrievalRoleArn": scope["role"]}},
        "Deployment": {"Type": "AWS::AppConfig::Deployment", "Properties": {
            "ApplicationId": scope["app"], "EnvironmentId": scope["env"],
            "ConfigurationProfileId": {"Ref": "Profile"}, "ConfigurationVersion": scope["version"],
            "DeploymentStrategyId": scope["strategy"]}}},
        "Outputs": {"Profile": {"Value": {"Ref": "Profile"}}}}
    client.create_stack(StackName=stack, TemplateBody=json.dumps(template))
    scope["cleanup"].callback(_delete_stack, client, stack)
    client.get_waiter("stack_create_complete").wait(
        StackName=stack, WaiterConfig={"Delay": 1, "MaxAttempts": 60})
    outputs = client.describe_stacks(StackName=stack)["Stacks"][0]["Outputs"]
    profile = next(value["OutputValue"] for value in outputs if value["OutputKey"] == "Profile")
    actual = scope["appconfig"].get_configuration_profile(
        ApplicationId=scope["app"], ConfigurationProfileId=profile)
    assert actual["RetrievalRoleArn"] == scope["role"]
    assert _read({**scope, "profile": profile}) == scope["content"]
    assert _read({**scope, "profile": profile}, legacy=True) == scope["content"]

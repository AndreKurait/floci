"""Validate configuration content before a deployment changes what clients receive."""
from contextlib import ExitStack
import json

import boto3
import pytest
from botocore.exceptions import ClientError, WaiterError


SCHEMA = json.dumps({
    "$schema": "http://json-schema.org/draft-04/schema#",
    "type": "object", "required": ["limit"], "additionalProperties": False,
    "properties": {"limit": {"$ref": "#/definitions/limit"}},
    "definitions": {"limit": {"type": "integer", "minimum": 1, "maximum": 100}},
})
VALIDATORS = [{"Type": "JSON_SCHEMA", "Content": SCHEMA}]


def _stop(scope, number):
    request = {"ApplicationId": scope["app"], "EnvironmentId": scope["env"], "DeploymentNumber": number}
    if scope["appconfig"].get_deployment(**request)["State"] not in ("ROLLED_BACK", "REVERTED"):
        scope["appconfig"].stop_deployment(**request, AllowRevert=True)


def _version(scope, content):
    result = scope["appconfig"].create_hosted_configuration_version(
        ApplicationId=scope["app"], ConfigurationProfileId=scope["profile"],
        Content=json.dumps(content).encode(), ContentType="application/json")
    scope["cleanup"].callback(scope["appconfig"].delete_hosted_configuration_version,
                               ApplicationId=scope["app"], ConfigurationProfileId=scope["profile"],
                               VersionNumber=result["VersionNumber"])
    return str(result["VersionNumber"])


def _deploy(scope, version):
    result = scope["appconfig"].start_deployment(
        ApplicationId=scope["app"], EnvironmentId=scope["env"],
        ConfigurationProfileId=scope["profile"], ConfigurationVersion=version,
        DeploymentStrategyId=scope["strategy"])
    scope["cleanup"].callback(_stop, scope, result["DeploymentNumber"])
    return result


def _content(scope):
    token = scope["appconfigdata"].start_configuration_session(
        ApplicationIdentifier=scope["app"], EnvironmentIdentifier=scope["env"],
        ConfigurationProfileIdentifier=scope["profile"])["InitialConfigurationToken"]
    response = scope["appconfigdata"].get_latest_configuration(ConfigurationToken=token)
    with response["Configuration"] as body:
        return json.loads(body.read())


@pytest.fixture
def validator_scope(aws_config, client_config, unique_name):
    with ExitStack() as cleanup:
        clients = {service: boto3.client(service, config=client_config, **aws_config)
                   for service in ("appconfig", "appconfigdata", "cloudformation")}
        for client in clients.values():
            cleanup.callback(client.close)
        scope = {**clients, "cleanup": cleanup, "name": unique_name}
        appconfig = scope["appconfig"]
        scope["app"] = appconfig.create_application(Name=unique_name)["Id"]
        cleanup.callback(appconfig.delete_application, ApplicationId=scope["app"])
        scope["env"] = appconfig.create_environment(ApplicationId=scope["app"], Name="test")["Id"]
        cleanup.callback(appconfig.delete_environment, ApplicationId=scope["app"],
                         EnvironmentId=scope["env"], DeletionProtectionCheck="BYPASS")
        scope["profile"] = appconfig.create_configuration_profile(
            ApplicationId=scope["app"], Name="validated-settings", LocationUri="hosted",
            Type="AWS.Freeform", Validators=VALIDATORS)["Id"]
        cleanup.callback(appconfig.delete_configuration_profile, ApplicationId=scope["app"],
                         ConfigurationProfileId=scope["profile"], DeletionProtectionCheck="BYPASS")
        scope["strategy"] = appconfig.create_deployment_strategy(
            Name=unique_name, DeploymentDurationInMinutes=0, FinalBakeTimeInMinutes=0,
            GrowthFactor=100, GrowthType="LINEAR", ReplicateTo="NONE")["Id"]
        cleanup.callback(appconfig.delete_deployment_strategy, DeploymentStrategyId=scope["strategy"])
        yield scope


def test_appconfig_schema_failure_preserves_the_active_version(validator_scope):
    scope = validator_scope
    _deploy(scope, _version(scope, {"limit": 10}))
    bad = _version(scope, {"limit": "unlimited"})
    with pytest.raises(ClientError) as failure:
        _deploy(scope, bad)
    assert failure.value.response["Error"]["Code"] == "BadRequestException"
    assert _content(scope) == {"limit": 10}
    deployments = scope["appconfig"].list_deployments(
        ApplicationId=scope["app"], EnvironmentId=scope["env"])["Items"]
    assert len(deployments) == 1


def test_appconfig_schema_validation_activity_checks_content_without_deploying(validator_scope):
    scope = validator_scope
    request = {"ApplicationId": scope["app"], "ConfigurationProfileId": scope["profile"]}
    good = _version(scope, {"limit": 10})
    result = scope["appconfig"].validate_configuration(**request, ConfigurationVersion=good)
    assert result["ResponseMetadata"]["HTTPStatusCode"] == 204
    bad = _version(scope, {"limit": 101})
    with pytest.raises(ClientError) as failure:
        scope["appconfig"].validate_configuration(**request, ConfigurationVersion=bad)
    assert failure.value.response["Error"]["Code"] == "BadRequestException"
    assert not scope["appconfig"].list_deployments(
        ApplicationId=scope["app"], EnvironmentId=scope["env"])["Items"]


def test_appconfig_schema_profile_reads_return_the_validator(validator_scope):
    scope = validator_scope
    profile = scope["appconfig"].get_configuration_profile(
        ApplicationId=scope["app"], ConfigurationProfileId=scope["profile"])
    assert profile["Validators"] == VALIDATORS


def test_appconfig_schema_updates_preserve_omitted_fields_and_allow_removing_validators(validator_scope):
    scope = validator_scope
    good = _version(scope, {"limit": 10})
    _deploy(scope, good)
    stricter = json.loads(SCHEMA)
    stricter["definitions"]["limit"]["maximum"] = 5
    request = {"ApplicationId": scope["app"], "ConfigurationProfileId": scope["profile"]}
    updated = scope["appconfig"].update_configuration_profile(
        **request, Validators=[{"Type": "JSON_SCHEMA", "Content": json.dumps(stricter)}])
    assert updated["Name"] == "validated-settings"
    assert updated["LocationUri"] == "hosted"
    assert _content(scope) == {"limit": 10}
    with pytest.raises(ClientError) as failure:
        _deploy(scope, good)
    assert failure.value.response["Error"]["Code"] == "BadRequestException"
    scope["appconfig"].update_configuration_profile(**request, Validators=[])
    _deploy(scope, _version(scope, {"limit": "unlimited"}))
    assert _content(scope) == {"limit": "unlimited"}


@pytest.mark.parametrize("schema", ["{", '{"type":"not-a-json-type"}'])
def test_appconfig_schema_defers_invalid_schema_until_validation(validator_scope, schema):
    scope = validator_scope
    profile = scope["appconfig"].create_configuration_profile(
        ApplicationId=scope["app"], Name="bad-schema", LocationUri="hosted",
        Validators=[{"Type": "JSON_SCHEMA", "Content": schema}])
    scope["cleanup"].callback(scope["appconfig"].delete_configuration_profile,
                              ApplicationId=scope["app"], ConfigurationProfileId=profile["Id"],
                              DeletionProtectionCheck="BYPASS")
    selected = {**scope, "profile": profile["Id"]}
    version = _version(selected, {"limit": 10})
    with pytest.raises(ClientError) as failure:
        scope["appconfig"].validate_configuration(
            ApplicationId=scope["app"], ConfigurationProfileId=profile["Id"], ConfigurationVersion=version)
    assert failure.value.response["Error"]["Code"] == "BadRequestException"
    request = {"ApplicationId": scope["app"], "ConfigurationProfileId": scope["profile"]}
    scope["appconfig"].update_configuration_profile(
        **request, Validators=[{"Type": "JSON_SCHEMA", "Content": schema}])
    assert scope["appconfig"].get_configuration_profile(**request)["Validators"][0]["Content"] == schema
    with pytest.raises(ClientError) as failure:
        scope["appconfig"].validate_configuration(**request, ConfigurationVersion=_version(scope, {"limit": 10}))
    assert failure.value.response["Error"]["Code"] == "BadRequestException"


def _delete_stack(client, stack):
    client.delete_stack(StackName=stack)
    client.get_waiter("stack_delete_complete").wait(
        StackName=stack, WaiterConfig={"Delay": 1, "MaxAttempts": 60})


def test_appconfig_schema_cloudformation_stores_invalid_declarations(validator_scope):
    scope = validator_scope
    client = scope["cloudformation"]
    stack = scope["name"] + "-invalid-schema"
    template = {"Resources": {
        "Profile": {"Type": "AWS::AppConfig::ConfigurationProfile", "Properties": {
            "ApplicationId": scope["app"], "Name": "bad-stack-schema", "LocationUri": "hosted",
            "Validators": [{"Type": "JSON_SCHEMA", "Content": '{"type":"not-a-json-type"}'}]}}}}
    client.create_stack(StackName=stack, TemplateBody=json.dumps(template))
    scope["cleanup"].callback(_delete_stack, client, stack)
    client.get_waiter("stack_create_complete").wait(
        StackName=stack, WaiterConfig={"Delay": 1, "MaxAttempts": 60})
    profile = client.describe_stack_resource(StackName=stack, LogicalResourceId="Profile")[
        "StackResourceDetail"]["PhysicalResourceId"]
    declaration = scope["appconfig"].get_configuration_profile(
        ApplicationId=scope["app"], ConfigurationProfileId=profile)
    assert declaration["Validators"][0]["Content"] == '{"type":"not-a-json-type"}'
    version = _version({**scope, "profile": profile}, {"limit": 10})
    with pytest.raises(ClientError) as failure:
        scope["appconfig"].validate_configuration(
            ApplicationId=scope["app"], ConfigurationProfileId=profile, ConfigurationVersion=version)
    assert failure.value.response["Error"]["Code"] == "BadRequestException"


def test_appconfig_schema_cloudformation_rollback_restores_rules_and_deployed_content(validator_scope):
    scope = validator_scope
    client = scope["cloudformation"]
    stack = scope["name"] + "-schema-update"

    def template(validators, content):
        properties = {"ApplicationId": scope["app"], "Name": "stack-settings", "LocationUri": "hosted"}
        if validators is not None:
            properties["Validators"] = validators
        return json.dumps({"Resources": {
            "Profile": {"Type": "AWS::AppConfig::ConfigurationProfile", "Properties": properties},
            # Deployment deletion reverts; retain the source needed for that rollback.
            "Version": {"Type": "AWS::AppConfig::HostedConfigurationVersion", "UpdateReplacePolicy": "Retain",
                        "Properties": {
                "ApplicationId": scope["app"], "ConfigurationProfileId": {"Ref": "Profile"},
                "Content": json.dumps(content), "ContentType": "application/json"}},
            "Deployment": {"Type": "AWS::AppConfig::Deployment", "Properties": {
                "ApplicationId": scope["app"], "EnvironmentId": scope["env"],
                "ConfigurationProfileId": {"Ref": "Profile"}, "ConfigurationVersion": {"Ref": "Version"},
                "DeploymentStrategyId": scope["strategy"]}}}})

    client.create_stack(StackName=stack, TemplateBody=template(VALIDATORS, {"limit": 10}))
    scope["cleanup"].callback(_delete_stack, client, stack)
    client.get_waiter("stack_create_complete").wait(
        StackName=stack, WaiterConfig={"Delay": 1, "MaxAttempts": 60})
    profile = client.describe_stack_resource(StackName=stack, LogicalResourceId="Profile")[
        "StackResourceDetail"]["PhysicalResourceId"]
    deployed = {**scope, "profile": profile}
    assert _content(deployed) == {"limit": 10}
    stricter = json.loads(SCHEMA)
    stricter["definitions"]["limit"]["maximum"] = 5
    client.update_stack(StackName=stack, TemplateBody=template(
        [{"Type": "JSON_SCHEMA", "Content": json.dumps(stricter)}], {"limit": 20}))
    with pytest.raises(WaiterError):
        client.get_waiter("stack_update_complete").wait(
            StackName=stack, WaiterConfig={"Delay": 1, "MaxAttempts": 60})
    assert client.describe_stacks(StackName=stack)["Stacks"][0]["StackStatus"] == "UPDATE_ROLLBACK_COMPLETE"
    restored = scope["appconfig"].get_configuration_profile(
        ApplicationId=scope["app"], ConfigurationProfileId=profile)
    assert restored["Validators"] == VALIDATORS
    assert _content(deployed) == {"limit": 10}

    client.update_stack(StackName=stack, TemplateBody=template(None, {"limit": "unlimited"}))
    client.get_waiter("stack_update_complete").wait(
        StackName=stack, WaiterConfig={"Delay": 1, "MaxAttempts": 60})
    cleared = scope["appconfig"].get_configuration_profile(
        ApplicationId=scope["app"], ConfigurationProfileId=profile)
    assert not cleared.get("Validators")
    assert _content(deployed) == {"limit": "unlimited"}

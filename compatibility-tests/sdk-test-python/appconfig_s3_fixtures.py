"""Owned S3 source fixtures shared by default and IAM-enabled AppConfig cases."""
from contextlib import ExitStack
import json

import boto3
import pytest
from botocore.exceptions import ClientError


def _empty_bucket(s3, bucket):
    for page in s3.get_paginator("list_object_versions").paginate(Bucket=bucket):
        objects = [{"Key": item["Key"], "VersionId": item["VersionId"]}
                   for field in ("Versions", "DeleteMarkers") for item in page.get(field, [])]
        if objects:
            s3.delete_objects(Bucket=bucket, Delete={"Objects": objects})
    s3.delete_bucket(Bucket=bucket)


def _read(scope, legacy=False):
    if legacy:
        result = scope["appconfig"].get_configuration(
            Application=scope["app"], Environment=scope["env"],
            Configuration=scope["profile"], ClientId="s3-source-sdk")
        body = result["Content"]
    else:
        token = scope["appconfigdata"].start_configuration_session(
            ApplicationIdentifier=scope["app"], EnvironmentIdentifier=scope["env"],
            ConfigurationProfileIdentifier=scope["profile"])["InitialConfigurationToken"]
        result = scope["appconfigdata"].get_latest_configuration(ConfigurationToken=token)
        body = result["Configuration"]
    try:
        return body.read()
    finally:
        body.close()


def _deploy(scope, version):
    result = scope["appconfig"].start_deployment(
        ApplicationId=scope["app"], EnvironmentId=scope["env"],
        ConfigurationProfileId=scope["profile"], ConfigurationVersion=version,
        DeploymentStrategyId=scope["strategy"])
    scope["cleanup"].callback(_stop, scope, result["DeploymentNumber"])
    return result


def _stop(scope, number):
    arguments = {"ApplicationId": scope["app"], "EnvironmentId": scope["env"], "DeploymentNumber": number}
    state = scope["appconfig"].get_deployment(**arguments)["State"]
    if state in ("ROLLED_BACK", "REVERTED"):
        return
    scope["appconfig"].stop_deployment(**arguments, AllowRevert=True)


@pytest.fixture
def s3_source_scope(aws_config, client_config, unique_name):
    with ExitStack() as cleanup:
        clients = {service: boto3.client(service, config=client_config, **aws_config)
                   for service in ("s3", "iam", "appconfig", "appconfigdata", "cloudformation")}
        for client in clients.values():
            cleanup.callback(client.close)
        scope = {**clients, "cleanup": cleanup, "name": unique_name,
                 "bucket": unique_name.lower(), "key": "settings/config.json"}
        s3 = scope["s3"]
        args = {"Bucket": scope["bucket"]}
        if s3.meta.region_name != "us-east-1":
            args["CreateBucketConfiguration"] = {"LocationConstraint": s3.meta.region_name}
        s3.create_bucket(**args)
        cleanup.callback(_empty_bucket, s3, scope["bucket"])
        s3.put_bucket_versioning(Bucket=scope["bucket"], VersioningConfiguration={"Status": "Enabled"})
        scope["content"] = b'{"limit":10}'
        scope["version"] = s3.put_object(
            Bucket=scope["bucket"], Key=scope["key"], Body=scope["content"],
            ContentType="application/json")["VersionId"]
        iam = scope["iam"]
        role = iam.create_role(RoleName=unique_name, AssumeRolePolicyDocument=json.dumps({
            "Version": "2012-10-17", "Statement": [
                {"Effect": "Allow", "Principal": {"Service": "appconfig.amazonaws.com"},
                 "Action": "sts:AssumeRole"}]}))["Role"]
        cleanup.callback(iam.delete_role, RoleName=unique_name)
        scope["role"] = role["Arn"]
        partition = role["Arn"].split(":")[1]
        scope["bucket_arn"] = f"arn:{partition}:s3:::{scope['bucket']}"
        iam.put_role_policy(RoleName=unique_name, PolicyName="source", PolicyDocument=json.dumps({
            "Version": "2012-10-17", "Statement": [
                {"Effect": "Allow", "Action": ["s3:GetObject", "s3:GetObjectVersion"],
                 "Resource": scope["bucket_arn"] + "/" + scope["key"]},
                {"Effect": "Allow", "Action": [
                    "s3:GetBucketLocation", "s3:GetBucketVersioning", "s3:ListBucket",
                    "s3:ListBucketVersions"], "Resource": scope["bucket_arn"]},
                {"Effect": "Allow", "Action": "s3:ListAllMyBuckets", "Resource": "*"}]}))
        cleanup.callback(iam.delete_role_policy, RoleName=unique_name, PolicyName="source")
        appconfig = scope["appconfig"]
        scope["app"] = appconfig.create_application(Name=unique_name)["Id"]
        cleanup.callback(appconfig.delete_application, ApplicationId=scope["app"])
        scope["env"] = appconfig.create_environment(ApplicationId=scope["app"], Name="test")["Id"]
        cleanup.callback(appconfig.delete_environment, ApplicationId=scope["app"],
                         EnvironmentId=scope["env"], DeletionProtectionCheck="BYPASS")
        scope["profile"] = appconfig.create_configuration_profile(
            ApplicationId=scope["app"], Name="settings",
            LocationUri=f"s3://{scope['bucket']}/{scope['key']}",
            RetrievalRoleArn=scope["role"], Type="AWS.Freeform")["Id"]
        cleanup.callback(appconfig.delete_configuration_profile, ApplicationId=scope["app"],
                         ConfigurationProfileId=scope["profile"], DeletionProtectionCheck="BYPASS")
        scope["strategy"] = appconfig.create_deployment_strategy(
            Name=unique_name, DeploymentDurationInMinutes=0, FinalBakeTimeInMinutes=0,
            GrowthFactor=100, GrowthType="LINEAR", ReplicateTo="NONE")["Id"]
        cleanup.callback(appconfig.delete_deployment_strategy, DeploymentStrategyId=scope["strategy"])
        yield scope


def _assert_rejected_deployment(scope, version):
    with pytest.raises(ClientError) as failure:
        _deploy(scope, version)
    assert failure.value.response["Error"]["Code"] == "BadRequestException"
    assert _read(scope) == scope["content"]
    assert _read(scope, legacy=True) == scope["content"]

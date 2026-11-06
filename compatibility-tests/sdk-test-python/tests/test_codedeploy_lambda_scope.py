"""Background Lambda deployments keep the caller's account and region."""

from contextlib import ExitStack, closing
import io
import json
import time
import uuid
import zipfile

import boto3
import pytest
from botocore.exceptions import ClientError


def wait_terminal(client, identifier):
    deadline = time.monotonic() + 10
    while True:
        info = client.get_deployment(deploymentId=identifier)["deploymentInfo"]
        if info["status"] in ("Succeeded", "Failed", "Stopped"):
            return info
        assert time.monotonic() < deadline, info
        time.sleep(0.1)


def code(version):
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr("index.py", f"def handler(event, context):\n    return {version}\n")
    return archive.getvalue()


@pytest.mark.parametrize("region", ["us-west-2", "cn-north-1"])
def test_lambda_worker_updates_only_the_request_account(
        aws_config, client_config, region, record_property):
    with ExitStack() as cleanup:
        management = {**aws_config, "region_name": region}
        sts = cleanup.enter_context(closing(boto3.client(
            "sts", config=client_config, **management)))
        organizations = cleanup.enter_context(closing(boto3.client(
            "organizations", config=client_config, **management)))
        identity = sts.get_caller_identity()
        default_account = identity["Account"]
        partition = identity["Arn"].split(":")[1]
        name = "lambda-scope-" + uuid.uuid4().hex[:12]
        try:
            organizations.describe_organization()
        except organizations.exceptions.AWSOrganizationsNotInUseException:
            organizations.create_organization(FeatureSet="ALL")
            cleanup.callback(organizations.delete_organization)
        created = organizations.create_account(
            AccountName=name, Email=name + "@example.test")["CreateAccountStatus"]
        deadline = time.monotonic() + 10
        while created["State"] == "IN_PROGRESS":
            assert time.monotonic() < deadline, created
            time.sleep(0.1)
            created = organizations.describe_create_account_status(
                CreateAccountRequestId=created["Id"])["CreateAccountStatus"]
        assert created["State"] == "SUCCEEDED", created
        owner = created["AccountId"]
        assert owner != default_account
        cleanup.callback(organizations.remove_account_from_organization, AccountId=owner)
        credentials = sts.assume_role(
            RoleArn=f"arn:{partition}:iam::{owner}:role/OrganizationAccountAccessRole",
            RoleSessionName=name,
        )["Credentials"]
        configs = {
            default_account: management,
            owner: {
                **management,
                "aws_access_key_id": credentials["AccessKeyId"],
                "aws_secret_access_key": credentials["SecretAccessKey"],
                "aws_session_token": credentials["SessionToken"],
            },
        }
        selections = {}
        for account in (default_account, owner):
            clients = {
                service: cleanup.enter_context(closing(boto3.client(
                    service, config=client_config, **configs[account])))
                for service in ("iam", "lambda", "codedeploy")
            }
            iam, functions, deployments = (clients[key] for key in ("iam", "lambda", "codedeploy"))
            role = iam.create_role(
                RoleName=name,
                AssumeRolePolicyDocument=json.dumps({
                    "Version": "2012-10-17",
                    "Statement": [{
                        "Effect": "Allow",
                        "Principal": {"Service": ["lambda.amazonaws.com", "codedeploy.amazonaws.com"]},
                        "Action": "sts:AssumeRole",
                    }],
                }),
            )["Role"]["Arn"]
            cleanup.callback(iam.delete_role, RoleName=name)
            original = functions.create_function(
                FunctionName=name, Runtime="python3.12", Handler="index.handler",
                Role=role, Code={"ZipFile": code(1)}, Publish=True,
            )["Version"]
            cleanup.callback(functions.delete_function, FunctionName=name)
            target = functions.update_function_code(
                FunctionName=name, ZipFile=code(2), Publish=True,
            )["Version"]
            assert original != target
            functions.create_alias(FunctionName=name, Name="live", FunctionVersion=original)
            deployments.create_application(applicationName=name, computePlatform="Lambda")
            cleanup.callback(deployments.delete_application, applicationName=name)
            deployments.create_deployment_group(
                applicationName=name, deploymentGroupName=name, serviceRoleArn=role,
                deploymentConfigName="CodeDeployDefault.LambdaAllAtOnce",
                deploymentStyle={"deploymentType": "BLUE_GREEN", "deploymentOption": "WITH_TRAFFIC_CONTROL"},
            )
            selections[account] = {
                "functions": functions, "deployments": deployments,
                "original": original, "target": target,
            }

        chosen = selections[owner]
        identifier = chosen["deployments"].create_deployment(
            applicationName=name, deploymentGroupName=name,
            revision={"revisionType": "AppSpecContent", "appSpecContent": {
                "content": json.dumps({
                    "version": 0.0,
                    "Resources": [{"Function": {"Type": "AWS::Lambda::Function", "Properties": {
                        "Name": name, "Alias": "live",
                        "CurrentVersion": chosen["original"], "TargetVersion": chosen["target"],
                    }}}],
                }),
            }},
        )["deploymentId"]

        def stop_if_running():
            chosen["deployments"].stop_deployment(deploymentId=identifier)
            wait_terminal(chosen["deployments"], identifier)

        cleanup.callback(stop_if_running)
        info = wait_terminal(chosen["deployments"], identifier)
        observed = {
            account: selection["functions"].get_alias(
                FunctionName=name, Name="live")["FunctionVersion"]
            for account, selection in selections.items()
        }
        record_property("deployment_status", info["status"])
        record_property("observed_alias_versions", json.dumps(observed, sort_keys=True))
        assert info["status"] == "Succeeded", info
        assert observed == {
            default_account: selections[default_account]["original"],
            owner: chosen["target"],
        }
        with pytest.raises(ClientError) as denied:
            selections[default_account]["deployments"].get_deployment(deploymentId=identifier)
        assert denied.value.response["Error"]["Code"] == "DeploymentDoesNotExistException"

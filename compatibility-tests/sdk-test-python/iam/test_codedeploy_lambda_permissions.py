"""Lambda deployments use their CodeDeploy service role for downstream calls."""
from contextlib import ExitStack, closing
import json

import boto3
import pytest

from tests.test_codedeploy_lambda_timing import rollout, wait_for


@pytest.fixture(name="aws_config", params=["us-east-1", "cn-north-1"])
def regional_config(aws_config, request):
    return {**aws_config, "region_name": request.param}


@pytest.fixture
def deployment_role(rollout, aws_config, client_config):
    with ExitStack() as cleanup:
        iam = cleanup.enter_context(closing(boto3.client("iam", config=client_config, **aws_config)))
        name = rollout["name"] + "-deployment"
        trust = {
            "Version": "2012-10-17",
            "Statement": [{
                "Effect": "Allow", "Principal": {"Service": "codedeploy.amazonaws.com"},
                "Action": "sts:AssumeRole",
            }],
        }
        role = iam.create_role(RoleName=name, AssumeRolePolicyDocument=json.dumps(trust))["Role"]
        cleanup.callback(iam.delete_role, RoleName=name)
        deployments = rollout["deployments"]
        deployments.update_deployment_group(
            applicationName=rollout["name"], currentDeploymentGroupName=rollout["name"],
            serviceRoleArn=role["Arn"],
        )
        identifiers = []
        state = {"policy": False}

        def delete_policy():
            if state["policy"]:
                iam.delete_role_policy(RoleName=name, PolicyName="deployment")

        cleanup.callback(delete_policy)

        def stop_all():
            for identifier in identifiers:
                deployments.stop_deployment(deploymentId=identifier)
                wait_for(
                    lambda: deployments.get_deployment(deploymentId=identifier)["deploymentInfo"],
                    lambda value: value["status"] in ("Succeeded", "Failed", "Stopped"),
                )

        def policy(*statements):
            iam.put_role_policy(
                RoleName=name, PolicyName="deployment",
                PolicyDocument=json.dumps({"Version": "2012-10-17", "Statement": list(statements)}),
            )
            state["policy"] = True

        def deploy(hook=None):
            spec = {
                "version": 0.0,
                "Resources": [{"Function": {"Type": "AWS::Lambda::Function", "Properties": {
                    "Name": rollout["name"], "Alias": "live",
                    "CurrentVersion": rollout["original"], "TargetVersion": rollout["target"],
                }}}],
            }
            if hook:
                spec["Hooks"] = [{"BeforeAllowTraffic": hook}]
            identifier = deployments.create_deployment(
                applicationName=rollout["name"], deploymentGroupName=rollout["name"],
                revision={"revisionType": "AppSpecContent",
                          "appSpecContent": {"content": json.dumps(spec)}},
            )["deploymentId"]
            identifiers.append(identifier)
            return wait_for(
                lambda: deployments.get_deployment(deploymentId=identifier)["deploymentInfo"],
                lambda value: value["status"] in ("Succeeded", "Failed", "Stopped"),
            )

        try:
            yield {
                **rollout, "iam": iam, "role_name": name, "role_arn": role["Arn"],
                "trust": trust, "policy": policy, "deploy": deploy, "cleanup": cleanup,
                "alias_arn": rollout["alias"]()["AliasArn"],
            }
        finally:
            stop_all()


def statement(action, resource, effect="Allow"):
    return {"Effect": effect, "Action": action, "Resource": resource}


def permit_alias(scope):
    return statement(["lambda:GetAlias", "lambda:UpdateAlias"], scope["alias_arn"])


def denied(scope, info):
    assert info["status"] == "Failed", info
    assert info["errorInformation"]["code"] in ("IAM_ROLE_PERMISSIONS", "IAM_ROLE_MISSING"), info
    assert scope["alias"]()["FunctionVersion"] == scope["original"]
    assert not scope["alias"]().get("RoutingConfig", {}).get("AdditionalVersionWeights")


def test_lambda_role_requires_permission_and_honors_explicit_deny(deployment_role, record_property):
    scope = deployment_role
    initial = scope["deploy"]()
    record_property("without_permission_status", initial["status"])
    denied(scope, initial)
    scope["policy"](permit_alias(scope))
    assert scope["deploy"]()["status"] == "Succeeded"
    assert scope["alias"]()["FunctionVersion"] == scope["target"]
    scope["functions"].update_alias(
        FunctionName=scope["name"], Name="live", FunctionVersion=scope["original"])
    scope["policy"](permit_alias(scope), statement("lambda:UpdateAlias", scope["alias_arn"], "Deny"))
    denied(scope, scope["deploy"]())


def test_lambda_role_must_trust_codedeploy(deployment_role, record_property):
    scope = deployment_role
    scope["policy"](permit_alias(scope))
    wrong = {
        "Version": "2012-10-17", "Statement": [{
            "Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"},
            "Action": "sts:AssumeRole",
        }],
    }
    scope["iam"].update_assume_role_policy(
        RoleName=scope["role_name"], PolicyDocument=json.dumps(wrong))
    info = scope["deploy"]()
    record_property("untrusted_role_status", info["status"])
    denied(scope, info)
    scope["iam"].update_assume_role_policy(
        RoleName=scope["role_name"], PolicyDocument=json.dumps(scope["trust"]))
    assert scope["deploy"]()["status"] == "Succeeded"
    assert scope["alias"]()["FunctionVersion"] == scope["target"]


def test_lambda_role_permissions_boundary_limits_alias_changes(deployment_role, record_property):
    scope = deployment_role
    scope["policy"](permit_alias(scope))
    boundary = scope["iam"].create_policy(
        PolicyName=scope["role_name"],
        PolicyDocument=json.dumps({
            "Version": "2012-10-17",
            "Statement": [statement("lambda:GetAlias", scope["alias_arn"])],
        }),
    )["Policy"]["Arn"]
    scope["cleanup"].callback(scope["iam"].delete_policy, PolicyArn=boundary)
    scope["iam"].put_role_permissions_boundary(RoleName=scope["role_name"], PermissionsBoundary=boundary)
    scope["cleanup"].callback(scope["iam"].delete_role_permissions_boundary, RoleName=scope["role_name"])
    info = scope["deploy"]()
    record_property("boundary_limited_status", info["status"])
    denied(scope, info)


def test_lambda_hook_permission_is_checked_before_invocation(deployment_role, record_property):
    scope = deployment_role
    scope["policy"](permit_alias(scope))
    hook = scope["name"] + "-missing-hook"
    info = scope["deploy"](hook)
    record_property("unauthorized_hook_error", info.get("errorInformation", {}))
    denied(scope, info)
    hook_arn = scope["alias_arn"].rsplit(":", 1)[0] + "-missing-hook"
    scope["policy"](permit_alias(scope), statement("lambda:InvokeFunction", hook_arn))
    allowed = scope["deploy"](hook)
    assert allowed["status"] == "Failed", allowed
    assert allowed["errorInformation"]["code"] == "HOOK_EXECUTION_FAILURE", allowed
    assert scope["alias"]()["FunctionVersion"] == scope["original"]

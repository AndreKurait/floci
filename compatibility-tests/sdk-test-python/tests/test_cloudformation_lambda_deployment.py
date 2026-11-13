"""CloudFormation alias updates execute their declared CodeDeploy policy."""

import json
import time
from contextlib import ExitStack, closing

import boto3
import pytest
from botocore.exceptions import ClientError

from test_codedeploy_lambda_timing import rollout, wait_for  # noqa: F401


@pytest.fixture
def alias_stack(rollout, aws_config, client_config):
    name = rollout["name"]
    application = name + "-cfn"
    with ExitStack() as cleanup:
        stacks = cleanup.enter_context(closing(boto3.client(
            "cloudformation", config=client_config, **aws_config)))
        iam = cleanup.enter_context(closing(boto3.client("iam", config=client_config, **aws_config)))
        iam.put_role_policy(
            RoleName=name, PolicyName="CloudFormationAliasDeployment",
            PolicyDocument=json.dumps({
                "Version": "2012-10-17", "Statement": [{
                    "Effect": "Allow", "Action": ["lambda:GetAlias", "lambda:UpdateAlias"],
                    "Resource": rollout["alias"]()["AliasArn"].rsplit(":", 1)[0] + ":cfn-live",
                }],
            }),
        )
        cleanup.callback(iam.delete_role_policy, RoleName=name, PolicyName="CloudFormationAliasDeployment")

        def template(version, description="original", after_hook=None):
            policy = {"ApplicationName": {"Ref": "Application"}, "DeploymentGroupName": {"Ref": "Group"}}
            if after_hook:
                policy["AfterAllowTrafficHook"] = after_hook
            return json.dumps({"Resources": {"Alias": {
                "Type": "AWS::Lambda::Alias",
                "Properties": {
                    "FunctionName": name, "Name": "cfn-live",
                    "FunctionVersion": version, "Description": description,
                },
                "UpdatePolicy": {"CodeDeployLambdaAliasUpdate": policy},
            }, "Group": {
                "Type": "AWS::CodeDeploy::DeploymentGroup",
                "Properties": {
                    "ApplicationName": {"Ref": "Application"}, "DeploymentGroupName": application,
                    "ServiceRoleArn": rollout["role"], "DeploymentConfigName": "CodeDeployDefault.LambdaAllAtOnce",
                    "DeploymentStyle": {"DeploymentType": "BLUE_GREEN", "DeploymentOption": "WITH_TRAFFIC_CONTROL"},
                },
            }, "Application": {
                "Type": "AWS::CodeDeploy::Application",
                "Properties": {"ApplicationName": application, "ComputePlatform": "Lambda"},
            }}})

        def status():
            return stacks.describe_stacks(StackName=name)["Stacks"][0]["StackStatus"]

        def alias():
            return rollout["functions"].get_alias(FunctionName=name, Name="cfn-live")

        def deployments():
            return rollout["deployments"].list_deployments(
                applicationName=application, deploymentGroupName=application,
            )["deployments"]

        stacks.create_stack(StackName=name, TemplateBody=template(rollout["original"]))
        try:
            assert wait_for(status, lambda value: not value.endswith("_IN_PROGRESS")) == "CREATE_COMPLETE"
            assert deployments() == [], "Initial alias creation must not create a deployment"
            yield {
                **rollout, "stacks": stacks, "status": status, "template": template,
                "cfn_alias": alias, "deployment_ids": deployments, "application": application,
            }
        finally:
            for identifier in deployments():
                info = rollout["deployments"].get_deployment(deploymentId=identifier)["deploymentInfo"]
                if info["status"] not in ("Succeeded", "Failed", "Stopped"):
                    rollout["deployments"].stop_deployment(deploymentId=identifier)
            wait_for(status, lambda value: not value.endswith("_IN_PROGRESS"), seconds=90)
            stacks.delete_stack(StackName=name)

            def deleted():
                try:
                    return status() == "DELETE_COMPLETE"
                except ClientError as error:
                    if error.response["Error"]["Code"] != "ValidationError":
                        raise
                    return True

            wait_for(deleted, bool)


def test_alias_update_waits_for_codedeploy_and_metadata_update_does_not_redeploy(alias_stack, record_property):
    state = alias_stack
    deployments = state["deployments"]
    events = list(reversed(state["stacks"].describe_stack_events(StackName=state["name"])["StackEvents"]))
    completed_group = next(i for i, event in enumerate(events)
                           if event["LogicalResourceId"] == "Group" and event["ResourceStatus"] == "CREATE_COMPLETE")
    started_alias = next(i for i, event in enumerate(events)
                         if event["LogicalResourceId"] == "Alias" and event["ResourceStatus"] == "CREATE_IN_PROGRESS")
    record_property("group_completion_before_alias", completed_group < started_alias)
    assert completed_group < started_alias, "UpdatePolicy references must establish resource dependencies"
    config = state["name"] + "-cfn-config"
    deployments.create_deployment_config(
        deploymentConfigName=config, computePlatform="Lambda",
        trafficRoutingConfig={
            "type": "TimeBasedCanary",
            "timeBasedCanary": {"canaryPercentage": 50, "canaryInterval": 1},
        },
    )
    try:
        deployments.update_deployment_group(
            applicationName=state["application"], currentDeploymentGroupName=state["application"],
            deploymentConfigName=config,
        )
        started = time.monotonic()
        state["stacks"].update_stack(
            StackName=state["name"], TemplateBody=state["template"](state["target"]),
        )
        identifiers = wait_for(
            lambda: {"ids": state["deployment_ids"](), "stack": state["status"]()},
            lambda value: bool(value["ids"]) or not value["stack"].endswith("_IN_PROGRESS"),
        )
        record_property("first_update_deployment_ids", json.dumps(identifiers["ids"]))
        record_property("first_update_stack_status", identifiers["stack"])
        assert len(identifiers["ids"]) == 1, identifiers
        identifier = identifiers["ids"][0]
        wait_for(
            state["cfn_alias"],
            lambda value: value.get("RoutingConfig", {}).get("AdditionalVersionWeights")
            == {state["target"]: 0.5},
        )
        assert state["status"]() == "UPDATE_IN_PROGRESS"
        assert state["cfn_alias"]()["FunctionVersion"] == state["original"]
        assert wait_for(
            state["status"], lambda value: not value.endswith("_IN_PROGRESS"), seconds=90,
        ) == "UPDATE_COMPLETE"
        elapsed = time.monotonic() - started
        record_property("configured_interval_seconds", 60)
        record_property("stack_update_seconds", elapsed)
        assert elapsed >= 59.5
        assert deployments.get_deployment(deploymentId=identifier)["deploymentInfo"]["status"] == "Succeeded"
        assert state["cfn_alias"]()["FunctionVersion"] == state["target"]
        assert state["alias"]()["FunctionVersion"] == state["original"], "Unrelated alias must remain unchanged"
        state["stacks"].update_stack(
            StackName=state["name"], TemplateBody=state["template"](state["target"], "metadata-only"),
        )
        assert wait_for(state["status"], lambda value: not value.endswith("_IN_PROGRESS")) == "UPDATE_COMPLETE"
        assert state["deployment_ids"]() == [identifier]
        assert state["cfn_alias"]()["Description"] == "metadata-only"
    finally:
        deployments.update_deployment_group(
            applicationName=state["application"], currentDeploymentGroupName=state["application"],
            deploymentConfigName="CodeDeployDefault.LambdaAllAtOnce",
        )
        deployments.delete_deployment_config(deploymentConfigName=config)


def test_failed_after_hook_rolls_back_the_alias_through_its_previous_policy(alias_stack, record_property):
    state = alias_stack
    state["stacks"].update_stack(
        StackName=state["name"],
        TemplateBody=state["template"](state["target"], "failed-update", state["name"] + "-missing-hook"),
    )
    status = wait_for(state["status"], lambda value: not value.endswith("_IN_PROGRESS"), seconds=30)
    identifiers = state["deployment_ids"]()
    record_property("stack_status", status)
    record_property("deployment_ids", json.dumps(identifiers))
    assert status == "UPDATE_ROLLBACK_COMPLETE"
    assert state["cfn_alias"]()["FunctionVersion"] == state["original"]
    assert state["cfn_alias"]()["Description"] == "original"
    info = [
        state["deployments"].get_deployment(deploymentId=identifier)["deploymentInfo"]
        for identifier in identifiers
    ]
    assert sorted(deployment["status"] for deployment in info) == ["Failed", "Succeeded"]
    recovery = next(deployment for deployment in info if deployment["status"] == "Succeeded")
    revision = json.loads(recovery["revision"]["appSpecContent"]["content"])
    assert not revision.get("Hooks")
    properties = revision["Resources"][0]["Function"]["Properties"]
    assert properties["CurrentVersion"] == state["target"]
    assert properties["TargetVersion"] == state["original"]

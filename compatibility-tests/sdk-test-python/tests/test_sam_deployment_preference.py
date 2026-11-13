"""SAM deployment preferences use CloudFormation's CodeDeploy alias update path."""

import json
import time
from contextlib import closing, contextmanager

import boto3
from botocore.exceptions import ClientError

from test_codedeploy_lambda_timing import rollout, wait_for  # noqa: F401


@contextmanager
def sam_stack(rollout, aws_config, client_config, record_property, *, enabled=True):
    name = rollout["name"] + "-sam"

    def template(generation):
        properties = {
            "FunctionName": name, "Runtime": "python3.12", "Handler": "index.handler",
            "InlineCode": f"def handler(event, context): return {generation!r}\n",
            "Role": rollout["role"], "DeploymentPreference": {"Enabled": enabled, "Type": "AllAtOnce"},
        }
        body = {
            "Transform": "AWS::Serverless-2016-10-31",
            "Resources": {"Function": {"Type": "AWS::Serverless::Function", "Properties": properties}},
        }
        if enabled:
            properties["AutoPublishAlias"] = "live"
            body["Outputs"] = {
                "Application": {"Value": {"Ref": "ServerlessDeploymentApplication"}},
                "Group": {"Value": {"Ref": "FunctionDeploymentGroup"}},
                "Alias": {"Value": {"Ref": "Function.Alias"}},
            }
        return json.dumps(body)

    with closing(boto3.client("cloudformation", config=client_config, **aws_config)) as stacks:
        def stack():
            return stacks.describe_stacks(StackName=name)["Stacks"][0]

        def terminal():
            return wait_for(stack, lambda value: not value["StackStatus"].endswith("_IN_PROGRESS"), seconds=60)

        started = time.monotonic()
        stacks.create_stack(StackName=name, TemplateBody=template("first"), Capabilities=["CAPABILITY_IAM"])
        try:
            created = terminal()
            record_property("create_stack_status", created["StackStatus"])
            record_property("create_stack_seconds", time.monotonic() - started)
            assert created["StackStatus"] == "CREATE_COMPLETE"
            yield {
                "name": name, "stacks": stacks, "stack": stack, "terminal": terminal,
                "template": template, "created": created,
            }
        finally:
            terminal()
            started = time.monotonic()
            stacks.delete_stack(StackName=name)

            def deleted():
                try:
                    return stack()["StackStatus"] == "DELETE_COMPLETE"
                except ClientError as error:
                    if error.response["Error"]["Code"] != "ValidationError":
                        raise
                    return True

            wait_for(deleted, bool, seconds=60)
            record_property("delete_stack_seconds", time.monotonic() - started)


def test_sam_update_runs_a_deployment_using_the_generated_group(rollout, aws_config, client_config, record_property):
    with sam_stack(rollout, aws_config, client_config, record_property) as state:
        body = state["stacks"].get_template(StackName=state["name"], TemplateStage="Processed")["TemplateBody"]
        if isinstance(body, str):
            body = json.loads(body)
        resources = body["Resources"]
        record_property("generated_resource_types", json.dumps(sorted(r["Type"] for r in resources.values())))
        assert {"ServerlessDeploymentApplication", "FunctionDeploymentGroup", "CodeDeployServiceRole"} <= resources.keys()
        assert resources["ServerlessDeploymentApplication"]["Type"] == "AWS::CodeDeploy::Application"
        assert resources["FunctionDeploymentGroup"]["Type"] == "AWS::CodeDeploy::DeploymentGroup"
        assert resources["CodeDeployServiceRole"]["Type"] == "AWS::IAM::Role"
        outputs = {item["OutputKey"]: item["OutputValue"] for item in state["created"]["Outputs"]}
        application, group = outputs["Application"], outputs["Group"]
        deployments = rollout["deployments"]
        configuration = deployments.get_deployment_group(
            applicationName=application, deploymentGroupName=group,
        )["deploymentGroupInfo"]
        assert configuration["deploymentConfigName"] == "CodeDeployDefault.LambdaAllAtOnce"
        assert configuration["autoRollbackConfiguration"]["enabled"] is True
        assert deployments.list_deployments(
            applicationName=application, deploymentGroupName=group,
        )["deployments"] == []
        original = rollout["functions"].get_alias(FunctionName=state["name"], Name="live")["FunctionVersion"]
        started = time.monotonic()
        state["stacks"].update_stack(
            StackName=state["name"], TemplateBody=state["template"]("second"), Capabilities=["CAPABILITY_IAM"],
        )
        updated = state["terminal"]()
        record_property("update_stack_status", updated["StackStatus"])
        record_property("update_stack_seconds", time.monotonic() - started)
        assert updated["StackStatus"] == "UPDATE_COMPLETE"
        identifiers = deployments.list_deployments(applicationName=application, deploymentGroupName=group)["deployments"]
        record_property("deployment_ids", json.dumps(identifiers))
        assert len(identifiers) == 1
        deployment = deployments.get_deployment(deploymentId=identifiers[0])["deploymentInfo"]
        assert deployment["status"] == "Succeeded"
        target = rollout["functions"].get_alias(FunctionName=state["name"], Name="live")["FunctionVersion"]
        assert target != original
        spec = json.loads(deployment["revision"]["appSpecContent"]["content"])
        properties = spec["Resources"][0]["Function"]["Properties"]
        assert properties["CurrentVersion"] == original
        assert properties["TargetVersion"] == target
        assert properties["Name"] == state["name"]
        assert properties["Alias"] == "live"


def test_disabled_preference_bootstraps_without_alias_or_deployment_resources(
    rollout, aws_config, client_config, record_property,
):
    with sam_stack(rollout, aws_config, client_config, record_property, enabled=False) as state:
        resources = state["stacks"].list_stack_resources(StackName=state["name"])["StackResourceSummaries"]
        assert [resource["ResourceType"] for resource in resources] == ["AWS::Lambda::Function"]
        assert rollout["functions"].list_aliases(FunctionName=state["name"])["Aliases"] == []

"""IAM_ROLE tasks reach API Gateway's resource-policy evaluation."""

import json
import time
from contextlib import ExitStack, closing

import boto3
import pytest
from botocore.session import Session


@pytest.mark.parametrize("effect", ["Allow", "Deny"])
def test_iam_role_task_obeys_api_resource_policy(
    aws_config, client_config, iam_client, unique_name, effect
):
    with ExitStack() as cleanup:
        api = cleanup.enter_context(closing(boto3.client("apigateway", config=client_config, **aws_config)))
        sfn = cleanup.enter_context(closing(boto3.client("stepfunctions", config=client_config, **aws_config)))
        name = "sfn-api-policy-" + unique_name
        role = iam_client.create_role(
            RoleName=name,
            AssumeRolePolicyDocument=json.dumps({
                "Version": "2012-10-17",
                "Statement": [{
                    "Effect": "Allow", "Principal": {"Service": "states.amazonaws.com"},
                    "Action": "sts:AssumeRole",
                }],
            }),
        )["Role"]["Arn"]
        cleanup.callback(iam_client.delete_role, RoleName=name)
        api_id = api.create_rest_api(name=name)["id"]
        cleanup.callback(api.delete_rest_api, restApiId=api_id)
        partition, account = role.split(":")[1], role.split(":")[4]
        region = api.meta.region_name
        invoke_arn = f"arn:{partition}:execute-api:{region}:{account}:{api_id}/test/POST/invoke"
        statement = {"Effect": "Allow", "Action": "execute-api:Invoke", "Resource": invoke_arn}
        iam_client.put_role_policy(
            RoleName=name, PolicyName="invoke",
            PolicyDocument=json.dumps({"Version": "2012-10-17", "Statement": [statement]}),
        )
        cleanup.callback(iam_client.delete_role_policy, RoleName=name, PolicyName="invoke")
        resource_statement = {**statement, "Effect": effect, "Principal": {"AWS": role}}
        api.update_rest_api(restApiId=api_id, patchOperations=[{
            "op": "replace", "path": "/policy",
            "value": json.dumps({"Version": "2012-10-17", "Statement": [resource_statement]}),
        }])
        root = next(item["id"] for item in api.get_resources(restApiId=api_id)["items"]
                    if item["path"] == "/")
        resource = api.create_resource(restApiId=api_id, parentId=root, pathPart="invoke")["id"]
        method = {"restApiId": api_id, "resourceId": resource, "httpMethod": "POST"}
        api.put_method(**method, authorizationType="AWS_IAM")
        api.put_method_response(**method, statusCode="200")
        api.put_integration(
            **method, type="MOCK",
            requestTemplates={"application/json": '{"statusCode":200}'},
        )
        api.put_integration_response(
            **method, statusCode="200",
            responseTemplates={"application/json": '{"resourcePolicyEvaluated":true}'},
        )
        api.create_deployment(restApiId=api_id, stageName="test")
        endpoint = Session().get_component("endpoint_resolver").construct_endpoint("execute-api", region)
        machine = sfn.create_state_machine(
            name=name, roleArn=role,
            definition=json.dumps({
                "StartAt": "Invoke",
                "States": {
                    "Invoke": {
                        "Type": "Task", "Resource": f"arn:{partition}:states:::apigateway:invoke",
                        "End": True,
                        "Parameters": {
                            "ApiEndpoint": f"{api_id}.{endpoint['hostname']}",
                            "Stage": "test", "Path": "invoke", "Method": "POST",
                            "AuthType": "IAM_ROLE", "RequestBody": {"owned": True},
                        },
                    },
                },
            }),
        )["stateMachineArn"]
        cleanup.callback(sfn.delete_state_machine, stateMachineArn=machine)
        execution = sfn.start_execution(stateMachineArn=machine, input="{}")["executionArn"]

        def stop_running_execution():
            if sfn.describe_execution(executionArn=execution)["status"] == "RUNNING":
                sfn.stop_execution(executionArn=execution)

        cleanup.callback(stop_running_execution)
        deadline = time.monotonic() + 30
        while True:
            result = sfn.describe_execution(executionArn=execution)
            if result["status"] != "RUNNING":
                break
            assert time.monotonic() < deadline, "API Gateway task did not finish"
            time.sleep(0.1)
        if effect == "Allow":
            assert result["status"] == "SUCCEEDED", result
            output = json.loads(result["output"])
            assert output["StatusCode"] == 200
            assert output["ResponseBody"] == {"resourcePolicyEvaluated": True}
        else:
            assert result["status"] == "FAILED", result
            assert result["error"] == "ApiGateway.403", result

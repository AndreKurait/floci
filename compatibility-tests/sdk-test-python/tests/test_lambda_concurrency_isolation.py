"""Lambda reservations and deletion retain the owning account and region."""

from contextlib import ExitStack, closing, contextmanager
import json

import boto3
import pytest
from botocore.exceptions import ClientError

from test_cloudformation_provisioned_concurrency import remove_stack, settled, template
from test_lambda_autoscaling_accounts import member_account_configs


@contextmanager
def isolated_functions(aws_config, client_config, unique_name, scope):
    """Use real member credentials and CloudFormation-owned execution roles."""
    with ExitStack() as resources:
        configs = resources.enter_context(member_account_configs(aws_config, client_config, unique_name))
        accounts = tuple(configs)
        first = configs[accounts[0]]
        other = configs[accounts[1]] if scope == "account" else dict(
            first, region_name="us-west-2" if first["region_name"] != "us-west-2" else "us-east-1"
        )
        document = json.loads(template(unique_name, False, provisioned=False))
        document.pop("Parameters")
        document["Resources"] = {
            name: document["Resources"][name] for name in ("Role", "Function")
        }
        document["Resources"]["Function"]["Properties"]["Code"]["ZipFile"] = (
            "import uuid\n"
            "worker = uuid.uuid4().hex\n"
            "def handler(event, context):\n"
            "    return {'arn': context.invoked_function_arn, 'worker': worker}\n"
        )
        functions = []
        for config in (first, other):
            cfn = resources.enter_context(closing(boto3.client(
                "cloudformation", config=client_config, **config
            )))
            client = resources.enter_context(closing(boto3.client(
                "lambda", config=client_config, **config
            )))
            resources.callback(remove_stack, cfn, unique_name)
            cfn.create_stack(
                StackName=unique_name, TemplateBody=json.dumps(document),
                Capabilities=["CAPABILITY_IAM"],
            )
            assert settled(cfn, unique_name)["StackStatus"] == "CREATE_COMPLETE"
            arn = client.get_function(FunctionName=unique_name)["Configuration"]["FunctionArn"]
            functions.append((client, arn))
        assert functions[0][1] != functions[1][1]
        yield functions


def invoke(client, function, arn):
    response = client.invoke(FunctionName=function, Payload=b"{}")
    assert response["StatusCode"] == 200 and not response.get("FunctionError"), response
    with response["Payload"] as payload:
        result = json.load(payload)
    assert result["arn"] == arn and result["worker"]
    return result


def assert_reservation(client, function, reserved, limit):
    assert client.get_function_concurrency(FunctionName=function)["ReservedConcurrentExecutions"] == reserved
    assert client.get_account_settings()["AccountLimit"]["UnreservedConcurrentExecutions"] == limit - reserved


@pytest.mark.parametrize("scope", ["account", "region"])
def test_reservation_and_function_removal_preserve_other_owners(
    aws_config, client_config, unique_name, scope
):
    with isolated_functions(aws_config, client_config, unique_name, scope) as functions:
        (first, _), (other, other_arn) = functions
        limit = first.get_account_settings()["AccountLimit"]["ConcurrentExecutions"]
        reserved = limit // 2 + 1
        for client, arn in functions:
            client.put_function_concurrency(
                FunctionName=unique_name, ReservedConcurrentExecutions=reserved
            )
            assert_reservation(client, unique_name, reserved, limit)
            invoke(client, unique_name, arn)
        first.delete_function_concurrency(FunctionName=unique_name)
        assert first.get_account_settings()["AccountLimit"]["UnreservedConcurrentExecutions"] == limit
        assert_reservation(other, unique_name, reserved, limit)
        first.delete_function(FunctionName=unique_name)
        with pytest.raises(ClientError) as missing:
            first.get_function(FunctionName=unique_name)
        assert missing.value.response["Error"]["Code"] == "ResourceNotFoundException"
        assert_reservation(other, unique_name, reserved, limit)
        invoke(other, unique_name, other_arn)

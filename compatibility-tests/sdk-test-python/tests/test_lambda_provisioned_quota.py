"""Initialized provisioned capacity consumes only its owner's Lambda quota."""

import pytest
from botocore.exceptions import ClientError

from test_lambda_application_autoscaling import capacity
from test_lambda_concurrency_isolation import isolated_functions, invoke


def assert_unreserved(client, expected):
    limits = client.get_account_settings()["AccountLimit"]
    assert limits["UnreservedConcurrentExecutions"] == expected, limits


@pytest.mark.parametrize("scope", ["account", "region"])
def test_provisioned_quota_overlap_and_release_preserve_other_owners(
    aws_config, client_config, unique_name, scope
):
    with isolated_functions(aws_config, client_config, unique_name, scope) as functions:
        (first, first_arn), (other, other_arn) = functions
        limits = [
            client.get_account_settings()["AccountLimit"]["ConcurrentExecutions"]
            for client, _ in functions
        ]
        for (client, arn), limit in zip(functions, limits):
            version = client.publish_version(FunctionName=unique_name)["Version"]
            client.create_alias(
                FunctionName=unique_name, Name="live", FunctionVersion=version
            )
            client.put_provisioned_concurrency_config(
                FunctionName=unique_name, Qualifier="live",
                ProvisionedConcurrentExecutions=1,
            )
            capacity(client, unique_name, 1)
            assert_unreserved(client, limit - 1)
            invoke(client, unique_name + ":live", arn + ":live")

        with pytest.raises(ClientError) as below_provisioned:
            first.put_function_concurrency(
                FunctionName=unique_name, ReservedConcurrentExecutions=0
            )
        assert below_provisioned.value.response["Error"]["Code"] == "InvalidParameterValueException"
        assert_unreserved(first, limits[0] - 1)
        capacity(first, unique_name, 1)

        first.put_function_concurrency(
            FunctionName=unique_name, ReservedConcurrentExecutions=2
        )
        assert_unreserved(first, limits[0] - 2)
        assert_unreserved(other, limits[1] - 1)
        invoke(first, unique_name + ":live", first_arn + ":live")
        first.delete_function_concurrency(FunctionName=unique_name)
        assert_unreserved(first, limits[0] - 1)

        first.delete_provisioned_concurrency_config(
            FunctionName=unique_name, Qualifier="live"
        )
        assert_unreserved(first, limits[0])
        with pytest.raises(ClientError) as missing:
            first.get_provisioned_concurrency_config(
                FunctionName=unique_name, Qualifier="live"
            )
        assert missing.value.response["Error"]["Code"] == "ProvisionedConcurrencyConfigNotFoundException"
        assert_unreserved(other, limits[1] - 1)
        capacity(other, unique_name, 1)
        invoke(other, unique_name + ":live", other_arn + ":live")
        other.delete_function(FunctionName=unique_name)
        assert_unreserved(other, limits[1])

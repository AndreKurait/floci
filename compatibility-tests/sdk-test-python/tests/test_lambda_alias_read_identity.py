"""Alias reads preserve the requested ARN while returning the primary version."""

import pytest


@pytest.mark.parametrize("form", ["name", "arn", "partial"])
@pytest.mark.parametrize("embedded", [False, True])
def test_alias_read_identity(lambda_client, minimal_lambda_zip, unique_name, form, embedded):
    created = lambda_client.create_function(
        FunctionName=unique_name, Runtime="nodejs22.x", Handler="index.handler",
        Role="arn:aws:iam::000000000000:role/lambda-role",
        Code={"ZipFile": minimal_lambda_zip},
    )
    try:
        version = lambda_client.publish_version(FunctionName=unique_name)["Version"]
        alias = lambda_client.create_alias(
            FunctionName=unique_name, Name="stable", FunctionVersion=version
        )
        arn = created["FunctionArn"]
        reference = {"name": unique_name, "arn": arn, "partial": arn.split(":", 4)[4]}[form]
        request = (
            {"FunctionName": reference + ":stable"} if embedded
            else {"FunctionName": reference, "Qualifier": "stable"}
        )
        function = lambda_client.get_function(**request)["Configuration"]
        configuration = lambda_client.get_function_configuration(**request)
        for response in (function, configuration):
            assert response["FunctionArn"] == alias["AliasArn"]
            assert response["Version"] == version
            assert response["CodeSha256"] == created["CodeSha256"]
        numeric = lambda_client.get_function(
            FunctionName=unique_name, Qualifier=version
        )["Configuration"]
        latest = lambda_client.get_function(FunctionName=unique_name)["Configuration"]
        assert (numeric["FunctionArn"], numeric["Version"]) == (arn + ":" + version, version)
        assert (latest["FunctionArn"], latest["Version"]) == (arn, "$LATEST")
    finally:
        lambda_client.delete_function(FunctionName=unique_name)

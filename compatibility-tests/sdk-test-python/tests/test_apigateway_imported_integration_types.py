"""OpenAPI integration type readback verified against live API Gateway."""

import json
import uuid
from contextlib import closing

import boto3


def test_imported_integration_types(aws_config):
    expected_types = {
        "aws": "AWS", "aws_proxy": "AWS_PROXY", "http": "HTTP",
        "http_proxy": "HTTP_PROXY", "mock": "MOCK", "any": "MOCK",
    }
    paths = {}
    for name in expected_types:
        integration_type = "mock" if name == "any" else name
        integration = {"type": integration_type, "timeoutInMillis": 12000}
        if integration_type != "mock":
            integration["httpMethod"] = "POST"
        if integration_type.startswith("aws"):
            integration["uri"] = (
                "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/"
                "functions/arn:aws:lambda:us-east-1:000000000000:function:fixture:live/invocations")
        elif integration_type.startswith("http"):
            integration["uri"] = "https://example.com/original"
        if integration_type in ("aws", "http", "mock"):
            integration["responses"] = {"default": {"statusCode": "200"}}
        operation = "x-amazon-apigateway-any-method" if name == "any" else "get"
        paths["/" + name] = {operation: {
            "responses": {"200": {"description": "ok"}},
            "x-amazon-apigateway-integration": integration,
        }}
    document = {"openapi": "3.0.2", "info": {
        "title": "integration-types-" + uuid.uuid4().hex, "version": "1"}, "paths": paths}

    with closing(boto3.client("apigateway", **aws_config)) as api:
        api_id = api.import_rest_api(body=json.dumps(document), failOnWarnings=True)["id"]
        try:
            for mode, timeout in [(None, 12000), ("merge", 14000), ("overwrite", 15000)]:
                for path in paths.values():
                    integration = next(iter(path.values()))["x-amazon-apigateway-integration"]
                    integration["timeoutInMillis"] = timeout
                    if mode and "uri" in integration:
                        integration["uri"] = integration["uri"].replace(
                            ":live/", ":next/").replace("/original", "/replacement")
                if mode:
                    api.put_rest_api(restApiId=api_id, mode=mode,
                                     body=json.dumps(document), failOnWarnings=True)
                resources = api.get_resources(restApiId=api_id)["items"]
                resources = {resource["path"]: resource["id"] for resource in resources}
                for name, expected_type in expected_types.items():
                    method = {"restApiId": api_id, "resourceId": resources["/" + name],
                              "httpMethod": "ANY" if name == "any" else "GET"}
                    integration = api.get_integration(**method)
                    embedded = api.get_method(**method)["methodIntegration"]
                    expected = next(iter(paths["/" + name].values()))["x-amazon-apigateway-integration"]
                    assert integration["type"] == embedded["type"] == expected_type
                    for field in ("uri", "httpMethod", "timeoutInMillis"):
                        if field in expected:
                            assert integration[field] == embedded[field] == expected[field]
        finally:
            api.delete_rest_api(restApiId=api_id)

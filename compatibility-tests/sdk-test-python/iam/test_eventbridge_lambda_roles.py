"""Cross-account EventBridge role permissions with real Lambda workers."""

from contextlib import ExitStack, closing, contextmanager
import json
import time

import boto3
from botocore.exceptions import ClientError
import pytest

from iam.test_eventbridge_lambda_permissions import _await_handler, _await_queue, _package, _received


def _client(cleanup, service, config, client_config):
    return cleanup.enter_context(closing(boto3.client(service, config=client_config, **config)))


@contextmanager
def _member(aws_config, client_config, name):
    """Provision the bus account and use its actual STS-issued management credentials."""
    with ExitStack() as cleanup:
        organizations = _client(cleanup, "organizations", aws_config, client_config)
        try:
            organizations.create_organization(FeatureSet="ALL")
        except ClientError as error:
            if error.response["Error"]["Code"] != "AlreadyInOrganizationException":
                raise
        else:
            cleanup.callback(organizations.delete_organization)
        root = organizations.list_roots()["Roots"][0]
        if not any(policy["Type"] == "SERVICE_CONTROL_POLICY" and policy["Status"] == "ENABLED"
                   for policy in root.get("PolicyTypes", [])):
            organizations.enable_policy_type(RootId=root["Id"], PolicyType="SERVICE_CONTROL_POLICY")
            cleanup.callback(
                organizations.disable_policy_type, RootId=root["Id"], PolicyType="SERVICE_CONTROL_POLICY",
            )
            deadline = time.monotonic() + 30
            while True:
                updated = next(item for item in organizations.list_roots()["Roots"] if item["Id"] == root["Id"])
                if any(policy["Type"] == "SERVICE_CONTROL_POLICY" and policy["Status"] == "ENABLED"
                       for policy in updated.get("PolicyTypes", [])):
                    break
                assert time.monotonic() < deadline, "SCP policy type did not become enabled"
                time.sleep(0.25)
        status = organizations.create_account(
            AccountName=name, Email=name + "@example.test",
        )["CreateAccountStatus"]
        deadline = time.monotonic() + 30
        while status["State"] == "IN_PROGRESS":
            assert time.monotonic() < deadline, "Member account creation did not finish"
            time.sleep(0.25)
            status = organizations.describe_create_account_status(
                CreateAccountRequestId=status["Id"],
            )["CreateAccountStatus"]
        assert status["State"] == "SUCCEEDED", status
        account_id = status["AccountId"]
        cleanup.callback(organizations.remove_account_from_organization, AccountId=account_id)
        partition = organizations.describe_account(AccountId=account_id)["Account"]["Arn"].split(":")[1]
        sts = _client(cleanup, "sts", aws_config, client_config)
        assert sts.get_caller_identity()["Account"] != account_id
        credentials = sts.assume_role(
            RoleArn=f"arn:{partition}:iam::{account_id}:role/OrganizationAccountAccessRole",
            RoleSessionName=name,
        )["Credentials"]
        config = {
            **aws_config,
            "aws_access_key_id": credentials["AccessKeyId"],
            "aws_secret_access_key": credentials["SecretAccessKey"],
            "aws_session_token": credentials["SessionToken"],
        }
        member_sts = _client(cleanup, "sts", config, client_config)
        assert member_sts.get_caller_identity()["Account"] == account_id
        yield config, organizations, account_id


def _policy(action, resource, effect="Allow"):
    return {"Version": "2012-10-17", "Statement": [{
        "Effect": effect, "Action": action, "Resource": resource,
    }]}


@pytest.mark.parametrize("custom_bus", [False, True])
def test_cross_account_role_rechecks_trust_grants_boundary_and_scp(
    aws_config, client_config, unique_name, custom_bus,
):
    with _member(aws_config, client_config, unique_name) as (member, organizations, account):
        with ExitStack() as cleanup:
            events = _client(cleanup, "events", member, client_config)
            iam = _client(cleanup, "iam", member, client_config)
            sqs = _client(cleanup, "sqs", member, client_config)
            target_iam = _client(cleanup, "iam", aws_config, client_config)
            functions = _client(cleanup, "lambda", aws_config, client_config)
            logs = _client(cleanup, "logs", aws_config, client_config)
            function = unique_name + "-handler"
            group = "/aws/lambda/" + function
            worker_role = target_iam.create_role(
                RoleName=function, AssumeRolePolicyDocument=json.dumps({
                    "Version": "2012-10-17", "Statement": [{
                        "Effect": "Allow", "Action": "sts:AssumeRole",
                        "Principal": {"Service": "lambda.amazonaws.com"},
                    }],
                }),
            )["Role"]["Arn"]
            cleanup.callback(target_iam.delete_role, RoleName=function)
            target_iam.put_role_policy(
                RoleName=function, PolicyName="logs",
                PolicyDocument=json.dumps(_policy(
                    ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"], "*",
                )),
            )
            cleanup.callback(target_iam.delete_role_policy, RoleName=function, PolicyName="logs")
            logs.create_log_group(logGroupName=group)
            cleanup.callback(logs.delete_log_group, logGroupName=group)
            created = functions.create_function(
                FunctionName=function, Runtime="python3.12", Role=worker_role,
                Handler="index.handler", Timeout=30, MemorySize=128,
                Code={"ZipFile": _package()}, Publish=True,
            )
            cleanup.callback(functions.delete_function, FunctionName=function)
            functions.get_waiter("function_active_v2").wait(
                FunctionName=function, WaiterConfig={"Delay": 1, "MaxAttempts": 60},
            )
            endpoint = functions.create_alias(
                FunctionName=function, Name="live", FunctionVersion=created["Version"],
            )["AliasArn"]
            assert endpoint.split(":")[4] != account
            warm = functions.invoke(FunctionName=endpoint, Payload=b'{"probe":"ready"}')
            with warm["Payload"] as payload:
                assert "FunctionError" not in warm, payload.read().decode()
                assert json.load(payload) == {"probe": "ready"}

            bus = unique_name + "-bus" if custom_bus else "default"
            if custom_bus:
                events.create_event_bus(Name=bus)
                cleanup.callback(events.delete_event_bus, Name=bus)
            rule = events.put_rule(
                Name=unique_name, EventBusName=bus,
                EventPattern=json.dumps({"source": [unique_name]}),
            )["RuleArn"]
            cleanup.callback(events.delete_rule, Name=unique_name, EventBusName=bus)
            trust = {
                "Version": "2012-10-17", "Statement": [{
                    "Effect": "Allow", "Action": "sts:AssumeRole",
                    "Principal": {"Service": "events.amazonaws.com"},
                    "Condition": {
                        "ArnEquals": {"aws:SourceArn": rule},
                        "StringEquals": {"aws:SourceAccount": account},
                    },
                }],
            }
            role = iam.create_role(
                RoleName=unique_name, Path="/eventbridge/",
                AssumeRolePolicyDocument=json.dumps(trust).replace(
                    "events.amazonaws.com", "sns.amazonaws.com",
                ),
            )["Role"]["Arn"]
            cleanup.callback(iam.delete_role, RoleName=unique_name)
            grant = dict(
                FunctionName=function, Qualifier="live", StatementId="role",
                Action="lambda:InvokeFunction", Principal=role,
            )
            functions.add_permission(**grant)
            identity = _policy("lambda:InvokeFunction", endpoint)
            iam.put_role_policy(
                RoleName=unique_name, PolicyName="invoke", PolicyDocument=json.dumps(identity),
            )
            cleanup.callback(iam.delete_role_policy, RoleName=unique_name, PolicyName="invoke")
            boundary = iam.create_policy(
                PolicyName=unique_name,
                PolicyDocument=json.dumps(_policy("lambda:InvokeFunction", endpoint, "Deny")),
            )["Policy"]["Arn"]
            cleanup.callback(iam.delete_policy, PolicyArn=boundary)
            scp_document = _policy("*", "*")
            scp_document["Statement"].append(_policy("lambda:InvokeFunction", endpoint, "Deny")["Statement"][0])
            scp = organizations.create_policy(
                Name=unique_name, Description="EventBridge target permission test",
                Type="SERVICE_CONTROL_POLICY", Content=json.dumps(scp_document),
            )["Policy"]["PolicySummary"]["Id"]
            cleanup.callback(organizations.delete_policy, PolicyId=scp)
            queues = {}
            for name in ("sibling", "dead-letter"):
                queue = sqs.create_queue(QueueName=unique_name + "-" + name)["QueueUrl"]
                cleanup.callback(sqs.delete_queue, QueueUrl=queue)
                arn = sqs.get_queue_attributes(
                    QueueUrl=queue, AttributeNames=["QueueArn"],
                )["Attributes"]["QueueArn"]
                policy = _policy("sqs:SendMessage", arn)
                policy["Statement"][0].update(
                    Principal={"Service": "events.amazonaws.com"},
                    Condition={"ArnEquals": {"aws:SourceArn": rule}},
                )
                sqs.set_queue_attributes(QueueUrl=queue, Attributes={"Policy": json.dumps(policy)})
                queues[name] = (queue, arn)
            response = events.put_targets(
                Rule=unique_name, EventBusName=bus, Targets=[
                    {"Id": "handler", "Arn": endpoint, "RoleArn": role,
                     "DeadLetterConfig": {"Arn": queues["dead-letter"][1]}},
                    {"Id": "sibling", "Arn": queues["sibling"][1]},
                ],
            )
            cleanup.callback(events.remove_targets, Rule=unique_name, EventBusName=bus, Ids=["handler", "sibling"])
            assert response["FailedEntryCount"] == 0, response

            def publish(phase, allowed=False):
                response = events.put_events(Entries=[{
                    "EventBusName": bus, "Source": unique_name, "DetailType": "permission-check",
                    "Detail": json.dumps({"message": phase}),
                }])
                assert response["FailedEntryCount"] == 0, response
                event_id = response["Entries"][0]["EventId"]
                _await_queue(sqs, queues["sibling"][0], event_id)
                if allowed:
                    _await_handler(logs, group, phase, event_id)
                else:
                    message = _await_queue(sqs, queues["dead-letter"][0], event_id)
                    attributes = {key: value["StringValue"] for key, value in message["MessageAttributes"].items()}
                    assert attributes["ERROR_CODE"] == "NO_PERMISSIONS"
                    assert attributes["RULE_ARN"] == rule
                    assert attributes["TARGET_ARN"] == endpoint
                    assert attributes["RETRY_ATTEMPTS"] == "0"
                    assert "EXHAUSTED_RETRY_CONDITION" not in attributes

            publish("wrong-trust")
            iam.update_assume_role_policy(RoleName=unique_name, PolicyDocument=json.dumps(trust))
            publish("allowed", allowed=True)
            iam.put_role_policy(
                RoleName=unique_name, PolicyName="invoke",
                PolicyDocument=json.dumps(_policy("lambda:InvokeFunction", created["FunctionArn"])),
            )
            publish("unqualified-identity")
            iam.put_role_policy(
                RoleName=unique_name, PolicyName="invoke", PolicyDocument=json.dumps(identity),
            )
            with ExitStack() as boundary_cleanup:
                iam.put_role_permissions_boundary(RoleName=unique_name, PermissionsBoundary=boundary)
                boundary_cleanup.callback(iam.delete_role_permissions_boundary, RoleName=unique_name)
                publish("boundary-deny")
            with ExitStack() as scp_cleanup:
                organizations.attach_policy(PolicyId=scp, TargetId=account)
                scp_cleanup.callback(organizations.detach_policy, PolicyId=scp, TargetId=account)
                publish("scp-deny")
            functions.remove_permission(FunctionName=function, Qualifier="live", StatementId="role")
            publish("resource-grant-revoked")
            functions.add_permission(**grant)
            publish("recovered", allowed=True)
            assert {event["detail"]["message"] for event in _received(logs, group)} == {"allowed", "recovered"}
            assert not sqs.receive_message(QueueUrl=queues["dead-letter"][0], WaitTimeSeconds=1).get("Messages")
        with ExitStack() as verification:
            iam = _client(verification, "iam", member, client_config)
            sqs = _client(verification, "sqs", member, client_config)
            events = _client(verification, "events", member, client_config)
            assert {
                role["RoleName"] for page in iam.get_paginator("list_roles").paginate()
                for role in page["Roles"]
            } == {"OrganizationAccountAccessRole"}
            assert not [
                policy for page in iam.get_paginator("list_policies").paginate(Scope="Local")
                for policy in page["Policies"]
            ]
            assert not sqs.list_queues().get("QueueUrls")
            assert [bus["Name"] for bus in events.list_event_buses()["EventBuses"]] == ["default"]
            assert not events.list_rules(EventBusName="default")["Rules"]

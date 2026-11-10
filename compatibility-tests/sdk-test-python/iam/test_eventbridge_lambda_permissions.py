"""EventBridge permission failures and recovery with real Lambda handlers."""

from contextlib import ExitStack, closing
import io
import json
import time
import zipfile

import boto3
import pytest


def _package():
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("index.py", """
import json

def handler(event, context):
    print("EVENT_DELIVERY=" + json.dumps(event), flush=True)
    return event
""")
    return stream.getvalue()


def _received(logs, group):
    events = []
    for page in logs.get_paginator("filter_log_events").paginate(logGroupName=group):
        for entry in page["events"]:
            marker = entry["message"].find("EVENT_DELIVERY=")
            if marker >= 0:
                event = json.loads(entry["message"][marker + len("EVENT_DELIVERY="):])
                if "detail" in event:
                    events.append(event)
    return events


def _await_handler(logs, group, message, event_id):
    deadline = time.monotonic() + 45
    while True:
        for event in _received(logs, group):
            if event["detail"].get("message") == message:
                assert event["id"] == event_id
                assert event["detail-type"] == "permission-check"
                return
        assert time.monotonic() < deadline, f"Lambda did not receive {message}"
        time.sleep(0.2)


def _await_queue(sqs, queue, event_id):
    deadline = time.monotonic() + 15
    while True:
        messages = sqs.receive_message(
            QueueUrl=queue, MaxNumberOfMessages=10, WaitTimeSeconds=1,
            MessageAttributeNames=["All"],
        ).get("Messages", [])
        for message in messages:
            sqs.delete_message(QueueUrl=queue, ReceiptHandle=message["ReceiptHandle"])
            if json.loads(message["Body"])["id"] == event_id:
                return message
        assert time.monotonic() < deadline, f"SQS did not receive event {event_id}"


@pytest.mark.parametrize("custom_bus", [False, True])
def test_qualified_lambda_permission_controls_delivery_and_dead_letters(
    aws_config, client_config, iam_client, lambda_client, logs_client, sqs_client,
    unique_name, custom_bus,
):
    function = unique_name + "-event-handler"
    group = "/aws/lambda/" + function
    with ExitStack() as cleanup:
        events = cleanup.enter_context(closing(boto3.client("events", config=client_config, **aws_config)))
        role = iam_client.create_role(
            RoleName=function,
            AssumeRolePolicyDocument=json.dumps({
                "Version": "2012-10-17",
                "Statement": [{
                    "Effect": "Allow", "Action": "sts:AssumeRole",
                    "Principal": {"Service": "lambda.amazonaws.com"},
                }],
            }),
        )["Role"]["Arn"]
        cleanup.callback(iam_client.delete_role, RoleName=function)
        iam_client.put_role_policy(
            RoleName=function, PolicyName="logs",
            PolicyDocument=json.dumps({
                "Version": "2012-10-17",
                "Statement": [{
                    "Effect": "Allow", "Resource": "*",
                    "Action": ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"],
                }],
            }),
        )
        cleanup.callback(iam_client.delete_role_policy, RoleName=function, PolicyName="logs")
        logs_client.create_log_group(logGroupName=group)
        cleanup.callback(logs_client.delete_log_group, logGroupName=group)
        created = lambda_client.create_function(
            FunctionName=function, Runtime="python3.12", Role=role,
            Handler="index.handler", Timeout=30, MemorySize=128,
            Code={"ZipFile": _package()}, Publish=True,
        )
        cleanup.callback(lambda_client.delete_function, FunctionName=function)
        lambda_client.get_waiter("function_active_v2").wait(
            FunctionName=function, WaiterConfig={"Delay": 1, "MaxAttempts": 60}
        )
        endpoint = lambda_client.create_alias(
            FunctionName=function, Name="live", FunctionVersion=created["Version"]
        )["AliasArn"]
        warm = lambda_client.invoke(FunctionName=endpoint, Payload=b'{"probe":"ready"}')
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
        queues = {}
        for name in ("sibling", "dead-letter"):
            queue = sqs_client.create_queue(QueueName=unique_name + "-" + name)["QueueUrl"]
            cleanup.callback(sqs_client.delete_queue, QueueUrl=queue)
            arn = sqs_client.get_queue_attributes(
                QueueUrl=queue, AttributeNames=["QueueArn"]
            )["Attributes"]["QueueArn"]
            sqs_client.set_queue_attributes(QueueUrl=queue, Attributes={"Policy": json.dumps({
                "Version": "2012-10-17",
                "Statement": [{
                    "Effect": "Allow", "Action": "sqs:SendMessage", "Resource": arn,
                    "Principal": {"Service": "events.amazonaws.com"},
                    "Condition": {"ArnEquals": {"aws:SourceArn": rule}},
                }],
            })})
            queues[name] = (queue, arn)
        response = events.put_targets(
            Rule=unique_name, EventBusName=bus, Targets=[
                {"Id": "handler", "Arn": endpoint, "DeadLetterConfig": {"Arn": queues["dead-letter"][1]}},
                {"Id": "sibling", "Arn": queues["sibling"][1]},
            ],
        )
        cleanup.callback(events.remove_targets, Rule=unique_name, EventBusName=bus, Ids=["handler", "sibling"])
        assert response["FailedEntryCount"] == 0, response
        grant = dict(
            FunctionName=function, Action="lambda:InvokeFunction",
            Principal="events.amazonaws.com", SourceArn=rule, SourceAccount=rule.split(":")[4],
        )
        # An unqualified grant does not authorize this alias target.
        lambda_client.add_permission(**grant, StatementId="unqualified")
        for phase in ("unqualified", "allowed", "revoked", "recovered"):
            if phase in ("allowed", "recovered"):
                lambda_client.add_permission(**grant, Qualifier="live", StatementId="qualified")
            elif phase == "revoked":
                lambda_client.remove_permission(
                    FunctionName=function, Qualifier="live", StatementId="qualified"
                )
            response = events.put_events(Entries=[{
                "EventBusName": bus, "Source": unique_name, "DetailType": "permission-check",
                "Detail": json.dumps({"message": phase}),
            }])
            assert response["FailedEntryCount"] == 0, response
            event_id = response["Entries"][0]["EventId"]
            _await_queue(sqs_client, queues["sibling"][0], event_id)
            if phase in ("allowed", "recovered"):
                _await_handler(logs_client, group, phase, event_id)
            else:
                message = _await_queue(sqs_client, queues["dead-letter"][0], event_id)
                attributes = {key: value["StringValue"] for key, value in message["MessageAttributes"].items()}
                assert attributes["ERROR_CODE"] == "NO_PERMISSIONS"
                assert attributes["RULE_ARN"] == rule
                assert attributes["TARGET_ARN"] == endpoint
                assert attributes["RETRY_ATTEMPTS"] == "0"
                assert "EXHAUSTED_RETRY_CONDITION" not in attributes
        assert {event["detail"]["message"] for event in _received(logs_client, group)} == {
            "allowed", "recovered",
        }
        assert not sqs_client.receive_message(
            QueueUrl=queues["dead-letter"][0], WaitTimeSeconds=1
        ).get("Messages")

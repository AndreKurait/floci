"""SNS grants and revocation with a real Lambda handler and an SQS sibling."""

from contextlib import ExitStack
import io
import json
import time
import zipfile

import pytest


def _package():
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("index.py", """
import json

def handler(event, context):
    print("SNS_DELIVERY=" + json.dumps(event), flush=True)
    return event
""")
    return stream.getvalue()


def _received(logs, group):
    events = []
    for page in logs.get_paginator("filter_log_events").paginate(logGroupName=group):
        for entry in page["events"]:
            marker = entry["message"].find("SNS_DELIVERY=")
            if marker >= 0:
                payload = json.loads(entry["message"][marker + len("SNS_DELIVERY="):])
                events.extend(payload.get("Records", []))
    return events


def _await_delivery(logs, group, topic, message):
    deadline = time.monotonic() + 45
    while True:
        for event in _received(logs, group):
            if event.get("Sns", {}).get("Message") == message:
                assert event["EventSource"] == "aws:sns"
                assert event["Sns"]["TopicArn"] == topic
                return
        assert time.monotonic() < deadline, f"Lambda did not receive {message}"
        time.sleep(0.2)


def _sqs_delivery(sqs, queue, topic, message):
    deadline = time.monotonic() + 15
    while True:
        received = sqs.receive_message(
            QueueUrl=queue, MaxNumberOfMessages=10, WaitTimeSeconds=1
        ).get("Messages", [])
        for item in received:
            envelope = json.loads(item["Body"])
            sqs.delete_message(QueueUrl=queue, ReceiptHandle=item["ReceiptHandle"])
            if envelope["Message"] == message:
                assert envelope["TopicArn"] == topic
                return
        assert time.monotonic() < deadline, f"SQS did not receive {message}"


@pytest.mark.parametrize("qualified", [False, True])
def test_sns_grant_revocation_and_recovery_keep_other_subscribers_running(
    iam_client, lambda_client, sns_client, sqs_client, logs_client, unique_name, qualified
):
    function = unique_name + "-sns-handler"
    log_group = "/aws/lambda/" + function
    with ExitStack() as cleanup:
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
        logs_client.create_log_group(logGroupName=log_group)
        cleanup.callback(logs_client.delete_log_group, logGroupName=log_group)
        created = lambda_client.create_function(
            FunctionName=function, Runtime="python3.12", Role=role,
            Handler="index.handler", Timeout=30, MemorySize=128,
            Code={"ZipFile": _package()}, Publish=True,
        )
        cleanup.callback(lambda_client.delete_function, FunctionName=function)
        lambda_client.get_waiter("function_active_v2").wait(
            FunctionName=function, WaiterConfig={"Delay": 1, "MaxAttempts": 60}
        )
        endpoint = created["FunctionArn"]
        permission = {"FunctionName": function}
        if qualified:
            endpoint = lambda_client.create_alias(
                FunctionName=function, Name="live", FunctionVersion=created["Version"]
            )["AliasArn"]
            permission["Qualifier"] = "live"
        warm = lambda_client.invoke(FunctionName=endpoint, Payload=b'{"probe":"ready"}')
        with warm["Payload"] as payload:
            assert "FunctionError" not in warm, payload.read().decode()
            assert json.load(payload) == {"probe": "ready"}

        topic = sns_client.create_topic(Name=unique_name + "-topic")["TopicArn"]
        cleanup.callback(sns_client.delete_topic, TopicArn=topic)
        queue = sqs_client.create_queue(QueueName=unique_name + "-sibling")["QueueUrl"]
        cleanup.callback(sqs_client.delete_queue, QueueUrl=queue)
        queue_arn = sqs_client.get_queue_attributes(
            QueueUrl=queue, AttributeNames=["QueueArn"]
        )["Attributes"]["QueueArn"]
        sqs_client.set_queue_attributes(QueueUrl=queue, Attributes={"Policy": json.dumps({
            "Version": "2012-10-17",
            "Statement": [{
                "Effect": "Allow", "Action": "sqs:SendMessage", "Resource": queue_arn,
                "Principal": {"Service": "sns.amazonaws.com"},
                "Condition": {"ArnEquals": {"aws:SourceArn": topic}},
            }],
        })})
        for protocol, target in (("lambda", endpoint), ("sqs", queue_arn)):
            subscription = sns_client.subscribe(
                TopicArn=topic, Protocol=protocol, Endpoint=target
            )["SubscriptionArn"]
            cleanup.callback(sns_client.unsubscribe, SubscriptionArn=subscription)

        grant = dict(
            permission, StatementId="topic", Action="lambda:InvokeFunction",
            Principal="sns.amazonaws.com", SourceArn=topic, SourceAccount=topic.split(":")[4],
        )
        lambda_client.add_permission(**grant)
        allowed, denied, recovered = [unique_name + "-" + name for name in ("allowed", "denied", "recovered")]
        sns_client.publish(TopicArn=topic, Message=allowed)
        _await_delivery(logs_client, log_group, topic, allowed)
        _sqs_delivery(sqs_client, queue, topic, allowed)

        lambda_client.remove_permission(**permission, StatementId="topic")
        sns_client.publish(TopicArn=topic, Message=denied)
        _sqs_delivery(sqs_client, queue, topic, denied)
        deadline = time.monotonic() + 5
        while True:
            assert all(event.get("Sns", {}).get("Message") != denied
                       for event in _received(logs_client, log_group))
            if time.monotonic() >= deadline:
                break
            time.sleep(0.2)

        lambda_client.add_permission(**grant)
        sns_client.publish(TopicArn=topic, Message=recovered)
        _await_delivery(logs_client, log_group, topic, recovered)
        _sqs_delivery(sqs_client, queue, topic, recovered)
        assert {event["Sns"]["Message"] for event in _received(logs_client, log_group)} == {
            allowed, recovered,
        }

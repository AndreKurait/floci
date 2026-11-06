"""CreateStack's rollback option preserves failed deployments for inspection."""

import json
import time

import pytest
from botocore.exceptions import ClientError


def template(queue, *, fail=True):
    resources = {"Queue": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": queue}}}
    if fail:
        resources["Failure"] = {
            "Type": "AWS::Lambda::Alias", "DependsOn": "Queue",
            "Properties": {"FunctionName": queue + "-absent", "FunctionVersion": "1", "Name": "live"},
        }
    return json.dumps({"Resources": resources})


def settled(client, name):
    deadline = time.monotonic() + 30
    while True:
        stack = client.describe_stacks(StackName=name)["Stacks"][0]
        if not stack["StackStatus"].endswith("_IN_PROGRESS"):
            return stack
        assert time.monotonic() < deadline, stack
        time.sleep(0.1)


def remove_stack(client, name):
    try:
        client.describe_stacks(StackName=name)
    except ClientError as missing:
        assert missing.response["Error"]["Code"] == "ValidationError"
        assert "does not exist" in missing.response["Error"]["Message"]
        return
    client.delete_stack(StackName=name)
    client.get_waiter("stack_delete_complete").wait(
        StackName=name, WaiterConfig={"Delay": 1, "MaxAttempts": 30}
    )


def assert_queue_absent(client, queue):
    with pytest.raises(ClientError) as missing:
        client.get_queue_url(QueueName=queue)
    assert missing.value.response["Error"]["Code"] in {
        "AWS.SimpleQueueService.NonExistentQueue", "QueueDoesNotExist",
    }


@pytest.mark.parametrize("disabled", [None, False, True], ids=["default", "enabled", "disabled"])
def test_create_failure_respects_rollback_and_manual_delete(
    cloudformation_client, sqs_client, unique_name, disabled
):
    stack, queue = unique_name + "-stack", unique_name + "-queue"
    options = {} if disabled is None else {"DisableRollback": disabled}
    try:
        cloudformation_client.create_stack(
            StackName=stack, TemplateBody=template(queue), **options
        )
        result = settled(cloudformation_client, stack)
        assert result["StackStatus"] == ("CREATE_FAILED" if disabled else "ROLLBACK_COMPLETE")
        assert result["DisableRollback"] is bool(disabled)
        events = cloudformation_client.describe_stack_events(StackName=stack)["StackEvents"]
        assert any(event["LogicalResourceId"] == "Failure"
                   and event["ResourceStatus"] == "CREATE_FAILED" for event in events), events
        if disabled:
            assert "Rollback requested" not in result.get("StackStatusReason", "")
            assert not any(event["ResourceStatus"].startswith("ROLLBACK_") for event in events)
            url = sqs_client.get_queue_url(QueueName=queue)["QueueUrl"]
            assert sqs_client.get_queue_attributes(QueueUrl=url, AttributeNames=["QueueArn"])["Attributes"]["QueueArn"]
        else:
            assert_queue_absent(sqs_client, queue)
    finally:
        remove_stack(cloudformation_client, stack)
    assert_queue_absent(sqs_client, queue)


def test_create_option_does_not_disable_a_later_update_rollback(
    cloudformation_client, sqs_client, unique_name
):
    stack, queue = unique_name + "-stack", unique_name + "-queue"
    try:
        cloudformation_client.create_stack(
            StackName=stack, TemplateBody=template(queue, fail=False), DisableRollback=True
        )
        created = settled(cloudformation_client, stack)
        assert created["StackStatus"] == "CREATE_COMPLETE"
        assert created["DisableRollback"] is True
        url = sqs_client.get_queue_url(QueueName=queue)["QueueUrl"]
        cloudformation_client.update_stack(StackName=stack, TemplateBody=template(queue))
        assert settled(cloudformation_client, stack)["StackStatus"] == "UPDATE_ROLLBACK_COMPLETE"
        assert sqs_client.get_queue_url(QueueName=queue)["QueueUrl"] == url
    finally:
        remove_stack(cloudformation_client, stack)
    assert_queue_absent(sqs_client, queue)


@pytest.mark.parametrize("disabled", [False, True])
def test_conflicting_create_failure_options_are_rejected_before_mutation(
    cloudformation_client, unique_name, disabled
):
    stack = unique_name + "-stack"
    try:
        with pytest.raises(ClientError) as conflict:
            cloudformation_client.create_stack(
                StackName=stack, TemplateBody=template(unique_name + "-queue", fail=False),
                DisableRollback=disabled, OnFailure="ROLLBACK",
            )
        assert conflict.value.response["Error"]["Code"] == "ValidationError"
        with pytest.raises(ClientError) as missing:
            cloudformation_client.describe_stacks(StackName=stack)
        assert missing.value.response["Error"]["Code"] == "ValidationError"
        assert "does not exist" in missing.value.response["Error"]["Message"]
    finally:
        remove_stack(cloudformation_client, stack)

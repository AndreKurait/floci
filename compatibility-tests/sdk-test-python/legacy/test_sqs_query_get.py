"""Boto 2 SQS Query GET calls share queue state with the current SDK."""

from urllib.parse import urlsplit
import uuid

from boto.exception import SQSError
from boto.regioninfo import RegionInfo
from boto.sqs.connection import SQSConnection
from boto.sqs.message import RawMessage
from boto.sqs.queue import Queue
import boto3
import pytest


@pytest.mark.parametrize("region,partition", [
    ("us-east-1", "aws"),
    ("us-gov-west-1", "aws-us-gov"),
])
def test_legacy_sqs_queue_get_lifecycle(aws_config, region, partition):
    endpoint = urlsplit(aws_config["endpoint_url"])
    config = dict(aws_config, region_name=region)
    legacy = SQSConnection(
        aws_access_key_id=config["aws_access_key_id"],
        aws_secret_access_key=config["aws_secret_access_key"],
        port=endpoint.port,
        is_secure=endpoint.scheme == "https",
        region=RegionInfo(name=region, endpoint=endpoint.hostname),
    )
    legacy.auth_region_name = region
    legacy.auth_service_name = "sqs"
    current = boto3.client("sqs", **config)
    name = "query-get-" + uuid.uuid4().hex[:12]
    queue_url = current.create_queue(QueueName=name)["QueueUrl"]
    queue = Queue(connection=legacy, url=queue_url)
    queue.set_message_class(RawMessage)
    deleted = False
    try:
        attributes = queue.get_attributes("QueueArn")
        assert attributes["QueueArn"] == f"arn:{partition}:sqs:{region}:000000000000:{name}"
        queue.write(RawMessage(body="legacy-query-message"))
        messages = current.receive_message(QueueUrl=queue_url, MaxNumberOfMessages=1)["Messages"]
        assert [message["Body"] for message in messages] == ["legacy-query-message"]
        current.delete_message(QueueUrl=queue_url, ReceiptHandle=messages[0]["ReceiptHandle"])
        current.send_message(QueueUrl=queue_url, MessageBody="current-sdk-message")
        received = queue.get_messages(1, wait_time_seconds=0)
        assert [message.get_body() for message in received] == ["current-sdk-message"]
        queue.delete_message(received[0])
        assert queue.get_attributes("ApproximateNumberOfMessages")["ApproximateNumberOfMessages"] == "0"
        queue.delete()
        deleted = True
        with pytest.raises(SQSError) as missing:
            queue.get_attributes("QueueArn")
        assert missing.value.status == 400
        assert missing.value.error_code == "AWS.SimpleQueueService.NonExistentQueue"
    finally:
        if not deleted:
            current.delete_queue(QueueUrl=queue_url)
        legacy.close()
        current.close()

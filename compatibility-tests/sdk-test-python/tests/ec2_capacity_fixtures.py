"""Account-scoped clients for capacity and request-replay SDK tests."""
import boto3
import pytest
from botocore.config import Config


@pytest.fixture
def client(aws_config, client_config):
    def connect(account="111111111111", region="us-east-1", endpoint_url=None):
        options = dict(aws_config, aws_access_key_id=account, region_name=region)
        if endpoint_url is not None:
            options["endpoint_url"] = endpoint_url
        return boto3.client("ec2", **options, config=client_config.merge(
            Config(proxies={}, connect_timeout=2, read_timeout=5, retries={"total_max_attempts": 1})))
    return connect


def describe(ec2, capacity_id):
    return ec2.describe_capacity_reservations(
        CapacityReservationIds=[capacity_id])["CapacityReservations"][0]

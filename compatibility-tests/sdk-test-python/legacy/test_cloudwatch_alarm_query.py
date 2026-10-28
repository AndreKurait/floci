"""Boto 2 alarm Query operations share storage with the current CloudWatch SDK."""

from urllib.parse import urlsplit
import uuid

from boto.ec2.cloudwatch import CloudWatchConnection
from boto.ec2.cloudwatch.alarm import MetricAlarm
from boto.regioninfo import RegionInfo
import pytest


class SignedCloudWatchConnection(CloudWatchConnection):
    def _required_auth_capability(self):
        return ["hmac-v4"]


def alarm_client(aws_config, version, region=None, access_key=None):
    endpoint = urlsplit(aws_config["endpoint_url"])
    region = region or aws_config["region_name"]
    client = SignedCloudWatchConnection(
        aws_access_key_id=access_key or aws_config["aws_access_key_id"],
        aws_secret_access_key=aws_config["aws_secret_access_key"],
        port=endpoint.port,
        is_secure=endpoint.scheme == "https",
        region=RegionInfo(name=region, endpoint=endpoint.hostname),
    )
    client.APIVersion = version
    client.auth_region_name = region
    client.auth_service_name = "monitoring"
    return client


@pytest.fixture
def alarms(cloudwatch_client):
    base = "query-alarm-" + uuid.uuid4().hex + "-"
    prefix = base + "selected-"
    selected = [prefix + "a", prefix + "b"]
    created = prefix + "c"
    other = base + "other"
    try:
        for name in selected + [other]:
            cloudwatch_client.put_metric_alarm(
                AlarmName=name,
                MetricName="Requests",
                Namespace="Compatibility/AlarmQuery",
                Statistic="Sum",
                Period=60,
                EvaluationPeriods=1,
                Threshold=5,
                ComparisonOperator="GreaterThanThreshold",
                ActionsEnabled=False,
            )
        yield prefix, selected, created, other
    finally:
        cloudwatch_client.delete_alarms(AlarmNames=selected + [created, other])


@pytest.mark.parametrize("version", ["2010-08-01", "2013-01-16"])
def test_alarm_query_lifecycle_preserves_scope_and_unselected_alarms(
    aws_config, cloudwatch_client, alarms, version
):
    prefix, selected, created, other = alarms
    client = alarm_client(aws_config, version)
    observed = client.describe_alarms(alarm_name_prefix=prefix)
    assert sorted(alarm.name for alarm in observed) == selected
    assert not observed.next_token
    assert all(alarm.metric == "Requests" and alarm.threshold == 5 for alarm in observed)

    alarm = MetricAlarm(
        name=created,
        metric="Requests",
        namespace="Compatibility/AlarmQuery",
        statistic="Sum",
        period=60,
        evaluation_periods=1,
        threshold=7,
        comparison=">",
    )
    alarm.actions_enabled = False
    assert client.create_alarm(alarm) is True
    assert sorted(alarm.name for alarm in client.describe_alarms(alarm_name_prefix=prefix)) == selected + [created]
    assert cloudwatch_client.describe_alarms(AlarmNames=[created])["MetricAlarms"][0]["Threshold"] == 7

    other_region = "eu-west-1" if aws_config["region_name"] != "eu-west-1" else "us-east-1"
    assert list(alarm_client(aws_config, version, region=other_region)
                .describe_alarms(alarm_name_prefix=prefix)) == []
    assert list(alarm_client(aws_config, version, access_key="999988887777")
                .describe_alarms(alarm_name_prefix=prefix)) == []

    assert client.delete_alarms(selected + [created]) is True
    assert list(client.describe_alarms(alarm_name_prefix=prefix)) == []
    assert cloudwatch_client.describe_alarms(AlarmNamePrefix=prefix)["MetricAlarms"] == []
    assert [alarm["AlarmName"] for alarm in cloudwatch_client.describe_alarms(AlarmNames=[other])["MetricAlarms"]] == [other]

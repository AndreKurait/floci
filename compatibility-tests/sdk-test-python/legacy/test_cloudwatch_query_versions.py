"""Common CloudWatch Query reads retain their requested API version."""

from datetime import datetime, timedelta, timezone
import xml.etree.ElementTree as ET

import pytest


@pytest.mark.parametrize("version", ["2010-08-01", "2013-01-16"])
def test_query_reads_return_shared_metrics_and_alarms_in_the_requested_namespace(
    cloudwatch_client, unique_name, version
):
    assert cloudwatch_client.meta.service_model.metadata["protocol"] == "query"
    namespace = "Compatibility/LegacyCloudWatch"
    now = datetime.now(timezone.utc)
    cloudwatch_client.put_metric_data(
        Namespace=namespace,
        MetricData=[{
            "MetricName": unique_name, "Timestamp": now - timedelta(minutes=5),
            "Value": 7, "Unit": "Count",
        }],
    )
    observed = {}

    def select_version(params, **kwargs):
        # The pinned legacy SDK owns the Query serialization and SigV4 signing.
        assert params["body"]["Version"] == "2010-08-01"
        params["body"]["Version"] = version

    def capture_response(http_response, model, **kwargs):
        assert http_response.status_code == 200, http_response.text
        observed[model.name] = ET.fromstring(http_response.content).tag

    events = cloudwatch_client.meta.events
    try:
        cloudwatch_client.put_metric_alarm(
            AlarmName=unique_name, Namespace=namespace, MetricName=unique_name,
            Statistic="Sum", Period=60, EvaluationPeriods=1, Threshold=5,
            ComparisonOperator="GreaterThanThreshold", ActionsEnabled=False,
        )
        events.register("before-call.cloudwatch", select_version)
        events.register("after-call.cloudwatch", capture_response)
        metrics = cloudwatch_client.list_metrics(
            Namespace=namespace, MetricName=unique_name
        )["Metrics"]
        assert [metric["MetricName"] for metric in metrics] == [unique_name]
        statistics = cloudwatch_client.get_metric_statistics(
            Namespace=namespace, MetricName=unique_name,
            StartTime=now - timedelta(minutes=10), EndTime=now,
            Period=60, Statistics=["Sum"],
        )["Datapoints"]
        assert sum(point["Sum"] for point in statistics) == 7
        alarms = cloudwatch_client.describe_alarms(AlarmNames=[unique_name])["MetricAlarms"]
        assert [(alarm["AlarmName"], alarm["Threshold"]) for alarm in alarms] == [(unique_name, 5)]
        assert observed == {
            action: f"{{http://monitoring.amazonaws.com/doc/{version}/}}{action}Response"
            for action in ("ListMetrics", "GetMetricStatistics", "DescribeAlarms")
        }
    finally:
        events.unregister("before-call.cloudwatch", select_version)
        events.unregister("after-call.cloudwatch", capture_response)
        cloudwatch_client.delete_alarms(AlarmNames=[unique_name])
    assert not cloudwatch_client.describe_alarms(AlarmNames=[unique_name])["MetricAlarms"]

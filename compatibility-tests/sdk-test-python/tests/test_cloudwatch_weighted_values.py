"""Read back actual weighted samples through the SDK's selected wire protocol."""
from datetime import datetime, timedelta, timezone

import pytest
from botocore.exceptions import ClientError


def _read(client, namespace, timestamp, **selection):
    return client.get_metric_statistics(
        Namespace=namespace, MetricName="Latency", Unit="Milliseconds",
        StartTime=timestamp - timedelta(seconds=1), EndTime=timestamp + timedelta(seconds=1),
        Period=60, **selection,
    )["Datapoints"]


@pytest.mark.parametrize("counts,total,expected_sum", [([3, 2], 5, 43), (None, 2, 18)])
def test_weighted_samples_round_trip(cloudwatch_client, unique_name, counts, total, expected_sum):
    timestamp = datetime.now(timezone.utc) - timedelta(seconds=60)
    namespace = "Weighted/" + unique_name
    datum = {"MetricName": "Latency", "Timestamp": timestamp, "Unit": "Milliseconds", "Values": [7, 11]}
    if counts is not None:
        datum["Counts"] = counts
    cloudwatch_client.put_metric_data(Namespace=namespace, MetricData=[datum])
    point, = _read(cloudwatch_client, namespace, timestamp,
                  Statistics=["SampleCount", "Sum", "Minimum", "Maximum"])
    assert point["SampleCount"] == total
    assert point["Sum"] == expected_sum
    assert point["Minimum"] == 7
    assert point["Maximum"] == 11
    percentile, = _read(cloudwatch_client, namespace, timestamp, ExtendedStatistics=["p90"])
    assert percentile["ExtendedStatistics"] == {"p90": 11.0}


def test_invalid_weighted_batch_publishes_nothing(cloudwatch_client, unique_name):
    timestamp = datetime.now(timezone.utc) - timedelta(seconds=60)
    namespace = "Weighted/" + unique_name
    common = {"MetricName": "Latency", "Timestamp": timestamp, "Unit": "Milliseconds"}
    with pytest.raises(ClientError) as error:
        cloudwatch_client.put_metric_data(Namespace=namespace, MetricData=[
            dict(common, Value=19), dict(common, Values=[7, 11], Counts=[3]),
        ])
    assert error.value.response["Error"]["Code"] == "InvalidParameterValue"
    assert _read(cloudwatch_client, namespace, timestamp, Statistics=["SampleCount"]) == []

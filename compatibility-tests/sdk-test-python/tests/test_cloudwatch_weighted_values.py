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


@pytest.mark.parametrize("counts,total,expected_sum", [([0, 1], 1, 11), ([0, 0], 0, 0), ([0.5, 1.5], 2, 20)])
def test_zero_and_fractional_weights_preserve_extrema_and_aggregate(
        cloudwatch_client, unique_name, counts, total, expected_sum):
    timestamp = datetime.now(timezone.utc) - timedelta(seconds=60)
    namespace = "Weighted/" + unique_name
    common = {"MetricName": "Latency", "Timestamp": timestamp, "Unit": "Milliseconds"}
    cloudwatch_client.put_metric_data(Namespace=namespace, MetricData=[
        dict(common, Values=[7, 11], Counts=counts),
    ])
    selection = {"Statistics": ["SampleCount", "Sum", "Minimum", "Maximum"]}
    point, = _read(cloudwatch_client, namespace, timestamp, **selection)
    assert point["SampleCount"] == total
    assert point["Sum"] == expected_sum
    assert point["Minimum"] == 7
    assert point["Maximum"] == 11
    cloudwatch_client.put_metric_data(Namespace=namespace, MetricData=[dict(common, Value=9)])
    combined, = _read(cloudwatch_client, namespace, timestamp, **selection)
    assert combined["SampleCount"] == total + 1
    assert combined["Sum"] == expected_sum + 9
    assert combined["Minimum"] == 7
    assert combined["Maximum"] == 11


def test_zero_statistic_set_does_not_turn_into_a_scalar(cloudwatch_client, unique_name):
    timestamp = datetime.now(timezone.utc) - timedelta(seconds=60)
    namespace = "Weighted/" + unique_name
    cloudwatch_client.put_metric_data(Namespace=namespace, MetricData=[{
        "MetricName": "Latency", "Timestamp": timestamp, "Unit": "Milliseconds",
        "StatisticValues": {"SampleCount": 0, "Sum": 0, "Minimum": 7, "Maximum": 11},
    }])
    point, = _read(cloudwatch_client, namespace, timestamp,
                  Statistics=["SampleCount", "Sum", "Minimum", "Maximum"])
    assert point["SampleCount"] == 0
    assert point["Sum"] == 0
    assert point["Minimum"] == 7
    assert point["Maximum"] == 11

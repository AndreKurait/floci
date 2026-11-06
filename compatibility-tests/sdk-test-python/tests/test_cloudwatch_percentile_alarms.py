"""Percentile alarms retain their statistic and evaluate the actual sample distribution."""
from contextlib import ExitStack
from datetime import datetime, timezone
import json
import time

import boto3
import pytest
from botocore.exceptions import ClientError


def _until(read, predicate, timeout=40):
    deadline = time.monotonic() + timeout
    while True:
        value = read()
        if predicate(value):
            return value
        assert time.monotonic() < deadline, f"Timed out; last observation: {value}"
        time.sleep(0.25)


@pytest.fixture
def percentile_scope(aws_config, client_config, unique_name):
    with ExitStack() as cleanup:
        cloudwatch = boto3.client("cloudwatch", config=client_config, **aws_config)
        cfn = boto3.client("cloudformation", config=client_config, **aws_config)
        cleanup.callback(cloudwatch.close)
        cleanup.callback(cfn.close)
        yield {"client": cloudwatch, "cfn": cfn, "name": unique_name,
               "namespace": "Percentile/" + unique_name, "cleanup": cleanup}


def _alarm(scope, **changes):
    return {
        "AlarmName": scope["name"], "Namespace": scope["namespace"],
        "MetricName": "Latency", "ExtendedStatistic": "p90",
        "Period": 60, "EvaluationPeriods": 1, "Threshold": 100,
        "ComparisonOperator": "GreaterThanThreshold", "TreatMissingData": "notBreaching",
        **changes,
    }


def _timestamp():
    return datetime.fromtimestamp(int(time.time()) // 60 * 60 - 60, timezone.utc)


def _read_alarm(scope):
    return scope["client"].describe_alarms(AlarmNames=[scope["name"]])["MetricAlarms"][0]


def _read_statistics(scope, metric, timestamp, **statistics):
    return scope["client"].get_metric_statistics(
        Namespace=scope["namespace"], MetricName=metric,
        StartTime=timestamp, EndTime=datetime.fromtimestamp(timestamp.timestamp() + 60, timezone.utc),
        Period=60, **statistics)["Datapoints"]


def test_percentile_alarm_round_trip_and_evaluation(percentile_scope):
    scope = percentile_scope
    client = scope["client"]
    client.put_metric_alarm(**_alarm(scope))
    scope["cleanup"].callback(client.delete_alarms, AlarmNames=[scope["name"]])
    described = _read_alarm(scope)
    assert described["ExtendedStatistic"] == "p90"
    assert "Statistic" not in described

    timestamp = _timestamp()
    client.put_metric_data(Namespace=scope["namespace"], MetricData=[
        {"MetricName": "Latency", "Value": value, "Timestamp": timestamp}
        for value in [10] * 19 + [1000]
    ])
    points = _read_statistics(scope, "Latency", timestamp, ExtendedStatistics=["p90"])
    assert len(points) == 1
    assert points[0]["ExtendedStatistics"] == {"p90": 10.0}
    assert "Maximum" not in points[0]
    data = client.get_metric_data(
        StartTime=timestamp, EndTime=datetime.fromtimestamp(timestamp.timestamp() + 60, timezone.utc),
        MetricDataQueries=[{"Id": "latency", "MetricStat": {
            "Metric": {"Namespace": scope["namespace"], "MetricName": "Latency"},
            "Period": 60, "Stat": "p90",
        }}])["MetricDataResults"]
    assert data[0]["Values"] == [10.0]

    client.set_alarm_state(AlarmName=scope["name"], StateValue="ALARM", StateReason="Verify reevaluation")
    _until(lambda: _read_alarm(scope), lambda row: row["StateValue"] == "OK")
    client.put_metric_data(Namespace=scope["namespace"], MetricData=[
        {"MetricName": "Latency", "Value": 1000, "Timestamp": timestamp} for _ in range(20)
    ])
    _until(lambda: _read_alarm(scope), lambda row: row["StateValue"] == "ALARM")


def test_percentile_alarm_accepts_ignore_for_low_sample_counts(percentile_scope):
    scope = percentile_scope
    client = scope["client"]
    client.put_metric_alarm(**_alarm(scope, EvaluateLowSampleCountPercentile="ignore"))
    scope["cleanup"].callback(client.delete_alarms, AlarmNames=[scope["name"]])
    assert _read_alarm(scope)["EvaluateLowSampleCountPercentile"] == "ignore"

    # p90 needs 10 / (1 - 0.9) = 100 samples per period before "ignore" lets it decide the state.
    # 99 breaching samples must leave the alarm where it is across evaluator ticks (every 10 s);
    # "evaluate" would alarm on them.
    client.set_alarm_state(AlarmName=scope["name"], StateValue="OK", StateReason="Low sample baseline")
    timestamp = _timestamp()
    client.put_metric_data(Namespace=scope["namespace"], MetricData=[
        {"MetricName": "Latency", "Timestamp": timestamp, "StatisticValues": {
            "SampleCount": 99, "Sum": 99000, "Minimum": 1000, "Maximum": 1000}},
    ])
    time.sleep(25)
    assert _read_alarm(scope)["StateValue"] == "OK"
    client.put_metric_data(Namespace=scope["namespace"], MetricData=[
        {"MetricName": "Latency", "Timestamp": timestamp, "Value": 1000},
    ])
    _until(lambda: _read_alarm(scope), lambda row: row["StateValue"] == "ALARM")


def test_percentile_cloudformation_preserves_and_updates_statistic_selection(percentile_scope):
    scope = percentile_scope
    cfn = scope["cfn"]
    name = scope["name"]
    template = {"Resources": {"Latency": {"Type": "AWS::CloudWatch::Alarm",
                                         "Properties": _alarm(scope, ExtendedStatistic="p50")}}}
    cfn.create_stack(StackName=name, TemplateBody=json.dumps(template))

    def cleanup():
        cfn.delete_stack(StackName=name)
        cfn.get_waiter("stack_delete_complete").wait(StackName=name, WaiterConfig={"Delay": 1, "MaxAttempts": 60})

    scope["cleanup"].callback(cleanup)
    cfn.get_waiter("stack_create_complete").wait(StackName=name, WaiterConfig={"Delay": 1, "MaxAttempts": 60})
    before = _read_alarm(scope)
    assert before["ExtendedStatistic"] == "p50" and "Statistic" not in before
    del template["Resources"]["Latency"]["Properties"]["ExtendedStatistic"]
    template["Resources"]["Latency"]["Properties"]["Statistic"] = "Maximum"
    cfn.update_stack(StackName=name, TemplateBody=json.dumps(template))
    cfn.get_waiter("stack_update_complete").wait(StackName=name, WaiterConfig={"Delay": 1, "MaxAttempts": 60})
    after = _read_alarm(scope)
    assert after["AlarmArn"] == before["AlarmArn"]
    assert after["Statistic"] == "Maximum" and "ExtendedStatistic" not in after

    properties = template["Resources"]["Latency"]["Properties"]
    for field in ("Namespace", "MetricName", "Statistic", "Period"):
        properties.pop(field)
    queries = [
        {"Id": "sample", "ReturnData": False,
         "Label": f"latency-{scope['client'].meta.region_name}",
         "MetricStat": {"Metric": {"Namespace": scope["namespace"], "MetricName": "Latency"},
                        "Period": 60, "Stat": "p90"}},
        {"Id": "scaled", "ReturnData": True, "Expression": "sample * 2"},
    ]
    properties["Metrics"] = [{**queries[0], "Label": {"Fn::Sub": "latency-${AWS::Region}"}}, queries[1]]
    cfn.update_stack(StackName=name, TemplateBody=json.dumps(template))
    cfn.get_waiter("stack_update_complete").wait(StackName=name, WaiterConfig={"Delay": 1, "MaxAttempts": 60})
    math_alarm = _read_alarm(scope)
    assert math_alarm["AlarmArn"] == before["AlarmArn"]
    assert math_alarm["Metrics"] == queries
    assert all(field not in math_alarm for field in ("Period", "Statistic", "ExtendedStatistic", "MetricName"))

    properties.clear()
    properties.update(_alarm(scope, ExtendedStatistic="p50"))
    cfn.update_stack(StackName=name, TemplateBody=json.dumps(template))
    cfn.get_waiter("stack_update_complete").wait(StackName=name, WaiterConfig={"Delay": 1, "MaxAttempts": 60})
    restored = _read_alarm(scope)
    assert restored["AlarmArn"] == before["AlarmArn"]
    assert restored["ExtendedStatistic"] == "p50" and "Metrics" not in restored


def test_percentile_uses_homogeneous_statistic_set_sample_counts(percentile_scope):
    scope = percentile_scope
    timestamp = _timestamp()
    scope["client"].put_metric_data(Namespace=scope["namespace"], MetricData=[
        {"MetricName": "Weighted", "Timestamp": timestamp,
         "StatisticValues": {"SampleCount": 999, "Sum": 9990, "Minimum": 10, "Maximum": 10}},
        {"MetricName": "Weighted", "Timestamp": timestamp, "Value": 1000},
    ])
    points = _read_statistics(scope, "Weighted", timestamp, ExtendedStatistics=["p0", "p50", "p90", "p100"])
    assert len(points) == 1
    assert points[0]["ExtendedStatistics"] == {"p0": 10.0, "p50": 10.0, "p90": 10.0, "p100": 1000.0}


@pytest.mark.parametrize("datum", [
    {"Value": -1},
    {"StatisticValues": {"SampleCount": 3, "Sum": 30, "Minimum": 1, "Maximum": 20}},
])
def test_percentile_does_not_invent_missing_distribution_data(percentile_scope, datum):
    scope = percentile_scope
    timestamp = _timestamp()
    scope["client"].put_metric_data(Namespace=scope["namespace"], MetricData=[
        {"MetricName": "Unavailable", "Timestamp": timestamp, **datum},
    ])
    assert _read_statistics(scope, "Unavailable", timestamp, ExtendedStatistics=["p90"]) == []
    assert len(_read_statistics(scope, "Unavailable", timestamp, Statistics=["Maximum"])) == 1


def test_percentile_invalid_selection_preserves_existing_alarm(percentile_scope):
    scope = percentile_scope
    client = scope["client"]
    client.put_metric_alarm(**_alarm(scope))
    scope["cleanup"].callback(client.delete_alarms, AlarmNames=[scope["name"]])
    for changes in ({"Statistic": "Average"}, {"ExtendedStatistic": "p101"},
                    {"EvaluateLowSampleCountPercentile": "sometimes"}):
        with pytest.raises(ClientError):
            client.put_metric_alarm(**_alarm(scope, **changes))
        assert _read_alarm(scope)["ExtendedStatistic"] == "p90"
    with pytest.raises(ClientError):
        _read_statistics(scope, "Latency", _timestamp(), Statistics=["Maximum"], ExtendedStatistics=["p90"])

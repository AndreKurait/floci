"""Metric math uses real samples and drives periodic alarms through the public SDK."""
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
        result = read()
        if predicate(result):
            return result
        assert time.monotonic() < deadline, f"Timed out; last observation: {result}"
        time.sleep(0.25)


@pytest.fixture
def math_scope(aws_config, client_config, unique_name):
    with ExitStack() as cleanup:
        clients = {name: boto3.client(name, config=client_config, **aws_config)
                   for name in ("cloudwatch", "cloudformation", "service-quotas")}
        for client in clients.values():
            cleanup.callback(client.close)
        yield {**clients, "cleanup": cleanup, "name": unique_name,
               "namespace": "MetricMath/" + unique_name,
               "bucket": int(time.time()) // 60 * 60 - 60}


def _query(scope, identifier, metric, statistic="Sum"):
    return {"Id": identifier, "ReturnData": False, "MetricStat": {
        "Metric": {"Namespace": scope["namespace"], "MetricName": metric},
        "Period": 60, "Stat": statistic}}


def _put(scope, metric, value, offset=0, namespace=None):
    scope["cloudwatch"].put_metric_data(
        Namespace=namespace or scope["namespace"], MetricData=[{
            "MetricName": metric, "Value": value,
            "Timestamp": datetime.fromtimestamp(scope["bucket"] + offset, timezone.utc)}])


def _data(scope, queries):
    return scope["cloudwatch"].get_metric_data(
        MetricDataQueries=queries, ScanBy="TimestampAscending",
        StartTime=datetime.fromtimestamp(scope["bucket"] - 120, timezone.utc),
        EndTime=datetime.fromtimestamp(scope["bucket"] + 60, timezone.utc))["MetricDataResults"]


def test_math_arithmetic_resolves_hidden_queries_and_sparse_samples(math_scope):
    scope = math_scope
    _put(scope, "Errors", 5, -120)
    _put(scope, "Requests", 20, -120)
    _put(scope, "Requests", 10, -60)
    _put(scope, "Errors", 5)
    _put(scope, "Requests", 0)
    results = _data(scope, [
        {"Id": "answer", "Expression": "rate + errors * 0", "Label": "Error percentage",
         "ReturnData": True},
        {"Id": "rate", "Expression": "100 * (errors / requests)", "ReturnData": False},
        _query(scope, "requests", "Requests"),
        _query(scope, "errors", "Errors"),
    ])
    assert len(results) == 1
    result = results[0]
    assert result["Id"] == "answer"
    assert result["Label"] == "Error percentage"
    assert result["StatusCode"] == "Complete"
    assert result["Values"] == [25.0, 0.0, 0.0]
    assert [int(value.timestamp()) for value in result["Timestamps"]] == [
        scope["bucket"] - 120, scope["bucket"] - 60, scope["bucket"]]
    ratio = _data(scope, [
        {"Id": "ratio", "Expression": "100 * errors / requests", "ReturnData": True},
        _query(scope, "errors", "Errors"), _query(scope, "requests", "Requests"),
    ])[0]
    assert ratio["Values"] == [25.0, 0.0]
    assert len(ratio["Timestamps"]) == 2


def test_math_period_and_arithmetic_precedence(math_scope):
    scope = math_scope
    _put(scope, "Requests", 1200)
    result = _data(scope, [
        {"Id": "rate", "Expression": "-(-requests) / PERIOD(requests) + 2 * 3 ^ 2",
         "ReturnData": True},
        _query(scope, "requests", "Requests"),
    ])[0]
    assert result["Values"] == [38.0]


def test_math_service_quota_uses_the_declared_lambda_quota(math_scope):
    scope = math_scope
    quota = scope["service-quotas"].get_service_quota(
        ServiceCode="lambda", QuotaCode="L-B99A9384")["Quota"]["Value"]
    _put(scope, "ConcurrentExecutions", 125, namespace="AWS/Lambda")
    usage = _query(scope, "usage", "ConcurrentExecutions", statistic="Maximum")
    usage["MetricStat"]["Metric"]["Namespace"] = "AWS/Lambda"
    result = _data(scope, [
        {"Id": "utilization", "Expression": "100 * usage / SERVICE_QUOTA(usage)",
         "ReturnData": True}, usage,
    ])[0]
    values = dict(zip((int(value.timestamp()) for value in result["Timestamps"]), result["Values"]))
    assert values[scope["bucket"]] == pytest.approx(100 * 125 / quota)


@pytest.mark.parametrize("expression", ["absent + 1", "SUM([requests])", "PERIOD(derived)"])
def test_math_invalid_expression_does_not_replace_an_alarm(math_scope, expression):
    scope = math_scope
    cloudwatch = scope["cloudwatch"]
    request = dict(AlarmName=scope["name"], EvaluationPeriods=1, Threshold=10,
                   ComparisonOperator="GreaterThanThreshold", TreatMissingData="notBreaching")
    queries = [
        {"Id": "result", "Expression": "requests / 60", "ReturnData": True},
        {"Id": "derived", "Expression": "requests * 2", "ReturnData": False},
        _query(scope, "requests", "Requests"),
    ]
    cloudwatch.put_metric_alarm(**request, Metrics=queries)
    scope["cleanup"].callback(cloudwatch.delete_alarms, AlarmNames=[scope["name"]])
    before = cloudwatch.describe_alarms(AlarmNames=[scope["name"]])["MetricAlarms"][0]["Metrics"]
    with pytest.raises(ClientError) as error:
        cloudwatch.put_metric_alarm(**request, Metrics=[
            {**queries[0], "Expression": expression}, *queries[1:]])
    assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
    assert cloudwatch.describe_alarms(AlarmNames=[scope["name"]])["MetricAlarms"][0]["Metrics"] == before


def test_math_rejects_cycles_and_foreign_account_selection(math_scope):
    scope = math_scope
    for queries in (
        [{"Id": "a", "Expression": "b + 1"}, {"Id": "b", "Expression": "a + 1", "ReturnData": False}],
        [{"Id": "result", "Expression": "requests + 1"},
         {**_query(scope, "requests", "Requests"), "AccountId": "987654321098"}],
    ):
        with pytest.raises(ClientError) as error:
            _data(scope, queries)
        assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400


def test_math_cloudformation_alarm_evaluates_error_ratio_and_recovery(math_scope, record_property):
    scope = math_scope
    cloudwatch = scope["cloudwatch"]
    cfn = scope["cloudformation"]
    _put(scope, "Errors", 0)
    _put(scope, "Requests", 100)
    queries = [
        {"Id": "rate", "Expression": "100 * errors / requests", "ReturnData": True},
        _query(scope, "errors", "Errors"), _query(scope, "requests", "Requests"),
    ]
    cfn.create_stack(StackName=scope["name"], TemplateBody=json.dumps({
        "Resources": {"Alarm": {"Type": "AWS::CloudWatch::Alarm", "Properties": {
            "AlarmName": scope["name"], "Metrics": queries, "EvaluationPeriods": 1,
            "Threshold": 10, "ComparisonOperator": "GreaterThanThreshold",
            "TreatMissingData": "notBreaching",
        }}}}))

    def delete_stack():
        cfn.delete_stack(StackName=scope["name"])
        cfn.get_waiter("stack_delete_complete").wait(
            StackName=scope["name"], WaiterConfig={"Delay": 1, "MaxAttempts": 60})

    scope["cleanup"].callback(delete_stack)
    cfn.get_waiter("stack_create_complete").wait(
        StackName=scope["name"], WaiterConfig={"Delay": 1, "MaxAttempts": 40})

    def state():
        return cloudwatch.describe_alarms(AlarmNames=[scope["name"]])["MetricAlarms"][0]["StateValue"]

    cloudwatch.set_alarm_state(AlarmName=scope["name"], StateValue="INSUFFICIENT_DATA",
                              StateReason="Wait for real periodic evaluation")
    _until(state, lambda value: value == "OK")
    began = time.monotonic()
    _put(scope, "Errors", 30)
    _until(state, lambda value: value == "ALARM")
    record_property("error_ratio_to_alarm_seconds", time.monotonic() - began)
    _put(scope, "Requests", 900)
    _until(state, lambda value: value == "OK")
    record_property("alarm_and_recovery_seconds", time.monotonic() - began)

"""Alarm monitor role admission with IAM enforcement enabled."""
import json

import pytest
from botocore.exceptions import ClientError

from appconfig_alarm_fixtures import _configuration, _deploy, alarm_monitor_scope


@pytest.mark.parametrize("refusal", ["permission", "trust"])
def test_alarm_monitor_rejects_unreadable_monitor_before_deployment(alarm_monitor_scope, refusal):
    scope = alarm_monitor_scope()
    iam = scope["iam"]
    if refusal == "permission":
        iam.put_role_policy(RoleName=scope["name"], PolicyName="monitor", PolicyDocument=json.dumps({
            "Version": "2012-10-17", "Statement": [
                {"Effect": "Deny", "Action": "cloudwatch:DescribeAlarms", "Resource": "*"}]}))
    else:
        iam.update_assume_role_policy(RoleName=scope["name"], PolicyDocument=json.dumps({
            "Version": "2012-10-17", "Statement": [
                {"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"},
                 "Action": "sts:AssumeRole"}]}))
    with pytest.raises(ClientError) as error:
        _deploy(scope)
    assert error.value.response["Error"]["Code"] == "BadRequestException"
    deployments = scope["appconfig"].list_deployments(ApplicationId=scope["app"], EnvironmentId=scope["env"])["Items"]
    assert [item["ConfigurationVersion"] for item in deployments] == ["1"]
    assert _configuration(scope)[0] == "1"

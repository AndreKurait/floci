"""Background Lambda scaling retains each alarm's owning AWS account."""

from contextlib import ExitStack, closing
from datetime import datetime, timedelta, timezone

import boto3

from test_cloudformation_provisioned_concurrency import remove_stack, settled, template
from test_lambda_application_autoscaling import capacity, deregister, target


def test_same_name_lambda_targets_scale_only_in_the_account_with_metrics(
    aws_config, client_config, unique_name
):
    function, stack_name = unique_name + "-function", unique_name + "-stack"
    accounts = ("222222222222", "333333333333")
    with ExitStack() as resources:
        clients = {
            account: {
                service: resources.enter_context(closing(boto3.client(
                    service, config=client_config, **dict(aws_config, aws_access_key_id=account)
                )))
                for service in ("cloudformation", "lambda", "application-autoscaling", "cloudwatch")
            }
            for account in accounts
        }
        created = []
        try:
            for account in accounts:
                services = clients[account]
                services["cloudformation"].create_stack(
                    StackName=stack_name, TemplateBody=template(function, False),
                    Capabilities=["CAPABILITY_IAM"],
                )
                created.append(account)
                assert settled(services["cloudformation"], stack_name)["StackStatus"] == "CREATE_COMPLETE"
                services["application-autoscaling"].register_scalable_target(
                    **target(function), MinCapacity=1, MaxCapacity=2
                )
                services["application-autoscaling"].put_scaling_policy(
                    **target(function), PolicyName=unique_name + "-policy", PolicyType="TargetTrackingScaling",
                    TargetTrackingScalingPolicyConfiguration={
                        "TargetValue": 0.5,
                        "PredefinedMetricSpecification": {
                            "PredefinedMetricType": "LambdaProvisionedConcurrencyUtilization"
                        },
                    },
                )
            now = datetime.now(timezone.utc)
            clients[accounts[0]]["cloudwatch"].put_metric_data(
                Namespace="AWS/Lambda",
                MetricData=[
                    {"MetricName": "ProvisionedConcurrencyUtilization",
                     "Dimensions": [{"Name": "FunctionName", "Value": function},
                                    {"Name": "Resource", "Value": function + ":live"}],
                     "Timestamp": now - timedelta(seconds=60 * minute), "Value": 0.9}
                    for minute in range(1, 7)
                ],
            )
            capacity(clients[accounts[0]]["lambda"], function, 2)
            capacity(clients[accounts[1]]["lambda"], function, 1)
            assert clients[accounts[0]]["application-autoscaling"].describe_scaling_activities(
                **target(function)
            )["ScalingActivities"]
            assert not clients[accounts[1]]["application-autoscaling"].describe_scaling_activities(
                **target(function)
            )["ScalingActivities"]
        finally:
            for account in reversed(created):
                try:
                    deregister(clients[account]["application-autoscaling"], function)
                finally:
                    remove_stack(clients[account]["cloudformation"], stack_name)

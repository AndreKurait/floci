"""Background Lambda scaling retains each alarm's owning AWS account."""

from contextlib import ExitStack, closing, contextmanager
from datetime import datetime, timedelta, timezone
import time

import boto3
from botocore.exceptions import ClientError

from test_cloudformation_provisioned_concurrency import remove_stack, settled, template
from test_lambda_application_autoscaling import capacity, deregister, target


@contextmanager
def member_account_configs(aws_config, client_config, unique_name):
    """Create member accounts and use their issued STS sessions with strict authentication."""
    with ExitStack() as cleanup:
        organizations = cleanup.enter_context(closing(
            boto3.client("organizations", config=client_config, **aws_config)
        ))
        try:
            organizations.create_organization(FeatureSet="ALL")
        except ClientError as error:
            if error.response["Error"]["Code"] != "AlreadyInOrganizationException":
                raise
        else:
            cleanup.callback(organizations.delete_organization)
        sts = cleanup.enter_context(closing(boto3.client("sts", config=client_config, **aws_config)))
        management_account = sts.get_caller_identity()["Account"]
        configs = {}
        for index in range(2):
            name = f"{unique_name}-{index}"
            status = organizations.create_account(
                AccountName=name, Email=name + "@example.test",
            )["CreateAccountStatus"]
            deadline = time.monotonic() + 30
            while status["State"] == "IN_PROGRESS":
                assert time.monotonic() < deadline, "Member account creation did not finish"
                time.sleep(0.25)
                status = organizations.describe_create_account_status(
                    CreateAccountRequestId=status["Id"]
                )["CreateAccountStatus"]
            assert status["State"] == "SUCCEEDED", status
            account = status["AccountId"]
            cleanup.callback(organizations.remove_account_from_organization, AccountId=account)
            partition = organizations.describe_account(AccountId=account)["Account"]["Arn"].split(":")[1]
            credentials = sts.assume_role(
                RoleArn=f"arn:{partition}:iam::{account}:role/OrganizationAccountAccessRole",
                RoleSessionName=name,
            )["Credentials"]
            config = dict(
                aws_config,
                aws_access_key_id=credentials["AccessKeyId"],
                aws_secret_access_key=credentials["SecretAccessKey"],
                aws_session_token=credentials["SessionToken"],
            )
            with closing(boto3.client("sts", config=client_config, **config)) as member:
                assert member.get_caller_identity()["Account"] == account
            assert account != management_account and account not in configs
            configs[account] = config
        yield configs


def test_same_name_lambda_targets_scale_only_in_the_account_with_metrics(
    aws_config, client_config, unique_name
):
    function, stack_name = unique_name + "-function", unique_name + "-stack"
    with ExitStack() as resources:
        configs = resources.enter_context(member_account_configs(aws_config, client_config, unique_name))
        accounts = tuple(configs)
        clients = {
            account: {
                service: resources.enter_context(closing(boto3.client(
                    service, config=client_config, **configs[account]
                )))
                for service in ("cloudformation", "lambda", "application-autoscaling", "cloudwatch")
            }
            for account in accounts
        }
        created = []
        try:
            for account in accounts:
                services = clients[account]
                created.append(account)
                services["cloudformation"].create_stack(
                    StackName=stack_name, TemplateBody=template(function, False),
                    Capabilities=["CAPABILITY_IAM"],
                )
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
            cleanup_accounts(clients, created, function, stack_name)


def cleanup_accounts(clients, created, function, stack_name):
    errors = []
    for account in reversed(created):
        for operation, client, resource in (
            (deregister, clients[account]["application-autoscaling"], function),
            (remove_stack, clients[account]["cloudformation"], stack_name),
        ):
            try:
                operation(client, resource)
            except Exception as error:
                errors.append((account, operation.__name__, error))
    if errors:
        raise RuntimeError("Lambda autoscaling account cleanup failed", errors) from errors[0][2]

"""Signed AppConfig caller permissions, resource identities, and role passing."""
from contextlib import ExitStack
import json

import boto3
import pytest
from botocore.exceptions import ClientError


@pytest.fixture(params=["us-east-1", "cn-north-1"])
def appconfig_permissions_scope(aws_config, client_config, unique_name, request):
    config = {**aws_config, "region_name": request.param}
    with ExitStack() as cleanup:
        root = {name: boto3.client(name, config=client_config, **config)
                for name in ("iam", "appconfig", "cloudwatch")}
        for client in root.values():
            cleanup.callback(client.close)
        iam = root["iam"]
        user = iam.create_user(UserName=unique_name)["User"]
        cleanup.callback(iam.delete_user, UserName=unique_name)
        key = iam.create_access_key(UserName=unique_name)["AccessKey"]
        cleanup.callback(iam.delete_access_key, UserName=unique_name, AccessKeyId=key["AccessKeyId"])
        user_config = {**config, "aws_access_key_id": key["AccessKeyId"],
                       "aws_secret_access_key": key["SecretAccessKey"]}
        user_config.pop("aws_session_token", None)
        caller = {name: boto3.client(name, config=client_config, **user_config)
                  for name in ("sts", "s3", "appconfig", "appconfigdata")}
        for client in caller.values():
            cleanup.callback(client.close)
        identity = caller["sts"].get_caller_identity()
        assert identity["Arn"] == user["Arn"], "Use the created IAM user, never root fallback"
        with pytest.raises(ClientError) as denied:
            caller["s3"].list_buckets()
        assert denied.value.response["Error"]["Code"] in ("AccessDenied", "AccessDeniedException")

        control = root["appconfig"]
        names = {"app": unique_name + " + 100%", "env": "production + 10%", "profile": "settings + 10%"}
        application = control.create_application(Name=names["app"])["Id"]
        cleanup.callback(control.delete_application, ApplicationId=application)
        environment = control.create_environment(ApplicationId=application, Name=names["env"])["Id"]
        cleanup.callback(control.delete_environment, ApplicationId=application,
                         EnvironmentId=environment, DeletionProtectionCheck="BYPASS")
        profile = control.create_configuration_profile(
            ApplicationId=application, Name=names["profile"], LocationUri="hosted", Type="AWS.Freeform")["Id"]
        cleanup.callback(control.delete_configuration_profile, ApplicationId=application,
                         ConfigurationProfileId=profile, DeletionProtectionCheck="BYPASS")
        created = control.create_hosted_configuration_version(
            ApplicationId=application, ConfigurationProfileId=profile,
            Content=b'{"enabled":true}', ContentType="application/json")
        created["Content"].close()
        strategy = control.create_deployment_strategy(
            Name=unique_name, DeploymentDurationInMinutes=0, FinalBakeTimeInMinutes=0,
            GrowthFactor=100, ReplicateTo="NONE")["Id"]
        cleanup.callback(control.delete_deployment_strategy, DeploymentStrategyId=strategy)
        deployment = control.start_deployment(
            ApplicationId=application, EnvironmentId=environment,
            ConfigurationProfileId=profile, ConfigurationVersion=str(created["VersionNumber"]),
            DeploymentStrategyId=strategy)
        cleanup.callback(control.stop_deployment, ApplicationId=application, EnvironmentId=environment,
                         DeploymentNumber=deployment["DeploymentNumber"], AllowRevert=True)
        partition = identity["Arn"].split(":")[1]
        prefix = f"arn:{partition}:appconfig:{request.param}:{identity['Account']}:application/{application}"
        yield {
            "cleanup": cleanup, "iam": iam, "root": root, "caller": caller, "user": unique_name,
            "user_arn": user["Arn"], "app": application, "env": environment, "profile": profile,
            "names": names, "version": created["VersionNumber"], "deployment": deployment["DeploymentNumber"],
            "policy_created": False,
            "arns": {"app": prefix, "env": f"{prefix}/environment/{environment}",
                     "profile": f"{prefix}/configurationprofile/{profile}",
                     "configuration": f"{prefix}/environment/{environment}/configuration/{profile}",
                     "deployment": f"{prefix}/environment/{environment}/deployment/{deployment['DeploymentNumber']}"},
        }


def _policy(scope, statements):
    scope["iam"].put_user_policy(
        UserName=scope["user"], PolicyName="appconfig", PolicyDocument=json.dumps(
            {"Version": "2012-10-17", "Statement": statements}))
    if not scope["policy_created"]:
        scope["cleanup"].callback(scope["iam"].delete_user_policy, UserName=scope["user"], PolicyName="appconfig")
        scope["policy_created"] = True


def _statement(action, resource, effect="Allow", condition=None):
    result = {"Effect": effect, "Action": action, "Resource": resource}
    if condition is not None:
        result["Condition"] = condition
    return result


def _denied(call):
    with pytest.raises(ClientError) as error:
        call()
    assert error.value.response["Error"]["Code"] in ("AccessDenied", "AccessDeniedException")


def _request(scope, operation, names=False):
    client = scope["caller"]["appconfig"]
    ids = scope["names"] if names else scope
    if operation == "GetApplication":
        response = client.get_application(ApplicationId=ids["app"])
    elif operation == "GetConfigurationProfile":
        response = client.get_configuration_profile(ApplicationId=ids["app"], ConfigurationProfileId=ids["profile"])
    elif operation == "GetConfiguration":
        response = client.get_configuration(
            Application=ids["app"], Environment=ids["env"], Configuration=ids["profile"],
            ClientId="permissions-probe")
    elif operation == "StartConfigurationSession":
        response = scope["caller"]["appconfigdata"].start_configuration_session(
            ApplicationIdentifier=ids["app"], EnvironmentIdentifier=ids["env"],
            ConfigurationProfileIdentifier=ids["profile"])
    elif operation == "CreateHostedConfigurationVersion":
        response = client.create_hosted_configuration_version(
            ApplicationId=ids["app"], ConfigurationProfileId=ids["profile"],
            Content=b'{"enabled":false}', ContentType="application/json")
    elif operation == "GetHostedConfigurationVersion":
        response = client.get_hosted_configuration_version(
            ApplicationId=ids["app"], ConfigurationProfileId=ids["profile"], VersionNumber=scope["version"])
    elif operation == "GetDeployment":
        response = client.get_deployment(
            ApplicationId=ids["app"], EnvironmentId=ids["env"], DeploymentNumber=scope["deployment"])
    else:
        raise AssertionError(operation)
    for key in ("Content", "Configuration"):
        body = response.get(key)
        if hasattr(body, "close"):
            body.close()
    return response


OPERATIONS = [
    ("GetApplication", ["app"]),
    ("GetConfigurationProfile", ["app", "profile"]),
    ("GetConfiguration", ["app", "env", "profile"]),
    ("StartConfigurationSession", ["configuration"]),
    ("CreateHostedConfigurationVersion", ["app", "profile"]),
    ("GetHostedConfigurationVersion", ["app", "profile"]),
    ("GetDeployment", ["app", "env", "deployment"]),
]


@pytest.mark.parametrize("operation,resources", OPERATIONS)
def test_appconfig_permissions_deny_allow_and_explicit_deny(appconfig_permissions_scope, operation, resources):
    scope = appconfig_permissions_scope
    call = lambda: _request(scope, operation)
    _denied(call)
    allow = _statement("appconfig:" + operation, [scope["arns"][name] for name in resources])
    _policy(scope, [allow])
    assert call()["ResponseMetadata"]["HTTPStatusCode"] in (200, 201)
    _policy(scope, [allow, _statement("appconfig:" + operation, scope["arns"][resources[-1]], "Deny")])
    _denied(call)


@pytest.mark.parametrize("operation,resources", OPERATIONS[:4])
def test_appconfig_permissions_use_ids_when_names_are_given(appconfig_permissions_scope, operation, resources):
    scope = appconfig_permissions_scope
    allow = _statement("appconfig:" + operation, [scope["arns"][name] for name in resources])
    _policy(scope, [allow])
    assert _request(scope, operation, names=True)["ResponseMetadata"]["HTTPStatusCode"] in (200, 201)
    _policy(scope, [allow, _statement("appconfig:" + operation, scope["arns"][resources[-1]], "Deny")])
    _denied(lambda: _request(scope, operation, names=True))


def test_appconfig_permissions_token_is_checked_without_consuming_it(appconfig_permissions_scope):
    scope = appconfig_permissions_scope
    configuration = scope["arns"]["configuration"]
    start = _statement("appconfig:StartConfigurationSession", configuration)
    _policy(scope, [start])
    token = _request(scope, "StartConfigurationSession")["InitialConfigurationToken"]
    read = lambda: scope["caller"]["appconfigdata"].get_latest_configuration(ConfigurationToken=token)
    _denied(read)
    _policy(scope, [start, _statement("appconfig:GetLatestConfiguration", configuration + "-other")])
    _denied(read)
    allow = _statement("appconfig:GetLatestConfiguration", configuration)
    _policy(scope, [start, allow, _statement("appconfig:GetLatestConfiguration", configuration, "Deny")])
    _denied(read)
    _policy(scope, [start, allow])
    result = read()
    try:
        assert json.loads(result["Configuration"].read()) == {"enabled": True}
    finally:
        result["Configuration"].close()


@pytest.mark.parametrize("target", ["profile", "environment"])
def test_appconfig_permissions_require_passing_the_role_to_appconfig(appconfig_permissions_scope, target):
    scope = appconfig_permissions_scope
    iam = scope["iam"]
    role = iam.create_role(RoleName=scope["user"], AssumeRolePolicyDocument=json.dumps({
        "Version": "2012-10-17", "Statement": [{"Effect": "Allow",
            "Principal": {"Service": "appconfig.amazonaws.com"}, "Action": "sts:AssumeRole"}]}))["Role"]["Arn"]
    scope["cleanup"].callback(iam.delete_role, RoleName=scope["user"])
    if target == "profile":
        operation = "CreateConfigurationProfile"
        call = lambda: scope["caller"]["appconfig"].create_configuration_profile(
            ApplicationId=scope["app"], Name="external", LocationUri="s3://permissions-test/config.json",
            RetrievalRoleArn=role, Type="AWS.Freeform")
        remove = lambda created: scope["root"]["appconfig"].delete_configuration_profile(
            ApplicationId=scope["app"], ConfigurationProfileId=created["Id"], DeletionProtectionCheck="BYPASS")
    else:
        operation = "CreateEnvironment"
        alarms = scope["root"]["cloudwatch"]
        alarms.put_metric_alarm(AlarmName=scope["user"], Namespace="AppConfigPermissions", MetricName="Errors",
                                ComparisonOperator="GreaterThanThreshold", EvaluationPeriods=1,
                                Period=60, Statistic="Sum", Threshold=0)
        scope["cleanup"].callback(alarms.delete_alarms, AlarmNames=[scope["user"]])
        alarm = alarms.describe_alarms(AlarmNames=[scope["user"]])["MetricAlarms"][0]["AlarmArn"]
        call = lambda: scope["caller"]["appconfig"].create_environment(
            ApplicationId=scope["app"], Name="monitored", Monitors=[{"AlarmArn": alarm, "AlarmRoleArn": role}])
        remove = lambda created: scope["root"]["appconfig"].delete_environment(
            ApplicationId=scope["app"], EnvironmentId=created["Id"], DeletionProtectionCheck="BYPASS")
    app_action = _statement("appconfig:" + operation, scope["arns"]["app"])
    _policy(scope, [app_action])
    _denied(call)
    _policy(scope, [app_action, _statement("iam:PassRole", role,
                                         condition={"StringEquals": {"iam:PassedToService": "lambda.amazonaws.com"}})])
    _denied(call)
    _policy(scope, [app_action, _statement("iam:PassRole", role,
                                         condition={"StringEquals": {"iam:PassedToService": "appconfig.amazonaws.com"}})])
    created = call()
    scope["cleanup"].callback(remove, created)
    assert created["ResponseMetadata"]["HTTPStatusCode"] in (200, 201)

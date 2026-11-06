"""AppConfig bake completion and manual rollback through both retrieval APIs."""

from contextlib import ExitStack
from datetime import datetime, timedelta
import time

import boto3
import pytest
from botocore.exceptions import ClientError


@pytest.mark.timeout(180)
def test_gradual_rollout_precedes_bake_and_can_be_stopped(
        aws_config, client_config, unique_name):
    with ExitStack() as cleanup:
        client = boto3.client("appconfig", config=client_config, **aws_config)
        cleanup.callback(client.close)
        app = client.create_application(Name=unique_name)["Id"]
        cleanup.callback(client.delete_application, ApplicationId=app)
        profile = client.create_configuration_profile(
            ApplicationId=app, Name="settings", LocationUri="hosted")["Id"]
        cleanup.callback(
            client.delete_configuration_profile, ApplicationId=app, ConfigurationProfileId=profile)
        version = client.create_hosted_configuration_version(
            ApplicationId=app, ConfigurationProfileId=profile,
            Content=b'{"enabled":true}', ContentType="application/json",
        )["VersionNumber"]
        cleanup.callback(
            client.delete_hosted_configuration_version, ApplicationId=app,
            ConfigurationProfileId=profile, VersionNumber=version)
        strategy = client.create_deployment_strategy(
            Name=unique_name, DeploymentDurationInMinutes=1, FinalBakeTimeInMinutes=1,
            GrowthFactor=50, GrowthType="LINEAR", ReplicateTo="NONE",
        )["Id"]
        cleanup.callback(client.delete_deployment_strategy, DeploymentStrategyId=strategy)
        env = client.create_environment(ApplicationId=app, Name="local")["Id"]
        cleanup.callback(client.delete_environment, ApplicationId=app, EnvironmentId=env)
        scope = {"ApplicationId": app, "EnvironmentId": env}
        arguments = {
            **scope, "ConfigurationProfileId": profile, "ConfigurationVersion": str(version),
            "DeploymentStrategyId": strategy,
        }

        cancelled = client.start_deployment(**arguments)
        assert cancelled["State"] == "DEPLOYING"
        assert cancelled["PercentageComplete"] == 0
        cancelled_target = {**scope, "DeploymentNumber": cancelled["DeploymentNumber"]}
        assert client.stop_deployment(**cancelled_target)["State"] == "ROLLED_BACK"

        began = time.monotonic()
        started = client.start_deployment(**arguments)
        target = {**scope, "DeploymentNumber": started["DeploymentNumber"]}
        assert started["State"] == "DEPLOYING"
        assert "CompletedAt" not in started
        states = []
        percentages = set()
        while time.monotonic() - began < 135:
            observed = client.get_deployment(**target)
            state = observed["State"]
            if not states or states[-1] != state:
                states.append(state)
                if state == "BAKING":
                    assert time.monotonic() - began >= 59
                    assert "CompletedAt" not in observed
                    assert observed["PercentageComplete"] == 100
                if state == "COMPLETE":
                    assert time.monotonic() - began >= 119
                    break
            percentages.add(observed["PercentageComplete"])
            assert state in {"DEPLOYING", "BAKING"}
            time.sleep(1)
        else:
            pytest.fail(f"The one-minute rollout and one-minute bake did not complete: {states}")

        assert states == ["DEPLOYING", "BAKING", "COMPLETE"]
        assert {0, 50, 100}.issubset(percentages)
        assert observed["CompletedAt"] - observed["StartedAt"] >= timedelta(minutes=2)
        listed = {row["DeploymentNumber"]: row
                  for row in client.list_deployments(**scope)["Items"]}
        assert listed[started["DeploymentNumber"]]["State"] == "COMPLETE"
        assert listed[started["DeploymentNumber"]]["CompletedAt"] == observed["CompletedAt"]
        assert client.get_deployment(**cancelled_target)["State"] == "ROLLED_BACK"


@pytest.mark.parametrize("wait_for_completion", [False, True], ids=["baking", "complete"])
@pytest.mark.parametrize("zero_padded", [False, True], ids=["decimal", "zero-padded"])
@pytest.mark.parametrize("delete_previous", [False, True], ids=["restore", "deleted"])
def test_deployment_rollback_restores_both_retrieval_apis(
        aws_config, client_config, unique_name, delete_previous, zero_padded, wait_for_completion):
    with ExitStack() as cleanup:
        client = boto3.client("appconfig", config=client_config, **aws_config)
        data = boto3.client("appconfigdata", config=client_config, **aws_config)
        cleanup.callback(client.close)
        cleanup.callback(data.close)
        app = client.create_application(Name=unique_name)["Id"]
        cleanup.callback(client.delete_application, ApplicationId=app)
        profile = client.create_configuration_profile(
            ApplicationId=app, Name="settings", LocationUri="hosted", Type="AWS.Freeform"
        )["Id"]
        cleanup.callback(
            client.delete_configuration_profile, ApplicationId=app, ConfigurationProfileId=profile)
        versions = []
        for content in (b'{"version":1}', b'{"version":2}'):
            version = client.create_hosted_configuration_version(
                ApplicationId=app, ConfigurationProfileId=profile,
                Content=content, ContentType="application/json",
            )["VersionNumber"]
            versions.append(version)
            cleanup.callback(
                client.delete_hosted_configuration_version, ApplicationId=app,
                ConfigurationProfileId=profile, VersionNumber=version,
            )
        strategies = []
        for bake in (0, 1):
            strategy = client.create_deployment_strategy(
                Name=f"{unique_name}-{bake}", DeploymentDurationInMinutes=0,
                FinalBakeTimeInMinutes=bake, GrowthFactor=100,
                GrowthType="LINEAR", ReplicateTo="NONE",
            )["Id"]
            strategies.append(strategy)
            cleanup.callback(client.delete_deployment_strategy, DeploymentStrategyId=strategy)
        env = client.create_environment(ApplicationId=app, Name="local")["Id"]
        cleanup.callback(client.delete_environment, ApplicationId=app, EnvironmentId=env)
        scope = {"ApplicationId": app, "EnvironmentId": env}

        def deploy(version, strategy):
            return client.start_deployment(
                **scope, ConfigurationProfileId=profile,
                ConfigurationVersion=str(version), DeploymentStrategyId=strategy)

        def assert_content(version, content):
            response = client.get_configuration(
                Application=app, Environment=env, Configuration=profile, ClientId=unique_name)
            with response["Content"] as stream:
                assert stream.read() == content
            assert response["ConfigurationVersion"] == str(version)
            token = data.start_configuration_session(
                ApplicationIdentifier=app, EnvironmentIdentifier=env,
                ConfigurationProfileIdentifier=profile,
            )["InitialConfigurationToken"]
            response = data.get_latest_configuration(ConfigurationToken=token)
            with response["Configuration"] as stream:
                assert stream.read() == content

        previous_version = ("0" if zero_padded else "") + str(versions[0])
        first = deploy(previous_version, strategies[0])
        assert first["State"] == "COMPLETE"
        assert isinstance(first["StartedAt"], datetime)
        assert first["CompletedAt"] == first["StartedAt"]
        assert_content(previous_version, b'{"version":1}')
        second = deploy(versions[1], strategies[1])
        target = {**scope, "DeploymentNumber": second["DeploymentNumber"]}
        assert second["State"] == "BAKING"
        assert second["PercentageComplete"] == 100
        assert second["FinalBakeTimeInMinutes"] == 1
        assert "CompletedAt" not in second
        assert_content(versions[1], b'{"version":2}')
        assert client.get_deployment(**target)["State"] == "BAKING"
        with pytest.raises(ClientError) as error:
            client.stop_deployment(**target)
        assert error.value.response["Error"]["Code"] == "BadRequestException"
        assert client.get_deployment(**target)["State"] == "BAKING"

        if delete_previous:
            client.delete_hosted_configuration_version(
                ApplicationId=app, ConfigurationProfileId=profile, VersionNumber=versions[0])
            before = client.get_deployment(**target)
            with pytest.raises(ClientError) as error:
                client.stop_deployment(**target, AllowRevert=True)
            assert error.value.response["Error"]["Code"] == "BadRequestException"
            assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
            after = client.get_deployment(**target)
            assert after["State"] == before["State"]
            assert after.get("CompletedAt") == before.get("CompletedAt")
            assert after["PercentageComplete"] == before["PercentageComplete"]
            assert_content(versions[1], b'{"version":2}')
            return

        if wait_for_completion:
            deadline = time.monotonic() + 75
            while time.monotonic() < deadline:
                observed = client.get_deployment(**target)
                if observed["State"] == "COMPLETE":
                    break
                assert observed["State"] == "BAKING"
                time.sleep(1)
            else:
                pytest.fail("The one-minute final bake did not complete")
            assert observed["CompletedAt"] - observed["StartedAt"] == timedelta(minutes=1)
            with pytest.raises(ClientError) as error:
                client.stop_deployment(**target)
            assert error.value.response["Error"]["Code"] == "BadRequestException"
            assert_content(versions[1], b'{"version":2}')

        stopped = client.stop_deployment(**target, AllowRevert=True)
        expected = "REVERTED" if wait_for_completion else "ROLLED_BACK"
        assert stopped["ResponseMetadata"]["HTTPStatusCode"] == 202
        assert stopped["State"] == expected
        assert isinstance(stopped["CompletedAt"], datetime)
        assert client.get_deployment(**target)["State"] == expected
        listed = {
            row["DeploymentNumber"]: row for row in client.list_deployments(**scope)["Items"]}
        assert listed[second["DeploymentNumber"]]["State"] == expected
        assert listed[second["DeploymentNumber"]]["CompletedAt"] == stopped["CompletedAt"]
        assert_content(previous_version, b'{"version":1}')
        with pytest.raises(ClientError) as error:
            client.stop_deployment(**target, AllowRevert=True)
        assert error.value.response["Error"]["Code"] == "BadRequestException"
        assert_content(previous_version, b'{"version":1}')

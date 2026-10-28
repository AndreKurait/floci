"""Auto Scaling tag Query/XML, filtering, pagination and ownership through boto3."""

import uuid
from contextlib import ExitStack, closing

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError


def group(stack, autoscaling, ec2, region, name, tags):
    image = ec2.register_image(
        Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1"
    )["ImageId"]
    stack.callback(ec2.deregister_image, ImageId=image)
    autoscaling.create_launch_configuration(
        LaunchConfigurationName=name, ImageId=image, InstanceType="t3.micro"
    )
    stack.callback(autoscaling.delete_launch_configuration, LaunchConfigurationName=name)
    autoscaling.create_auto_scaling_group(
        AutoScalingGroupName=name,
        LaunchConfigurationName=name,
        MinSize=0,
        MaxSize=0,
        DesiredCapacity=0,
        AvailabilityZones=[region + "a"],
        Tags=tags,
    )
    stack.callback(
        autoscaling.delete_auto_scaling_group, AutoScalingGroupName=name, ForceDelete=True
    )


def clients(stack, aws_config, client_config):
    return tuple(
        stack.enter_context(closing(boto3.client(service, config=client_config, **aws_config)))
        for service in ("autoscaling", "ec2")
    )


@pytest.fixture
def tagged_groups(aws_config, client_config):
    prefix = "describe-tags-" + uuid.uuid4().hex[:12]
    with ExitStack() as stack:
        autoscaling, ec2 = clients(stack, aws_config, client_config)
        names = [prefix + "-a", prefix + "-b"]
        for name, value in zip(names, ["Production", "development"]):
            group(stack, autoscaling, ec2, aws_config["region_name"], name, [
                {"Key": "environment", "Value": value, "PropagateAtLaunch": True},
                {"Key": "owner", "Value": "<ops&team>", "PropagateAtLaunch": False},
            ])
        yield autoscaling, names


def test_describe_tags_filters_match_each_tag_and_track_mutation(tagged_groups):
    autoscaling, names = tagged_groups
    scope = {"Name": "auto-scaling-group", "Values": names}
    tags = autoscaling.describe_tags(Filters=[
        scope, {"Name": "Key", "Values": ["environment"]},
        {"Name": "value", "Values": ["Production", "development"]},
        {"Name": "propagate-at-launch", "Values": ["true"]},
    ])["Tags"]
    assert {(tag["ResourceId"], tag["Value"]) for tag in tags} == {
        (names[0], "Production"), (names[1], "development")
    }
    assert all(tag["ResourceType"] == "auto-scaling-group" for tag in tags)
    assert all(tag["Key"] == "environment" and tag["PropagateAtLaunch"] is True for tag in tags)
    assert autoscaling.describe_tags(Filters=[
        scope, {"Name": "key", "Values": ["environment"]},
        {"Name": "value", "Values": ["<ops&team>"]},
    ])["Tags"] == []
    assert autoscaling.describe_tags(Filters=[
        scope, {"Name": "value", "Values": ["production"]},
    ])["Tags"] == []
    owners = autoscaling.describe_tags(Filters=[
        scope, {"Name": "key", "Values": ["owner"]},
        {"Name": "propagate-at-launch", "Values": ["false"]},
    ])["Tags"]
    assert len(owners) == 2 and all(tag["Value"] == "<ops&team>" for tag in owners)

    tag = {"ResourceId": names[0], "ResourceType": "auto-scaling-group",
           "Key": "owner", "Value": "updated", "PropagateAtLaunch": True}
    autoscaling.create_or_update_tags(Tags=[tag])
    exact = [{"Name": "auto-scaling-group", "Values": [names[0]]},
             {"Name": "key", "Values": ["owner"]}]
    assert autoscaling.describe_tags(Filters=exact)["Tags"] == [tag]
    autoscaling.delete_tags(Tags=[tag])
    assert autoscaling.describe_tags(Filters=exact)["Tags"] == []


def test_describe_tags_sdk_paginator_and_invalid_token(tagged_groups):
    autoscaling, names = tagged_groups
    filters = [{"Name": "auto-scaling-group", "Values": names}]
    full = autoscaling.describe_tags(Filters=filters)["Tags"]
    pages = list(autoscaling.get_paginator("describe_tags").paginate(
        Filters=filters, PaginationConfig={"PageSize": 1}
    ))
    assert len(pages) == len(full) == 4
    assert [tag for page in pages for tag in page["Tags"]] == full
    assert all(page.get("NextToken") for page in pages[:-1])
    assert not pages[-1].get("NextToken")
    for token in ("", " ", "0", "1", "not-a-token", "-1", "999999"):
        with pytest.raises(ClientError) as error:
            autoscaling.describe_tags(Filters=filters, NextToken=token)
        assert error.value.response["Error"]["Code"] == "ValidationError"


def test_describe_tags_empty_values_and_boolean_matching(tagged_groups):
    autoscaling, names = tagged_groups
    scope = {"Name": "auto-scaling-group", "Values": names}
    full = autoscaling.describe_tags(Filters=[scope])["Tags"]
    for name in ("auto-scaling-group", "key", "value", "propagate-at-launch"):
        assert autoscaling.describe_tags(Filters=[
            scope, {"Name": name, "Values": []},
        ])["Tags"] == full
    assert len(autoscaling.describe_tags(Filters=[
        scope, {"Name": "\u212Aey", "Values": ["environment"]},
    ])["Tags"]) == 2
    for value in ("true", "TRUE", "TrUe", " true ", "\tTRUE\n"):
        tags = autoscaling.describe_tags(Filters=[
            scope, {"Name": "propagate-at-launch", "Values": [value]},
        ])["Tags"]
        assert len(tags) == 2 and all(tag["PropagateAtLaunch"] is True for tag in tags)
    tags = autoscaling.describe_tags(Filters=[
        scope, {"Name": "propagate-at-launch", "Values": [" FALSE "]},
    ])["Tags"]
    assert len(tags) == 2 and all(tag["PropagateAtLaunch"] is False for tag in tags)
    assert autoscaling.describe_tags(Filters=[
        scope, {"Name": "propagate-at-launch", "Values": ["TrUe", " FALSE "]},
    ])["Tags"] == full


def test_describe_tags_rejects_invalid_filters_and_record_limits(tagged_groups):
    autoscaling, names = tagged_groups
    scope = {"Name": "auto-scaling-group", "Values": names}
    for invalid in (
        {"Name": "unknown", "Values": ["value"]},
        {"Name": "unknown", "Values": []},
        {"Name": "key"},
        {"Values": []},
        {"Values": ["value"]},
        {"Name": "", "Values": ["value"]},
        {"Name": " key ", "Values": ["value"]},
        {"Name": "propagate-at-launch", "Values": ["invalid"]},
        {"Name": "propagate-at-launch", "Values": ["true", "1"]},
        {"Name": "propagate-at-launch", "Values": ["0"]},
        {"Name": "propagate-at-launch", "Values": ["tru e"]},
    ):
        with pytest.raises(ClientError) as error:
            autoscaling.describe_tags(Filters=[scope, invalid])
        assert error.value.response["Error"]["Code"] == "ValidationError"
        assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
    assert len(autoscaling.describe_tags(Filters=[scope], MaxRecords=100)["Tags"]) == 4
    for limit in (0, -1, 101):
        with pytest.raises(ClientError) as error:
            autoscaling.describe_tags(Filters=[scope], MaxRecords=limit)
        assert error.value.response["Error"]["Code"] == "ValidationError"


def test_describe_tags_account_and_region_scope(aws_config, client_config):
    name = "describe-tags-scope-" + uuid.uuid4().hex[:12]
    region = aws_config["region_name"]
    other_region = "us-west-2" if region != "us-west-2" else "eu-west-1"
    contexts = [
        ("home", region, "111111111111"),
        ("other-region", other_region, "111111111111"),
        ("other-account", region, "222222222222"),
    ]
    with ExitStack() as stack:
        scoped_clients = []
        for value, selected_region, account in contexts:
            config = {**aws_config, "region_name": selected_region, "aws_access_key_id": account}
            autoscaling, ec2 = clients(stack, config, client_config)
            group(stack, autoscaling, ec2, selected_region, name, [
                {"Key": "scope", "Value": value, "PropagateAtLaunch": False},
            ])
            scoped_clients.append((autoscaling, value))
        for autoscaling, value in scoped_clients:
            tags = autoscaling.describe_tags(Filters=[
                {"Name": "auto-scaling-group", "Values": [name]},
            ])["Tags"]
            assert tags == [{"ResourceId": name, "ResourceType": "auto-scaling-group",
                             "Key": "scope", "Value": value, "PropagateAtLaunch": False}]


def test_describe_tags_malformed_record_counts(aws_config):
    with closing(boto3.client(
        "autoscaling", config=Config(parameter_validation=False), **aws_config
    )) as autoscaling:
        for value in ("", "invalid", "1.5"):
            with pytest.raises(ClientError) as error:
                autoscaling.describe_tags(MaxRecords=value)
            assert error.value.response["Error"]["Code"] == "MalformedInput"
            assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400


def test_describe_tags_filter_validation(ec2_client, aws_config, client_config):
    name = "tag-validation-" + uuid.uuid4().hex[:12]
    with closing(boto3.client("autoscaling", config=client_config, **aws_config)) as autoscaling:
        with ExitStack() as cleanup:
            image = ec2_client.register_image(
                Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1"
            )["ImageId"]
            cleanup.callback(ec2_client.deregister_image, ImageId=image)
            template = ec2_client.create_launch_template(
                LaunchTemplateName=name,
                LaunchTemplateData={"ImageId": image, "InstanceType": "t3.micro"},
            )["LaunchTemplate"]["LaunchTemplateId"]
            cleanup.callback(ec2_client.delete_launch_template, LaunchTemplateId=template)
            autoscaling.create_auto_scaling_group(
                AutoScalingGroupName=name, MinSize=0, MaxSize=0, DesiredCapacity=0,
                AvailabilityZones=[aws_config["region_name"] + "a"],
                LaunchTemplate={"LaunchTemplateId": template},
                Tags=[
                    {"Key": "propagated", "Value": "Yes", "PropagateAtLaunch": True},
                    {"Key": "not-propagated", "Value": "No", "PropagateAtLaunch": False},
                ],
            )
            cleanup.callback(autoscaling.delete_auto_scaling_group,
                             AutoScalingGroupName=name, ForceDelete=True)
            group_filter = {"Name": "auto-scaling-group", "Values": [name]}
            for filter_name, values, expected in [
                ("KEY", ["propagated"], ["propagated"]),
                ("\u212Aey", ["propagated"], ["propagated"]),
                ("key", ["PROPAGATED"], []),
                ("propagate-at-launch", ["TrUe"], ["propagated"]),
                ("propagate-at-launch", [" false "], ["not-propagated"]),
                ("propagate-at-launch", ["\tTRUE\n"], ["propagated"]),
                ("propagate-at-launch", [], ["not-propagated", "propagated"]),
            ]:
                tags = autoscaling.describe_tags(Filters=[
                    group_filter, {"Name": filter_name, "Values": values},
                ])["Tags"]
                assert sorted(tag["Key"] for tag in tags) == expected
            for filter_value in [
                {"Name": "key"}, {"Values": ["propagated"]},
                {"Name": "unknown", "Values": []},
                {"Name": "propagate-at-launch", "Values": ["1"]},
                {"Name": "propagate-at-launch", "Values": ["tru e"]},
            ]:
                with pytest.raises(ClientError) as failure:
                    autoscaling.describe_tags(Filters=[group_filter, filter_value])
                assert failure.value.response["Error"]["Code"] == "ValidationError"


def test_describe_tags_pagination_validation(aws_config):
    config = Config(parameter_validation=False, retries={"max_attempts": 1})
    with closing(boto3.client("autoscaling", config=config, **aws_config)) as autoscaling:
        for value in [0, -1, 101, "abc", "", "1.5"]:
            with pytest.raises(ClientError) as failure:
                autoscaling.describe_tags(MaxRecords=value)
            expected = "ValidationError" if isinstance(value, int) else "MalformedInput"
            assert failure.value.response["Error"]["Code"] == expected
        for token in ["invalid", ""]:
            with pytest.raises(ClientError) as failure:
                autoscaling.describe_tags(NextToken=token)
            assert failure.value.response["Error"]["Code"] == "ValidationError"

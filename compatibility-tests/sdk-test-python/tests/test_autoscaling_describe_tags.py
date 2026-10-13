"""Auto Scaling tag Query/XML, filtering, pagination and ownership through boto3."""

import uuid
from contextlib import ExitStack, closing

import boto3
import pytest
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
    for token in ("not-a-token", "-1", "999999"):
        with pytest.raises(ClientError) as error:
            autoscaling.describe_tags(Filters=filters, NextToken=token)
        assert error.value.response["Error"]["Code"] == "InvalidNextToken"


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

"""CloudFormation prefix lists are the same owned resources exposed by EC2."""

import json

from botocore.exceptions import ClientError
import pytest


def test_prefix_list_lifecycle(cloudformation_client, ec2_client, unique_name):
    stack = "prefix-list-" + unique_name
    properties = {
        "PrefixListName": stack,
        "AddressFamily": "IPv4",
        "MaxEntries": 1,
        "Entries": [{"Cidr": "192.0.2.0/24", "Description": "first"}],
        "Tags": [{"Key": "fixture", "Value": unique_name}],
    }
    template = {
        "Resources": {"Firewall": {"Type": "AWS::EC2::PrefixList", "Properties": properties}},
        "Outputs": {
            name: {"Value": {"Fn::GetAtt": ["Firewall", name]}}
            for name in ("PrefixListId", "Arn", "OwnerId")
        },
    }
    template["Outputs"]["Ref"] = {"Value": {"Ref": "Firewall"}}
    waiter_config = {"Delay": 1, "MaxAttempts": 60}
    cloudformation_client.create_stack(StackName=stack, TemplateBody=json.dumps(template))
    try:
        cloudformation_client.get_waiter("stack_create_complete").wait(
            StackName=stack, WaiterConfig=waiter_config
        )
        outputs = {
            row["OutputKey"]: row["OutputValue"]
            for row in cloudformation_client.describe_stacks(StackName=stack)["Stacks"][0]["Outputs"]
        }
        prefix_id = outputs["Ref"]
        actual = ec2_client.describe_managed_prefix_lists(PrefixListIds=[prefix_id])["PrefixLists"][0]
        assert outputs["PrefixListId"] == prefix_id
        assert outputs["Arn"] == actual["PrefixListArn"]
        assert outputs["OwnerId"] == actual["OwnerId"]
        assert actual["Version"] == 1
        assert ec2_client.get_managed_prefix_list_entries(PrefixListId=prefix_id)["Entries"] == properties["Entries"]

        properties["MaxEntries"] = 2
        cloudformation_client.update_stack(StackName=stack, TemplateBody=json.dumps(template))
        cloudformation_client.get_waiter("stack_update_complete").wait(
            StackName=stack, WaiterConfig=waiter_config
        )
        resized = ec2_client.describe_managed_prefix_lists(PrefixListIds=[prefix_id])["PrefixLists"][0]
        assert resized["MaxEntries"] == 2
        assert resized["Version"] == 1

        properties["Entries"] = [{"Cidr": "198.51.100.0/24", "Description": "replacement"}]
        properties.pop("Tags")
        cloudformation_client.update_stack(StackName=stack, TemplateBody=json.dumps(template))
        cloudformation_client.get_waiter("stack_update_complete").wait(
            StackName=stack, WaiterConfig=waiter_config
        )
        assert cloudformation_client.describe_stack_resource(
            StackName=stack, LogicalResourceId="Firewall"
        )["StackResourceDetail"]["PhysicalResourceId"] == prefix_id
        actual = ec2_client.describe_managed_prefix_lists(PrefixListIds=[prefix_id])["PrefixLists"][0]
        assert actual["MaxEntries"] == 2
        assert actual["Version"] == 2
        assert all(tag["Key"] != "fixture" for tag in actual.get("Tags", []))
        assert ec2_client.get_managed_prefix_list_entries(PrefixListId=prefix_id)["Entries"] == properties["Entries"]
    finally:
        cloudformation_client.delete_stack(StackName=stack)
        cloudformation_client.get_waiter("stack_delete_complete").wait(
            StackName=stack, WaiterConfig=waiter_config
        )
    with pytest.raises(ClientError) as missing:
        ec2_client.get_managed_prefix_list_entries(PrefixListId=prefix_id)
    assert missing.value.response["Error"]["Code"] == "InvalidPrefixListID.NotFound"

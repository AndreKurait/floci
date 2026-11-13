"""Stack cleanup preserves a key created outside the stack under a reused name."""

import json

import pytest
from botocore.exceptions import ClientError


@pytest.mark.parametrize("operation", ["delete", "replace"])
@pytest.mark.parametrize("imported", [False, True], ids=["generated", "imported"])
def test_stack_cleanup_preserves_reused_key_name(
    cloudformation_client, ec2_client, ssm_client, unique_name, imported, operation
):
    cfn, ec2, ssm = cloudformation_client, ec2_client, ssm_client
    name = f"key-ownership-{unique_name}"
    properties = {"KeyName": name, "KeyType": "ed25519"}
    if imported:
        seed = ec2.create_key_pair(KeyName=f"{name}-seed", KeyType="ed25519")[
            "KeyPairId"
        ]
        try:
            properties["PublicKeyMaterial"] = ec2.describe_key_pairs(
                KeyPairIds=[seed], IncludePublicKey=True
            )["KeyPairs"][0]["PublicKey"]
        finally:
            ec2.delete_key_pair(KeyPairId=seed)

    template = {
        "Resources": {"Key": {"Type": "AWS::EC2::KeyPair", "Properties": properties}},
        "Outputs": {"Id": {"Value": {"Fn::GetAtt": ["Key", "KeyPairId"]}}},
    }
    stack_id = None
    replacement_id = None
    deleted = False

    def wait(operation):
        cfn.get_waiter(f"stack_{operation}_complete").wait(
            StackName=stack_id, WaiterConfig={"Delay": 1, "MaxAttempts": 120}
        )

    def assert_parameter_missing(key_id):
        with pytest.raises(ClientError) as missing:
            ssm.get_parameter(Name=f"/ec2/keypair/{key_id}")
        assert missing.value.response["Error"]["Code"] == "ParameterNotFound"

    try:
        stack_id = cfn.create_stack(
            StackName=name, TemplateBody=json.dumps(template)
        )["StackId"]
        wait("create")
        outputs = cfn.describe_stacks(StackName=stack_id)["Stacks"][0]["Outputs"]
        original_id = next(
            item["OutputValue"] for item in outputs if item["OutputKey"] == "Id"
        )
        assert ec2.describe_key_pairs(KeyNames=[name])["KeyPairs"][0][
            "KeyPairId"
        ] == original_id
        if imported:
            assert_parameter_missing(original_id)
        else:
            assert ssm.get_parameter(Name=f"/ec2/keypair/{original_id}")[
                "Parameter"
            ]["Type"] == "SecureString"

        ec2.delete_key_pair(KeyPairId=original_id)
        replacement_id = ec2.create_key_pair(
            KeyName=name, KeyType="ed25519"
        )["KeyPairId"]
        assert replacement_id != original_id
        ssm.put_parameter(
            Name=f"/ec2/keypair/{replacement_id}",
            Type="String",
            Value="external-owner",
        )

        if operation == "replace":
            properties["KeyName"] = f"{name}-updated"
            cfn.update_stack(StackName=stack_id, TemplateBody=json.dumps(template))
            wait("update")
        else:
            cfn.delete_stack(StackName=stack_id)
            wait("delete")
            deleted = True
        assert ec2.describe_key_pairs(KeyNames=[name])["KeyPairs"][0][
            "KeyPairId"
        ] == replacement_id
        assert_parameter_missing(original_id)
        assert ssm.get_parameter(Name=f"/ec2/keypair/{replacement_id}")[
            "Parameter"
        ]["Value"] == "external-owner"
    finally:
        try:
            if stack_id is not None and not deleted:
                cfn.delete_stack(StackName=stack_id)
                wait("delete")
        finally:
            if replacement_id is not None:
                try:
                    ec2.delete_key_pair(KeyPairId=replacement_id)
                finally:
                    try:
                        ssm.delete_parameter(Name=f"/ec2/keypair/{replacement_id}")
                    except ClientError as error:
                        if error.response["Error"]["Code"] != "ParameterNotFound":
                            raise

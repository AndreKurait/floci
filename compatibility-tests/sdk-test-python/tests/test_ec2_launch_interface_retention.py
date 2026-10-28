"""Launch-time ENI retention through the public EC2 SDK."""
from contextlib import ExitStack
import uuid

import pytest
from botocore.exceptions import ClientError


def _terminate(client, instance_id):
    client.terminate_instances(InstanceIds=[instance_id])
    client.get_waiter("instance_terminated").wait(
        InstanceIds=[instance_id], WaiterConfig={"Delay": 1, "MaxAttempts": 30})


def _delete_interfaces(client, interface_ids):
    if interface_ids:
        interfaces = client.describe_network_interfaces(
            Filters=[{"Name": "network-interface-id", "Values": interface_ids}])["NetworkInterfaces"]
        for interface in interfaces:
            client.delete_network_interface(NetworkInterfaceId=interface["NetworkInterfaceId"])


def _release_address(client, allocation):
    address = client.describe_addresses(AllocationIds=[allocation])["Addresses"][0]
    if address.get("AssociationId"):
        client.disassociate_address(AssociationId=address["AssociationId"])
    client.release_address(AllocationId=allocation)


def _instances(client):
    return {
        instance["InstanceId"]: instance
        for reservation in client.describe_instances()["Reservations"]
        for instance in reservation["Instances"]
    }


@pytest.mark.parametrize("via_template", [False, True])
@pytest.mark.parametrize("supplied_interface", [False, True])
@pytest.mark.parametrize("delete", [None, False, True])
def test_primary_interface_retention(ec2_client, via_template, supplied_interface, delete):
    ec2 = ec2_client
    name = "interface-retention-" + uuid.uuid4().hex
    expected_delete = not supplied_interface if delete is None else delete
    with ExitStack() as cleanup:
        vpc = ec2.create_vpc(CidrBlock="10.238.0.0/24")["Vpc"]["VpcId"]
        cleanup.callback(ec2.delete_vpc, VpcId=vpc)
        group = ec2.create_security_group(
            GroupName=name, Description="replacement interface group", VpcId=vpc)["GroupId"]
        cleanup.callback(ec2.delete_security_group, GroupId=group)
        subnet = ec2.create_subnet(VpcId=vpc, CidrBlock="10.238.0.0/26")["Subnet"]["SubnetId"]
        cleanup.callback(ec2.delete_subnet, SubnetId=subnet)
        image = ec2.register_image(
            Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1")["ImageId"]
        cleanup.callback(ec2.deregister_image, ImageId=image)
        interface_ids = []
        cleanup.callback(_delete_interfaces, ec2, interface_ids)
        network = {"DeviceIndex": 0}
        if supplied_interface:
            interface = ec2.create_network_interface(SubnetId=subnet)["NetworkInterface"]
            interface_ids.append(interface["NetworkInterfaceId"])
            network["NetworkInterfaceId"] = interface_ids[0]
        else:
            network["SubnetId"] = subnet
        if delete is not None:
            network["DeleteOnTermination"] = delete
        launch = {"ImageId": image, "InstanceType": "t3.nano", "NetworkInterfaces": [network]}
        if via_template:
            template = ec2.create_launch_template(
                LaunchTemplateName=name, LaunchTemplateData=launch)["LaunchTemplate"]["LaunchTemplateId"]
            cleanup.callback(ec2.delete_launch_template, LaunchTemplateId=template)
            launch = {"LaunchTemplate": {"LaunchTemplateId": template}}
        if supplied_interface and delete is True:
            before = _instances(ec2)
            with pytest.raises(ClientError) as error:
                ec2.run_instances(MinCount=1, MaxCount=1, **launch)
            assert error.value.response["Error"]["Code"] == "InvalidParameterCombination"
            assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
            assert _instances(ec2) == before
            unchanged = ec2.describe_network_interfaces(
                NetworkInterfaceIds=interface_ids)["NetworkInterfaces"][0]
            assert unchanged["Status"] == "available" and not unchanged.get("Attachment")
            return
        instance = ec2.run_instances(MinCount=1, MaxCount=1, **launch)["Instances"][0]
        instance_id = instance["InstanceId"]
        cleanup.callback(_terminate, ec2, instance_id)
        eni = instance["NetworkInterfaces"][0]
        eni_id = eni["NetworkInterfaceId"]
        if eni_id not in interface_ids:
            interface_ids.append(eni_id)
        assert eni["Attachment"]["DeleteOnTermination"] == expected_delete
        ec2.create_tags(Resources=[eni_id], Tags=[{"Key": "Name", "Value": name}])
        allocation = ec2.allocate_address(Domain="vpc")["AllocationId"]
        cleanup.callback(_release_address, ec2, allocation)
        association = ec2.associate_address(
            AllocationId=allocation, InstanceId=instance_id)["AssociationId"]

        def interfaces():
            return ec2.describe_network_interfaces(
                Filters=[{"Name": "network-interface-id", "Values": [eni_id]}])["NetworkInterfaces"]

        ec2.modify_instance_attribute(InstanceId=instance_id, Groups=[group])
        before = interfaces()[0]
        assert [entry["GroupId"] for entry in before["Groups"]] == [group]
        with pytest.raises(ClientError):
            ec2.modify_instance_attribute(InstanceId=instance_id, Groups=["sg-00000000000000000"])
        assert interfaces()[0]["Groups"] == before["Groups"]
        assert before["Attachment"]["DeleteOnTermination"] == expected_delete
        assert before["TagSet"] == [{"Key": "Name", "Value": name}]
        ec2.stop_instances(InstanceIds=[instance_id])
        ec2.get_waiter("instance_stopped").wait(
            InstanceIds=[instance_id], WaiterConfig={"Delay": 1, "MaxAttempts": 30})
        assert interfaces()[0]["Attachment"]["InstanceId"] == instance_id
        _terminate(ec2, instance_id)
        _terminate(ec2, instance_id)
        address = ec2.describe_addresses(AllocationIds=[allocation])["Addresses"][0]
        if expected_delete:
            assert interfaces() == []
            assert not address.get("AssociationId")
            assert not address.get("NetworkInterfaceId")
        else:
            retained = interfaces()[0]
            assert retained["Status"] == "available"
            assert not retained.get("Attachment")
            for key in ("PrivateIpAddress", "SubnetId", "VpcId", "Groups", "TagSet"):
                assert retained[key] == before[key]
            assert retained["Association"]["AssociationId"] == association
            assert address["AssociationId"] == association
            assert address["NetworkInterfaceId"] == eni_id
            assert not address.get("InstanceId")
            reused = ec2.run_instances(
                ImageId=image, InstanceType="t3.nano", MinCount=1, MaxCount=1,
                NetworkInterfaces=[{"DeviceIndex": 0, "NetworkInterfaceId": eni_id}])["Instances"][0]
            cleanup.callback(_terminate, ec2, reused["InstanceId"])
            assert reused["NetworkInterfaces"][0]["NetworkInterfaceId"] == eni_id
            assert interfaces()[0]["Attachment"]["InstanceId"] == reused["InstanceId"]
            assert interfaces()[0]["Attachment"]["DeleteOnTermination"] is False


@pytest.mark.parametrize("delete", [False, True])
def test_request_interface_replaces_template_retention(ec2_client, delete):
    ec2 = ec2_client
    name = "interface-override-" + uuid.uuid4().hex
    with ExitStack() as cleanup:
        image = ec2.register_image(
            Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1")["ImageId"]
        cleanup.callback(ec2.deregister_image, ImageId=image)
        subnet = ec2.describe_subnets(
            Filters=[{"Name": "default-for-az", "Values": ["true"]}])["Subnets"][0]["SubnetId"]
        template = ec2.create_launch_template(
            LaunchTemplateName=name, LaunchTemplateData={
                "ImageId": image, "InstanceType": "t3.nano",
                "NetworkInterfaces": [{
                    "DeviceIndex": 0, "SubnetId": subnet, "DeleteOnTermination": not delete,
                }],
            })["LaunchTemplate"]["LaunchTemplateId"]
        cleanup.callback(ec2.delete_launch_template, LaunchTemplateId=template)
        instance = ec2.run_instances(
            LaunchTemplate={"LaunchTemplateId": template}, MinCount=1, MaxCount=1,
            NetworkInterfaces=[{"DeviceIndex": 0, "SubnetId": subnet, "DeleteOnTermination": delete}]
        )["Instances"][0]
        eni_id = instance["NetworkInterfaces"][0]["NetworkInterfaceId"]
        cleanup.callback(_delete_interfaces, ec2, [eni_id])
        cleanup.callback(_terminate, ec2, instance["InstanceId"])
        assert instance["NetworkInterfaces"][0]["Attachment"]["DeleteOnTermination"] == delete
        _terminate(ec2, instance["InstanceId"])
        retained = ec2.describe_network_interfaces(
            Filters=[{"Name": "network-interface-id", "Values": [eni_id]}])["NetworkInterfaces"]
        assert bool(retained) != delete

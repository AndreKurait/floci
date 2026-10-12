"""Primary ENI association filters verified against live EC2 on 2026-10-12."""

import uuid
from contextlib import ExitStack


def test_network_interface_association_filters(ec2_client):
    with ExitStack() as cleanup:
        vpc = ec2_client.create_vpc(CidrBlock="10.237.0.0/24")["Vpc"]["VpcId"]
        cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
        subnet = ec2_client.create_subnet(
            VpcId=vpc, CidrBlock="10.237.0.0/26")["Subnet"]["SubnetId"]
        cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
        image = ec2_client.register_image(
            Name="eni-filter-" + uuid.uuid4().hex, Architecture="x86_64",
            RootDeviceName="/dev/sda1")["ImageId"]
        cleanup.callback(ec2_client.deregister_image, ImageId=image)
        instances = ec2_client.run_instances(
            ImageId=image, InstanceType="t3.nano", MinCount=2, MaxCount=2,
            SubnetId=subnet)["Instances"]
        cleanup.callback(ec2_client.terminate_instances,
                         InstanceIds=[instance["InstanceId"] for instance in instances])
        eni_ids = [instance["NetworkInterfaces"][0]["NetworkInterfaceId"]
                   for instance in instances]
        allocation = ec2_client.allocate_address(Domain="vpc")["AllocationId"]
        cleanup.callback(ec2_client.release_address, AllocationId=allocation)
        association = ec2_client.associate_address(
            AllocationId=allocation, InstanceId=instances[0]["InstanceId"])["AssociationId"]
        # Floci resolves a reachable address on association; read the actual association.
        interface = ec2_client.describe_network_interfaces(
            NetworkInterfaceIds=[eni_ids[0]])["NetworkInterfaces"][0]
        address = next(ip["Association"] for ip in interface["PrivateIpAddresses"] if ip["Primary"])
        public_ip, owner = address["PublicIp"], address["IpOwnerId"]

        def matched(**filters):
            result = ec2_client.describe_network_interfaces(
                NetworkInterfaceIds=eni_ids,
                Filters=[{"Name": key, "Values": value} for key, value in filters.items()])
            return [item["NetworkInterfaceId"] for item in result["NetworkInterfaces"]]

        try:
            assert matched(**{"association.public-ip": [public_ip]}) == [eni_ids[0]]
            assert matched(**{"association.public-ip": ["192.0.2.1"]}) == []
            assert matched(**{"association.ip-owner-id": [owner]}) == [eni_ids[0]]
            assert matched(**{"association.ip-owner-id": ["wrong-owner"]}) == []
            assert matched(**{"association.public-ip": ["192.0.2.1", public_ip]}) == [eni_ids[0]]
            assert matched(**{"association.public-ip": ["*"],
                              "association.ip-owner-id": ["wrong-owner"]}) == []
        finally:
            ec2_client.disassociate_address(AssociationId=association)
        assert matched(**{"association.public-ip": ["*"]}) == []
        assert matched(**{"association.ip-owner-id": [owner]}) == []

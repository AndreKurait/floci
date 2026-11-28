package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AttachInternetGatewayRequest;
import software.amazon.awssdk.services.ec2.model.CreateNetworkAclRequest;
import software.amazon.awssdk.services.ec2.model.CreateRouteTableRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.DeleteInternetGatewayRequest;
import software.amazon.awssdk.services.ec2.model.DeleteNetworkAclRequest;
import software.amazon.awssdk.services.ec2.model.DeleteRouteTableRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSubnetRequest;
import software.amazon.awssdk.services.ec2.model.DeleteVpcRequest;
import software.amazon.awssdk.services.ec2.model.DetachInternetGatewayRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.SubnetIpv6CidrBlockAssociation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.Vpc;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ec2VpcDeleteDependencyTest {

    @Test
    void createVpcDryRunDoesNotStoreVpc() {
        try (Ec2Client ec2 = TestFixtures.ec2Client("123456789012")) {
            String name = TestFixtures.uniqueName("dryrun-vpc");
            CreateVpcRequest request = CreateVpcRequest.builder().cidrBlock("10.83.0.0/16")
                    .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.VPC)
                            .tags(Tag.builder().key("Name").value(name).build()).build()).build();
            List<String> before = ec2.describeVpcs().vpcs().stream().map(Vpc::vpcId).toList();
            try {
                assertDryRun(() -> ec2.createVpc(request.toBuilder().dryRun(true).build()));
                assertThat(ec2.describeVpcs().vpcs()).extracting(Vpc::vpcId)
                        .containsExactlyInAnyOrderElementsOf(before);
                assertThat(ec2.createVpc(request).vpc().cidrBlock()).isEqualTo("10.83.0.0/16");
            } finally {
                ec2.describeVpcs(r -> r.filters(Filter.builder().name("tag:Name").values(name).build()))
                        .vpcs().forEach(vpc -> ec2.deleteVpc(r -> r.vpcId(vpc.vpcId())));
            }
        }
    }

    @Test
    void createIpv6OnlySubnetDryRunDoesNotStoreSubnet() throws Exception {
        try (Ec2Client ec2 = TestFixtures.ec2Client("123456789012")) {
            Vpc vpc = ec2.createVpc(r -> r.cidrBlock("10.87.0.0/16")
                    .amazonProvidedIpv6CidrBlock(true)).vpc();
            try {
                String ipv6Cidr = vpc.ipv6CidrBlockAssociationSet().get(0).ipv6CidrBlock().replace("/56", "/64");
                CreateSubnetRequest request = CreateSubnetRequest.builder().vpcId(vpc.vpcId())
                        .ipv6CidrBlock(ipv6Cidr).ipv6Native(true).build();
                assertDryRun(() -> ec2.createSubnet(request.toBuilder().dryRun(true).build()));
                assertThat(ec2.describeSubnets(r -> r.filters(vpcFilter(vpc.vpcId()))).subnets()).isEmpty();
                List<SubnetIpv6CidrBlockAssociation> associations =
                        ec2.createSubnet(request).subnet().ipv6CidrBlockAssociationSet();
                assertThat(associations).hasSize(1);
                String createdCidr = associations.get(0).ipv6CidrBlock();
                assertThat(createdCidr.split("/")[1]).isEqualTo("64");
                assertThat(InetAddress.getByName(createdCidr.split("/")[0]))
                        .isEqualTo(InetAddress.getByName(ipv6Cidr.split("/")[0]));
            } finally {
                ec2.describeSubnets(r -> r.filters(vpcFilter(vpc.vpcId()))).subnets()
                        .forEach(subnet -> ec2.deleteSubnet(r -> r.subnetId(subnet.subnetId())));
                ec2.deleteVpc(r -> r.vpcId(vpc.vpcId()));
            }
        }
    }

    @Test
    void createSubnetDryRunDoesNotStoreSubnetOrConsumeItsRange() {
        try (Ec2Client ec2 = TestFixtures.ec2Client("123456789012")) {
            String vpc = ec2.createVpc(r -> r.cidrBlock("10.84.0.0/16")).vpc().vpcId();
            CreateSubnetRequest request = CreateSubnetRequest.builder().vpcId(vpc).cidrBlock("10.84.1.0/24").build();
            try {
                assertDryRun(() -> ec2.createSubnet(request.toBuilder().dryRun(true).build()));
                assertThat(ec2.describeSubnets(r -> r.filters(vpcFilter(vpc))).subnets()).isEmpty();
                assertThat(ec2.createSubnet(request).subnet().cidrBlock()).isEqualTo("10.84.1.0/24");
                assertDryRun(() -> ec2.createSubnet(request.toBuilder().dryRun(true).build()));
            } finally {
                ec2.describeSubnets(r -> r.filters(vpcFilter(vpc))).subnets()
                        .forEach(subnet -> ec2.deleteSubnet(r -> r.subnetId(subnet.subnetId())));
                ec2.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    @Test
    void createSecurityGroupDryRunDoesNotStoreGroupOrReserveItsName() {
        try (Ec2Client ec2 = TestFixtures.ec2Client("123456789012")) {
            String vpc = ec2.createVpc(r -> r.cidrBlock("10.85.0.0/16")).vpc().vpcId();
            CreateSecurityGroupRequest request = CreateSecurityGroupRequest.builder().vpcId(vpc)
                    .groupName("dryrun-group").description("public SDK dryrun contract").build();
            try {
                assertDryRun(() -> ec2.createSecurityGroup(request.toBuilder().dryRun(true).build()));
                assertThat(ec2.describeSecurityGroups(r -> r.filters(vpcFilter(vpc))).securityGroups()).hasSize(1);
                String group = ec2.createSecurityGroup(request).groupId();
                assertThat(ec2.describeSecurityGroups(r -> r.groupIds(group)).securityGroups()).hasSize(1);
                assertDryRun(() -> ec2.createSecurityGroup(request.toBuilder().dryRun(true).build()));
            } finally {
                ec2.describeSecurityGroups(r -> r.filters(vpcFilter(vpc))).securityGroups().stream()
                        .filter(group -> !"default".equals(group.groupName()))
                        .forEach(group -> ec2.deleteSecurityGroup(r -> r.groupId(group.groupId())));
                ec2.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    private static Filter vpcFilter(String vpc) {
        return Filter.builder().name("vpc-id").values(vpc).build();
    }

    private static void assertDryRun(Runnable request) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(Ec2Exception.class, error -> {
            assertThat(error.statusCode()).isEqualTo(412);
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("DryRunOperation");
            assertThat(error.requestId()).isNotBlank();
        });
    }

    @Test
    void deleteVpcFailsWithDependencyViolationUntilCallerCreatedResourcesAreRemoved() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(CreateVpcRequest.builder().cidrBlock("10.82.0.0/16").build())
                    .vpc().vpcId();
            String subnetId = ec2.createSubnet(CreateSubnetRequest.builder()
                            .vpcId(vpcId).cidrBlock("10.82.1.0/24").build())
                    .subnet().subnetId();
            String groupId = ec2.createSecurityGroup(CreateSecurityGroupRequest.builder()
                            .vpcId(vpcId).groupName("dependency-violation-sg").description("dependency test").build())
                    .groupId();
            String routeTableId = ec2.createRouteTable(CreateRouteTableRequest.builder().vpcId(vpcId).build())
                    .routeTable().routeTableId();
            String aclId = ec2.createNetworkAcl(CreateNetworkAclRequest.builder().vpcId(vpcId).build())
                    .networkAcl().networkAclId();
            String igwId = ec2.createInternetGateway().internetGateway().internetGatewayId();
            ec2.attachInternetGateway(AttachInternetGatewayRequest.builder()
                    .internetGatewayId(igwId).vpcId(vpcId).build());

            assertDependencyViolation(ec2, vpcId);

            ec2.deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnetId).build());
            assertDependencyViolation(ec2, vpcId);
            ec2.deleteSecurityGroup(DeleteSecurityGroupRequest.builder().groupId(groupId).build());
            assertDependencyViolation(ec2, vpcId);
            ec2.deleteRouteTable(DeleteRouteTableRequest.builder().routeTableId(routeTableId).build());
            assertDependencyViolation(ec2, vpcId);
            ec2.deleteNetworkAcl(DeleteNetworkAclRequest.builder().networkAclId(aclId).build());
            assertDependencyViolation(ec2, vpcId);
            ec2.detachInternetGateway(DetachInternetGatewayRequest.builder()
                    .internetGatewayId(igwId).vpcId(vpcId).build());
            ec2.deleteInternetGateway(DeleteInternetGatewayRequest.builder().internetGatewayId(igwId).build());

            ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build());
            Filter vpcFilter = Filter.builder().name("vpc-id").values(vpcId).build();
            assertThat(ec2.describeVpcs(request -> request.filters(vpcFilter)).vpcs()).isEmpty();
        }
    }

    private static void assertDependencyViolation(Ec2Client ec2, String vpcId) {
        assertThatThrownBy(() -> ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build()))
                .isInstanceOfSatisfying(Ec2Exception.class,
                        e -> assertThat(e.awsErrorDetails().errorCode()).isEqualTo("DependencyViolation"));
    }
}

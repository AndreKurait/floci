package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2SecurityGroupDeletionIntegrationTest {
    private static final String REGION = "us-west-2";
    private static final String ACCOUNT = "555566667788";

    @Inject
    Ec2Service service;

    @Test
    void defaultGroupCannotBeDeleted() {
        RequestScopes.runAs(ACCOUNT, () -> {
            String vpc = service.createDefaultVpc(REGION).getVpcId();
            String group = service.describeSecurityGroups(
                    REGION, List.of(), List.of("default"),
                    Map.of("vpc-id", List.of(vpc))).getFirst().getGroupId();
            AwsException error = assertThrows(AwsException.class, () ->
                    service.deleteSecurityGroup(REGION, group, null, false));
            assertEquals("Client.CannotDelete", error.getErrorCode());
            assertEquals(group, service.describeSecurityGroups(
                    REGION, List.of(group), List.of(), Map.of()).getFirst().getGroupId());
        });
    }

    @Test
    void nameWithoutDefaultVpcCannotSelectACustomVpcGroup() {
        RequestScopes.runAs(ACCOUNT, () -> {
            String defaultVpc = service.createDefaultVpc(REGION).getVpcId();
            for (Subnet subnet : service.describeSubnets(
                    REGION, List.of(), Map.of("vpc-id", List.of(defaultVpc)))) {
                service.deleteSubnet(REGION, subnet.getSubnetId());
            }
            for (InternetGateway gateway : service.describeInternetGateways(
                    REGION, List.of(), Map.of("attachment.vpc-id", List.of(defaultVpc)))) {
                service.detachInternetGateway(REGION, gateway.getInternetGatewayId(), defaultVpc);
                service.deleteInternetGateway(REGION, gateway.getInternetGatewayId());
            }
            service.deleteVpc(REGION, defaultVpc);
            String customVpc = service.createVpc(REGION, "10.229.0.0/24", false).getVpcId();
            SecurityGroup group = service.createSecurityGroup(
                    REGION, "without-default-" + UUID.randomUUID(), "owned test group", customVpc);
            try {
                AwsException error = assertThrows(AwsException.class, () ->
                        service.deleteSecurityGroup(REGION, null, group.getGroupName(), false));
                assertEquals("VPCIdNotSpecified", error.getErrorCode());
                assertEquals(group.getGroupId(), service.describeSecurityGroups(
                        REGION, List.of(group.getGroupId()), List.of(), Map.of()).getFirst().getGroupId());
            } finally {
                service.deleteSecurityGroup(REGION, group.getGroupId());
                service.deleteVpc(REGION, customVpc);
                service.createDefaultVpc(REGION);
            }
        });
    }

    @Test
    void selfReferenceAllowsDeletionAndOwnRulesAreRemoved() {
        RequestScopes.runAs(ACCOUNT, () -> {
            String vpc = service.createVpc(REGION, "10.230.0.0/24", false).getVpcId();
            SecurityGroup group = service.createSecurityGroup(
                    REGION, "self-reference-" + UUID.randomUUID(), "owned test group", vpc);
            try {
                UserIdGroupPair pair = new UserIdGroupPair();
                pair.setGroupId(group.getGroupId());
                IpPermission permission = new IpPermission();
                permission.setIpProtocol("-1");
                permission.getUserIdGroupPairs().add(pair);
                service.authorizeSecurityGroupIngress(REGION, group.getGroupId(), List.of(permission));
                assertFalse(service.describeSecurityGroupRules(
                        REGION, List.of(group.getGroupId()), List.of()).isEmpty());
                service.deleteSecurityGroup(REGION, group.getGroupId());
                assertTrue(service.describeSecurityGroupRules(
                        REGION, List.of(group.getGroupId()), List.of()).isEmpty());
            } finally {
                if (!service.describeSecurityGroups(
                        REGION, List.of(group.getGroupId()), List.of(), Map.of()).isEmpty()) {
                    service.deleteSecurityGroup(REGION, group.getGroupId());
                }
                service.deleteVpc(REGION, vpc);
            }
        });
    }
}

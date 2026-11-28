package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EC2 duplicate permission lifecycle")
class Ec2DuplicateRuleTest {

    private Ec2Client ec2;
    private String group;
    private String vpc;

    @BeforeEach
    void client() {
        ec2 = TestFixtures.ec2Client();
    }

    @AfterEach
    void cleanup() {
        try {
            if (group != null) {
                ec2.deleteSecurityGroup(request -> request.groupId(group));
            }
            if (vpc != null) {
                ec2.deleteVpc(request -> request.vpcId(vpc));
            }
        } finally {
            ec2.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("rejects a duplicate batch without writes and permits authorization after revocation")
    void duplicateBatchAndReauthorization(boolean egress) {
        vpc = ec2.createVpc(request -> request.cidrBlock("10.92.0.0/16")).vpc().vpcId();
        group = ec2.createSecurityGroup(request -> request.vpcId(vpc)
                .groupName(TestFixtures.uniqueName("duplicate-permission"))
                .description("Duplicate permission lifecycle")).groupId();
        IpPermission original = permission("192.0.2.27/24", "original");
        SecurityGroupRule created = authorize(egress, original).get(0);
        assertThat(created.cidrIpv4()).isEqualTo("192.0.2.0/24");
        Set<SecurityGroupRule> before = rules();

        assertThatThrownBy(() -> authorize(egress, permission("198.51.100.8/32", "new"),
                permission("192.0.2.0/24", "different description")))
                .isInstanceOfSatisfying(Ec2Exception.class, failure -> {
                    assertThat(failure.statusCode()).isEqualTo(400);
                    assertThat(failure.awsErrorDetails().errorCode()).isEqualTo("InvalidPermission.Duplicate");
                });
        assertThat(rules()).isEqualTo(before);

        if (egress) {
            ec2.revokeSecurityGroupEgress(request -> request.groupId(group).ipPermissions(original));
        } else {
            ec2.revokeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(original));
        }
        assertThat(rules()).noneMatch(rule -> created.securityGroupRuleId().equals(rule.securityGroupRuleId()));
        assertThat(authorize(egress, original).get(0).securityGroupRuleId())
                .isNotEqualTo(created.securityGroupRuleId());
    }

    private List<SecurityGroupRule> authorize(boolean egress, IpPermission... permissions) {
        return egress
                ? ec2.authorizeSecurityGroupEgress(request -> request.groupId(group).ipPermissions(permissions))
                        .securityGroupRules()
                : ec2.authorizeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(permissions))
                        .securityGroupRules();
    }

    private Set<SecurityGroupRule> rules() {
        return Set.copyOf(ec2.describeSecurityGroupRules(request ->
                request.filters(filter -> filter.name("group-id").values(group))).securityGroupRules());
    }

    private static IpPermission permission(String cidr, String description) {
        return IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                .ipRanges(IpRange.builder().cidrIp(cidr).description(description).build()).build();
    }
}

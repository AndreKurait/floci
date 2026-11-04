package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.Ipv6Range;
import software.amazon.awssdk.services.ec2.model.PrefixListId;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRuleDescription;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.UserIdGroupPair;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationRuleUpdateIdentityIntegrationTest {

    private static final String CIDR = "192.0.2.10/32";
    private final String name = "cfn-rule-identity-" + UUID.randomUUID();
    private CloudFormationClient cfn;
    private Ec2Client ec2;
    private String account;
    private String vpcId;
    private String groupId;
    private String otherGroupId;
    private String prefixListId;
    private String namedGroupId;
    private String stackId;

    @BeforeEach
    void createFoundation() {
        account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
        vpcId = ec2.createVpc(request -> request.cidrBlock("10.94.0.0/16")).vpc().vpcId();
        groupId = ec2.createSecurityGroup(request -> request.vpcId(vpcId)
                .groupName(name).description("Rule identity")).groupId();
        otherGroupId = ec2.createSecurityGroup(request -> request.vpcId(vpcId)
                .groupName(name + "-other").description("Unrelated group")).groupId();
    }

    @AfterEach
    void cleanup() {
        try {
            if (stackId != null) {
                cfn.deleteStack(request -> request.stackName(stackId));
                awaitStatus("DELETE_COMPLETE");
            }
            for (String id : List.of(groupId, otherGroupId)) {
                ec2.deleteSecurityGroup(request -> request.groupId(id));
            }
            if (prefixListId != null) {
                ec2.deleteManagedPrefixList(request -> request.prefixListId(prefixListId));
            }
            if (namedGroupId != null) {
                ec2.deleteSecurityGroup(request -> request.groupId(namedGroupId));
            }
            ec2.deleteVpc(request -> request.vpcId(vpcId));
        } finally {
            ec2.close();
            cfn.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void descriptionChangesKeepRuleIdentityAndTags(boolean egress) {
        create(egress);
        String original = ruleId();
        ec2.createTags(request -> request.resources(original)
                .tags(Tag.builder().key("ownership").value("retained").build()));
        List<Tag> tags = rule(original).tags();

        update(egress, "updated description", "before", CIDR);
        assertEquals(original, ruleId());
        assertDescription(original, egress, "updated description");
        assertEquals(tags, rule(original).tags());
        assertReferenceOutputs(original);

        update(egress, null, "before", CIDR);
        assertEquals(original, ruleId());
        assertDescription(original, egress, null);
        assertEquals(tags, rule(original).tags());
        assertReferenceOutputs(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedStackUpdateLeavesTheRuleUnchanged(boolean egress) {
        create(egress);
        SecurityGroupRule original = rule(ruleId());

        update(egress, "original", "after", CIDR);

        assertEquals(original.securityGroupRuleId(), ruleId());
        assertEquals(original, rule(ruleId()));
        assertEquals("after", output("Other"));
        assertReferenceOutputs(original.securityGroupRuleId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedParameterReplacesAccessInsteadOfComparingRawProperties(boolean egress) {
        create(egress);
        String original = ruleId();

        update(egress, "original", "before", "192.0.2.11/32");

        String replacement = ruleId();
        assertNotEquals(original, replacement);
        List<SecurityGroupRule> remaining = ec2.describeSecurityGroupRules(request ->
                        request.filters(filter -> filter.name("group-id").values(groupId)))
                .securityGroupRules();
        assertTrue(remaining.stream().noneMatch(candidate -> original.equals(candidate.securityGroupRuleId())));
        SecurityGroupRule changed = rule(replacement);
        assertEquals("192.0.2.11/32", changed.cidrIpv4());
        assertEquals(egress, changed.isEgress());
        assertEquals(443, changed.fromPort());
        assertEquals("original", changed.description());
        assertReferenceOutputs(replacement);
        assertEquals(1, remaining.stream().filter(candidate -> candidate.fromPort() != null
                        && candidate.fromPort() == 443).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void descriptionApiUpdatesBothViewsByIdAndPermission(boolean egress) {
        create(egress);
        String id = ruleId();
        updateDescription(ec2, groupId, egress, id, "by id");
        assertDescription(id, egress, "by id");

        IpPermission permission = IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                .ipRanges(IpRange.builder().cidrIp(CIDR).description("by permission").build()).build();
        if (egress) {
            ec2.updateSecurityGroupRuleDescriptionsEgress(request ->
                    request.groupId(groupId).ipPermissions(permission));
        } else {
            ec2.updateSecurityGroupRuleDescriptionsIngress(request ->
                    request.groupId(groupId).ipPermissions(permission));
        }
        assertDescription(id, egress, "by permission");

        updateDescription(ec2, groupId, egress, id, null);
        assertDescription(id, egress, null);
        assertEquals(id, ruleId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void descriptionApiRejectsUnownedOrWrongDirectionRules(boolean egress) {
        create(egress);
        String id = ruleId();
        SecurityGroupRule original = rule(id);

        assertEquals(400, assertThrows(Ec2Exception.class, () ->
                updateDescription(ec2, otherGroupId, egress, id, "foreign group")).statusCode());
        assertEquals(400, assertThrows(Ec2Exception.class, () ->
                updateDescription(ec2, groupId, !egress, id, "wrong direction")).statusCode());
        assertEquals(400, assertThrows(Ec2Exception.class, () ->
                updateDescription(ec2, groupId, egress, "sgr-00000000000000000", "missing")).statusCode());
        String foreignAccount = account.equals("111111111111") ? "222222222222" : "111111111111";
        try (Ec2Client foreign = AwsTestClients.create(Ec2Client.builder(), foreignAccount, Region.US_EAST_1);
             Ec2Client otherRegion = AwsTestClients.create(Ec2Client.builder(), account, Region.US_WEST_2)) {
            assertEquals(400, assertThrows(Ec2Exception.class, () ->
                    updateDescription(foreign, groupId, egress, id, "foreign account")).statusCode());
            assertEquals(400, assertThrows(Ec2Exception.class, () ->
                    updateDescription(otherRegion, groupId, egress, id, "foreign region")).statusCode());
        }
        assertEquals(original, rule(id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void descriptionApiUpdatesEveryPeerTypeWithoutReplacingPermissions(boolean egress) {
        prefixListId = ec2.createManagedPrefixList(request -> request.prefixListName(name)
                .addressFamily("IPv4").maxEntries(1).entries(entry -> entry.cidr(CIDR)))
                .prefixList().prefixListId();
        IpPermission original = multiSourcePermission("original");
        List<SecurityGroupRule> rules = egress
                ? ec2.authorizeSecurityGroupEgress(request -> request.groupId(groupId).ipPermissions(original))
                        .securityGroupRules()
                : ec2.authorizeSecurityGroupIngress(request -> request.groupId(groupId).ipPermissions(original))
                        .securityGroupRules();
        assertEquals(4, rules.size());

        IpPermission updated = multiSourcePermission("every source");
        if (egress) {
            ec2.updateSecurityGroupRuleDescriptionsEgress(request -> request.groupId(groupId).ipPermissions(updated));
        } else {
            ec2.updateSecurityGroupRuleDescriptionsIngress(request -> request.groupId(groupId).ipPermissions(updated));
        }
        for (SecurityGroupRule originalRule : rules) {
            SecurityGroupRule current = rule(originalRule.securityGroupRuleId());
            assertEquals(originalRule.toBuilder().description("every source").build(), current);
        }
        IpPermission stored = permission(egress, 8443);
        assertEquals("every source", stored.ipRanges().getFirst().description());
        assertEquals("every source", stored.ipv6Ranges().getFirst().description());
        assertEquals("every source", stored.prefixListIds().getFirst().description());
        assertEquals("every source", stored.userIdGroupPairs().getFirst().description());

        for (SecurityGroupRule originalRule : rules) {
            updateDescription(ec2, groupId, egress, originalRule.securityGroupRuleId(), null);
            assertNull(rule(originalRule.securityGroupRuleId()).description());
        }
        stored = permission(egress, 8443);
        assertNull(stored.ipRanges().getFirst().description());
        assertNull(stored.ipv6Ranges().getFirst().description());
        assertNull(stored.prefixListIds().getFirst().description());
        assertNull(stored.userIdGroupPairs().getFirst().description());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void descriptionApiValidatesTheBatchAndDryRunBeforeChangingRules(boolean egress) {
        create(egress);
        String id = ruleId();
        SecurityGroupRule original = rule(id);
        SecurityGroupRuleDescription valid = SecurityGroupRuleDescription.builder()
                .securityGroupRuleId(id).description("would change").build();
        SecurityGroupRuleDescription missing = valid.toBuilder()
                .securityGroupRuleId("sgr-00000000000000000").build();
        if (egress) {
            assertEquals(400, assertThrows(Ec2Exception.class, () ->
                    ec2.updateSecurityGroupRuleDescriptionsEgress(request -> request.groupId(groupId)
                            .securityGroupRuleDescriptions(valid, missing))).statusCode());
            assertEquals("DryRunOperation", assertThrows(Ec2Exception.class, () ->
                    ec2.updateSecurityGroupRuleDescriptionsEgress(request -> request.groupId(groupId)
                            .securityGroupRuleDescriptions(valid).dryRun(true))).awsErrorDetails().errorCode());
        } else {
            assertEquals(400, assertThrows(Ec2Exception.class, () ->
                    ec2.updateSecurityGroupRuleDescriptionsIngress(request -> request.groupId(groupId)
                            .securityGroupRuleDescriptions(valid, missing))).statusCode());
            assertEquals("DryRunOperation", assertThrows(Ec2Exception.class, () ->
                    ec2.updateSecurityGroupRuleDescriptionsIngress(request -> request.groupId(groupId)
                            .securityGroupRuleDescriptions(valid).dryRun(true))).awsErrorDetails().errorCode());
        }
        for (String invalid : List.of("x".repeat(256), "invalid\nnewline")) {
            assertEquals(400, assertThrows(Ec2Exception.class, () ->
                    updateDescription(ec2, groupId, egress, id, invalid)).statusCode());
        }
        assertEquals(original, rule(id));
        assertDescription(id, egress, "original");
        updateDescription(ec2, groupId, egress, id, "a & b");
        assertDescription(id, egress, "a & b");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void descriptionApiResolvesGroupNamesOnlyInTheDefaultVpc(boolean egress) {
        namedGroupId = ec2.createSecurityGroup(request -> request.groupName(name)
                .description("Default VPC name selection")).groupId();
        IpPermission access = IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                .ipRanges(IpRange.builder().cidrIp(CIDR).description("original").build()).build();
        String id = (egress
                ? ec2.authorizeSecurityGroupEgress(request -> request.groupId(namedGroupId).ipPermissions(access))
                        .securityGroupRules()
                : ec2.authorizeSecurityGroupIngress(request -> request.groupId(namedGroupId).ipPermissions(access))
                        .securityGroupRules()).getFirst().securityGroupRuleId();
        SecurityGroupRuleDescription update = SecurityGroupRuleDescription.builder()
                .securityGroupRuleId(id).description("selected by name").build();
        if (egress) {
            ec2.updateSecurityGroupRuleDescriptionsEgress(request -> request.groupName(name)
                    .securityGroupRuleDescriptions(update));
        } else {
            ec2.updateSecurityGroupRuleDescriptionsIngress(request -> request.groupName(name)
                    .securityGroupRuleDescriptions(update));
        }
        assertEquals("selected by name", rule(id).description());
        assertTrue(ec2.describeSecurityGroupRules(request ->
                        request.filters(filter -> filter.name("group-id").values(groupId)))
                .securityGroupRules().stream().noneMatch(candidate -> id.equals(candidate.securityGroupRuleId())));
    }

    private IpPermission multiSourcePermission(String description) {
        return IpPermission.builder().ipProtocol("tcp").fromPort(8443).toPort(8443)
                .ipRanges(IpRange.builder().cidrIp(CIDR).description(description).build())
                .ipv6Ranges(Ipv6Range.builder().cidrIpv6("2001:db8::/64").description(description).build())
                .prefixListIds(PrefixListId.builder().prefixListId(prefixListId).description(description).build())
                .userIdGroupPairs(UserIdGroupPair.builder().groupId(otherGroupId)
                        .userId(account).description(description).build()).build();
    }

    private IpPermission permission(boolean egress, int port) {
        SecurityGroup group = ec2.describeSecurityGroups(request -> request.groupIds(groupId))
                .securityGroups().getFirst();
        return (egress ? group.ipPermissionsEgress() : group.ipPermissions()).stream()
                .filter(candidate -> candidate.fromPort() != null && candidate.fromPort() == port)
                .findFirst().orElseThrow();
    }

    private void updateDescription(Ec2Client client, String selectedGroup, boolean egress,
                                   String ruleId, String description) {
        SecurityGroupRuleDescription value = SecurityGroupRuleDescription.builder()
                .securityGroupRuleId(ruleId).description(description).build();
        if (egress) {
            client.updateSecurityGroupRuleDescriptionsEgress(request ->
                    request.groupId(selectedGroup).securityGroupRuleDescriptions(value));
        } else {
            client.updateSecurityGroupRuleDescriptionsIngress(request ->
                    request.groupId(selectedGroup).securityGroupRuleDescriptions(value));
        }
    }

    private void create(boolean egress) {
        stackId = cfn.createStack(request -> request.stackName(name)
                .templateBody(template(egress, "original", "before"))
                .parameters(parameter -> parameter.parameterKey("PeerCidr").parameterValue(CIDR))).stackId();
        awaitStatus("CREATE_COMPLETE");
        assertDescription(ruleId(), egress, "original");
    }

    private void update(boolean egress, String description, String other, String cidr) {
        cfn.updateStack(request -> request.stackName(stackId)
                .templateBody(template(egress, description, other))
                .parameters(parameter -> parameter.parameterKey("PeerCidr").parameterValue(cidr)));
        awaitStatus("UPDATE_COMPLETE");
    }

    private String ruleId() {
        return cfn.describeStackResource(request -> request.stackName(stackId)
                .logicalResourceId("Rule")).stackResourceDetail().physicalResourceId();
    }

    private SecurityGroupRule rule(String id) {
        return ec2.describeSecurityGroupRules(request -> request.securityGroupRuleIds(id))
                .securityGroupRules().getFirst();
    }

    private void assertDescription(String id, boolean egress, String expected) {
        assertEquals(expected, rule(id).description());
        SecurityGroup group = ec2.describeSecurityGroups(request -> request.groupIds(groupId))
                .securityGroups().getFirst();
        List<IpPermission> permissions = egress ? group.ipPermissionsEgress() : group.ipPermissions();
        IpRange range = permissions.stream().filter(permission -> permission.fromPort() != null
                        && permission.fromPort() == 443)
                .flatMap(permission -> permission.ipRanges().stream())
                .filter(peer -> CIDR.equals(peer.cidrIp())).findFirst().orElseThrow();
        if (expected == null) {
            assertNull(range.description());
        } else {
            assertEquals(expected, range.description());
        }
    }

    private void assertReferenceOutputs(String id) {
        assertEquals(id, output("RuleRef"));
        assertEquals(id, output("RuleAttribute"));
    }

    private String output(String key) {
        return cfn.describeStacks(request -> request.stackName(stackId)).stacks().getFirst()
                .outputs().stream().filter(value -> key.equals(value.outputKey()))
                .map(Output::outputValue).findFirst().orElseThrow();
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(expected, cfn.describeStacks(request -> request.stackName(stackId))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private String template(boolean egress, String description, String other) {
        String descriptionProperty = description == null ? "" : ",\"Description\":\"" + description + "\"";
        return """
                {"Parameters":{"PeerCidr":{"Type":"String"}},"Resources":{
                  "Rule":{"Type":"AWS::EC2::SecurityGroup%s","Properties":{
                    "GroupId":"%s","IpProtocol":"tcp","FromPort":443,"ToPort":443,
                    "CidrIp":{"Ref":"PeerCidr"}%s}}},
                 "Outputs":{"Other":{"Value":"%s"},"RuleRef":{"Value":{"Ref":"Rule"}},
                    "RuleAttribute":{"Value":{"Fn::GetAtt":["Rule","Id"]}}}}
                """.formatted(egress ? "Egress" : "Ingress", groupId, descriptionProperty, other);
    }
}

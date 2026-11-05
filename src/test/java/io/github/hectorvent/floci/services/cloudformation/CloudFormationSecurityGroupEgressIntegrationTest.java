package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationSecurityGroupEgressIntegrationTest {

    private static final Map<String, Object> HTTPS = Map.of(
            "IpProtocol", "tcp", "FromPort", 443, "ToPort", 443, "CidrIp", "192.0.2.0/24");
    private final ObjectMapper mapper = new ObjectMapper();
    private CloudFormationClient cfn;
    private Ec2Client ec2;
    private String stack;
    private String group;

    @BeforeEach
    void clients() {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
    }

    @AfterEach
    void cleanup() {
        try {
            if (stack != null) {
                cfn.deleteStack(request -> request.stackName(stack));
                awaitStatus("DELETE_COMPLETE");
            }
        } finally {
            ec2.close();
            cfn.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitRulesReplaceDefaultsAndUpdatesPreserveIndependentRules(boolean ipv6)
            throws JsonProcessingException {
        Map<String, Object> rule = ipv6
                ? Map.of("IpProtocol", "tcp", "FromPort", 443, "ToPort", 443,
                        "CidrIpv6", "2001:db8::/64") : HTTPS;
        create(List.of(rule));
        List<SecurityGroupRule> initial = egressRules();
        assertEquals(1, initial.size(), "An explicit egress rule must replace EC2's default allow-all rule");
        SecurityGroupRule declared = initial.getFirst();
        assertEquals("tcp", declared.ipProtocol());
        assertEquals(443, declared.fromPort());
        assertEquals(443, declared.toPort());
        assertEquals(ipv6 ? "2001:db8::/64" : "192.0.2.0/24",
                ipv6 ? declared.cidrIpv6() : declared.cidrIpv4());
        assertEquals(1, permissions().size(), "Both EC2 describe APIs must omit the default");

        IpPermission independent = IpPermission.builder().ipProtocol("tcp").fromPort(8443).toPort(8443)
                .ipRanges(IpRange.builder().cidrIp("198.51.100.4/32").build())
                .userIdGroupPairs(List.of()).ipv6Ranges(List.of()).prefixListIds(List.of()).build();
        String independentId = ec2.authorizeSecurityGroupEgress(request -> request.groupId(group)
                .ipPermissions(independent)).securityGroupRules().getFirst().securityGroupRuleId();
        update(List.of(rule), "initial", "tag-only");
        assertEquals(2, egressRules().size());
        assertTrue(egressRules().stream().anyMatch(row ->
                independentId.equals(row.securityGroupRuleId())));
        assertTrue(egressRules().stream().anyMatch(row ->
                declared.securityGroupRuleId().equals(row.securityGroupRuleId())));

        update(List.of(), "initial", "remove-inline");
        assertEquals(List.of(independentId),
                egressRules().stream().map(SecurityGroupRule::securityGroupRuleId).toList());
        assertEquals(List.of(independent), permissions());
        ec2.revokeSecurityGroupEgress(request -> request.groupId(group).ipPermissions(independent));
        assertTrue(egressRules().isEmpty());
        assertTrue(permissions().isEmpty(), "Removing the final rule must not recreate allow-all egress");
    }

    @Test
    void explicitlyDeclaredAllowAllIsOwnedAndRemovedWithTheProperty() throws JsonProcessingException {
        create(List.of(Map.of("IpProtocol", "-1", "CidrIp", "0.0.0.0/0")));
        assertEquals(1, egressRules().size());
        assertEquals("-1", egressRules().getFirst().ipProtocol());
        update(null, "initial", "remove-property");
        assertTrue(egressRules().isEmpty());
        assertTrue(permissions().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noInitialRulesRetainTheDefaultAndDoNotRestoreARevokedDefault(boolean empty)
            throws JsonProcessingException {
        List<Map<String, Object>> rules = empty ? List.of() : null;
        create(rules);
        assertFalse(permissions().isEmpty());
        assertTrue(egressRules().stream().anyMatch(row ->
                "-1".equals(row.ipProtocol()) && "0.0.0.0/0".equals(row.cidrIpv4())));
        List<IpPermission> defaults = permissions();
        ec2.revokeSecurityGroupEgress(request -> request.groupId(group).ipPermissions(defaults));
        update(rules, "initial", "tag-only");
        assertTrue(permissions().isEmpty(), "A later update must not reinitialize default egress");
        assertTrue(egressRules().isEmpty());
    }

    @Test
    void replacementAlsoRemovesItsNewImplicitDefault() throws JsonProcessingException {
        create(List.of(HTTPS));
        String original = group;
        update(List.of(HTTPS), "replacement", "replacement");
        group = cfn.describeStackResource(request -> request.stackName(stack).logicalResourceId("Group"))
                .stackResourceDetail().physicalResourceId();
        assertNotEquals(original, group);
        assertEquals(1, egressRules().size());
        assertEquals("192.0.2.0/24", egressRules().getFirst().cidrIpv4());
        assertEquals(443, egressRules().getFirst().toPort());
        assertFalse(ec2.describeSecurityGroups().securityGroups().stream()
                .anyMatch(row -> original.equals(row.groupId())));
    }

    private void create(List<Map<String, Object>> rules) throws JsonProcessingException {
        String body = template(rules, "initial", "initial");
        stack = cfn.createStack(request -> request.stackName("inline-egress-" + UUID.randomUUID())
                .templateBody(body)).stackId();
        awaitStatus("CREATE_COMPLETE");
        group = cfn.describeStackResource(request -> request.stackName(stack).logicalResourceId("Group"))
                .stackResourceDetail().physicalResourceId();
        assertEquals(group, cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst()
                .outputs().stream().filter(row -> "GroupId".equals(row.outputKey()))
                .findFirst().orElseThrow().outputValue());
    }

    private void update(List<Map<String, Object>> rules, String description, String tag)
            throws JsonProcessingException {
        String body = template(rules, description, tag);
        cfn.updateStack(request -> request.stackName(stack).templateBody(body));
        awaitStatus("UPDATE_COMPLETE");
    }

    private String template(List<Map<String, Object>> rules, String description, String tag)
            throws JsonProcessingException {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("GroupDescription", description);
        properties.put("VpcId", Map.of("Ref", "Vpc"));
        properties.put("Tags", List.of(Map.of("Key", "revision", "Value", tag)));
        if (rules != null) {
            properties.put("SecurityGroupEgress", rules);
        }
        return mapper.writeValueAsString(Map.of(
                "Resources", Map.of(
                        "Vpc", Map.of("Type", "AWS::EC2::VPC",
                                "Properties", Map.of("CidrBlock", "10.173.0.0/16")),
                        "Group", Map.of("Type", "AWS::EC2::SecurityGroup", "Properties", properties)),
                "Outputs", Map.of("GroupId", Map.of("Value", Map.of("Fn::GetAtt", List.of("Group", "GroupId"))))));
    }

    private List<IpPermission> permissions() {
        return ec2.describeSecurityGroups(request -> request.groupIds(group))
                .securityGroups().getFirst().ipPermissionsEgress();
    }

    private List<SecurityGroupRule> egressRules() {
        return ec2.describeSecurityGroupRules(request -> request.filters(
                filter -> filter.name("group-id").values(group))).securityGroupRules().stream()
                .filter(SecurityGroupRule::isEgress).toList();
    }

    private void awaitStatus(String status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertEquals(status, cfn.describeStacks(request -> request.stackName(stack))
                        .stacks().getFirst().stackStatusAsString()));
    }
}

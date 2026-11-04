package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.Ipv6Range;
import software.amazon.awssdk.services.ec2.model.PrefixListId;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;
import software.amazon.awssdk.services.ec2.model.UserIdGroupPair;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2SecurityGroupDuplicateRuleIntegrationTest {

    private final String name = "duplicate-rule-" + UUID.randomUUID();
    private Ec2Client ec2;
    private CloudFormationClient cfn;
    private String vpc;
    private String group;
    private String peerGroup;
    private String prefixList;
    private String stack;

    @BeforeEach
    void createGroups() {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        vpc = ec2.createVpc(request -> request.cidrBlock("10.93.0.0/16")).vpc().vpcId();
        group = ec2.createSecurityGroup(request -> request.vpcId(vpc).groupName(name)
                .description("Duplicate rule target")).groupId();
        peerGroup = ec2.createSecurityGroup(request -> request.vpcId(vpc).groupName(name + "-peer")
                .description("Referenced rule peer")).groupId();
    }

    @AfterEach
    void cleanup() {
        try {
            if (stack != null) {
                cfn.deleteStack(request -> request.stackName(stack));
                awaitStatus("DELETE_COMPLETE");
            }
            ec2.deleteSecurityGroup(request -> request.groupId(group));
            ec2.deleteSecurityGroup(request -> request.groupId(peerGroup));
            if (prefixList != null) {
                ec2.deleteManagedPrefixList(request -> request.prefixListId(prefixList));
            }
            ec2.deleteVpc(request -> request.vpcId(vpc));
        } finally {
            ec2.close();
            cfn.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, ipv4", "true, ipv4", "false, ipv6", "true, ipv6",
            "false, group", "true, group", "false, prefix", "true, prefix"})
    void descriptionsDoNotDistinguishPermissions(boolean egress, String peer) {
        authorize(egress, permission(peer, "original"));
        Set<SecurityGroupRule> before = rules();
        SecurityGroup originalGroup = describeGroup();

        assertDuplicate(() -> authorize(egress, permission(peer, "different description")));

        assertEquals(before, rules());
        assertEquals(originalGroup, describeGroup());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDuplicateRejectsTheEntireBatch(boolean egress) {
        IpPermission existing = ipv4("192.0.2.10/32");
        authorize(egress, existing);
        Set<SecurityGroupRule> before = rules();
        SecurityGroup originalGroup = describeGroup();

        assertDuplicate(() -> authorize(egress, ipv4("192.0.2.11/32"), existing));

        assertEquals(before, rules());
        assertEquals(originalGroup, describeGroup());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedSourcesAndRepeatedPermissionsAreRejectedWithoutWrites(boolean egress) {
        Set<SecurityGroupRule> before = rules();
        SecurityGroup originalGroup = describeGroup();
        IpPermission duplicateSources = ipv4("192.0.2.10/32").toBuilder()
                .ipRanges(IpRange.builder().cidrIp("192.0.2.10/32").build(),
                        IpRange.builder().cidrIp("192.0.2.10/32").description("another label").build())
                .build();

        assertDuplicate(() -> authorize(egress, duplicateSources));
        assertEquals(before, rules());
        assertEquals(originalGroup, describeGroup());
        assertDuplicate(() -> authorize(egress, ipv4("192.0.2.10/32"), ipv4("192.0.2.10/32")));
        assertEquals(before, rules());
        assertEquals(originalGroup, describeGroup());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void equivalentCidrsHaveOneCanonicalRuleAndCanBeRevokedByTheOriginalInput(boolean egress, boolean ipv6) {
        String supplied = ipv6 ? "2001:db8:0:0::27/64" : "192.0.2.27/24";
        String canonical = ipv6 ? "2001:db8::/64" : "192.0.2.0/24";
        IpPermission original = ipv6
                ? IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                        .ipv6Ranges(Ipv6Range.builder().cidrIpv6(supplied).build()).build()
                : ipv4(supplied);
        SecurityGroupRule created = authorize(egress, original).getFirst();
        assertEquals(canonical, ipv6 ? created.cidrIpv6() : created.cidrIpv4());
        SecurityGroupRule stored = rules().stream()
                .filter(rule -> rule.securityGroupRuleId().equals(created.securityGroupRuleId()))
                .findFirst().orElseThrow();
        assertEquals(canonical, ipv6 ? stored.cidrIpv6() : stored.cidrIpv4());
        IpPermission duplicate = ipv6
                ? original.toBuilder().ipv6Ranges(Ipv6Range.builder().cidrIpv6(canonical).build()).build()
                : ipv4(canonical);
        assertDuplicate(() -> authorize(egress, duplicate));
        assertDuplicate(() -> authorize(egress, original));

        IpPermission described = ipv6
                ? original.toBuilder().ipv6Ranges(Ipv6Range.builder().cidrIpv6(supplied)
                        .description("canonical selector").build()).build()
                : original.toBuilder().ipRanges(IpRange.builder().cidrIp(supplied)
                        .description("canonical selector").build()).build();
        if (egress) {
            ec2.updateSecurityGroupRuleDescriptionsEgress(request -> request.groupId(group).ipPermissions(described));
        } else {
            ec2.updateSecurityGroupRuleDescriptionsIngress(request -> request.groupId(group).ipPermissions(described));
        }
        assertEquals("canonical selector", ec2.describeSecurityGroupRules(request ->
                request.securityGroupRuleIds(created.securityGroupRuleId()))
                .securityGroupRules().getFirst().description());

        if (egress) {
            ec2.revokeSecurityGroupEgress(request -> request.groupId(group).ipPermissions(original));
        } else {
            ec2.revokeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(original));
        }
        assertTrue(rules().stream().noneMatch(rule ->
                rule.securityGroupRuleId().equals(created.securityGroupRuleId())));
        assertNotEquals(created.securityGroupRuleId(), authorize(egress, original)
                .getFirst().securityGroupRuleId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protocolNumbersIdentifyTheSamePermissionButOtherPortsAndDirectionsRemainIndependent(boolean egress) {
        IpPermission original = ipv4("192.0.2.10/32");
        String id = authorize(egress, original).getFirst().securityGroupRuleId();

        assertDuplicate(() -> authorize(egress, original.toBuilder().ipProtocol("6").build()));

        assertNotEquals(id, authorize(egress, original.toBuilder().fromPort(8443).toPort(8443).build())
                .getFirst().securityGroupRuleId());
        assertNotEquals(id, authorize(egress, original.toBuilder().ipProtocol("udp").build())
                .getFirst().securityGroupRuleId());
        assertNotEquals(id, authorize(!egress, original).getFirst().securityGroupRuleId());
        assertNotEquals(id, ec2.authorizeSecurityGroupIngress(request ->
                request.groupId(peerGroup).ipPermissions(original))
                .securityGroupRules().getFirst().securityGroupRuleId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allTrafficIgnoresPortsWhenCheckingDuplicates(boolean egress) {
        IpPermission original = IpPermission.builder().ipProtocol("-1")
                .ipRanges(IpRange.builder().cidrIp("192.0.2.10/32").build()).build();
        authorize(egress, original);
        Set<SecurityGroupRule> before = rules();

        assertDuplicate(() -> authorize(egress, original.toBuilder().fromPort(0).toPort(0).build()));

        assertEquals(before, rules());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simultaneousAuthorizationsCreateOnlyOneRule(boolean egress) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<String> operation = () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                authorize(egress, ipv4("192.0.2.10/32"));
                return "created";
            } catch (Ec2Exception duplicate) {
                assertEquals("InvalidPermission.Duplicate", duplicate.awsErrorDetails().errorCode());
                return "duplicate";
            }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(operation);
            Future<String> second = executor.submit(operation);
            List<String> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("created"::equals).count(), results.toString());
            assertEquals(1, results.stream().filter("duplicate"::equals).count(), results.toString());
        }
        assertEquals(1, rules().stream()
                .filter(rule -> rule.isEgress() == egress && "192.0.2.10/32".equals(rule.cidrIpv4())).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void declarativeCreationFailsWithoutRemovingThePreexistingManualPermission(boolean egress) {
        SecurityGroupRule original = authorize(egress, ipv4("192.0.2.10/32")).getFirst();
        String template = """
                {"Resources":{"Rule":{"Type":"AWS::EC2::SecurityGroup%s","Properties":{
                  "GroupId":"%s","IpProtocol":"tcp","FromPort":443,"ToPort":443,"CidrIp":"192.0.2.10/32"}}}}
                """.formatted(egress ? "Egress" : "Ingress", group);
        stack = cfn.createStack(request -> request.stackName(name).templateBody(template)).stackId();
        awaitStatus("ROLLBACK_COMPLETE");
        assertTrue(cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents().stream()
                .anyMatch(event -> "CREATE_FAILED".equals(event.resourceStatusAsString())
                        && event.resourceStatusReason().contains("already exists")));
        assertTrue(rules().contains(original));

        cfn.deleteStack(request -> request.stackName(stack));
        awaitStatus("DELETE_COMPLETE");
        assertTrue(rules().contains(original));
        stack = null;
    }

    private IpPermission permission(String peer, String description) {
        IpPermission.Builder builder = IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443);
        switch (peer) {
            case "ipv4" -> builder.ipRanges(IpRange.builder().cidrIp("192.0.2.10/32")
                    .description(description).build());
            case "ipv6" -> builder.ipv6Ranges(Ipv6Range.builder().cidrIpv6("2001:db8::10/128")
                    .description(description).build());
            case "group" -> builder.userIdGroupPairs(UserIdGroupPair.builder().groupId(peerGroup)
                    .description(description).build());
            case "prefix" -> {
                if (prefixList == null) {
                    prefixList = ec2.createManagedPrefixList(request -> request.prefixListName(name)
                            .addressFamily("IPv4").maxEntries(1)
                            .entries(entry -> entry.cidr("192.0.2.0/24"))).prefixList().prefixListId();
                }
                builder.prefixListIds(PrefixListId.builder().prefixListId(prefixList)
                        .description(description).build());
            }
            default -> throw new IllegalArgumentException(peer);
        }
        return builder.build();
    }

    private static IpPermission ipv4(String cidr) {
        return IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                .ipRanges(IpRange.builder().cidrIp(cidr).build()).build();
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

    private SecurityGroup describeGroup() {
        return ec2.describeSecurityGroups(request -> request.groupIds(group)).securityGroups().getFirst();
    }

    private static void assertDuplicate(Executable operation) {
        Ec2Exception error = assertThrows(Ec2Exception.class, operation);
        assertEquals(400, error.statusCode());
        assertEquals("InvalidPermission.Duplicate", error.awsErrorDetails().errorCode());
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25)).untilAsserted(() -> {
            String actual = cfn.describeStacks(request -> request.stackName(stack))
                    .stacks().getFirst().stackStatusAsString();
            assertEquals(expected, actual);
        });
    }
}

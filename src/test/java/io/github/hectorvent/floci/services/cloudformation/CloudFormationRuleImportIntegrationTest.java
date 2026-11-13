package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.CreateChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.ResourceIdentifierSummary;
import software.amazon.awssdk.services.cloudformation.model.ResourceToImport;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationRuleImportIntegrationTest {

    private static final String CIDR = "192.0.2.27/32";
    private final String name = "rule-import-" + UUID.randomUUID();
    private final List<String> stacks = new ArrayList<>();
    private CloudFormationClient cfn;
    private Ec2Client ec2;
    private String account;
    private String groupId;
    private String vpcId;

    @BeforeEach
    void createManuallyManagedGroup() {
        account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
        vpcId = ec2.createVpc(request -> request.cidrBlock("10.93.0.0/16")).vpc().vpcId();
        groupId = ec2.createSecurityGroup(request -> request.vpcId(vpcId)
                .groupName(name).description("Manually managed group")).groupId();
    }

    @AfterEach
    void cleanup() {
        try {
            for (String stack : stacks) {
                cfn.deleteStack(request -> request.stackName(stack));
                awaitStatus(stack, "DELETE_COMPLETE");
            }
            if (groupId != null) {
                ec2.deleteSecurityGroup(request -> request.groupId(groupId));
            }
            if (vpcId != null) {
                ec2.deleteVpc(request -> request.vpcId(vpcId));
            }
        } finally {
            ec2.close();
            cfn.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void templateSummaryNamesTheRuleIdentifier(boolean egress) {
        List<ResourceIdentifierSummary> identifiers = cfn.getTemplateSummary(request ->
                        request.templateBody(template(egress, "Retain", CIDR, false)))
                .resourceIdentifierSummaries();

        assertEquals(1, identifiers.size());
        assertEquals(type(egress), identifiers.getFirst().resourceType());
        assertEquals(List.of("Id"), identifiers.getFirst().resourceIdentifiers());
        assertEquals(List.of("Rule"), identifiers.getFirst().logicalResourceIds());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void importIntoNewStackKeepsPermissionIdentityAndRetainsItOnDeletion(boolean egress) {
        SecurityGroupRule original = authorize(egress);
        List<SecurityGroupRule> before = rules();
        CreateChangeSetResponse changeSet = importRule(name, egress, original.securityGroupRuleId(),
                template(egress, "Retain", CIDR, false));
        stacks.add(changeSet.stackId());
        DescribeChangeSetResponse ready = ready(changeSet.id());
        assertEquals("Import", ready.changes().getFirst().resourceChange().actionAsString());
        assertEquals(original.securityGroupRuleId(),
                ready.changes().getFirst().resourceChange().physicalResourceId());

        cfn.executeChangeSet(request -> request.changeSetName(changeSet.id()));
        awaitStatus(changeSet.stackId(), "IMPORT_COMPLETE");

        assertEquals(before, rules());
        assertReferences(changeSet.stackId(), original.securityGroupRuleId());
        assertTrue(cfn.describeStackEvents(request -> request.stackName(changeSet.stackId()))
                .stackEvents().stream().anyMatch(event -> "IMPORT_COMPLETE".equals(event.resourceStatusAsString())));
        cfn.deleteStack(request -> request.stackName(changeSet.stackId()));
        awaitStatus(changeSet.stackId(), "DELETE_COMPLETE");
        stacks.remove(changeSet.stackId());
        assertEquals(before, rules());
        assertTrue(cfn.describeStackEvents(request -> request.stackName(changeSet.stackId())).stackEvents().stream()
                .anyMatch(event -> "Rule".equals(event.logicalResourceId())
                        && "DELETE_SKIPPED".equals(event.resourceStatusAsString())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void importAddsOwnershipToExistingStackWithoutChangingItsResources(boolean egress) {
        SecurityGroupRule original = authorize(egress);
        String stack = cfn.createStack(request -> request.stackName(name)
                .templateBody("{\"Resources\":" + foundation() + "}")).stackId();
        stacks.add(stack);
        awaitStatus(stack, "CREATE_COMPLETE");
        String prior = cfn.describeStackResource(request -> request.stackName(stack)
                .logicalResourceId("Foundation")).stackResourceDetail().physicalResourceId();

        CreateChangeSetResponse changeSet = importRule(stack, egress, original.securityGroupRuleId(),
                template(egress, "Delete", CIDR, true));
        DescribeChangeSetResponse ready = ready(changeSet.id());
        assertEquals(1, ready.changes().size());
        assertEquals("Import", ready.changes().getFirst().resourceChange().actionAsString());
        assertEquals(original.securityGroupRuleId(),
                ready.changes().getFirst().resourceChange().physicalResourceId());
        cfn.executeChangeSet(request -> request.changeSetName(changeSet.id()));
        awaitStatus(stack, "IMPORT_COMPLETE");

        assertEquals(prior, cfn.describeStackResource(request -> request.stackName(stack)
                .logicalResourceId("Foundation")).stackResourceDetail().physicalResourceId());
        assertEquals(original, rule(original.securityGroupRuleId()));
        assertReferences(stack, original.securityGroupRuleId());
        cfn.deleteStack(request -> request.stackName(stack));
        awaitStatus(stack, "DELETE_COMPLETE");
        stacks.remove(stack);
        assertFalse(rules().stream().anyMatch(rule -> original.securityGroupRuleId().equals(rule.securityGroupRuleId())));
    }

    @Test
    void importDoesNotRewriteLivePropertiesToMatchTheTemplate() {
        SecurityGroupRule original = authorize(false);
        CreateChangeSetResponse changeSet = importRule(name, false, original.securityGroupRuleId(),
                template(false, "Retain", "192.0.2.99/32", false));
        stacks.add(changeSet.stackId());
        ready(changeSet.id());
        cfn.executeChangeSet(request -> request.changeSetName(changeSet.id()));
        awaitStatus(changeSet.stackId(), "IMPORT_COMPLETE");

        assertEquals(original, rule(original.securityGroupRuleId()));
        assertReferences(changeSet.stackId(), original.securityGroupRuleId());
    }

    @Test
    void anAdoptedRuleParticipatesInOrdinaryStackUpdates() {
        SecurityGroupRule original = authorize(false);
        CreateChangeSetResponse changeSet = importRule(name, false, original.securityGroupRuleId(),
                template(false, "Delete", CIDR, false));
        stacks.add(changeSet.stackId());
        ready(changeSet.id());
        cfn.executeChangeSet(request -> request.changeSetName(changeSet.id()));
        awaitStatus(changeSet.stackId(), "IMPORT_COMPLETE");

        cfn.updateStack(request -> request.stackName(changeSet.stackId())
                .templateBody(template(false, "Delete", "192.0.2.29/32", false)));
        awaitStatus(changeSet.stackId(), "UPDATE_COMPLETE");
        String replacement = cfn.describeStackResource(request -> request.stackName(changeSet.stackId())
                .logicalResourceId("Rule")).stackResourceDetail().physicalResourceId();
        assertFalse(original.securityGroupRuleId().equals(replacement));
        assertEquals("192.0.2.29/32", rule(replacement).cidrIpv4());
        assertFalse(rules().stream().anyMatch(rule -> original.securityGroupRuleId().equals(rule.securityGroupRuleId())));
        assertReferences(changeSet.stackId(), replacement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-policy", "invalid-policy", "wrong-identifier", "extra-identifier",
            "wrong-type", "unsupported-type", "missing-logical-id", "duplicate-logical-id", "wrong-direction",
            "unknown-property", "missing-protocol", "invalid-string", "invalid-port"})
    void rejectedImportDoesNotChangeTheExistingPermission(String invalid) {
        SecurityGroupRule original = authorize(false);
        String body = template(false, "Retain", CIDR, false);
        ResourceToImport resource = importIdentifier(false, original.securityGroupRuleId());
        if ("missing-policy".equals(invalid)) {
            body = body.replace("\"DeletionPolicy\":\"Retain\",", "");
        } else if ("invalid-policy".equals(invalid)) {
            body = body.replace("\"Retain\"", "\"Keep\"");
        } else if ("wrong-identifier".equals(invalid)) {
            resource = resource.toBuilder().resourceIdentifier(Map.of("GroupId", groupId)).build();
        } else if ("extra-identifier".equals(invalid)) {
            resource = resource.toBuilder().resourceIdentifier(
                    Map.of("Id", original.securityGroupRuleId(), "GroupId", groupId)).build();
        } else if ("wrong-type".equals(invalid)) {
            resource = resource.toBuilder().resourceType(type(true)).build();
        } else if ("unsupported-type".equals(invalid)) {
            body = body.replace(type(false), "AWS::SSM::Parameter");
            resource = resource.toBuilder().resourceType("AWS::SSM::Parameter").build();
        } else if ("missing-logical-id".equals(invalid)) {
            resource = resource.toBuilder().logicalResourceId("Absent").build();
        } else if ("wrong-direction".equals(invalid)) {
            body = template(true, "Retain", CIDR, false);
            resource = resource.toBuilder().resourceType(type(true)).build();
        } else if ("unknown-property".equals(invalid)) {
            body = body.replace("\"Properties\":{", "\"Properties\":{\"Unexpected\":\"value\",");
        } else if ("missing-protocol".equals(invalid)) {
            body = body.replace("\"IpProtocol\":\"tcp\",", "");
        } else if ("invalid-string".equals(invalid)) {
            body = body.replace("\"IpProtocol\":\"tcp\"", "\"IpProtocol\":[]");
        } else if ("invalid-port".equals(invalid)) {
            body = body.replace("\"FromPort\":443", "\"FromPort\":\"not-a-port\"");
        }
        List<ResourceToImport> imports = "duplicate-logical-id".equals(invalid)
                ? List.of(resource, resource) : List.of(resource);
        CreateChangeSetResponse changeSet = importResources(name, body, imports);
        stacks.add(changeSet.stackId());
        assertFailed(changeSet.id());
        assertEquals(original, rule(original.securityGroupRuleId()));
        assertTrue(cfn.describeStackResources(request -> request.stackName(changeSet.stackId()))
                .stackResources().isEmpty());
    }

    @Test
    void importListIsRequiredAndCannotBeUsedForAnUpdate() {
        SecurityGroupRule original = authorize(false);
        CloudFormationException missing = assertThrows(CloudFormationException.class, () ->
                cfn.createChangeSet(request -> request.stackName(name).changeSetName("missing")
                        .changeSetType("IMPORT").templateBody(template(false, "Retain", CIDR, false))));
        assertEquals("ValidationError", missing.awsErrorDetails().errorCode());
        CloudFormationException invalidType = assertThrows(CloudFormationException.class, () ->
                cfn.createChangeSet(request -> request.stackName(name).changeSetName("not-an-import")
                        .changeSetType("UPDATE").templateBody(template(false, "Retain", CIDR, false))
                        .resourcesToImport(importIdentifier(false, original.securityGroupRuleId()))));
        assertEquals("ValidationError", invalidType.awsErrorDetails().errorCode());
        assertEquals(original, rule(original.securityGroupRuleId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRuleCannotBeImportedFromAnotherAccountOrRegion(boolean otherRegion) {
        SecurityGroupRule original = authorize(false);
        String targetAccount = otherRegion ? account : account.equals("100000000000") ? "200000000000" : "100000000000";
        try (CloudFormationClient foreign = AwsTestClients.create(CloudFormationClient.builder(),
                targetAccount, otherRegion ? Region.EU_WEST_1 : Region.US_EAST_1)) {
            CreateChangeSetResponse changeSet = foreign.createChangeSet(request -> request.stackName(name)
                    .changeSetName("foreign").changeSetType("IMPORT")
                    .templateBody(template(false, "Retain", CIDR, false))
                    .resourcesToImport(importIdentifier(false, original.securityGroupRuleId())));
            try {
                DescribeChangeSetResponse failed = foreign.describeChangeSet(request -> request.changeSetName(changeSet.id()));
                assertEquals("FAILED", failed.statusAsString());
                assertEquals("UNAVAILABLE", failed.executionStatusAsString());
                assertTrue(failed.statusReason().contains(original.securityGroupRuleId()), failed.statusReason());
                assertEquals(original, rule(original.securityGroupRuleId()));
            } finally {
                foreign.deleteStack(request -> request.stackName(changeSet.stackId()));
                await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                        .untilAsserted(() -> assertEquals("DELETE_COMPLETE",
                        foreign.describeStacks(request -> request.stackName(changeSet.stackId()))
                                .stacks().getFirst().stackStatusAsString()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "delete"})
    void importCannotAlsoChangeOtherResources(String mutation) {
        SecurityGroupRule original = authorize(false);
        String stack = createFoundation();
        String priorId = cfn.describeStackResource(request -> request.stackName(stack)
                .logicalResourceId("Foundation")).stackResourceDetail().physicalResourceId();
        String body = template(false, "Retain", CIDR, !"delete".equals(mutation));
        if ("update".equals(mutation)) {
            body = body.replace("untouched", "changed");
        } else if ("create".equals(mutation)) {
            body = body.replace("\"Resources\":{", "\"Resources\":{\"Extra\":{\"Type\":\"AWS::SSM::Parameter\","
                    + "\"Properties\":{\"Type\":\"String\",\"Value\":\"new\"}},");
        }
        CreateChangeSetResponse changeSet = importRule(stack, false, original.securityGroupRuleId(), body);
        assertFailed(changeSet.id());
        assertEquals(priorId, cfn.describeStackResource(request -> request.stackName(stack)
                .logicalResourceId("Foundation")).stackResourceDetail().physicalResourceId());
        assertEquals(1, cfn.describeStackResources(request -> request.stackName(stack)).stackResources().size());
        assertEquals(original, rule(original.securityGroupRuleId()));
        awaitStatus(stack, "CREATE_COMPLETE");
    }

    @Test
    void parameterChangesCannotModifyExistingResourcesDuringImport() {
        SecurityGroupRule original = authorize(false);
        String body = template(false, "Retain", CIDR, true).replace("\"untouched\"", "{\"Ref\":\"Setting\"}")
                .replace("{\"Resources\":", "{\"Parameters\":{\"Setting\":{\"Type\":\"String\",\"Default\":\"first\"}},"
                        + "\"Resources\":");
        String initial = """
                {"Parameters":{"Setting":{"Type":"String","Default":"first"}},
                 "Resources":{"Foundation":{"Type":"AWS::SSM::Parameter",
                 "Properties":{"Type":"String","Value":{"Ref":"Setting"}}}}}
                """;
        String stack = cfn.createStack(request -> request.stackName(name).templateBody(initial)).stackId();
        stacks.add(stack);
        awaitStatus(stack, "CREATE_COMPLETE");
        CreateChangeSetResponse changeSet = cfn.createChangeSet(request -> request.stackName(stack)
                .changeSetName("changed-parameter").changeSetType("IMPORT").templateBody(body)
                .parameters(parameter -> parameter.parameterKey("Setting").parameterValue("second"))
                .resourcesToImport(importIdentifier(false, original.securityGroupRuleId())));
        assertFailed(changeSet.id());
        assertEquals(original, rule(original.securityGroupRuleId()));
    }

    @Test
    void aPhysicalRuleCannotHaveTwoLogicalOwnersInOneImport() {
        SecurityGroupRule original = authorize(false);
        String body = twoRuleTemplate(false);
        CreateChangeSetResponse changeSet = importResources(name, body,
                List.of(importIdentifier(false, original.securityGroupRuleId()),
                        importIdentifier(false, original.securityGroupRuleId()).toBuilder().logicalResourceId("Copy").build()));
        stacks.add(changeSet.stackId());
        assertFailed(changeSet.id());
        assertEquals(original, rule(original.securityGroupRuleId()));
    }

    @Test
    void executionRechecksAllRulesAndRollsBackOnlyOwnership() {
        SecurityGroupRule original = authorize(false);
        SecurityGroupRule removed = ec2.authorizeSecurityGroupIngress(request -> request.groupId(groupId)
                .ipPermissions(permission("192.0.2.28/32"))).securityGroupRules().getFirst();
        String stack = createFoundation();
        CreateChangeSetResponse changeSet = importResources(stack, twoRuleTemplate(true),
                List.of(importIdentifier(false, original.securityGroupRuleId()),
                        importIdentifier(false, removed.securityGroupRuleId()).toBuilder().logicalResourceId("Copy").build()));
        ready(changeSet.id());
        ec2.revokeSecurityGroupIngress(request -> request.groupId(groupId)
                .securityGroupRuleIds(removed.securityGroupRuleId()));

        cfn.executeChangeSet(request -> request.changeSetName(changeSet.id()));
        awaitStatus(stack, "IMPORT_ROLLBACK_COMPLETE");
        assertEquals("EXECUTE_FAILED",
                cfn.describeChangeSet(request -> request.changeSetName(changeSet.id())).executionStatusAsString());
        assertEquals(List.of("Foundation"), cfn.describeStackResources(request -> request.stackName(stack))
                .stackResources().stream().map(resource -> resource.logicalResourceId()).toList());
        assertEquals(original, rule(original.securityGroupRuleId()));

        CreateChangeSetResponse retry = importRule(name + "-retry", false, original.securityGroupRuleId(),
                template(false, "Retain", CIDR, false));
        stacks.add(retry.stackId());
        ready(retry.id());
        cfn.executeChangeSet(request -> request.changeSetName(retry.id()));
        awaitStatus(retry.stackId(), "IMPORT_COMPLETE");
    }

    @Test
    void simultaneousImportsCannotBothOwnTheSameRule() throws Exception {
        SecurityGroupRule original = authorize(false);
        List<CreateChangeSetResponse> competing = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            CreateChangeSetResponse changeSet = importRule(name + "-" + index, false, original.securityGroupRuleId(),
                    template(false, "Retain", CIDR, false));
            stacks.add(changeSet.stackId());
            ready(changeSet.id());
            competing.add(changeSet);
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> executions = new ArrayList<>();
            for (CreateChangeSetResponse changeSet : competing) {
                executions.add(executor.submit(() ->
                        cfn.executeChangeSet(request -> request.changeSetName(changeSet.id()))));
            }
            for (Future<?> execution : executions) {
                execution.get();
            }
        }
        await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> {
            List<String> statuses = competing.stream().map(changeSet ->
                    cfn.describeStacks(request -> request.stackName(changeSet.stackId()))
                            .stacks().getFirst().stackStatusAsString()).toList();
            assertEquals(Set.of("IMPORT_COMPLETE", "IMPORT_ROLLBACK_COMPLETE"), Set.copyOf(statuses));
        });
        assertEquals(1, competing.stream().mapToInt(changeSet ->
                cfn.describeStackResources(request -> request.stackName(changeSet.stackId())).stackResources().size()).sum());
        assertEquals(original, rule(original.securityGroupRuleId()));

        CreateChangeSetResponse alreadyOwned = importRule(name + "-owned", false, original.securityGroupRuleId(),
                template(false, "Retain", CIDR, false));
        stacks.add(alreadyOwned.stackId());
        assertFailed(alreadyOwned.id());
    }

    @Test
    void previousTemplateKeepsImportSemanticsWhenAConditionBecomesActive() {
        SecurityGroupRule original = authorize(false);
        String rule = ruleDefinition(false, "Retain", CIDR).replace("{\"Type\":",
                "{\"Condition\":\"Enabled\",\"Type\":");
        String body = """
                {"Parameters":{"Enable":{"Type":"String","Default":"no"}},
                 "Conditions":{"Enabled":{"Fn::Equals":[{"Ref":"Enable"},"yes"]}},
                 "Resources":{%s"Rule":%s}}
                """.formatted(foundationEntries(), rule);
        String stack = cfn.createStack(request -> request.stackName(name).templateBody(body)).stackId();
        stacks.add(stack);
        awaitStatus(stack, "CREATE_COMPLETE");
        assertEquals(List.of("Foundation"), cfn.describeStackResources(request -> request.stackName(stack))
                .stackResources().stream().map(resource -> resource.logicalResourceId()).toList());

        CreateChangeSetResponse changeSet = cfn.createChangeSet(request -> request.stackName(stack)
                .changeSetName("adopt-previous").changeSetType("IMPORT").usePreviousTemplate(true)
                .parameters(parameter -> parameter.parameterKey("Enable").parameterValue("yes"))
                .resourcesToImport(importIdentifier(false, original.securityGroupRuleId())));
        assertEquals("Import", ready(changeSet.id()).changes().getFirst().resourceChange().actionAsString());
        cfn.executeChangeSet(request -> request.changeSetName(changeSet.id()));
        awaitStatus(stack, "IMPORT_COMPLETE");
        assertEquals(original, rule(original.securityGroupRuleId()));
        assertEquals(body, cfn.getTemplate(request -> request.stackName(stack).templateStage("Original")).templateBody());
    }

    private String createFoundation() {
        String stack = cfn.createStack(request -> request.stackName(name)
                .templateBody("{\"Resources\":" + foundation() + "}")).stackId();
        stacks.add(stack);
        awaitStatus(stack, "CREATE_COMPLETE");
        return stack;
    }

    private String twoRuleTemplate(boolean includeFoundation) {
        return "{\"Resources\":{" + (includeFoundation ? foundationEntries() : "") + "\"Rule\":"
                + ruleDefinition(false, "Retain", CIDR) + ",\"Copy\":"
                + ruleDefinition(false, "Retain", "192.0.2.28/32") + "}}";
    }

    private void assertFailed(String changeSet) {
        DescribeChangeSetResponse failed = cfn.describeChangeSet(request -> request.changeSetName(changeSet));
        assertEquals("FAILED", failed.statusAsString(), failed.statusReason());
        assertEquals("UNAVAILABLE", failed.executionStatusAsString());
        assertFalse(failed.statusReason().isBlank());
        CloudFormationException error = assertThrows(CloudFormationException.class, () ->
                cfn.executeChangeSet(request -> request.changeSetName(changeSet)));
        assertEquals("InvalidChangeSetStatus", error.awsErrorDetails().errorCode());
    }

    private ResourceToImport importIdentifier(boolean egress, String ruleId) {
        return ResourceToImport.builder().logicalResourceId("Rule").resourceType(type(egress))
                .resourceIdentifier(Map.of("Id", ruleId)).build();
    }

    private CreateChangeSetResponse importResources(String stack, String template, List<ResourceToImport> resources) {
        return cfn.createChangeSet(request -> request.stackName(stack).changeSetName("adopt-" + UUID.randomUUID())
                .changeSetType("IMPORT").templateBody(template).resourcesToImport(resources));
    }

    private IpPermission permission(String cidr) {
        return IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                .ipRanges(IpRange.builder().cidrIp(cidr).description("Existing permission").build()).build();
    }

    private SecurityGroupRule authorize(boolean egress) {
        IpPermission permission = permission(CIDR);
        return (egress
                ? ec2.authorizeSecurityGroupEgress(request -> request.groupId(groupId).ipPermissions(permission))
                        .securityGroupRules()
                : ec2.authorizeSecurityGroupIngress(request -> request.groupId(groupId).ipPermissions(permission))
                        .securityGroupRules()).getFirst();
    }

    private CreateChangeSetResponse importRule(String stack, boolean egress, String ruleId, String template) {
        return cfn.createChangeSet(request -> request.stackName(stack).changeSetName("adopt-" + UUID.randomUUID())
                .changeSetType("IMPORT").templateBody(template)
                .resourcesToImport(ResourceToImport.builder().logicalResourceId("Rule").resourceType(type(egress))
                        .resourceIdentifier(Map.of("Id", ruleId)).build()));
    }

    private DescribeChangeSetResponse ready(String changeSet) {
        await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> {
            DescribeChangeSetResponse response = cfn.describeChangeSet(request -> request.changeSetName(changeSet));
            assertEquals("CREATE_COMPLETE", response.statusAsString(), response.statusReason());
            assertEquals("AVAILABLE", response.executionStatusAsString());
        });
        return cfn.describeChangeSet(request -> request.changeSetName(changeSet));
    }

    private void awaitStatus(String stack, String status) {
        await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() ->
                assertEquals(status, cfn.describeStacks(request -> request.stackName(stack))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private void assertReferences(String stack, String id) {
        assertEquals(id, cfn.describeStackResource(request -> request.stackName(stack)
                .logicalResourceId("Rule")).stackResourceDetail().physicalResourceId());
        assertEquals(List.of(id, id), cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst()
                .outputs().stream().map(output -> output.outputValue()).toList());
    }

    private List<SecurityGroupRule> rules() {
        return ec2.describeSecurityGroupRules(request ->
                request.filters(filter -> filter.name("group-id").values(groupId))).securityGroupRules();
    }

    private SecurityGroupRule rule(String id) {
        return rules().stream().filter(rule -> id.equals(rule.securityGroupRuleId())).findFirst().orElseThrow();
    }

    private String foundation() {
        return """
                {"Foundation":{"Type":"AWS::SSM::Parameter","Properties":{"Type":"String","Value":"untouched"}}}
                """;
    }

    private String template(boolean egress, String deletionPolicy, String cidr, boolean includeFoundation) {
        return """
                {"Resources":{%s"Rule":%s},
                 "Outputs":{"RuleRef":{"Value":{"Ref":"Rule"}},"RuleId":{"Value":{"Fn::GetAtt":["Rule","Id"]}}}}
                """.formatted(includeFoundation ? foundationEntries() : "", ruleDefinition(egress, deletionPolicy, cidr));
    }

    private String ruleDefinition(boolean egress, String deletionPolicy, String cidr) {
        return """
                {"Type":"%s","DeletionPolicy":"%s","Properties":{
                  "GroupId":"%s","IpProtocol":"tcp","FromPort":443,"ToPort":443,"CidrIp":"%s"}}
                """.formatted(type(egress), deletionPolicy, groupId, cidr).strip();
    }

    private String foundationEntries() {
        String resources = foundation().strip();
        return resources.substring(1, resources.length() - 1) + ",";
    }

    private String type(boolean egress) {
        return "AWS::EC2::SecurityGroup" + (egress ? "Egress" : "Ingress");
    }
}

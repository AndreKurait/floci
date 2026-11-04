package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetPatchBaselineResponse;
import software.amazon.awssdk.services.ssm.model.PatchOrchestratorFilter;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.Tag;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.github.hectorvent.floci.testing.CfnStackWaits.awaitStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
class CloudFormationSsmPatchBaselineSdkIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "830011110046";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void stackOwnsTheBaselineReturnedByRefAndIdAndDeletesItsBindings(String region) throws Exception {
        String name = "cfn-baseline-" + UUID.randomUUID();
        ObjectNode template = template();
        try (CloudFormationClient cfn = cfn(region); SsmClient ssm = ssm(region)) {
            String previousDefault = ssm.getDefaultPatchBaseline(request -> request
                    .operatingSystem("AMAZON_LINUX_2")).baselineId();
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            String id = null;
            try {
                Stack stack = awaitStatus(cfn, name, "CREATE_COMPLETE");
                id = output(stack, "BaselineRef");
                assertTrue(id.matches("pb-[0-9a-f]{17}"),
                        "Ref must name a real SSM patch baseline, not a CloudFormation stub: " + id);
                String baselineId = id;
                assertEquals(id, output(stack, "BaselineId"));
                assertEquals(id, cfn.describeStackResource(request -> request.stackName(name)
                        .logicalResourceId("Baseline")).stackResourceDetail().physicalResourceId());
                GetPatchBaselineResponse baseline = ssm.getPatchBaseline(request -> request.baselineId(baselineId));
                assertEquals(name, baseline.name());
                assertEquals("AMAZON_LINUX_2", baseline.operatingSystemAsString());
                assertEquals("Policy in " + region, baseline.description());
                assertEquals(List.of("kernel*"), baseline.approvedPatches());
                assertEquals("BLOCK", baseline.rejectedPatchesActionAsString());
                assertEquals(List.of("old-package*"), baseline.rejectedPatches());
                assertEquals("HIGH", baseline.approvedPatchesComplianceLevelAsString());
                assertTrue(baseline.approvedPatchesEnableNonSecurity());
                assertEquals("example-repository", baseline.sources().getFirst().name());
                assertEquals(0, baseline.approvalRules().patchRules().getFirst().approveAfterDays());
                assertEquals(List.of(name), baseline.patchGroups());
                assertEquals(id, ssm.getPatchBaselineForPatchGroup(request -> request.patchGroup(name)
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
                assertEquals(id, ssm.getDefaultPatchBaseline(request -> request
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
                assertEquals(List.of(Tag.builder().key("purpose").value("cloudformation").build()),
                        ssm.listTagsForResource(request -> request.resourceType("PatchBaseline")
                                .resourceId(baselineId)).tagList());
            } finally {
                deleteStack(cfn, name);
            }
            assertMissing(ssm, id);
            assertEquals(previousDefault, ssm.getPatchBaselineForPatchGroup(request -> request
                    .patchGroup(name).operatingSystem("AMAZON_LINUX_2")).baselineId());
            assertEquals(previousDefault, ssm.getDefaultPatchBaseline(request -> request
                    .operatingSystem("AMAZON_LINUX_2")).baselineId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void updatesReplaceOptionalPolicyAndOwnedTagsWithoutReplacingTheBaseline(String region) throws Exception {
        String name = "cfn-policy-" + UUID.randomUUID();
        ObjectNode template = template();
        try (CloudFormationClient cfn = cfn(region); SsmClient ssm = ssm(region)) {
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            try {
                String id = output(awaitStatus(cfn, name, "CREATE_COMPLETE"), "BaselineRef");
                GetPatchBaselineResponse initial = ssm.getPatchBaseline(request -> request.baselineId(id));
                ssm.addTagsToResource(request -> request.resourceType("PatchBaseline").resourceId(id)
                        .tags(Tag.builder().key("outside").value("keep").build()));
                ssm.registerPatchBaselineForPatchGroup(request -> request.baselineId(id).patchGroup(name + "-outside"));
                ObjectNode props = properties(template);
                props.remove(List.of("Description", "ApprovalRules", "GlobalFilters", "Sources",
                        "ApprovedPatchesComplianceLevel", "ApprovedPatchesEnableNonSecurity",
                        "RejectedPatches", "RejectedPatchesAction", "AvailableSecurityUpdatesComplianceStatus", "Tags"));
                props.put("Name", name + "-renamed").put("DefaultBaseline", false);
                props.putArray("ApprovedPatches").add("new-package*");
                props.putArray("PatchGroups").add(name + "-next");
                assertPreview(cfn, name, template, "False");
                Stack stack = update(cfn, name, template, "UPDATE_COMPLETE");
                assertEquals(id, output(stack, "BaselineRef"));
                GetPatchBaselineResponse updated = ssm.getPatchBaseline(request -> request.baselineId(id));
                assertEquals(name + "-renamed", updated.name());
                assertEquals(initial.createdDate(), updated.createdDate());
                assertNull(updated.description());
                assertNull(updated.approvalRules());
                assertNull(updated.globalFilters());
                assertTrue(updated.sources().isEmpty());
                assertTrue(updated.rejectedPatches().isEmpty());
                assertEquals("ALLOW_AS_DEPENDENCY", updated.rejectedPatchesActionAsString());
                assertEquals("UNSPECIFIED", updated.approvedPatchesComplianceLevelAsString());
                assertEquals(false, updated.approvedPatchesEnableNonSecurity());
                assertEquals(List.of("new-package*"), updated.approvedPatches());
                assertEquals(List.of(name + "-next"), updated.patchGroups());
                assertEquals(Map.of("outside", "keep"), tags(ssm, id));
                assertEquals(id, ssm.getDefaultPatchBaseline(request -> request
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
            } finally {
                deleteStack(cfn, name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void conflictingGroupRollsBackThePartiallyAppliedUpdateAndAllowsRetry(String region) throws Exception {
        String name = "cfn-conflict-" + UUID.randomUUID();
        ObjectNode template = template();
        properties(template).put("DefaultBaseline", false);
        try (CloudFormationClient cfn = cfn(region); SsmClient ssm = ssm(region)) {
            String other = ssm.createPatchBaseline(request -> request.name("outside-" + name)
                    .operatingSystem("AMAZON_LINUX_2")).baselineId();
            ssm.registerPatchBaselineForPatchGroup(request -> request.baselineId(other).patchGroup(name + "-occupied"));
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            try {
                String id = output(awaitStatus(cfn, name, "CREATE_COMPLETE"), "BaselineRef");
                GetPatchBaselineResponse initial = ssm.getPatchBaseline(request -> request.baselineId(id));
                ObjectNode props = properties(template);
                props.put("Description", "attempted");
                props.putArray("PatchGroups").add(name + "-free").add(name + "-occupied");
                props.putArray("Tags").addObject().put("Key", "purpose").put("Value", "attempted");
                Stack rolledBack = update(cfn, name, template, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals(id, output(rolledBack, "BaselineRef"));
                GetPatchBaselineResponse restored = ssm.getPatchBaseline(request -> request.baselineId(id));
                assertEquals(initial.description(), restored.description());
                assertEquals(initial.approvalRules(), restored.approvalRules());
                assertEquals(initial.sources(), restored.sources());
                assertEquals(initial.patchGroups(), restored.patchGroups());
                assertEquals(Map.of("purpose", "cloudformation"), tags(ssm, id));
                assertEquals(other, ssm.getPatchBaselineForPatchGroup(request -> request
                        .patchGroup(name + "-occupied").operatingSystem("AMAZON_LINUX_2")).baselineId());
                ssm.deregisterPatchBaselineForPatchGroup(request -> request
                        .baselineId(other).patchGroup(name + "-occupied"));
                update(cfn, name, template, "UPDATE_COMPLETE");
                GetPatchBaselineResponse retried = ssm.getPatchBaseline(request -> request.baselineId(id));
                assertEquals("attempted", retried.description());
                assertEquals(Set.of(name + "-free", name + "-occupied"), Set.copyOf(retried.patchGroups()));
                assertEquals(Map.of("purpose", "attempted"), tags(ssm, id));
            } finally {
                deleteStack(cfn, name);
                deleteBaseline(ssm, other);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void operatingSystemReplacementHonorsRetention(boolean retain) throws Exception {
        String name = "cfn-replace-" + UUID.randomUUID();
        ObjectNode template = template();
        properties(template).put("DefaultBaseline", false);
        if (retain) {
            template.withObject("/Resources/Baseline").put("UpdateReplacePolicy", "Retain");
        }
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            String original = null;
            String replacement = null;
            try {
                original = output(awaitStatus(cfn, name, "CREATE_COMPLETE"), "BaselineRef");
                properties(template).put("OperatingSystem", "AMAZON_LINUX_2023");
                assertPreview(cfn, name, template, "True");
                replacement = output(update(cfn, name, template, "UPDATE_COMPLETE"), "BaselineRef");
                assertNotEquals(original, replacement);
                String next = replacement;
                assertEquals("AMAZON_LINUX_2023",
                        ssm.getPatchBaseline(request -> request.baselineId(next)).operatingSystemAsString());
                if (retain) {
                    String prior = original;
                    assertEquals(List.of(name), ssm.getPatchBaseline(request -> request.baselineId(prior)).patchGroups());
                } else {
                    assertMissing(ssm, original);
                }
            } finally {
                deleteStack(cfn, name);
                if (retain && original != null) {
                    deleteBaseline(ssm, original);
                }
            }
            assertMissing(ssm, replacement);
        }
    }

    @Test
    void laterFailureDeletesTheReplacementAndRestoresTheOriginalIdentity() throws Exception {
        String name = "cfn-rollback-" + UUID.randomUUID();
        ObjectNode template = template();
        properties(template).put("DefaultBaseline", false);
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            ssm.putParameter(request -> request.name(name).type("String").value("outside"));
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            try {
                String original = output(awaitStatus(cfn, name, "CREATE_COMPLETE"), "BaselineRef");
                properties(template).put("OperatingSystem", "AMAZON_LINUX_2023");
                ObjectNode blocked = template.withObject("/Resources").putObject("Blocked");
                blocked.put("Type", "AWS::SSM::Parameter").put("DependsOn", "Baseline");
                blocked.putObject("Properties").put("Name", name).put("Type", "String").put("Value", "attempted");
                Stack rollback = update(cfn, name, template, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals(original, output(rollback, "BaselineRef"));
                assertEquals(original, output(rollback, "BaselineId"));
                assertEquals(List.of(original), baselineIds(ssm, name));
                assertEquals("AMAZON_LINUX_2",
                        ssm.getPatchBaseline(request -> request.baselineId(original)).operatingSystemAsString());
                assertEquals("outside", ssm.getParameter(request -> request.name(name)).parameter().value());
            } finally {
                deleteStack(cfn, name);
                ssm.deleteParameter(request -> request.name(name));
            }
        }
    }

    @Test
    void defaultRegistrationDoesNotUndoAnOutsideSelectionWithoutAFalseToTrueTransition() throws Exception {
        String name = "cfn-default-" + UUID.randomUUID();
        ObjectNode template = template();
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            String other = ssm.createPatchBaseline(request -> request.name("outside-" + name)
                    .operatingSystem("AMAZON_LINUX_2")).baselineId();
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            try {
                String id = output(awaitStatus(cfn, name, "CREATE_COMPLETE"), "BaselineRef");
                ssm.registerDefaultPatchBaseline(request -> request.baselineId(other));
                properties(template).put("Description", "keep outside default");
                update(cfn, name, template, "UPDATE_COMPLETE");
                assertEquals(other, ssm.getDefaultPatchBaseline(request -> request
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
                properties(template).put("DefaultBaseline", false);
                update(cfn, name, template, "UPDATE_COMPLETE");
                assertEquals(other, ssm.getDefaultPatchBaseline(request -> request
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
                properties(template).put("DefaultBaseline", true);
                update(cfn, name, template, "UPDATE_COMPLETE");
                assertEquals(id, ssm.getDefaultPatchBaseline(request -> request
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
                properties(template).remove("DefaultBaseline");
                update(cfn, name, template, "UPDATE_COMPLETE");
                assertEquals(id, ssm.getDefaultPatchBaseline(request -> request
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
            } finally {
                deleteStack(cfn, name);
                deleteBaseline(ssm, other);
            }
        }
    }

    @Test
    void failedCreateRemovesItsBaselineAndPreservesTheExistingGroupOwner() throws Exception {
        String name = "cfn-create-conflict-" + UUID.randomUUID();
        ObjectNode template = template();
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            String other = ssm.createPatchBaseline(request -> request.name("outside-" + name)
                    .operatingSystem("AMAZON_LINUX_2")).baselineId();
            ssm.registerPatchBaselineForPatchGroup(request -> request.baselineId(other).patchGroup(name));
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            try {
                awaitStatus(cfn, name, "ROLLBACK_COMPLETE");
                assertTrue(baselineIds(ssm, name).isEmpty());
                assertEquals(other, ssm.getPatchBaselineForPatchGroup(request -> request.patchGroup(name)
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
            } finally {
                deleteStack(cfn, name);
                deleteBaseline(ssm, other);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-name", "invalid-boolean"})
    void invalidPropertiesFailWithoutCreatingABaseline(String failure) throws Exception {
        String name = "cfn-invalid-" + UUID.randomUUID();
        ObjectNode template = template();
        if (failure.equals("missing-name")) {
            properties(template).remove("Name");
        } else {
            properties(template).put("DefaultBaseline", "not-a-boolean");
        }
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            try {
                awaitStatus(cfn, name, "ROLLBACK_COMPLETE");
                assertTrue(baselineIds(ssm, name).isEmpty());
            } finally {
                deleteStack(cfn, name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedTagUpdatesCannotEraseThePreviousValue(boolean explicitNull) throws Exception {
        String name = "cfn-tag-" + UUID.randomUUID();
        ObjectNode template = template();
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            try {
                String id = output(awaitStatus(cfn, name, "CREATE_COMPLETE"), "BaselineRef");
                ObjectNode tag = properties(template).putArray("Tags").addObject().put("Key", "purpose");
                if (explicitNull) {
                    tag.putNull("Value");
                }
                properties(template).put("Description", "attempted");
                if (explicitNull) {
                    CloudFormationException error = assertThrows(CloudFormationException.class,
                            () -> cfn.updateStack(request -> request.stackName(name)
                                    .templateBody(template.toString())));
                    assertEquals(400, error.statusCode());
                    assertTrue(error.awsErrorDetails().errorMessage().contains("'null' values are not allowed"));
                    awaitStatus(cfn, name, "CREATE_COMPLETE");
                } else {
                    update(cfn, name, template, "UPDATE_ROLLBACK_COMPLETE");
                }
                assertEquals(Map.of("purpose", "cloudformation"), tags(ssm, id));
                assertEquals("Policy in us-east-1",
                        ssm.getPatchBaseline(request -> request.baselineId(id)).description());
            } finally {
                deleteStack(cfn, name);
            }
        }
    }

    @Test
    void resolvesTypedParametersInRulesAndOmitsNoValue() throws Exception {
        String name = "cfn-typed-" + UUID.randomUUID();
        ObjectNode template = template();
        ObjectNode parameters = template.putObject("Parameters");
        parameters.putObject("Days").put("Type", "Number").put("Default", 3);
        parameters.putObject("Groups").put("Type", "CommaDelimitedList").put("Default", name + "-one," + name + "-two");
        ObjectNode rule = (ObjectNode) properties(template).path("ApprovalRules").path("PatchRules").get(0);
        rule.putObject("ApproveAfterDays").put("Ref", "Days");
        properties(template).putObject("PatchGroups").put("Ref", "Groups");
        properties(template).put("DefaultBaseline", "TRUE");
        properties(template).put("ApprovedPatchesEnableNonSecurity", "false");
        properties(template).putObject("Description").put("Ref", "AWS::NoValue");
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            try {
                String id = output(awaitStatus(cfn, name, "CREATE_COMPLETE"), "BaselineRef");
                GetPatchBaselineResponse baseline = ssm.getPatchBaseline(request -> request.baselineId(id));
                assertEquals(3, baseline.approvalRules().patchRules().getFirst().approveAfterDays());
                assertEquals(Set.of(name + "-one", name + "-two"), Set.copyOf(baseline.patchGroups()));
                assertEquals(false, baseline.approvedPatchesEnableNonSecurity());
                assertNull(baseline.description());
            } finally {
                deleteStack(cfn, name);
            }
        }
    }

    @Test
    void deletingAnAlreadyRemovedBaselineSucceeds() throws Exception {
        String name = "cfn-gone-" + UUID.randomUUID();
        ObjectNode template = template();
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            try {
                String id = output(awaitStatus(cfn, name, "CREATE_COMPLETE"), "BaselineRef");
                deleteBaseline(ssm, id);
            } finally {
                deleteStack(cfn, name);
            }
        }
    }

    private static ObjectNode template() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {
                  "Resources": {
                    "Baseline": {
                      "Type": "AWS::SSM::PatchBaseline",
                      "Properties": {
                        "Name": {"Ref": "AWS::StackName"},
                        "OperatingSystem": "AMAZON_LINUX_2",
                        "Description": {"Fn::Sub": "Policy in ${AWS::Region}"},
                        "ApprovalRules": {"PatchRules": [{
                          "ApproveAfterDays": 0,
                          "PatchFilterGroup": {"PatchFilters": [
                            {"Key": "CLASSIFICATION", "Values": ["Security"]}
                          ]}
                        }]},
                        "GlobalFilters": {"PatchFilters": [{"Key": "PRODUCT", "Values": ["*"]}]},
                        "ApprovedPatches": ["kernel*"],
                        "ApprovedPatchesComplianceLevel": "HIGH",
                        "ApprovedPatchesEnableNonSecurity": true,
                        "RejectedPatches": ["old-package*"],
                        "RejectedPatchesAction": "BLOCK",
                        "AvailableSecurityUpdatesComplianceStatus": "NON_COMPLIANT",
                        "Sources": [{
                          "Name": "example-repository",
                          "Products": ["*"],
                          "Configuration": "[example]\\nname=Example\\nbaseurl=file:///repo\\nenabled=1\\n"
                        }],
                        "PatchGroups": [{"Ref": "AWS::StackName"}],
                        "DefaultBaseline": true,
                        "Tags": [{"Key": "purpose", "Value": "cloudformation"}]
                      }
                    }
                  },
                  "Outputs": {
                    "BaselineRef": {"Value": {"Ref": "Baseline"}},
                    "BaselineId": {"Value": {"Fn::GetAtt": ["Baseline", "Id"]}}
                  }
                }
                """);
    }

    private static ObjectNode properties(ObjectNode template) {
        return template.withObject("/Resources/Baseline/Properties");
    }

    private static Map<String, String> tags(SsmClient client, String id) {
        return client.listTagsForResource(request -> request.resourceType("PatchBaseline").resourceId(id))
                .tagList().stream().collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private static List<String> baselineIds(SsmClient client, String name) {
        return client.describePatchBaselines(request -> request.filters(
                PatchOrchestratorFilter.builder().key("NAME_PREFIX").values(name).build()))
                .baselineIdentities().stream().map(value -> value.baselineId()).toList();
    }

    private static Stack update(CloudFormationClient client, String name, ObjectNode template, String expected) {
        client.updateStack(request -> request.stackName(name).templateBody(template.toString()));
        return awaitStatus(client, name, expected);
    }

    private static void assertPreview(CloudFormationClient client, String stack, ObjectNode template,
                                      String replacement) {
        String change = "preview-" + UUID.randomUUID();
        client.createChangeSet(request -> request.stackName(stack).changeSetName(change)
                .changeSetType("UPDATE").templateBody(template.toString()));
        try {
            DescribeChangeSetResponse preview = client.describeChangeSet(request -> request
                    .stackName(stack).changeSetName(change));
            assertEquals("CREATE_COMPLETE", preview.statusAsString(), preview.statusReason());
            assertEquals(replacement, preview.changes().stream()
                    .filter(value -> "Baseline".equals(value.resourceChange().logicalResourceId()))
                    .findFirst().orElseThrow().resourceChange().replacementAsString());
        } finally {
            client.deleteChangeSet(request -> request.stackName(stack).changeSetName(change));
        }
    }

    private static void deleteBaseline(SsmClient client, String id) {
        GetPatchBaselineResponse baseline = client.getPatchBaseline(request -> request.baselineId(id));
        for (String group : baseline.patchGroups()) {
            client.deregisterPatchBaselineForPatchGroup(request -> request.baselineId(id).patchGroup(group));
        }
        client.deletePatchBaseline(request -> request.baselineId(id));
    }

    private static String output(Stack stack, String key) {
        return stack.outputs().stream().filter(value -> key.equals(value.outputKey()))
                .findFirst().orElseThrow().outputValue();
    }

    private static void assertMissing(SsmClient client, String id) {
        SsmException failure = assertThrows(SsmException.class,
                () -> client.getPatchBaseline(request -> request.baselineId(id)));
        assertEquals("DoesNotExistException", failure.awsErrorDetails().errorCode());
    }

    private static void deleteStack(CloudFormationClient client, String name) throws Exception {
        client.deleteStack(request -> request.stackName(name));
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                Stack stack = client.describeStacks(request -> request.stackName(name)).stacks().getFirst();
                assertNotEquals("DELETE_FAILED", stack.stackStatusAsString(), stack.stackStatusReason());
            } catch (CloudFormationException error) {
                assertEquals("ValidationError", error.awsErrorDetails().errorCode());
                assertTrue(error.getMessage().contains("does not exist"), error.getMessage());
                return;
            }
            Thread.sleep(25);
        }
        fail("Stack was not deleted: " + name);
    }

    private static CloudFormationClient cfn(String region) {
        return CloudFormationClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private static SsmClient ssm(String region) {
        return SsmClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}

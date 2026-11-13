package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.model.StackUpdateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFormationDriftOriginTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";

    @Test
    void completedRootOriginReadsWithoutChangingSerializedLegacyStackOrChangeSets() throws Exception {
        Stack root = stack("root", "first");
        prove(root);
        Map<String, Stack> rows = Map.of(key(root), root);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        String before = json.writeValueAsString(rows);
        assertEquals(new CloudFormationDriftOrigin(null, null), capture(root, rows));
        assertEquals(before, json.writeValueAsString(rows));
        root.getChangeSets().clear();
        assertNotNull(capture(root, rows).failure());
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-create", "available", "failed", "status", "wrong-lifetime", "wrong-name",
            "wrong-key", "wrong-account", "wrong-region", "wrong-partition", "wrong-service", "wrong-arn-name",
            "missing-id", "multiple-completed", "update-only"})
    void missingCorruptForeignAndAmbiguousCreateProvenanceCannotProveRoot(String mutation) {
        Stack root = stack("root", "first");
        ChangeSet proof = prove(root);
        switch (mutation) {
            case "no-create" -> root.getChangeSets().clear();
            case "available" -> proof.setExecutionStatus("AVAILABLE");
            case "failed" -> proof.setExecutionStatus("EXECUTE_FAILED");
            case "status" -> proof.setStatus("FAILED");
            case "wrong-lifetime" -> root.setStackId(arn("stack/root/second"));
            case "wrong-name" -> proof.setStackName("other");
            case "wrong-key" -> {
                root.getChangeSets().clear();
                root.getChangeSets().put("wrong", proof);
            }
            case "wrong-account" -> proof.setChangeSetId(proof.getChangeSetId().replace(ACCOUNT, "999999999999"));
            case "wrong-region" -> proof.setChangeSetId(proof.getChangeSetId().replace(REGION, "us-west-2"));
            case "wrong-partition" -> proof.setChangeSetId(proof.getChangeSetId().replace("arn:aws:", "arn:aws-cn:"));
            case "wrong-service" -> proof.setChangeSetId(proof.getChangeSetId().replace(":cloudformation:", ":sns:"));
            case "wrong-arn-name" -> proof.setChangeSetId(arn("changeSet/other/lifetime"));
            case "missing-id" -> proof.setChangeSetId(null);
            case "multiple-completed" -> {
                ChangeSet another = prove(root);
                another.setChangeSetName("another");
                another.setChangeSetId(arn("changeSet/another/lifetime"));
                root.getChangeSets().put("create", proof);
                root.getChangeSets().put("another", another);
            }
            default -> proof.setChangeSetType("UPDATE");
        }
        CloudFormationDriftOrigin origin = capture(root, Map.of(key(root), root));
        assertNull(origin.parentId());
        assertNotNull(origin.failure());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullPhysicalIdInLiveOrRollbackParentRefusesNestedEvenIfChildHasCreateProof(boolean rollback) {
        Stack child = stack("child", "child-generation");
        prove(child);
        Stack parent = stack("parent", "parent-generation");
        StackResource link = link(child.getStackId());
        if (rollback) {
            parent.setUpdateRollbackSnapshot(new StackUpdateSnapshot("{}", "{}", Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of("Child", link), Map.of()));
        } else {
            parent.getResources().put("Child", link);
        }
        assertEquals(new CloudFormationDriftOrigin(parent.getStackId(), null),
                capture(child, Map.of(key(parent), parent, key(child), child)));
        // No parent record cannot turn a genuine unmarked nested/legacy row into a proven root.
        child.getChangeSets().clear();
        assertNotNull(capture(child, Map.of(key(child), child)).failure());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "region", "lifetime"})
    void foreignScopeOrDifferentFullPhysicalLifetimeCannotEstablishAncestry(String difference) {
        Stack root = stack("root", "first");
        prove(root);
        Stack parent = stack("parent", "parent-generation");
        parent.getResources().put("Child", link(root.getStackId()));
        switch (difference) {
            case "account" -> parent.setAccountId("999999999999");
            case "region" -> parent.setRegion("us-west-2");
            default -> parent.getResources().get("Child").setPhysicalId(arn("stack/root/old-lifetime"));
        }
        assertEquals(new CloudFormationDriftOrigin(null, null),
                capture(root, Map.of(key(parent), parent, key(root), root)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "region", "partition", "key", "logical-id", "type", "self",
            "missing-physical", "ambiguous"})
    void corruptOrAmbiguousScopedParentEvidenceNeverBecomesAbsence(String mutation) {
        Stack child = stack("child", "child-generation");
        prove(child);
        Stack parent = stack("parent", "parent-generation");
        parent.getResources().put("Child", link(child.getStackId()));
        String parentKey = key(parent);
        Map<String, Stack> rows = new LinkedHashMap<>();
        rows.put(key(child), child);
        rows.put(parentKey, parent);
        switch (mutation) {
            case "owner" -> parent.setAccountId("999999999999");
            case "region" -> parent.setRegion("us-west-2");
            case "partition" -> parent.setStackId(parent.getStackId().replace("arn:aws:", "arn:aws-cn:"));
            case "key" -> {
                rows.remove(parentKey);
                rows.put(ACCOUNT + ":" + REGION + ":wrong", parent);
            }
            case "logical-id" -> parent.getResources().get("Child").setLogicalId("Wrong");
            case "type" -> parent.getResources().get("Child").setResourceType("AWS::SNS::Topic");
            case "self" -> child.getResources().put("Child", link(child.getStackId()));
            case "missing-physical" -> parent.getResources().get("Child").setPhysicalId(null);
            default -> {
                Stack other = stack("other", "other-generation");
                other.getResources().put("Child", link(child.getStackId()));
                rows.put(key(other), other);
            }
        }
        assertNotNull(capture(child, rows).failure());
    }

    @Test
    void deletingOrRetainingNonCreateChangeSetsDoesNotInventRootProvenance() {
        Stack root = stack("root", "first");
        prove(root);
        Map<String, Stack> rows = Map.of(key(root), root);
        assertNull(capture(root, rows).failure());
        ChangeSet update = new ChangeSet();
        update.setChangeSetType("UPDATE");
        update.setExecutionStatus("EXECUTE_COMPLETE");
        // ExecuteChangeSet's existing admission discards all other change sets.
        root.getChangeSets().clear();
        root.getChangeSets().put("executed-update", update);
        assertTrue(capture(root, rows).failure().contains("retained completed CREATE"));
    }

    private static CloudFormationDriftOrigin capture(Stack stack, Map<String, Stack> rows) {
        return CloudFormationDriftOrigin.capture(stack, rows, ACCOUNT, REGION);
    }

    static ChangeSet prove(Stack stack) {
        ChangeSet proof = new ChangeSet();
        proof.setChangeSetName("create");
        proof.setChangeSetId(arn("changeSet/create/" + stack.getStackName()));
        proof.setStackName(stack.getStackName());
        proof.setStackId(stack.getStackId());
        proof.setChangeSetType("CREATE");
        proof.setExecutionStatus("EXECUTE_COMPLETE");
        stack.getChangeSets().put("create", proof);
        return proof;
    }

    private static Stack stack(String name, String lifetime) {
        Stack stack = new Stack();
        stack.setStackName(name);
        stack.setStackId(arn("stack/" + name + "/" + lifetime));
        stack.setAccountId(ACCOUNT);
        stack.setRegion(REGION);
        stack.setCreationTime(Instant.EPOCH);
        return stack;
    }

    private static StackResource link(String child) {
        StackResource link = new StackResource();
        link.setLogicalId("Child");
        link.setResourceType("AWS::CloudFormation::Stack");
        link.setPhysicalId(child);
        return link;
    }

    private static String key(Stack stack) {
        return stack.getAccountId() + ":" + stack.getRegion() + ":" + stack.getStackName();
    }

    private static String arn(String resource) {
        return "arn:aws:cloudformation:" + REGION + ":" + ACCOUNT + ":" + resource;
    }
}

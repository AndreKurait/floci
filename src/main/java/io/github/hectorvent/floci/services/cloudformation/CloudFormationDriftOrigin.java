package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.model.StackUpdateSnapshot;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Dev's stored Stack has no parent identity. Only retained, completed CREATE provenance proves a root.
 * This read-only inference is repeated at publication; it does not lock other parents' lifecycle writers.
 */
record CloudFormationDriftOrigin(String parentId, String failure) {
    static CloudFormationDriftOrigin capture(Stack current, Map<String, Stack> stacks,
                                            String account, String region) {
        try {
            Set<String> parents = new LinkedHashSet<>();
            String prefix = account + ":" + region + ":";
            for (Map.Entry<String, Stack> entry : stacks.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) {
                    continue;
                }
                Stack parent = entry.getValue();
                boolean live = refersTo(parent.resourcesSnapshot(), current.getStackId());
                StackUpdateSnapshot rollback = parent.getUpdateRollbackSnapshot();
                boolean retained = rollback != null && refersTo(rollback.resources(), current.getStackId());
                if (!live && !retained) {
                    continue;
                }
                if (!validStack(entry.getKey(), parent, account, region)
                        || current.getStackId().equals(parent.getStackId())) {
                    return refused("Stored nested-stack ancestry is corrupt");
                }
                parents.add(parent.getStackId());
            }
            if (parents.size() > 1) {
                return refused("Stored nested-stack ancestry is ambiguous");
            }
            if (!parents.isEmpty()) {
                return new CloudFormationDriftOrigin(parents.iterator().next(), null);
            }
            int completed = 0;
            for (Map.Entry<String, ChangeSet> entry : current.changeSetsSnapshot().entrySet()) {
                ChangeSet change = entry.getValue();
                if (change == null) {
                    return refused("Stored CREATE provenance is corrupt");
                }
                if (!"CREATE".equals(change.getChangeSetType())) {
                    continue;
                }
                AwsArnUtils.Arn arn = AwsArnUtils.parse(change.getChangeSetId());
                String[] identity = arn.resource().split("/", -1);
                if (!current.getStackId().equals(change.getStackId())
                        || !current.getStackName().equals(change.getStackName())
                        || !entry.getKey().equals(change.getChangeSetName())
                        || !scoped(arn, account, region) || identity.length != 3
                        || !"changeSet".equals(identity[0]) || !entry.getKey().equals(identity[1])
                        || identity[2].isEmpty() || change.getCreationTime() == null) {
                    return refused("Stored CREATE provenance does not match the stack lifetime");
                }
                if ("EXECUTE_COMPLETE".equals(change.getExecutionStatus())
                        && "CREATE_COMPLETE".equals(change.getStatus())) {
                    completed++;
                }
            }
            return completed == 1 ? new CloudFormationDriftOrigin(null, null)
                    : refused("Drift detection requires one retained completed CREATE change set proving root origin");
        } catch (IllegalArgumentException | NullPointerException corrupt) {
            return refused("Stored nested-stack ancestry or CREATE provenance is corrupt");
        }
    }

    private static boolean refersTo(Map<String, StackResource> resources, String stackId) {
        if (resources == null) {
            throw new IllegalArgumentException("Missing ancestry resource map");
        }
        boolean found = false;
        for (Map.Entry<String, StackResource> entry : resources.entrySet()) {
            StackResource resource = entry.getValue();
            if (resource == null) {
                throw new IllegalArgumentException("Missing ancestry resource");
            }
            boolean nested = "AWS::CloudFormation::Stack".equals(resource.getResourceType());
            if (nested && (resource.getPhysicalId() == null || resource.getPhysicalId().isEmpty())) {
                throw new IllegalArgumentException("Unresolved nested-stack physical identity");
            }
            if (stackId.equals(resource.getPhysicalId())) {
                if (!nested || !entry.getKey().equals(resource.getLogicalId())) {
                    throw new IllegalArgumentException("Inconsistent nested-stack resource identity");
                }
                found = true;
            }
        }
        return found;
    }

    private static boolean validStack(String key, Stack stack, String account, String region) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(stack.getStackId());
        String[] identity = arn.resource().split("/", -1);
        return scoped(arn, account, region) && account.equals(stack.getAccountId())
                && region.equals(stack.getRegion()) && stack.getStackName() != null
                && key.equals(account + ":" + region + ":" + stack.getStackName())
                && identity.length == 3 && "stack".equals(identity[0])
                && stack.getStackName().equals(identity[1]) && !identity[2].isEmpty()
                && stack.getCreationTime() != null;
    }

    private static boolean scoped(AwsArnUtils.Arn arn, String account, String region) {
        return account.equals(arn.accountId()) && region.equals(arn.region())
                && "cloudformation".equals(arn.service())
                && AwsRegions.partitionFor(region).equals(arn.partition());
    }

    private static CloudFormationDriftOrigin refused(String reason) {
        return new CloudFormationDriftOrigin(null, reason);
    }
}

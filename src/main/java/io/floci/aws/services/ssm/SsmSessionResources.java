package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsArnUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Session Manager selectors that depend on document ownership or the target's node type. */
public final class SsmSessionResources {
    private SsmSessionResources() {}

    public static List<String> of(String action, JsonNode json, String region,
                                   String accountId, String partition) {
        if ("GetConnectionStatus".equals(action)) {
            String target = json == null ? "" : json.path("Target").asText("");
            return List.of(target.isEmpty() ? "*" : nodeArn(target, region, accountId, partition));
        }
        if (!"StartSession".equals(action)) {
            return null;
        }
        if (json == null) {
            return List.of("*");
        }
        String name = json.path("DocumentName").asText("");
        if (name.isEmpty()) {
            name = "SSM-SessionManagerRunShell";
        }
        Set<String> resources = new LinkedHashSet<>();
        resources.add(AwsArnUtils.isArnFor(name, "ssm") ? name
                : new AwsArnUtils.Arn(partition, "ssm", region,
                        SsmService.isAwsSessionDocument(name) ? "" : accountId, "document/" + name).toString());
        String target = json.path("Target").asText("");
        if (!target.isEmpty()) {
            resources.add(nodeArn(target, region, accountId, partition));
        }
        return List.copyOf(resources);
    }

    private static String nodeArn(String target, String region, String accountId, String partition) {
        boolean managed = target.startsWith("mi-");
        return new AwsArnUtils.Arn(partition, managed ? "ssm" : "ec2", region, accountId,
                (managed ? "managed-instance/" : "instance/") + target).toString();
    }
}

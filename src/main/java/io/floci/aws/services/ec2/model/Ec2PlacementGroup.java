package io.floci.aws.services.ec2.model;

import io.floci.aws.core.common.AwsArnUtils;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** One immutable placement-group metadata generation, including its create-time tags. */
@RegisterForReflection
public record Ec2PlacementGroup(String accountId, String region, String partition, String groupName,
                                String groupId, String generation, String strategy, Integer partitionCount,
                                String spreadLevel, Map<String, String> tags) {

    public Ec2PlacementGroup {
        tags = Map.copyOf(tags);
    }

    public String arn() {
        return new AwsArnUtils.Arn(partition, "ec2", region, accountId, "placement-group/" + groupName).toString();
    }
}

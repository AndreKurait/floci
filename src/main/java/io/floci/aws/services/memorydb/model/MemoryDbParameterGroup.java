package io.floci.aws.services.memorydb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** One immutable stored group generation, including its independently persisted tags. */
@RegisterForReflection
public record MemoryDbParameterGroup(
        String accountId,
        String region,
        String partition,
        String name,
        String arn,
        String family,
        String description,
        String generation,
        long revision,
        String catalogVersion,
        Map<String, String> overrides,
        Map<String, String> tags) {

    public MemoryDbParameterGroup {
        overrides = Map.copyOf(overrides);
        tags = Map.copyOf(tags);
    }

    public MemoryDbParameterGroup replace(Map<String, String> nextOverrides, Map<String, String> nextTags) {
        return new MemoryDbParameterGroup(accountId, region, partition, name, arn, family, description,
                generation, Math.incrementExact(revision), catalogVersion, nextOverrides, nextTags);
    }
}

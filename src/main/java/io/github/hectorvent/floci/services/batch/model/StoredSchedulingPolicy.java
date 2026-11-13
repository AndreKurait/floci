package io.github.hectorvent.floci.services.batch.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** One immutable policy generation. Only explicit public projections reach the wire. */
@RegisterForReflection
public record StoredSchedulingPolicy(
        int schemaVersion, String account, String partition, String region, String name, String arn,
        String generation, long revision, String kind, Fairshare fairshare, Quota quota,
        Map<String, String> tags, long createdAtMillis, long updatedAtMillis) {

    public StoredSchedulingPolicy {
        tags = Map.copyOf(tags);
    }

    public StoredSchedulingPolicy replace(String nextKind, Fairshare nextFairshare, Quota nextQuota,
                                           Map<String, String> nextTags, long now) {
        return new StoredSchedulingPolicy(schemaVersion, account, partition, region, name, arn,
                generation, Math.incrementExact(revision), nextKind, nextFairshare, nextQuota, nextTags,
                createdAtMillis, Math.max(updatedAtMillis, now));
    }

    @RegisterForReflection
    public record Fairshare(int shareDecaySeconds, int computeReservation, List<Share> shareDistribution) {
        public Fairshare {
            shareDistribution = List.copyOf(shareDistribution);
        }
    }

    @RegisterForReflection
    public record Share(String shareIdentifier, float weightFactor) {}

    @RegisterForReflection
    public record Quota(String idleResourceAssignmentStrategy) {}
}

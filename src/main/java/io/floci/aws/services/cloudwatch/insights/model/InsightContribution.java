package io.floci.aws.services.cloudwatch.insights.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** Counts evaluated at ingestion, independent of subsequent changes to a rule definition. */
@RegisterForReflection
public record InsightContribution(long timestamp, List<String> keys, long count) {
    public InsightContribution {
        keys = List.copyOf(keys);
        if (count < 1) {
            throw new IllegalArgumentException("Contribution count must be positive");
        }
    }
}

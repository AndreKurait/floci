package io.github.hectorvent.floci.services.cloudwatch.insights.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An immutable rule revision. The owner token is internal CloudFormation bookkeeping. */
@RegisterForReflection
public record InsightRule(String name, String arn, String definition, String state,
                          Map<String, String> tags, String ownerToken, List<InsightContribution> history) {
    public InsightRule {
        tags = tags == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(tags));
        history = history == null ? List.of() : List.copyOf(history);
    }

    public InsightRule(String name, String arn, String definition, String state,
                       Map<String, String> tags, String ownerToken) {
        this(name, arn, definition, state, tags, ownerToken, List.of());
    }

    public InsightRule withState(String nextState) {
        return new InsightRule(name, arn, definition, nextState, tags, ownerToken, history);
    }

    public InsightRule withTags(Map<String, String> nextTags) {
        return new InsightRule(name, arn, definition, state, nextTags, ownerToken, history);
    }

    public InsightRule withHistory(List<InsightContribution> nextHistory) {
        return new InsightRule(name, arn, definition, state, tags, ownerToken, nextHistory);
    }
}

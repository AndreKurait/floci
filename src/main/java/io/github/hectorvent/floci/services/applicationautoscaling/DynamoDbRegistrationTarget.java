package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalableTarget;

import java.util.Map;
import java.util.Set;

/** One prepared target publication inside DynamoDB's registration capacity critical section. */
public final class DynamoDbRegistrationTarget {
    private static final Set<String> DIMENSIONS = Set.of(
            "dynamodb:table:ReadCapacityUnits", "dynamodb:table:WriteCapacityUnits",
            "dynamodb:index:ReadCapacityUnits", "dynamodb:index:WriteCapacityUnits");

    private final StorageBackend<String, ScalableTarget> targets;
    private final Map<String, ScalableTarget> replacement;

    public DynamoDbRegistrationTarget(StorageBackend<String, ScalableTarget> targets,
                                     String key, ScalableTarget candidate) {
        this.targets = targets;
        this.replacement = Map.of(key, new ScalableTarget(candidate));
    }

    public static boolean supports(String namespace, String dimension) {
        return "dynamodb".equals(namespace) && dimension != null && DIMENSIONS.contains(dimension);
    }

    /** The backend contract rejects before replacement; no fallible work follows this call. */
    public void publish() {
        targets.putAllAtomically(replacement);
    }
}

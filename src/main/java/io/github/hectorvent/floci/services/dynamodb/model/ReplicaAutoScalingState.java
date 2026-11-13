package io.github.hectorvent.floci.services.dynamodb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** Settings for one table incarnation, independent of mutable table metadata. */
@RegisterForReflection
public record ReplicaAutoScalingState(String tableId, Settings write,
                                     Map<String, Settings> indexWrites, Map<String, Replica> replicas) {
    public ReplicaAutoScalingState {
        indexWrites = indexWrites == null ? Map.of() : Map.copyOf(indexWrites);
        replicas = replicas == null ? Map.of() : Map.copyOf(replicas);
    }

    @RegisterForReflection
    public record Replica(Settings read, Map<String, Settings> indexReads) {
        public Replica {
            indexReads = indexReads == null ? Map.of() : Map.copyOf(indexReads);
        }
    }

    @RegisterForReflection
    public record Settings(Long minimumUnits, Long maximumUnits, Boolean disabled, String roleArn, Policy policy) {}

    @RegisterForReflection
    public record Policy(String name, TargetTracking targetTracking) {}

    @RegisterForReflection
    public record TargetTracking(Boolean disableScaleIn, Integer scaleInCooldown,
                                 Integer scaleOutCooldown, Double targetValue) {}
}

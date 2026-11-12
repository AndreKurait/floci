package io.github.hectorvent.floci.services.cloudformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One immutable aggregate committed for a single stack lifetime. */
@RegisterForReflection
public record StackDriftRecord(String accountId, String region, String stackId, long revision,
                               Map<String, ResourceDrift> resources, Map<String, Detection> detections) {

    public StackDriftRecord {
        resources = Map.copyOf(resources);
        detections = Map.copyOf(detections);
    }

    @RegisterForReflection
    public record Difference(String propertyPath, String expectedValue, String actualValue) {
    }

    @RegisterForReflection
    public record ResourceDrift(String stackId, String logicalId, String physicalId, String resourceType,
                                String expectedProperties, String actualProperties, String status,
                                Instant timestamp, List<Difference> differences) {
        public ResourceDrift {
            differences = List.copyOf(differences);
        }
    }

    @RegisterForReflection
    public record Detection(String stackId, String id, String stackStatus, Instant timestamp,
                            int driftedResourceCount) {
    }
}

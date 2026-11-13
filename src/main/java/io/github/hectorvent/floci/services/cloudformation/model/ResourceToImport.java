package io.github.hectorvent.floci.services.cloudformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public record ResourceToImport(String resourceType, String logicalResourceId,
                               Map<String, String> resourceIdentifier) {
    public ResourceToImport {
        resourceIdentifier = Map.copyOf(resourceIdentifier);
    }
}

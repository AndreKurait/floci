package io.floci.aws.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** Immutable documentation captured separately from a deployment's API definition. */
@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public record DocumentationVersion(String version, String description, long createdDate,
                                   String accountId, String region, String apiId, List<Part> parts) {

    public DocumentationVersion {
        parts = List.copyOf(parts);
    }

    @RegisterForReflection
    public record Part(Map<String, String> location, String properties) {
        public Part {
            location = Map.copyOf(location);
        }
    }
}

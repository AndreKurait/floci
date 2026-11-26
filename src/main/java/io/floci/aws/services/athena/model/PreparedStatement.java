package io.floci.aws.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public record PreparedStatement(
        @JsonProperty("StatementName") String statementName,
        @JsonProperty("WorkGroupName") String workGroupName,
        @JsonProperty("QueryStatement") String queryStatement,
        @JsonProperty("Description") String description,
        @JsonProperty("LastModifiedTime") Instant lastModifiedTime) {
}

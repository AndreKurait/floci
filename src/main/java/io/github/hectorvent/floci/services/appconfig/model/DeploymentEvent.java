package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An event in the AppConfig deployment's standard EventLog. */
@RegisterForReflection
public record DeploymentEvent(@JsonProperty("EventType") String eventType,
                              @JsonProperty("Description") String description,
                              @JsonProperty("OccurredAt") String occurredAt,
                              @JsonProperty("TriggeredBy") String triggeredBy) {
}

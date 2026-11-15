package io.github.hectorvent.floci.services.account.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AccountRegion(@JsonProperty("RegionName") String regionName,
                            @JsonProperty("RegionOptStatus") String regionOptStatus) {
}

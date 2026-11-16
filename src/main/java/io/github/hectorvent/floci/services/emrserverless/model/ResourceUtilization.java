package io.github.hectorvent.floci.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GetJobRun's {@code billedResourceUtilization} and {@code totalResourceUtilization}, the API's
 * ResourceUtilization and TotalResourceUtilization, which have the same members.
 */
@RegisterForReflection
public record ResourceUtilization(@JsonProperty("vCPUHour") Double vCPUHour,
                                  @JsonProperty("memoryGBHour") Double memoryGBHour,
                                  @JsonProperty("storageGBHour") Double storageGBHour) {

    public static final ResourceUtilization NONE = new ResourceUtilization(0.0, 0.0, 0.0);
}

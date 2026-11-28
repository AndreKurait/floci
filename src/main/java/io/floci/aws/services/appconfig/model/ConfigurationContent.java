package io.floci.aws.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The bytes admitted by a deployment, independent of later source or role changes. */
@RegisterForReflection
public record ConfigurationContent(@JsonProperty("Content") byte[] content,
                                   @JsonProperty("ContentType") String contentType) {
    public ConfigurationContent {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}

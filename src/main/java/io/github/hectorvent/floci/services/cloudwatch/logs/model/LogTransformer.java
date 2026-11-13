package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** The supported single parseJSON definition, including omitted optional members. */
@RegisterForReflection
public record LogTransformer(String source, String destination, long creationTime, long lastModifiedTime) {
}

package io.github.hectorvent.floci.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** The name and ARN exposed by ListAccessPolicies, without policy permission contents. */
@RegisterForReflection
public record AccessPolicy(String arn, String name) {
}
